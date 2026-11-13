"""The internet-facing half: the truck pairs once, then posts batches of log rows.

Nothing here reads data back out. A valid token can only add rows for its own truck.

  POST /v1/pair  {"code": "ABCD2345"}                      -> {"token": "...", "device": "Silverado"}
  POST /v1/rows  Authorization: Bearer <token>
                 {"app": "0.5.0", "rows": [{"at_ms", "tz_min", "source", "trigger",
                                             "values": {key: number}, "arrays": {kind: [number]}}]}
                                                            -> {"received", "new", "rejected"}
  GET  /v1/ping  Authorization: Bearer <token>             -> {"device", "rows", "last_at_ms"}

A 200 from /v1/rows means the whole batch is dealt with and the app may move its cursor on: rows the
server already has count as received, and a row it cannot store is counted as rejected, not retried.
"""
import gzip
import json
import zlib

from fastapi import FastAPI, HTTPException, Request

from . import db, security

MAX_BODY = 1_000_000
MAX_INFLATED = 16_000_000
MAX_ROWS = 2000

app = FastAPI(title="sev-stats ingest", docs_url=None, redoc_url=None, openapi_url=None)
db.init()

_pair_by_ip = security.Limiter(8, 600)
_pair_all = security.Limiter(200, 3600)


def client_ip(request: Request) -> str:
    """nginx is the only caller, so its X-Real-IP is the truck's address."""
    return request.headers.get("x-real-ip") or (request.client.host if request.client else "?")


async def read_json(request: Request) -> dict:
    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > MAX_BODY:
        raise HTTPException(413, "body too large")
    body = bytearray()
    async for chunk in request.stream():
        body += chunk
        if len(body) > MAX_BODY:
            raise HTTPException(413, "body too large")
    if request.headers.get("content-encoding", "").lower() == "gzip":
        try:
            d = zlib.decompressobj(16 + zlib.MAX_WBITS)
            body = d.decompress(bytes(body), MAX_INFLATED)
            if d.unconsumed_tail:
                raise HTTPException(413, "body too large")
        except (zlib.error, gzip.BadGzipFile):
            raise HTTPException(400, "bad gzip") from None
    try:
        data = json.loads(body)
    except (ValueError, UnicodeDecodeError):
        raise HTTPException(400, "bad json") from None
    if not isinstance(data, dict):
        raise HTTPException(400, "expected an object")
    return data


def device_for(request: Request) -> int:
    auth = request.headers.get("authorization", "")
    if not auth.lower().startswith("bearer "):
        raise HTTPException(401, "token required")
    with db.reader() as con:
        row = con.execute("SELECT id FROM devices WHERE token_hash = ?", (security.token_hash(auth[7:].strip()),)).fetchone()
    if row is None:
        raise HTTPException(401, "unknown token")
    return row["id"]


@app.post("/v1/pair")
async def pair(request: Request):
    ip = client_ip(request)
    if _pair_by_ip.blocked(ip) or _pair_all.blocked("*"):
        raise HTTPException(429, "too many attempts, wait a few minutes")
    data = await read_json(request)
    code = security.normalise_code(str(data.get("code", "")))
    token = security.new_token()
    with db.writer() as con:
        con.execute("DELETE FROM pair_codes WHERE expires_ms < ?", (db.now_ms(),))
        row = con.execute(
            "SELECT p.device_id, d.name FROM pair_codes p JOIN devices d ON d.id = p.device_id WHERE p.code = ?", (code,)
        ).fetchone()
        if row is not None:
            con.execute("DELETE FROM pair_codes WHERE code = ?", (code,))
            con.execute(
                "UPDATE devices SET token_hash = ?, paired_ms = ?, last_ip = ? WHERE id = ?",
                (security.token_hash(token), db.now_ms(), ip, row["device_id"]),
            )
    if row is None:
        _pair_by_ip.fail(ip)
        _pair_all.fail("*")
        raise HTTPException(403, "code not valid")
    return {"token": token, "device": row["name"]}


@app.post("/v1/rows")
async def rows(request: Request):
    device_id = device_for(request)
    data = await read_json(request)
    batch = data.get("rows")
    if not isinstance(batch, list) or len(batch) > MAX_ROWS:
        raise HTTPException(400, f"rows: a list of at most {MAX_ROWS}")
    version = data.get("app")
    version = version[:32] if isinstance(version, str) else None
    with db.writer() as con:
        return db.store(con, device_id, batch, client_ip(request), version)


@app.get("/v1/ping")
def ping(request: Request):
    device_id = device_for(request)
    with db.reader() as con:
        d = con.execute("SELECT name FROM devices WHERE id = ?", (device_id,)).fetchone()
        r = con.execute("SELECT COUNT(*) n, MAX(at_ms) last FROM readings WHERE device_id = ?", (device_id,)).fetchone()
    return {"device": d["name"], "rows": r["n"], "last_at_ms": r["last"]}
