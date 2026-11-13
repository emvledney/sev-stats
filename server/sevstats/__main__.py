"""`python -m sevstats ingest|dash`: the two halves run as separate containers on separate ports.

ingest: the only part nginx exposes to the internet. Pairing and row upload, nothing readable.
dash:   the dashboard. Published on the LAN address only, never proxied.
"""
import os
import sys

import uvicorn


def main() -> None:
    role = sys.argv[1] if len(sys.argv) > 1 else ""
    if role == "ingest":
        target, port = "sevstats.ingest:app", 8790
    elif role == "dash":
        target, port = "sevstats.dash:app", 8791
    else:
        sys.exit("usage: python -m sevstats ingest|dash")
    uvicorn.run(
        target,
        host="0.0.0.0",
        port=int(os.environ.get("SEV_PORT", port)),
        proxy_headers=False,
        server_header=False,
        access_log=os.environ.get("SEV_ACCESS_LOG", "0") == "1",
    )


if __name__ == "__main__":
    main()
