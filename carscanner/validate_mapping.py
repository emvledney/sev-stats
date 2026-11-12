"""Offline checks of exported definitions; never opens an adapter or network.

Run: python carscanner/validate_mapping.py
Captured and synthetic fixtures are labeled separately below.
"""

from __future__ import annotations

from collections import Counter
import csv
import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def scalar(row: dict, payload_hex: str) -> float:
    """Evaluate the original byte-set specification, independently of display text."""
    raw = row["raw"]
    check(raw["TP"] == 1, "Fixture requires a byte-set definition")
    payload = bytes.fromhex(payload_hex)
    start, length = raw["SBI"], raw["DL"]
    check(length > 0 and len(payload) >= start + length, "Short fixture payload")
    value = int.from_bytes(
        payload[start : start + length],
        "little" if raw["RBS"] else "big",
        signed=raw["SIG"],
    )
    return value * raw["MUL"] / raw["DIV"] + raw["OFS"]


def main() -> None:
    profiles = []
    for stem in ("gm-ultium-2021-2025", "gm-ultium-2026-onward"):
        document = json.loads((ROOT / f"{stem}.json").read_text(encoding="utf-8"))
        signals = document["signals"]
        profiles.append(signals)
        check(len(signals) == 197, f"{stem}: sensor count")
        check(len({x["id"] for x in signals}) == 197, f"{stem}: unique sensor IDs")
        check(len({(x["header"], x["command"]) for x in signals}) == 123,
              f"{stem}: ECU/request count")
        check(Counter(x["header"] for x in signals) ==
              {"DACBF1": 145, "DA17F1": 29, "DA1DF1": 15, "DA28F1": 8},
              f"{stem}: ECU coverage")
        check(all(x["command"].startswith("22") and not x["raw"]["ACT"]
                  for x in signals), f"{stem}: read-only sensor requests")
        check(Counter(x["raw"]["TP"] for x in signals) == {1: 195, 0: 2},
              f"{stem}: decoder kinds")
        with (ROOT / f"{stem}.csv").open(encoding="utf-8-sig", newline="") as stream:
            rows = list(csv.DictReader(stream))
        check(len(rows) == 197, f"{stem}: CSV row count")
        check([int(x["id"]) for x in rows] == [x["id"] for x in signals],
              f"{stem}: CSV/JSON sensor identity")
        for row in signals:
            raw = row["raw"]
            for normalized, original in (
                ("command", "CMD"), ("header", "HDR"), ("offset", "SBI"),
                ("length", "DL"), ("signed", "SIG"), ("little_endian", "RBS"),
                ("multiplier", "MUL"), ("divider", "DIV"), ("addend", "OFS"),
            ):
                check(row[normalized] == raw[original],
                      f"{stem}: field fidelity {row['id']} {normalized}")

    physical_keys = (
        "CMD", "HDR", "SBI", "DL", "MUL", "DIV", "OFS", "SIG", "TP",
        "BIT", "RBS", "FR", "UN", "BCM", "ACM",
    )
    physical = [Counter(tuple(x["raw"][k] for k in physical_keys) for x in rows)
                for rows in profiles]
    check(physical[0] == physical[1], "Same sensor decodes across year profiles")
    by_id = {x["id"]: x for x in profiles[0]}

    # Actual nine-byte payload recorded in docs/session1-results.md on this truck.
    payload = "98 99 77 98 99 78 98 9C 6E"
    for sensor_id, expected in ((15225048, 0.1518), (15225049, 0.1520),
                                (15225050, 0.3036)):
        check(math.isclose(scalar(by_id[sensor_id], payload), expected, abs_tol=1e-9),
              f"Recorded truck current fixture {sensor_id}")

    # Actual Silverado SOC payload quoted in the existing WiCAN #606 research.
    check(math.isclose(scalar(by_id[15225108], "71 23"),
                       44.194705119401846, abs_tol=1e-10), "Recorded SOC fixture")

    # Synthetic bytes for the owner's reported display values, NOT captured frames.
    synthetic = ((15225142, "EB 3C 16", 602.2),  # Ah capacity
                 (15225143, "EB 3C 16", 22.0),   # Ah deviation
                 (15225109, "51 6C", 208.44),    # kWh capability
                 (15225048, "98 96 80", 0.0),    # offset current zero
                 (15225112, "60 61", 16.0),      # module 1 temperature 1
                 (15225113, "60 61", 17.0),      # module 1 temperature 2
                 (15225001, "58 06", 0.0))       # axle torque zero
    for sensor_id, data, expected in synthetic:
        check(math.isclose(scalar(by_id[sensor_id], data), expected, abs_tol=1e-9),
              f"Synthetic decode fixture {sensor_id}")
    check(by_id[15225142]["unit"] == "Ah", "Capacity unit")
    check(by_id[15225192]["unit"] == "kW", "4676 is mapped to a power limit")
    check(by_id[15225017]["length"] == 1, "Preserve source 2885 length anomaly")
    check(by_id[15225144]["offset"] == 2 and by_id[15225144]["length"] == 1,
          "Preserve source 27AE duplicate anomaly")
    check(by_id[15225196]["raw"]["FR"] == "(A*256+B)*0.01+(C*256+D)*0.01",
          "Formula-row expression takes precedence over byte-set fields")
    print("PASS: both 197-row exports, 123 ECU/request pairs each, CSV/JSON parity,")
    print("source field fidelity, model-year decode parity, captured truck examples,")
    print("synthetic capacity/temperature/torque fixtures, and preserved anomalies.")
    print("This validates extraction and decoding only; no new truck test was performed.")


if __name__ == "__main__":
    main()
