#!/usr/bin/env python3
"""Golden vectors for the Kotlin OBD core (dev.silveradostats.obd), produced by the Python probe.

Decodes a set of reply payloads with the app's real profile (app/src/main/assets/profile.json)
using ultium_probe.profile.Field.decode, applies the probe's VIN masking (cli._mask_payload),
runs cli.derive() on whole value sets, and records the simulator's answers (transports/sim.py)
at frozen times. ObdGoldenParityTest asserts the Kotlin port reproduces every value exactly
(same int/float kind, same double bits).

Run it again whenever assets/profile.json is re-exported (ObdGoldenParityTest checks the
profile's SHA-256), from the repo root; -B keeps bytecode out of tools/probe:
  PYTHONDONTWRITEBYTECODE=1 python3 -B android/scripts/gen_golden_decode.py [repo root]

Writes android/app/src/test/resources/golden_decode.json.
"""

from __future__ import annotations

import hashlib
import json
import os
import random
import sys
import types

_HERE = os.path.dirname(os.path.abspath(__file__))
_GUESS = os.path.dirname(os.path.dirname(_HERE))  # android/scripts -> repo root
ROOT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else
                       (_GUESS if os.path.isdir(os.path.join(_GUESS, "tools", "probe")) else os.getcwd()))
sys.dont_write_bytecode = True
sys.path.insert(0, os.path.join(ROOT, "tools", "probe", "src"))

from ultium_probe import cli, profile  # noqa: E402
from ultium_probe.transports import sim as simmod  # noqa: E402

ASSET = os.path.join(ROOT, "android", "app", "src", "main", "assets", "profile.json")
TOML = os.path.join(ROOT, "tools", "probe", "dids", "silverado_ev.toml")
OUT = os.path.join(ROOT, "android", "app", "src", "test", "resources", "golden_decode.json")

# ---------------------------------------------------------------- profile (the app's asset)
raw_asset = open(ASSET, "rb").read()
doc = json.loads(raw_asset)
fields = [profile.Field(**f) for f in doc["fields"]]
modules = {k: profile.Module(k, m["tx"], m["rx"], m.get("description", "")) for k, m in doc["modules"].items()}
prof = profile.Profile(doc["name"], modules, fields)

toml = profile.load(TOML)
if [vars(f) for f in toml.fields] != [vars(f) for f in fields]:
    print("WARNING: profile.json differs from the TOML; re-run './probe export-profile' first", file=sys.stderr)

ECU_OF = {k: (m.tx >> 8) & 0xFF for k, m in modules.items()}
PRI_OF = {k: m.tx >> 24 for k, m in modules.items()}

# ---------------------------------------------------------------- simulator with a frozen clock
clock = [0.0]
simmod.time = types.SimpleNamespace(monotonic=lambda: clock[0])
sim = simmod.UltiumSim()          # t0 = 0.0
SIM_TIMES = [0.0, 17.25, 123.4, 1000.0, 3599.9]


def sim_answer(t: float, ecu: int, pri: int, payload: bytes) -> bytes | None:
    clock[0] = t
    return sim.answer(ecu, pri, payload)


def sim_data(t: float, mod: str, did: int) -> bytes | None:
    r = sim_answer(t, ECU_OF[mod], PRI_OF[mod], bytes([0x22, did >> 8, did & 0xFF]))
    if r and r[0] == 0x62:
        return r[3:]
    return None


# ---------------------------------------------------------------- truck-like payloads (session 1)
def u16(v: int) -> bytes:
    return int(v).to_bytes(2, "big")


MODULE_MV = [3673.0, 3673.2, 3673.5, 3674.2, 3671.2, 3669.4, 3674.7, 3674.2, 3673.2, 3670.1, 3677.1, 3672.7,
             3671.8, 3676.8, 3675.9, 3676.1, 3671.9, 3672.9, 3673.5, 3673.7, 3671.0, 3671.4, 3670.9, 3671.2]
truck_groups = []
for g in range(192):
    m = g // 8
    truck_groups.append(int(round(MODULE_MV[m] * 10)) + ((g * 7) % 5) - 2)   # 0.1 mV units = V*1e4


def half_summary(first: int) -> bytes:
    half = truck_groups[first:first + 96]
    lo = first + half.index(min(half))
    hi = first + half.index(max(half))
    return (u16(round(sum(half) / 96)) + u16(min(half)) + u16(max(half))
            + bytes([lo + 1, lo // 8 + 1, hi + 1, hi // 8 + 1]))


TRUCK = {
    ("becm", 0x27C6): u16(27446),
    ("becm", 0x27AF): u16(8530),
    ("becm", 0x27C7): (19323).to_bytes(3, "big"),
    ("becm", 0x27C0): (2100).to_bytes(3, "big"),
    ("becm", 0x2AF5): half_summary(96),
    ("becm", 0x2AF6): half_summary(0),
    ("becm", 0x2AF7): u16(35252) + u16(35260) + u16(35270) + u16(35268),
    ("becm", 0x2AF1): bytes([96 + (i % 3 == 0) for i in range(24)]),
    ("becm", 0x2AF2): bytes([0x61] * 24),
    ("becm", 0x2AF8): bytes.fromhex("989977989978989c6e"),
    ("becm", 0x2AF9): bytes.fromhex("222222222222111111111111"),
    ("becm", 0x2AF3): bytes.fromhex("6160 0e03".replace(" ", "")),
    ("becm", 0x2AF4): bytes.fromhex("61600e03"),
    ("becm", 0x2B43): bytes([107, 107]) + bytes([106 + i % 3 for i in range(24)]),
    ("becm", 0x5401): b"\x00",
    ("becm", 0x0046): bytes([60]),
    ("becm", 0x33E5): bytes([124]),
    ("dmcm", 0x2885): u16(35270),
    ("dmcm", 0x2414): (12).to_bytes(2, "big", signed=True),
    ("dmcm", 0x33E5): bytes([126]),
    ("dmc2", 0x2885): u16(35360),
    ("bcm", 0x434F): bytes([100]),
    ("bcm", 0x40E5): u16(691),
    ("bcm", 0x40E6): u16(627),
    ("bcm", 0x4127): u16(1048),
    ("bcm", 0x4124): u16(418),
    ("bcm", 0x4149): u16(100),
}
for did in range(0x2AE1, 0x2AF1):
    first = (did - 0x2AE1) * 12
    TRUCK[("becm", did)] = b"".join(u16(truck_groups[i]) + bytes([i // 8 + 1]) for i in range(first, first + 12))

# ---------------------------------------------------------------- decode vectors
rng = random.Random(20261103)


def rand(n: int) -> bytes:
    return bytes(rng.randrange(256) for _ in range(n))


EDGES = [b"", b"\x00", b"\xff", b"\x12\x34", b"\xff\xfe\xfd", bytes(range(36)), b"\xff" * 40,
         b"1GCSIMULATED00000", b"1GC-SIMULATED0000", b"\x80\x81\x82abc\x00 ", b"  AB\x00CD \x00\x00"]


def decode_all(fs, data: bytes) -> dict:
    return {f.name: f.decode(data) for f in fs}


decode_vectors = []
for (mod, did), fs in prof.reads().items():
    payloads = []
    for t in SIM_TIMES:
        d = sim_data(t, mod, did)
        if d is not None:
            payloads.append(("sim@%g" % t, d))
    if (mod, did) in TRUCK:
        payloads.append(("truck", TRUCK[(mod, did)]))
    base = payloads[0][1] if payloads else b"\x01\x02"
    payloads += [("short", base[:-1]), ("long", base + b"\x01"), ("rand5", rand(5)), ("rand17", rand(17)),
                 ("rand37", rand(37)), ("rand64", rand(64))]
    payloads += [("edge%d" % i, e) for i, e in enumerate(EDGES)]
    for label, data in payloads:
        masked = cli._mask_payload(did, data, False)
        decode_vectors.append({"module": mod, "did": did, "case": label, "hex": data.hex(),
                               "masked_hex": masked.hex(), "values": decode_all(fs, masked),
                               "raw_values": decode_all(fs, data)})

# ---------------------------------------------------------------- VIN masking vectors
mask_vectors = []
for did, data in [(0xF190, b"1GCSIMULATED00000"), (0x4193, b"1GCSIMULATED00000"), (0xF190, b"1GC\xffSIMULATED0000"),
                  (0xF190, b"SHORT"), (0xF190, b""), (0x2AE1, b"1GCSIMULATED00000"), (0x2AE1, b"1GCSIMULATED0000"),
                  (0x2AE1, b"1GCSIMULATED000000"), (0x2AE1, b"1GC SIMULATED0000"), (0x1234, b"abcdefghjklmnprst"),
                  (0x1234, b"1GCSIMULATED0000\x00"), (0x1234, bytes(17)), (0xF190, b"\x00\x01\x02")]:
    mask_vectors.append({"did": did, "hex": data.hex(), "masked_hex": cli._mask_payload(did, data, False).hex(),
                         "vin_mask": cli.mask_vin(data.decode("ascii", errors="replace"))})


# ---------------------------------------------------------------- derive vectors
def with_ah(d: dict) -> dict:
    """The Kotlin port adds ah_nominal_est = BMS energy at 100 % / (series groups x 3.6 V nominal)."""
    d = dict(d)
    if d.get("bms_energy_at_100_kwh") and d.get("series_groups"):
        d["ah_nominal_est"] = round(d["bms_energy_at_100_kwh"] * 1000 / (d["series_groups"] * 3.6), 1)
    return d


def snapshot(source) -> dict:
    vals = {}
    for (mod, did), fs in prof.reads().items():
        data = source(mod, did)
        if data is None:
            continue
        masked = cli._mask_payload(did, data, False)
        for f in fs:
            vals[f.name] = f.decode(masked)
    return vals


derive_inputs = []
for t in SIM_TIMES:
    derive_inputs.append(("sim@%g" % t, snapshot(lambda m, d, t=t: sim_data(t, m, d))))
derive_inputs.append(("truck", snapshot(lambda m, d: TRUCK.get((m, d)))))
truck_regen = dict(derive_inputs[-1][1], pack_current=-250.35)
derive_inputs.append(("truck regen", truck_regen))
derive_inputs += [
    ("empty", {}),
    ("padding", {"cells_001_012": [3.9] * 12, "cells_013_024": [3.8] + [0.0] * 11}),
    ("ints", {"cell_min_a": 3, "cell_max_a": 4, "cell_avg_a": 3, "pack_voltage": 300, "pack_current": 2.5}),
    ("falsy", {"soc": 0.0, "energy_remaining": 85.3, "cell_min_a": 0.0, "cell_max_a": 3.7, "pack_voltage": 0.0,
               "pack_current": 0.0, "cell_avg_a": 3.6}),
    ("negative zero power", {"pack_voltage": 352.7, "pack_current": -0.001}),
    ("missing current", {"pack_voltage": 352.7, "pack_current": None}),
    ("odd lists", {"cells_001_012": None, "cells_x": "abc", "cells_y": [0.5, 0.6, 1, 0], "module_soc_raw": [0, 5, 0, 7]}),
    ("three averages", {"cell_avg_a": 1e16, "cell_avg_b": 1.0, "cell_avg": -1e16, "pack_voltage": 352.0,
                        "cell_min": 3.1, "cell_max_b": 3.9}),
    ("int energy", {"energy_remaining": 100, "soc": 50}),
    ("ties", {"cells_a": [3.7, 3.6, 3.6, 3.8, 3.8], "cells_b": [3.6, 3.8]}),
    ("rounding tie power", {"pack_voltage": 125.0, "pack_current": 1.0}),
    ("rounding tie spread", {"cell_min_a": 0.5, "cell_max_a": 0.50025, "cell_avg_a": 0.25, "pack_voltage": 0.0125}),
    ("rounding tie energy", {"energy_remaining": 0.0625, "soc": 50.0}),
    ("only one half", {"cell_min_b": 3.6709, "cell_max_b": 3.6769, "cell_avg_b": 3.6732, "pack_voltage": 352.62,
                       "energy_remaining": 85.3, "soc": 41.88}),
]
derive_vectors = [{"case": name, "values": vals, "derived": with_ah(cli.derive({k: {"v": v} for k, v in vals.items()}))}
                  for name, vals in derive_inputs]

# ---------------------------------------------------------------- simulator answers
sim_dids = sorted({did for (_, did) in prof.reads()} | {0xF190, 0x27C0, 0xBEEF, 0x2AE0, 0x2AF0, 0x2AF2, 0x2AF7,
                                                         0x434F, 0x4127, 0x4124, 0x4149, 0x2414, 0x33E5})
sim_physical = []
for t in SIM_TIMES[:3]:
    for ecu in (0xCB, 0x17, 0x1D, 0x40, 0x45, 0x99):
        for pri in (0x14, 0x18):
            for did in sim_dids:
                r = sim_answer(t, ecu, pri, bytes([0x22, did >> 8, did & 0xFF]))
                sim_physical.append({"t": t, "ecu": ecu, "pri": pri, "payload": "22%04x" % did,
                                     "reply": r.hex() if r is not None else None})
            for mode in (0x03, 0x07, 0x0A):
                r = sim_answer(t, ecu, pri, bytes([mode]))
                sim_physical.append({"t": t, "ecu": ecu, "pri": pri, "payload": "%02x" % mode,
                                     "reply": r.hex() if r is not None else None})
sim_functional = [{"mode": m, "pid": p, "replies": [r.hex() for r in sim.functional(m, p)]}
                  for m, p in ((0x01, 0x00), (0x01, 0x0D), (0x09, 0x0A), (0x09, 0x02), (0x01, 0x05), (0x09, 0x04))]

out = {
    "generator": "gen_golden_decode.py (Python ultium_probe; see ObdGoldenParityTest)",
    "profile_sha256": hashlib.sha256(raw_asset).hexdigest(),
    "decode": decode_vectors,
    "mask": mask_vectors,
    "derive": derive_vectors,
    "sim": {"times": SIM_TIMES, "physical": sim_physical, "functional": sim_functional},
}
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w") as fh:
    json.dump(out, fh, separators=(",", ":"))
    fh.write("\n")
print(f"wrote {OUT}: {len(decode_vectors)} decode, {len(mask_vectors)} mask, {len(derive_vectors)} derive, "
      f"{len(sim_physical)} sim physical vectors", file=sys.stderr)
