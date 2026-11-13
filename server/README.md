# Silverado Stats server

Receives the truck's log rows and shows them on a dashboard that only the home network can reach.

```
truck app ──HTTPS──> nginx (api.vl-development.com/sev/v1/) ──> ingest :8790 ─┐
                                                                              ├─ SQLite (/data/sevstats.db)
browser on the LAN ──HTTP──> 10.0.0.15:8791 ─────────────────> dash   :8791 ─┘
```

Two containers from one image. `ingest` can only pair a truck and accept rows; nothing can be read
through it. `dash` is the dashboard and is published on the LAN address only.

## Run

```bash
cp .env.example .env            # data folder and the two bind addresses
mkdir -p /mnt/skpool/appdata/silverado-stats && chown 10001 /mnt/skpool/appdata/silverado-stats
docker compose up -d --build
```

Open `http://10.0.0.15:8791`. The first visit creates the one login. Then Trucks → Add truck gives a
pairing code to type into the app (Settings → Upload, in Park).

`nginx-sev.conf` holds the two pieces to add to the nginx project; rebuild and restart nginx after.

## Test

```bash
docker compose --profile test run --rm test
```

## Upload protocol (v1)

| Call | Auth | Body | Reply |
|---|---|---|---|
| `POST /v1/pair` | none, rate limited | `{"code": "ABCD2345"}` | `{"token", "device"}` |
| `POST /v1/rows` | `Authorization: Bearer <token>` | `{"app": "0.5.0", "rows": [row…]}`, may be gzip | `{"received", "new", "rejected"}` |
| `GET /v1/ping` | bearer | | `{"device", "rows", "last_at_ms"}` |

A row is `{"at_ms", "tz_min", "source": "car"|"mx", "trigger", "values": {key: number}, "arrays": {kind: [number|null]}}`.
`values` keys are the app's log column names; a key the server has not seen becomes a new column.
`arrays` carries the per-cell snapshots (`cell_v`, `module_t`).

- A row is identified by truck, source and `at_ms`. Sending it again changes nothing, so the app can
  retry freely and can start over from its first row.
- 200 means the batch is dealt with: the app moves its cursor past it. Anything else: keep the cursor
  and try later. 401 means the token no longer works (uploads stopped, or the truck was paired again).
- Limits: 2000 rows and 1 MB (as sent) per call.

## Data

One SQLite file in the data folder; copying it (or the dashboard's Export → SQLite) is a backup.
Pairing a truck again keeps its id and history and replaces its token.
