"""SQLite storage, shared by the ingest and dashboard processes (WAL, one file).

`readings` mirrors the app's log table: a few fixed columns and one REAL column per value. A value
key the server has not seen yet gets its column on arrival, the same way the app's own database
grows, so a new app version needs no server change.

A device is a truck. Re-pairing a truck swaps its token and keeps its id, so its history stays in
one piece and an upload that starts over cannot duplicate rows.
"""
import contextlib
import json
import os
import re
import sqlite3
import threading
import time
from pathlib import Path

KEY_RE = re.compile(r"^[a-z][a-z0-9_]{0,39}$")
TAG_RE = re.compile(r"^[a-z0-9_]{1,24}$")
FIXED = ("device_id", "at_ms", "source", "tz_min", "trigger_code", "received_ms")
MAX_COLUMNS = 400
MAX_ARRAY = 512
# 2024-01-01: the app refuses to log with a clock before this.
MIN_AT_MS = 1_704_067_200_000

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices(
  id INTEGER PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT UNIQUE, created_ms INTEGER NOT NULL,
  paired_ms INTEGER, last_seen_ms INTEGER, last_ip TEXT, app_version TEXT);
CREATE TABLE IF NOT EXISTS pair_codes(
  code TEXT PRIMARY KEY, device_id INTEGER NOT NULL, expires_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS readings(
  device_id INTEGER NOT NULL, at_ms INTEGER NOT NULL, source TEXT NOT NULL, tz_min INTEGER NOT NULL,
  trigger_code TEXT NOT NULL, received_ms INTEGER NOT NULL,
  PRIMARY KEY(device_id, source, at_ms)) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS arrays(
  device_id INTEGER NOT NULL, kind TEXT NOT NULL, at_ms INTEGER NOT NULL, source TEXT NOT NULL,
  vals TEXT NOT NULL, PRIMARY KEY(device_id, kind, at_ms, source)) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS uploads(
  id INTEGER PRIMARY KEY, device_id INTEGER NOT NULL, received_ms INTEGER NOT NULL,
  n_rows INTEGER NOT NULL, n_new INTEGER NOT NULL, n_rejected INTEGER NOT NULL, ip TEXT, app_version TEXT);
CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY CHECK(id = 1), name TEXT NOT NULL, pw TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS sessions(token_hash TEXT PRIMARY KEY, expires_ms INTEGER NOT NULL);
"""

_write = threading.Lock()


def now_ms() -> int:
    return int(time.time() * 1000)


def path() -> Path:
    return Path(os.environ.get("SEV_DATA", "./data")) / "sevstats.db"


def connect() -> sqlite3.Connection:
    p = path()
    p.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(p, timeout=15, isolation_level=None)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA synchronous=NORMAL")
    db.execute("PRAGMA busy_timeout=15000")
    return db


@contextlib.contextmanager
def reader():
    db = connect()
    try:
        yield db
    finally:
        db.close()


@contextlib.contextmanager
def writer():
    """One write transaction. The lock covers this process, BEGIN IMMEDIATE the other one."""
    with _write:
        db = connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            yield db
            db.execute("COMMIT")
        except BaseException:
            with contextlib.suppress(sqlite3.Error):
                db.execute("ROLLBACK")
            raise
        finally:
            db.close()


def init() -> None:
    with writer() as db:
        for stmt in SCHEMA.split(";"):
            if stmt.strip():
                db.execute(stmt)


def value_columns(db: sqlite3.Connection) -> list[str]:
    """The value columns, in the order they first arrived."""
    return [r["name"] for r in db.execute("PRAGMA table_info(readings)") if r["name"] not in FIXED]


def _num(v) -> float | None:
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return None
    f = float(v)
    return f if f == f and abs(f) != float("inf") else None


def clean_row(raw, now: int) -> dict | None:
    """A validated row, or None when it cannot be stored. Unknown extras are ignored."""
    if not isinstance(raw, dict):
        return None
    at, tz = raw.get("at_ms"), raw.get("tz_min", 0)
    src, trig = raw.get("source"), raw.get("trigger", "")
    if isinstance(at, bool) or not isinstance(at, int) or not (MIN_AT_MS <= at <= now + 86_400_000):
        return None
    if isinstance(tz, bool) or not isinstance(tz, int) or not (-900 <= tz <= 900):
        return None
    if not isinstance(src, str) or not TAG_RE.match(src):
        return None
    if not isinstance(trig, str) or not TAG_RE.match(trig or "none"):
        return None
    values = {}
    rv = raw.get("values") or {}
    if not isinstance(rv, dict):
        return None
    for k, v in rv.items():
        n = _num(v)
        if isinstance(k, str) and KEY_RE.match(k) and k not in FIXED and n is not None:
            values[k] = n
    arrays = {}
    ra = raw.get("arrays") or {}
    if not isinstance(ra, dict):
        return None
    for k, v in ra.items():
        if isinstance(k, str) and KEY_RE.match(k) and isinstance(v, list) and 0 < len(v) <= MAX_ARRAY:
            arrays[k] = [_num(x) for x in v]
    return {"at_ms": at, "tz_min": tz, "source": src, "trigger": trig or "none", "values": values, "arrays": arrays}


def store(db: sqlite3.Connection, device_id: int, raw_rows: list, ip: str | None, app_version: str | None) -> dict:
    """Stores a batch inside the caller's transaction. A row already held is left as it is."""
    now = now_ms()
    have = set(value_columns(db))
    n_new = n_rejected = 0
    for raw in raw_rows:
        row = clean_row(raw, now)
        if row is None:
            n_rejected += 1
            continue
        for k in row["values"]:
            if k not in have:
                if len(have) >= MAX_COLUMNS:
                    continue
                db.execute(f'ALTER TABLE readings ADD COLUMN "{k}" REAL')
                have.add(k)
        vals = {k: v for k, v in row["values"].items() if k in have}
        cols = ["device_id", "at_ms", "source", "tz_min", "trigger_code", "received_ms", *vals]
        args = [device_id, row["at_ms"], row["source"], row["tz_min"], row["trigger"], now, *vals.values()]
        cur = db.execute(
            f"INSERT OR IGNORE INTO readings({','.join(chr(34) + c + chr(34) for c in cols)}) "
            f"VALUES({','.join('?' * len(cols))})",
            args,
        )
        n_new += cur.rowcount
        for kind, arr in row["arrays"].items():
            db.execute(
                "INSERT OR IGNORE INTO arrays(device_id, kind, at_ms, source, vals) VALUES(?,?,?,?,?)",
                (device_id, kind, row["at_ms"], row["source"], json.dumps(arr, separators=(",", ":"))),
            )
    db.execute(
        "INSERT INTO uploads(device_id, received_ms, n_rows, n_new, n_rejected, ip, app_version) VALUES(?,?,?,?,?,?,?)",
        (device_id, now, len(raw_rows), n_new, n_rejected, ip, app_version),
    )
    db.execute("DELETE FROM uploads WHERE id <= (SELECT MAX(id) FROM uploads) - 5000")
    db.execute(
        "UPDATE devices SET last_seen_ms = ?, last_ip = ?, app_version = COALESCE(?, app_version) WHERE id = ?",
        (now, ip, app_version, device_id),
    )
    return {"received": len(raw_rows), "new": n_new, "rejected": n_rejected}
