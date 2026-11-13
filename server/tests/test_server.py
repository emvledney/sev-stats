"""Run with: docker compose run --rm test   (see compose.yaml)."""
import gzip
import importlib
import json
import sqlite3
import time

import pytest
from fastapi.testclient import TestClient

NOW = int(time.time() * 1000)
H = {"X-Sev": "1"}


@pytest.fixture()
def srv(tmp_path, monkeypatch):
    monkeypatch.setenv("SEV_DATA", str(tmp_path))
    from sevstats import dash, db, ingest

    for m in (db, ingest, dash):
        importlib.reload(m)
    return TestClient(ingest.app), TestClient(dash.app), db


def signed_in(dash):
    assert dash.post("/api/setup", json={"name": "owner", "password": "correct horse"}, headers=H).status_code == 200
    return dash


def paired(ingest, dash, name="Silverado"):
    code = dash.post("/api/pair-code", json={"name": name}, headers=H).json()
    token = ingest.post("/v1/pair", json={"code": code["code"]}).json()["token"]
    return {"Authorization": f"Bearer {token}"}, code["device"]


def row(i=0, source="mx", **values):
    return {"at_ms": NOW - 3_600_000 + i * 60_000, "tz_min": -300, "source": source, "trigger": "job", "values": values or {"soc_pct": 80.0 - i}}


def test_ingest_needs_a_token(srv):
    ingest, _, _ = srv
    assert ingest.post("/v1/rows", json={"rows": []}).status_code == 401
    assert ingest.post("/v1/rows", json={"rows": []}, headers={"Authorization": "Bearer nope"}).status_code == 401
    assert ingest.get("/v1/ping").status_code == 401


def test_ingest_offers_nothing_to_read(srv):
    ingest, _, _ = srv
    for path in ("/", "/api/overview", "/api/export.csv", "/docs", "/openapi.json", "/v1/rows"):
        assert ingest.get(path).status_code in (404, 405)


def test_dashboard_needs_a_login(srv):
    _, dash, _ = srv
    for path in ("/api/overview", "/api/rows", "/api/export.csv", "/api/export.sqlite", "/api/series?source=mx&cols=a", "/api/arrays?kind=cell_v"):
        assert dash.get(path).status_code == 401
    assert dash.post("/api/pair-code", json={"name": "x"}, headers=H).status_code == 401
    assert dash.get("/").status_code == 200  # the page itself, which then asks for the login


def test_setup_once_then_login(srv):
    _, dash, _ = srv
    assert dash.get("/api/me").json() == {"setup": True, "signed_in": False}
    assert dash.post("/api/setup", json={"name": "owner", "password": "short"}, headers=H).status_code == 400
    signed_in(dash)
    assert dash.get("/api/me").json() == {"setup": False, "signed_in": True}
    assert dash.post("/api/setup", json={"name": "other", "password": "another long one"}, headers=H).status_code == 409
    dash.post("/api/logout", json={}, headers=H)
    dash.cookies.clear()
    assert dash.get("/api/overview").status_code == 401
    assert dash.post("/api/login", json={"name": "owner", "password": "wrong horse"}, headers=H).status_code == 403
    assert dash.post("/api/login", json={"name": "owner", "password": "correct horse"}).status_code == 400  # no header
    assert dash.post("/api/login", json={"name": "Owner", "password": "correct horse"}, headers=H).status_code == 200
    assert dash.get("/api/overview").status_code == 200


def test_login_attempts_are_limited(srv):
    _, dash, _ = srv
    signed_in(dash)
    dash.cookies.clear()
    codes = [dash.post("/api/login", json={"name": "owner", "password": f"guess {i} guess"}, headers=H).status_code for i in range(10)]
    assert codes[:8] == [403] * 8 and codes[8:] == [429, 429]


def test_pairing_code_works_once(srv):
    ingest, dash, _ = srv
    signed_in(dash)
    code = dash.post("/api/pair-code", json={"name": "Silverado"}, headers=H).json()["code"]
    assert ingest.post("/v1/pair", json={"code": "ZZZZ9999"}).status_code == 403
    spaced = f"{code[:4]} {code[4:]}".lower()
    assert ingest.post("/v1/pair", json={"code": spaced}).status_code == 200
    assert ingest.post("/v1/pair", json={"code": code}).status_code == 403


def test_pairing_guesses_are_limited(srv):
    ingest, _, _ = srv
    codes = [ingest.post("/v1/pair", json={"code": f"AAAA{i:04d}"}).status_code for i in range(10)]
    assert codes[:8] == [403] * 8 and codes[8:] == [429, 429]


def test_expired_code_is_refused(srv):
    ingest, dash, db = srv
    signed_in(dash)
    code = dash.post("/api/pair-code", json={"name": "Silverado"}, headers=H).json()["code"]
    with db.writer() as con:
        con.execute("UPDATE pair_codes SET expires_ms = ?", (NOW - 1,))
    assert ingest.post("/v1/pair", json={"code": code}).status_code == 403


def test_rows_are_stored_once(srv):
    ingest, dash, _ = srv
    signed_in(dash)
    auth, _ = paired(ingest, dash)
    batch = {"app": "0.5.0", "rows": [row(i) for i in range(5)]}
    assert ingest.post("/v1/rows", json=batch, headers=auth).json() == {"received": 5, "new": 5, "rejected": 0}
    assert ingest.post("/v1/rows", json=batch, headers=auth).json() == {"received": 5, "new": 0, "rejected": 0}
    assert ingest.get("/v1/ping", headers=auth).json()["rows"] == 5
    o = dash.get("/api/overview").json()
    assert o["sources"][0]["rows"] == 5 and o["devices"][0]["app_version"] == "0.5.0"
    assert [u["n_new"] for u in o["uploads"]] == [0, 5]


def test_same_time_from_both_sources_are_two_rows(srv):
    ingest, dash, _ = srv
    signed_in(dash)
    auth, _ = paired(ingest, dash)
    r = ingest.post("/v1/rows", json={"rows": [row(0, "mx"), row(0, "car", car_soc_pct=79.0)]}, headers=auth).json()
    assert r["new"] == 2


def test_new_value_keys_become_columns(srv):
    ingest, dash, _ = srv
    signed_in(dash)
    auth, _ = paired(ingest, dash)
    ingest.post("/v1/rows", json={"rows": [row(0, soc_pct=80.5, brand_new_reading_v=3.71)]}, headers=auth)
    s = dash.get("/api/series?source=mx&cols=soc_pct,brand_new_reading_v").json()
    assert s["series"] == {"soc_pct": [80.5], "brand_new_reading_v": [3.71]}
    assert dash.get("/api/series?source=mx&cols=nope").status_code == 400


def test_bad_rows_are_counted_and_never_stored(srv):
    ingest, dash, db = srv
    signed_in(dash)
    auth, _ = paired(ingest, dash)
    bad = [
        "text",
        {"at_ms": 5, "source": "mx"},  # a clock that was never set
        {"at_ms": NOW + 3 * 86_400_000, "source": "mx"},  # the future
        {"at_ms": NOW, "source": "mx; DROP TABLE readings"},
        {"at_ms": NOW, "source": "mx", "tz_min": 99999},
        {"at_ms": True, "source": "mx"},
    ]
    assert ingest.post("/v1/rows", json={"rows": bad + [row(0)]}, headers=auth).json() == {"received": 7, "new": 1, "rejected": 6}
    # Values that are not finite numbers, and keys that are not plain names, are dropped; the row stays.
    odd = row(1, soc_pct=70.0)
    odd["values"].update({'x" REAL); DROP TABLE readings; --': 1, "Upper": 1, "at_ms": 1, "flag": True, "text": "7"})
    assert ingest.post("/v1/rows", json={"rows": [odd]}, headers=auth).json()["new"] == 1
    with db.reader() as con:
        assert db.value_columns(con) == ["soc_pct"]


def test_batch_limits(srv):
    ingest, dash, _ = srv
    signed_in(dash)
    auth, _ = paired(ingest, dash)
    assert ingest.post("/v1/rows", json={"rows": [row(0)] * 2001}, headers=auth).status_code == 400
    assert ingest.post("/v1/rows", json={"rows": "x"}, headers=auth).status_code == 400
    assert ingest.post("/v1/rows", content=b"{", headers=auth).status_code == 400
    big = json.dumps({"rows": [], "pad": "x" * 1_100_000}).encode()
    assert ingest.post("/v1/rows", content=big, headers=auth).status_code == 413
    bomb = gzip.compress(b'{"rows": [], "pad": "' + b"x" * 40_000_000 + b'"}')
    assert len(bomb) < 1_000_000
    assert ingest.post("/v1/rows", content=bomb, headers={**auth, "Content-Encoding": "gzip"}).status_code == 413


def test_gzip_upload(srv):
    ingest, dash, _ = srv
    signed_in(dash)
    auth, _ = paired(ingest, dash)
    body = gzip.compress(json.dumps({"rows": [row(i) for i in range(3)]}).encode())
    assert ingest.post("/v1/rows", content=body, headers={**auth, "Content-Encoding": "gzip"}).json()["new"] == 3


def test_cell_snapshots(srv):
    ingest, dash, _ = srv
    signed_in(dash)
    auth, _ = paired(ingest, dash)
    rows = []
    for i in range(3):
        r = row(i)
        r["arrays"] = {"cell_v": [3.70 + i * 0.01, 3.72, None, 3.74]}
        rows.append(r)
    ingest.post("/v1/rows", json={"rows": rows}, headers=auth)
    a = dash.get("/api/arrays?kind=cell_v&idx=1,3").json()
    assert a["min"] == [3.70, 3.71, 3.72] and a["max"] == [3.74] * 3
    assert a["picked"]["1"] == [3.70, 3.71, 3.72] and a["picked"]["3"] == [None] * 3
    assert a["latest"]["vals"] == [3.72, 3.72, None, 3.74]
    lines = dash.get("/api/export-arrays.csv?kind=cell_v").text.splitlines()
    assert lines[0] == "time_utc,source,cell_v_1,cell_v_2,cell_v_3,cell_v_4" and lines[1].endswith(",mx,3.7,3.72,,3.74")


def test_revoke_and_pair_again_keep_one_history(srv):
    ingest, dash, _ = srv
    signed_in(dash)
    auth, device = paired(ingest, dash)
    ingest.post("/v1/rows", json={"rows": [row(0)]}, headers=auth)
    dash.post(f"/api/devices/{device}/revoke", json={}, headers=H)
    assert ingest.post("/v1/rows", json={"rows": [row(1)]}, headers=auth).status_code == 401
    code = dash.post("/api/pair-code", json={"device": device}, headers=H).json()["code"]
    auth2 = {"Authorization": "Bearer " + ingest.post("/v1/pair", json={"code": code}).json()["token"]}
    # The truck starts over from its first row: nothing doubles.
    assert ingest.post("/v1/rows", json={"rows": [row(0), row(1)]}, headers=auth2).json()["new"] == 1
    assert len(dash.get("/api/overview").json()["devices"]) == 1


def test_one_truck_cannot_be_another(srv):
    ingest, dash, _ = srv
    signed_in(dash)
    a, _ = paired(ingest, dash, "A")
    b, _ = paired(ingest, dash, "B")
    ingest.post("/v1/rows", json={"rows": [row(0)]}, headers=a)
    assert ingest.post("/v1/rows", json={"rows": [row(0)]}, headers=b).json()["new"] == 1
    assert ingest.get("/v1/ping", headers=a).json() == {"device": "A", "rows": 1, "last_at_ms": row(0)["at_ms"]}


def test_csv_matches_the_apps_layout(srv):
    ingest, dash, _ = srv
    signed_in(dash)
    auth, _ = paired(ingest, dash)
    at = 1_762_000_000_123
    ingest.post("/v1/rows", json={"rows": [
        {"at_ms": at, "tz_min": -300, "source": "mx", "trigger": "mx_visit", "values": {"soc_pct": 81.25, "cell_min_v": 3.70456}},
        {"at_ms": at + 1000, "tz_min": -300, "source": "car", "trigger": "job", "values": {"soc_pct": 80.0}},
    ]}, headers=auth)
    lines = dash.get("/api/export.csv").text.splitlines()
    assert lines[0] == "time_utc,tz_minutes,source,trigger,soc_pct,cell_min_v"
    assert lines[1] == "2025-11-01T12:26:40.123Z,-300,mx,mx_visit,81.25,3.7046"
    assert lines[2] == "2025-11-01T12:26:41.123Z,-300,car,job,80,"
    assert len(dash.get("/api/export.csv?source=car").text.splitlines()) == 2


def test_sqlite_download_holds_no_secrets(srv, tmp_path):
    ingest, dash, _ = srv
    signed_in(dash)
    auth, _ = paired(ingest, dash)
    ingest.post("/v1/rows", json={"rows": [row(0)]}, headers=auth)
    out = tmp_path / "copy.sqlite"
    out.write_bytes(dash.get("/api/export.sqlite").content)
    con = sqlite3.connect(out)
    assert con.execute("SELECT COUNT(*) FROM readings").fetchone()[0] == 1
    assert con.execute("SELECT COUNT(*) FROM users").fetchone()[0] == 0
    assert con.execute("SELECT COUNT(*) FROM sessions").fetchone()[0] == 0
    assert con.execute("SELECT token_hash FROM devices").fetchone()[0] is None
    # The live database still has them.
    assert ingest.get("/v1/ping", headers=auth).status_code == 200


def test_long_series_is_averaged_down(srv):
    ingest, dash, _ = srv
    signed_in(dash)
    auth, _ = paired(ingest, dash)
    base = NOW - 30 * 86_400_000
    for start in range(0, 13_000, 1000):
        rows = [{"at_ms": base + i * 60_000, "source": "mx", "trigger": "job", "values": {"soc_pct": float(i % 2)}} for i in range(start, start + 1000)]
        assert ingest.post("/v1/rows", json={"rows": rows}, headers=auth).json()["new"] == 1000
    s = dash.get("/api/series?source=mx&cols=soc_pct").json()
    assert s["rows"] == 13_000 and s["per_point"] == 3 and len(s["t"]) == 4334
    page = dash.get("/api/rows?source=mx&limit=50&offset=100").json()
    assert page["total"] == 13_000 and len(page["rows"]) == 50 and page["columns"] == ["soc_pct"]


def test_password_change_signs_other_browsers_out(srv):
    _, dash, _ = srv
    signed_in(dash)
    old_cookie = dash.cookies.get("sev_session")
    assert dash.post("/api/password", json={"old": "nope", "new": "a new long password"}, headers=H).status_code == 403
    assert dash.post("/api/password", json={"old": "correct horse", "new": "a new long password"}, headers=H).status_code == 200
    assert dash.get("/api/overview").status_code == 200
    dash.cookies.clear()
    dash.cookies.set("sev_session", old_cookie)
    assert dash.get("/api/overview").status_code == 401


def test_the_apps_own_request_body_is_accepted(srv):
    """The literal body android's UploadModelTest.aRowGoesOutAsTheServerReadsIt pins: keep the two in step."""
    ingest, dash, _ = srv
    signed_in(dash)
    auth, _ = paired(ingest, dash)
    body = (
        '{"app":"0.5.0","rows":[{"at_ms":1800000000123,"tz_min":-300,"source":"mx","trigger":"mx_visit",'
        '"values":{"soc_pct":81.25,"cell_min_v":3.7046,"pack_kw":0},"arrays":{"cell_v":[3.7012,null,3.7],"module_t":[21.5,null]}},'
        '{"at_ms":1800000060000,"tz_min":-300,"source":"car","trigger":"mx_screen","values":{"soc_pct":79}}]}'
    )
    import sevstats.db as db_mod
    real_now = db_mod.now_ms
    db_mod.now_ms = lambda: 1_800_000_100_000  # the pinned rows are dated 2027
    try:
        assert ingest.post("/v1/rows", content=body, headers=auth).json() == {"received": 2, "new": 2, "rejected": 0}
    finally:
        db_mod.now_ms = real_now
    a = dash.get("/api/arrays?kind=cell_v").json()
    assert a["latest"]["vals"] == [3.7012, None, 3.7]
    assert dash.get("/api/series?source=mx&cols=soc_pct,cell_min_v,pack_kw").json()["series"] == {"soc_pct": [81.25], "cell_min_v": [3.7046], "pack_kw": [0.0]}
