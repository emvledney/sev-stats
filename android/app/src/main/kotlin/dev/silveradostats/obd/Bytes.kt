package dev.silveradostats.obd

/** Small byte / hex helpers shared by the OBD core (no locale-dependent formatting). */

private const val HEX_UPPER = "0123456789ABCDEF"
private const val HEX_LOWER = "0123456789abcdef"

internal fun u8(b: Byte): Int = b.toInt() and 0xFF

/** Uppercase hex with at least [width] digits, e.g. hex(0x7, 2) = "07". */
internal fun hex(value: Long, width: Int): String = java.lang.Long.toHexString(value).uppercase().padStart(width, '0')

internal fun hex(value: Int, width: Int): String = hex(value.toLong() and 0xFFFFFFFFL, width)

/** Python's f"{x:#x}" for a CAN id. */
internal fun hexId(id: Long): String = if (id < 0) "-0x" + java.lang.Long.toHexString(-id) else "0x" + java.lang.Long.toHexString(id)

/** Python's bytes.hex(): lowercase, no separator. */
fun hexLower(data: ByteArray): String = buildString(data.size * 2) {
  for (b in data) {
    append(HEX_LOWER[u8(b) shr 4])
    append(HEX_LOWER[u8(b) and 0xF])
  }
}

/** Python's bytes.hex().upper(): what the ELM link sends for a bus payload. */
fun hexUpper(data: ByteArray): String = buildString(data.size * 2) {
  for (b in data) {
    append(HEX_UPPER[u8(b) shr 4])
    append(HEX_UPPER[u8(b) and 0xF])
  }
}

/** Python's bytes.hex(" "): lowercase pairs separated by single spaces. */
fun hexSpaced(data: ByteArray): String = data.joinToString(" ") { b -> "" + HEX_LOWER[u8(b) shr 4] + HEX_LOWER[u8(b) and 0xF] }

/** Python's bytes.fromhex() for an even-length string of hex digits (no separators). */
fun bytesFromHex(s: String): ByteArray {
  require(s.length % 2 == 0) { "odd-length hex string" }
  return ByteArray(s.length / 2) { i ->
    val hi = Character.digit(s[2 * i], 16)
    val lo = Character.digit(s[2 * i + 1], 16)
    require(hi >= 0 && lo >= 0) { "not a hex string: $s" }
    ((hi shl 4) or lo).toByte()
  }
}

internal fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

/** data[from:] with Python slice semantics (empty when [from] is past the end). */
internal fun ByteArray.sliceFrom(from: Int): ByteArray = if (from >= size) ByteArray(0) else copyOfRange(maxOf(0, from), size)

/** data[from:to] with Python slice semantics for non-negative bounds. */
internal fun ByteArray.pySlice(from: Int, to: Int): ByteArray {
  val a = from.coerceIn(0, size)
  val b = to.coerceIn(a, size)
  return copyOfRange(a, b)
}

/** Python's bytes.decode("ascii", errors="replace"): every byte >= 0x80 becomes U+FFFD. */
fun asciiReplace(data: ByteArray): String = buildString(data.size) {
  for (b in data) append(if (b >= 0) b.toInt().toChar() else '�')
}

/** Python's str.encode() (UTF-8). */
internal fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

/** Python's bytes.decode(errors="replace") (UTF-8). */
internal fun utf8Decode(data: ByteArray): String = String(data, Charsets.UTF_8)

/** Python's default whitespace for str.strip() / bytes.strip(). */
internal fun isPyWhitespaceByte(b: Byte): Boolean = b == ' '.code.toByte() || b in 9..13

/** bytes.rstrip(): drop trailing ASCII whitespace. */
internal fun ByteArray.pyRstrip(): ByteArray {
  var end = size
  while (end > 0 && isPyWhitespaceByte(this[end - 1])) end--
  return copyOfRange(0, end)
}

/** bytes.strip(chars). */
internal fun ByteArray.pyStrip(chars: String): ByteArray {
  var a = 0
  var b = size
  while (a < b && this[a].toInt().toChar() in chars && this[a] >= 0) a++
  while (b > a && this[b - 1].toInt().toChar() in chars && this[b - 1] >= 0) b--
  return copyOfRange(a, b)
}

internal fun ByteArray.indexOfByte(value: Byte): Int {
  for (i in indices) if (this[i] == value) return i
  return -1
}

/** Python's repr() of a bytes object, for error messages (b'...'). */
internal fun pyBytesRepr(data: ByteArray): String = buildString {
  append("b'")
  for (b in data) {
    val c = u8(b)
    when {
      c == '\\'.code -> append("\\\\")
      c == '\''.code -> append("\\'")
      c == '\r'.code -> append("\\r")
      c == '\n'.code -> append("\\n")
      c == '\t'.code -> append("\\t")
      c in 0x20..0x7E -> append(c.toChar())
      else -> append("\\x").append(HEX_LOWER[c shr 4]).append(HEX_LOWER[c and 0xF])
    }
  }
  append("'")
}
