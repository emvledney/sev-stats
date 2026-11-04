# Session 1 results: 2024 Silverado EV, 2026-11-03

Setup:
- **Adapter:** OBDLink MX+ (STN2255 v5.8.1) over Bluetooth Classic, laptop running `./probe`.
- **Truck:** on, in Park, not charging.
- **Traffic:** read-only throughout.

Raw logs are in `logs/`, which is git-ignored.

## Safety check
- **Trouble codes:** gateway codes read before and after: **none either time, no new codes**.
- **Warnings:** none reported on the cluster or center screen.
- **Requests:** about 400, all UDS `0x22`, OBD `01`/`09` and DTC reads, at least 75 ms apart.

## Truck facts learned
| Item | Value |
|---|---|
| Model year (VIN 10th character) | 2024 |
| Pack | **24 modules (Max Range), 96 series groups**: two 400 V halves of 12 modules, 192 cell-group records |
| Modules answering the OBD census | 7. Gateway `45` is named; drive units `17`/`1D`, battery `CB` and body controller `40` answer 0x22 |
| Battery module priority | answers at **both 0x14 and 0x18** |
| Drive unit 1 (`17`) priority | both |
| Body controller (`40`) | 0x18 |
| Bluetooth round trip | about 50–110 ms per read on a held connection; about 450 ms when the connection is opened per command |

## Values read (truck idle)
| Signal | DID | Value | Status |
|---|---|---|---|
| SoC | CB 27C6 | 41.88 % (dash: 42 %) | ✅ confirmed |
| Energy remaining | CB 27AF | 85.3 kWh | ✅ |
| BMS energy at 100 % (derived) | 27AF ÷ SoC | about 203.5 kWh | ✅ derived |
| Range (BMS) | CB 27C7 | 187.6 mi | ✅ (compare with the dash next time) |
| Pack voltage | 17 2885 / 1D 2885 / CB 2AF7 ×4 | 352.7 / 353.6 / 352.5–352.7 V | ✅ |
| **Pack current** | **17 2414** | **0.0 to +0.6 A** (idle, about 0.1–0.2 kW) | ✅ answers; sign under load still to check |
| Pack current (fallback) | 1D 2414 | NRC 0x31 | ✗ not on this truck (removed) |
| **Cell groups** | **CB 2AE1–2AF0** | **192 groups, 3.6694–3.6771 V** | ✅ |
| Half A summary (modules 1–12) | CB 2AF6 | avg 3.6729; min 3.6692 at group 41 / module 6; max 3.6769 at group 81 / module 11 | ✅ new |
| Half B summary (modules 13–24) | CB 2AF5 | avg 3.6732; min 3.6709 at group 180 / module 23; max 3.6769 at group 105 / module 14 | ✅ |
| Module temperatures | CB 2AF1 | 16–17 °C (x−80) | likely; confirm against outside temp |
| Module temperatures, second set | CB 2AF2 | all 17 °C | new, unverified |
| Coolant temps | 40 40E5 / 40E6 | 21.6 / 19.6 °C | ✅ plausible |
| Pack temp candidate | CB 0046 | 20 °C | plausible |
| "HV battery temp" | 40 434F | **60 °C**: wrong for this truck | ✗ don't display |
| Battery temp A / B | 40 4127 / 4124 | 32.75 / 13.06 °C | ✗ meaning unclear |
| 12 V | CB 33E5 | 12.4 V; the adapter saw 14.7 → 12.6 → 13.2 V while on | ✅ |
| Charge-state code | CB 5401 | 0 (idle) | ✅ |
| Charger pilot current | 40 4149 | 10.0 A (unplugged default) | ✅ |
| Unknown | CB 2AF8 / 2AF9 | `98 99 77 98 99 78 98 9c 6e` / `22×6 11×6` | raw |

## Findings that change the design
1. **Each 400 V half sums exactly to the pack voltage.** Groups 1–96 total 352.61 V and groups 97–192 total 352.62 V. So the array numbering is right, and the two halves sit in parallel while driving.
2. **Cell-group voltages within a module agree within about 0.3 mV, while module averages differ by up to 7.7 mV** (module 6 lowest at 3669.4 mV, module 11 highest at 3677.1 mV). The dashboard heatmap will show modules clearly, and the spread figure is effectively module-to-module.
3. The battery module's `2AF5` min/max cover only half B. The dashboard combines `2AF6` + `2AF5`, or uses the full array, for pack min/max.
4. Per-module mean cell voltage (mV):
   - modules 1–6: 3673.0, 3673.2, 3673.5, 3674.2, 3671.2, 3669.4
   - modules 7–12: 3674.7, 3674.2, 3673.2, 3670.1, 3677.1, 3672.7
   - modules 13–18: 3671.8, 3676.8, 3675.9, 3676.1, 3671.9, 3672.9
   - modules 19–24: 3673.5, 3673.7, 3671.0, 3671.4, 3670.9, 3671.2

## Still to do on the truck (Phase 2)
- **Pack current under real load:** sign and scale while driving, plus regen.
- **Charging:**
  - AC: pilot current, `2414` sign, `5401` codes
  - DC fast charge: what changes when the halves switch to series
- **Truck-off behavior:** how long modules take to go quiet with the poller on, then the 12 V drain test.
- **Temperature scaling:** check `2AF1`/`2AF2` against outside temperature.
