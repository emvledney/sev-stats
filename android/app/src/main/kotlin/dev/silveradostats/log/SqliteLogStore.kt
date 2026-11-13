package dev.silveradostats.log

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The log on the head unit: one SQLite table in the app's private storage, some tens of MB a year at
 * most, far below what CarWatchdog objects to (gigabytes a day). Uninstalling the app deletes it, so export it first.
 * Thread safe (SQLiteDatabase serialises access). Blocking: call off the main thread.
 *
 * A column added to [LogCol] later is added to the table when the database opens: older rows simply
 * have no value in it.
 *
 * A row's arrays (every cell group, every module) are in a table of their own, by the row's id, as text:
 * about 1.5 KB for each MX+ row that read them.
 */
class SqliteLogStore(context: Context) : SQLiteOpenHelper(context.applicationContext, FILE, null, VERSION), LogStore, UploadQueue {
  override fun onCreate(db: SQLiteDatabase) {
    val cols = LogCol.entries.joinToString("") { ", ${it.key} REAL" }
    db.execSQL("CREATE TABLE $TABLE (at_ms INTEGER NOT NULL, tz_min INTEGER NOT NULL, source TEXT NOT NULL, trigger_code TEXT NOT NULL$cols)")
    db.execSQL("CREATE INDEX log_time ON $TABLE (at_ms)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

  override fun onOpen(db: SQLiteDatabase) {
    if (db.isReadOnly) return
    db.execSQL("CREATE TABLE IF NOT EXISTS $ARRAYS (row_id INTEGER NOT NULL, kind TEXT NOT NULL, vals TEXT NOT NULL, PRIMARY KEY (row_id, kind))")
    val have = HashSet<String>()
    db.rawQuery("PRAGMA table_info($TABLE)", null).use { c -> while (c.moveToNext()) have += c.getString(1) }
    for (col in LogCol.entries) if (col.key !in have) db.execSQL("ALTER TABLE $TABLE ADD COLUMN ${col.key} REAL")
    // Rows from before the two capacities had a column each: the flag says which one each row holds.
    if (LogCol.CAR_NOMINAL_KWH.key !in have) {
      db.execSQL("UPDATE $TABLE SET ${LogCol.CAR_NOMINAL_KWH.key} = ${LogCol.CAR_CAPACITY_KWH.key} WHERE ${LogCol.CAR_CAPACITY_NOMINAL.key} = 1")
      db.execSQL("UPDATE $TABLE SET ${LogCol.CAR_USABLE_KWH.key} = ${LogCol.CAR_CAPACITY_KWH.key} WHERE ${LogCol.CAR_CAPACITY_NOMINAL.key} = 0")
    }
  }

  override fun insert(row: LogRow) {
    val cv = ContentValues()
    cv.put("at_ms", row.atMs)
    cv.put("tz_min", row.tzMinutes)
    cv.put("source", row.source.code)
    cv.put("trigger_code", row.trigger.code)
    for ((c, v) in row.values) cv.put(c.key, v)
    val db = writableDatabase
    if (row.arrays.isEmpty()) {
      db.insert(TABLE, null, cv)
      return
    }
    db.beginTransaction()
    try {
      val id = db.insert(TABLE, null, cv)
      if (id >= 0) {
        for ((kind, vals) in row.arrays) {
          val a = ContentValues()
          a.put("row_id", id)
          a.put("kind", kind)
          a.put("vals", UploadWire.packArray(vals))
          db.insert(ARRAYS, null, a)
        }
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  override fun after(rowId: Long, limit: Int): List<QueuedRow> {
    val db = readableDatabase
    val rows = db.rawQuery("SELECT rowid AS row_id, * FROM $TABLE WHERE rowid > ? ORDER BY rowid LIMIT ?", arrayOf(rowId.toString(), limit.toString())).use { c ->
      val out = ArrayList<QueuedRow>(c.count)
      while (c.moveToNext()) out += QueuedRow(c.getLong(c.getColumnIndexOrThrow("row_id")), rowAt(c, LogCol.entries))
      out
    }
    if (rows.isEmpty()) return rows
    val arrays = HashMap<Long, LinkedHashMap<String, List<Double?>>>()
    db.rawQuery("SELECT row_id, kind, vals FROM $ARRAYS WHERE row_id > ? AND row_id <= ? ORDER BY row_id, kind", arrayOf(rowId.toString(), rows.last().id.toString())).use { c ->
      while (c.moveToNext()) arrays.getOrPut(c.getLong(0)) { LinkedHashMap() }[c.getString(1)] = UploadWire.unpackArray(c.getString(2))
    }
    return rows.map { q -> arrays[q.id]?.let { q.copy(row = q.row.copy(arrays = it)) } ?: q }
  }

  override fun waiting(rowId: Long): Long =
    readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE rowid > ?", arrayOf(rowId.toString())).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

  override fun lastAt(source: LogSource): Long? =
    readableDatabase.rawQuery("SELECT MAX(at_ms) FROM $TABLE WHERE source = ?", arrayOf(source.code)).use { c ->
      if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
    }

  override fun count(source: LogSource): Long =
    readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE source = ?", arrayOf(source.code)).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

  override fun rows(fromMs: Long, source: LogSource, cols: Collection<LogCol>): List<LogRow> {
    val names = (listOf("at_ms", "tz_min", "source", "trigger_code") + cols.map { it.key }).joinToString(", ")
    return readableDatabase.rawQuery("SELECT $names FROM $TABLE WHERE at_ms >= ? AND source = ? ORDER BY at_ms", arrayOf(fromMs.toString(), source.code)).use { c ->
      val out = ArrayList<LogRow>(c.count)
      while (c.moveToNext()) out += rowAt(c, cols)
      out
    }
  }

  override fun latest(source: LogSource, cols: Collection<LogCol>): Map<LogCol, LatestValue> {
    val out = LinkedHashMap<LogCol, LatestValue>()
    for (col in cols) {
      readableDatabase.rawQuery("SELECT ${col.key}, at_ms FROM $TABLE WHERE source = ? AND ${col.key} IS NOT NULL ORDER BY at_ms DESC LIMIT 1", arrayOf(source.code)).use { c ->
        if (c.moveToFirst()) out[col] = LatestValue(c.getDouble(0), c.getLong(1))
      }
    }
    return out
  }

  override fun <T> useRows(block: (Sequence<LogRow>) -> T): T =
    readableDatabase.rawQuery("SELECT * FROM $TABLE ORDER BY at_ms", null).use { c ->
      block(generateSequence { if (c.moveToNext()) rowAt(c, LogCol.entries) else null })
    }

  private fun rowAt(c: Cursor, cols: Collection<LogCol>): LogRow {
    val v = LinkedHashMap<LogCol, Double>()
    for (col in cols) {
      val i = c.getColumnIndex(col.key)
      if (i >= 0 && !c.isNull(i)) v[col] = c.getDouble(i)
    }
    return LogRow(
      atMs = c.getLong(c.getColumnIndexOrThrow("at_ms")),
      tzMinutes = c.getInt(c.getColumnIndexOrThrow("tz_min")),
      source = LogSource.of(c.getString(c.getColumnIndexOrThrow("source"))),
      trigger = LogTrigger.of(c.getString(c.getColumnIndexOrThrow("trigger_code"))),
      values = v,
    )
  }

  private companion object {
    const val FILE = "battery_log.db"
    const val TABLE = "log"
    const val ARRAYS = "log_arrays"
    const val VERSION = 1
  }
}
