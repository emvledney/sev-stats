"""The LAN-only half: login, charts, table, downloads, and pairing a truck.

Published on the LAN address only (compose.yaml) and never proxied by nginx. The first visit sets
the one user's name and password.
"""
import csv
import io
import json
import os
import sqlite3
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.responses import FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from starlette.background import BackgroundTask

from . import db, security

WEB = Path(__file__).parent / "web"
COOKIE = "sev_session"
SESSION_MS = 30 * 86_400_000
MAX_POINTS = 6000

app = FastAPI(title="sev-stats dashboard", docs_url=None, redoc_url=None, openapi_url=None)
db.init()

_login = security.Limiter(8, 300)


# ---- session ---------------------------------------------------------------------------------


def has_user(con: sqlite3.Connection) -> bool:
    return con.execute("SELECT 1 FROM users").fetchone() is not None


def session_ok(request: Request) -> bool:
    token = request.cookies.get(COOKIE)
    if not token:
        return False
    with db.reader() as con:
        row = con.execute("SELECT expires_ms FROM sessions WHERE token_hash = ?", (security.token_hash(token),)).fetchone()
    return row is not None and row["expires_ms"] > db.now_ms()


def user(request: Request) -> None:
    if not session_ok(request):
        raise HTTPException(401, "sign in")


def changes(request: Request) -> None:
    """Every change needs this header: a page on another site cannot add it to a request it forges."""
    if request.headers.get("x-sev") != "1":
        raise HTTPException(400, "missing header")


def start_session(response: Response) -> None:
    token = security.new_token()
    with db.writer() as con:
        con.execute("DELETE FROM sessions WHERE expires_ms < ?", (db.now_ms(),))
        con.execute("INSERT INTO sessions VALUES(?, ?)", (security.token_hash(token), db.now_ms() + SESSION_MS))
    response.set_cookie(COOKIE, token, max_age=SESSION_MS // 1000, httponly=True, samesite="strict", path="/")


def credentials(data: dict) -> tuple[str, str]:
    name, pw = data.get("name"), data.get("password")
    if not isinstance(name, str) or not isinstance(pw, str) or not name.strip():
        raise HTTPException(400, "name and password required")
    return name.strip()[:64], pw


@app.get("/api/me")
def me(request: Request):
    with db.reader() as con:
        setup = not has_user(con)
    return {"setup": setup, "signed_in": not setup and session_ok(request)}


@app.post("/api/setup", dependencies=[Depends(changes)])
async def setup(request: Request, response: Response):
    name, pw = credentials(await request.json())
    if len(pw) < 10:
        raise HTTPException(400, "password: at least 10 characters")
    hashed = security.hash_password(pw)
    with db.writer() as con:
        if has_user(con):
            raise HTTPException(409, "already set up")
        con.execute("INSERT INTO users VALUES(1, ?, ?)", (name, hashed))
    start_session(response)
    return {"ok": True}


@app.post("/api/login", dependencies=[Depends(changes)])
async def login(request: Request, response: Response):
    if _login.blocked("*"):
        raise HTTPException(429, "too many attempts, wait a few minutes")
    name, pw = credentials(await request.json())
    with db.reader() as con:
        row = con.execute("SELECT name, pw FROM users WHERE id = 1").fetchone()
    if row is None or not security.check_password(pw, row["pw"]) or name.lower() != row["name"].lower():
        _login.fail("*")
        raise HTTPException(403, "wrong name or password")
    start_session(response)
    return {"ok": True}


@app.post("/api/logout", dependencies=[Depends(changes)])
def logout(request: Request, response: Response):
    token = request.cookies.get(COOKIE)
    if token:
        with db.writer() as con:
            con.execute("DELETE FROM sessions WHERE token_hash = ?", (security.token_hash(token),))
    response.delete_cookie(COOKIE, path="/")
    return {"ok": True}


@app.post("/api/password", dependencies=[Depends(changes), Depends(user)])
async def password(request: Request, response: Response):
    data = await request.json()
    old, new = data.get("old"), data.get("new")
    if not isinstance(old, str) or not isinstance(new, str) or len(new) < 10:
        raise HTTPException(400, "new password: at least 10 characters")
    with db.reader() as con:
        row = con.execute("SELECT pw FROM users WHERE id = 1").fetchone()
    if not security.check_password(old, row["pw"]):
        raise HTTPException(403, "current password is wrong")
    hashed = security.hash_password(new)
    with db.writer() as con:
        con.execute("UPDATE users SET pw = ? WHERE id = 1", (hashed,))
        con.execute("DELETE FROM sessions")
    start_session(response)
    return {"ok": True}


# ---- reading the data ------------------------------------------------------------------------


def where(source: str | None, device: int | None, frm: int | None, to: int | None, table: str = "") -> tuple[str, list]:
    parts, args = [], []
    for col, op, v in (("device_id", "=", device), ("source", "=", source), ("at_ms", ">=", frm), ("at_ms", "<=", to)):
        if v is not None:
            parts.append(f"{table}{col} {op} ?")
            args.append(v)
    return (" WHERE " + " AND ".join(parts) if parts else ""), args


def pick(con: sqlite3.Connection, cols: str | None) -> list[str]:
    have = db.value_columns(con)
    if not cols:
        return have
    want = [c for c in cols.split(",") if c]
    bad = [c for c in want if c not in have]
    if bad:
        raise HTTPException(400, f"unknown column: {bad[0]}")
    return want


def q(c: str) -> str:
    return '"' + c + '"'


@app.get("/api/overview", dependencies=[Depends(user)])
def overview(device: int | None = None):
    with db.reader() as con:
        cols = db.value_columns(con)
        dev_sql, dev_args = where(None, device, None, None)
        counts = ",".join(f"COUNT({q(c)}) AS {q(c)}" for c in cols)
        sources = []
        for r in con.execute(
            f"SELECT source, COUNT(*) n, MIN(at_ms) first, MAX(at_ms) last{',' + counts if cols else ''} "
            f"FROM readings{dev_sql} GROUP BY source ORDER BY source",
            dev_args,
        ):
            latest = []
            for c in cols:
                if not r[c]:
                    continue
                sql, args = where(r["source"], device, None, None)
                v = con.execute(
                    f"SELECT at_ms, {q(c)} v FROM readings{sql} AND {q(c)} IS NOT NULL ORDER BY at_ms DESC LIMIT 1", args
                ).fetchone()
                latest.append({"col": c, "n": r[c], "at_ms": v["at_ms"], "value": v["v"]})
            sources.append({"source": r["source"], "rows": r["n"], "first_ms": r["first"], "last_ms": r["last"], "latest": latest})
        uploads = [
            dict(r)
            for r in con.execute(
                "SELECT u.received_ms, u.n_rows, u.n_new, u.n_rejected, u.app_version, d.name device "
                "FROM uploads u JOIN devices d ON d.id = u.device_id ORDER BY u.id DESC LIMIT 30"
            )
        ]
        kinds = [dict(r) for r in con.execute("SELECT kind, COUNT(*) n, MAX(at_ms) last_ms FROM arrays GROUP BY kind ORDER BY kind")]
    return {"now_ms": db.now_ms(), "sources": sources, "uploads": uploads, "arrays": kinds, "devices": devices()}


@app.get("/api/series", dependencies=[Depends(user)])
def series(source: str, cols: str, device: int | None = None, frm: int | None = None, to: int | None = None):
    """Columns over time. Over [MAX_POINTS] rows, each bucket of rows is averaged (empty cells ignored)."""
    with db.reader() as con:
        want = pick(con, cols)
        sql, args = where(source, device, frm, to)
        rows = con.execute(f"SELECT at_ms,{','.join(q(c) for c in want)} FROM readings{sql} ORDER BY at_ms", args).fetchall()
    n = len(rows)
    step = -(-n // MAX_POINTS) if n > MAX_POINTS else 1
    t, out = [], {c: [] for c in want}
    for i in range(0, n, step):
        chunk = rows[i : i + step]
        t.append(sum(r["at_ms"] for r in chunk) / len(chunk) / 1000)
        for c in want:
            vals = [r[c] for r in chunk if r[c] is not None]
            out[c].append(sum(vals) / len(vals) if vals else None)
    return {"t": t, "series": out, "rows": n, "per_point": step}


@app.get("/api/rows", dependencies=[Depends(user)])
def rows(source: str | None = None, device: int | None = None, frm: int | None = None, to: int | None = None,
         limit: int = 200, offset: int = 0):
    limit, offset = max(1, min(limit, 1000)), max(0, offset)
    with db.reader() as con:
        cols = db.value_columns(con)
        sql, args = where(source, device, frm, to)
        total = con.execute(f"SELECT COUNT(*) FROM readings{sql}", args).fetchone()[0]
        got = con.execute(
            f"SELECT at_ms, tz_min, source, trigger_code{''.join(',' + q(c) for c in cols)} FROM readings{sql} "
            "ORDER BY at_ms DESC LIMIT ? OFFSET ?",
            [*args, limit, offset],
        ).fetchall()
    used = [c for c in cols if any(r[c] is not None for r in got)]
    return {
        "total": total,
        "columns": used,
        "rows": [[r["at_ms"], r["tz_min"], r["source"], r["trigger_code"], *[r[c] for c in used]] for r in got],
    }


def number(x: float | None) -> str:
    """As the app's CSV: at most four decimals, no trailing zeros, never an exponent."""
    if x is None:
        return ""
    s = f"{x:.4f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def iso(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + f"{ms % 1000:03d}Z"


@app.get("/api/export.csv", dependencies=[Depends(user)])
def export_csv(source: str | None = None, device: int | None = None, frm: int | None = None, to: int | None = None,
               cols: str | None = None):
    """The app's own CSV layout (time_utc, tz_minutes, source, trigger, then the values)."""
    con = db.connect()
    want = pick(con, cols)
    sql, args = where(source, device, frm, to)

    def lines():
        try:
            buf = io.StringIO()
            w = csv.writer(buf, lineterminator="\n")
            w.writerow(["time_utc", "tz_minutes", "source", "trigger", *want])
            cur = con.execute(
                f"SELECT at_ms, tz_min, source, trigger_code{''.join(',' + q(c) for c in want)} FROM readings{sql} ORDER BY at_ms", args
            )
            for i, r in enumerate(cur):
                w.writerow([iso(r[0]), r[1], r[2], r[3], *[number(v) for v in r[4:]]])
                if i % 500 == 499:
                    yield buf.getvalue()
                    buf.seek(0)
                    buf.truncate()
            yield buf.getvalue()
        finally:
            con.close()

    name = f"silverado-stats-{source or 'all'}-{datetime.now():%Y%m%d}.csv"
    return StreamingResponse(lines(), media_type="text/csv", headers={"Content-Disposition": f'attachment; filename="{name}"'})


@app.get("/api/export-arrays.csv", dependencies=[Depends(user)])
def export_arrays(kind: str, device: int | None = None, frm: int | None = None, to: int | None = None):
    """One line per snapshot: time_utc, source, then one cell per position."""
    con = db.connect()
    sql, args = where(None, device, frm, to)
    sql = sql + (" AND " if sql else " WHERE ") + "kind = ?"

    def lines():
        try:
            buf = io.StringIO()
            w = csv.writer(buf, lineterminator="\n")
            width = con.execute("SELECT MAX(json_array_length(vals)) FROM arrays WHERE kind = ?", (kind,)).fetchone()[0] or 0
            w.writerow(["time_utc", "source", *[f"{kind}_{i + 1}" for i in range(width)]])
            for i, r in enumerate(con.execute(f"SELECT at_ms, source, vals FROM arrays{sql} ORDER BY at_ms", [*args, kind])):
                w.writerow([iso(r[0]), r[1], *[number(v) for v in json.loads(r[2])]])
                if i % 200 == 199:
                    yield buf.getvalue()
                    buf.seek(0)
                    buf.truncate()
            yield buf.getvalue()
        finally:
            con.close()

    name = f"silverado-stats-{kind}-{datetime.now():%Y%m%d}.csv"
    return StreamingResponse(lines(), media_type="text/csv", headers={"Content-Disposition": f'attachment; filename="{name}"'})


@app.get("/api/export.sqlite", dependencies=[Depends(user)])
def export_sqlite():
    """A consistent copy of the data. Logins, sessions and device tokens are left out of it."""
    fd, tmp = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    src, dst = db.connect(), sqlite3.connect(tmp)
    try:
        src.backup(dst)
        for t in ("users", "sessions", "pair_codes"):
            dst.execute(f"DELETE FROM {t}")
        dst.execute("UPDATE devices SET token_hash = NULL, last_ip = NULL")
        dst.commit()
        dst.execute("VACUUM")
    finally:
        src.close()
        dst.close()
    name = f"silverado-stats-{datetime.now():%Y%m%d}.sqlite"
    return FileResponse(tmp, media_type="application/vnd.sqlite3", filename=name, background=BackgroundTask(os.unlink, tmp))


@app.get("/api/arrays", dependencies=[Depends(user)])
def arrays(kind: str, device: int | None = None, frm: int | None = None, to: int | None = None, idx: str | None = None):
    """Snapshots of one kind over time: min, mean and max of each, plus the positions in [idx] (1-based)."""
    try:
        picks = [int(x) for x in (idx or "").split(",") if x][:24]
    except ValueError:
        raise HTTPException(400, "idx: numbers") from None
    with db.reader() as con:
        sql, args = where(None, device, frm, to)
        sql = sql + (" AND " if sql else " WHERE ") + "kind = ?"
        got = con.execute(f"SELECT at_ms, vals FROM arrays{sql} ORDER BY at_ms", [*args, kind]).fetchall()
    step = -(-len(got) // MAX_POINTS) if len(got) > MAX_POINTS else 1
    got = got[::step]
    t, lo, mean, hi = [], [], [], []
    picked = {i: [] for i in picks}
    last = None
    for r in got:
        vals = json.loads(r["vals"])
        nums = [v for v in vals if v is not None]
        t.append(r["at_ms"] / 1000)
        lo.append(min(nums) if nums else None)
        hi.append(max(nums) if nums else None)
        mean.append(sum(nums) / len(nums) if nums else None)
        for i in picks:
            picked[i].append(vals[i - 1] if 0 < i <= len(vals) else None)
        last = {"at_ms": r["at_ms"], "vals": vals}
    return {"t": t, "min": lo, "mean": mean, "max": hi, "picked": picked, "latest": last, "every": step}


# ---- trucks ----------------------------------------------------------------------------------


def devices() -> list[dict]:
    with db.reader() as con:
        return [
            dict(r)
            for r in con.execute(
                "SELECT d.id, d.name, d.created_ms, d.paired_ms, d.last_seen_ms, d.app_version, "
                "d.token_hash IS NOT NULL AS paired, (SELECT COUNT(*) FROM readings r WHERE r.device_id = d.id) AS n_rows "
                "FROM devices d ORDER BY d.id"
            )
        ]


@app.post("/api/pair-code", dependencies=[Depends(changes), Depends(user)])
async def pair_code(request: Request):
    """A one-time code for the truck's Settings page. With [device] it re-pairs that truck: its old token stops working once the code is used."""
    data = await request.json()
    device, name = data.get("device"), data.get("name")
    code = security.new_code()
    with db.writer() as con:
        if device is None:
            if not isinstance(name, str) or not name.strip():
                raise HTTPException(400, "name required")
            device = con.execute("INSERT INTO devices(name, created_ms) VALUES(?, ?)", (name.strip()[:64], db.now_ms())).lastrowid
        elif con.execute("SELECT 1 FROM devices WHERE id = ?", (device,)).fetchone() is None:
            raise HTTPException(404, "no such truck")
        con.execute("DELETE FROM pair_codes WHERE device_id = ? OR expires_ms < ?", (device, db.now_ms()))
        expires = db.now_ms() + security.CODE_TTL_MS
        con.execute("INSERT INTO pair_codes VALUES(?, ?, ?)", (code, device, expires))
    return {"code": code, "expires_ms": expires, "device": device}


@app.post("/api/devices/{device}/revoke", dependencies=[Depends(changes), Depends(user)])
def revoke(device: int):
    """Stops the truck's uploads. Its rows stay."""
    with db.writer() as con:
        con.execute("UPDATE devices SET token_hash = NULL WHERE id = ?", (device,))
        con.execute("DELETE FROM pair_codes WHERE device_id = ?", (device,))
    return {"ok": True}


@app.post("/api/devices/{device}/rename", dependencies=[Depends(changes), Depends(user)])
async def rename(device: int, request: Request):
    name = (await request.json()).get("name")
    if not isinstance(name, str) or not name.strip():
        raise HTTPException(400, "name required")
    with db.writer() as con:
        con.execute("UPDATE devices SET name = ? WHERE id = ?", (name.strip()[:64], device))
    return {"ok": True}


@app.middleware("http")
async def headers(request: Request, call_next):
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Content-Security-Policy"] = "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:"
    if request.url.path.startswith("/api/"):
        response.headers["Cache-Control"] = "no-store"
    return response


app.mount("/", StaticFiles(directory=WEB, html=True), name="web")
