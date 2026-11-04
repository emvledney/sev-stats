### Table 1: DLC (SAE J1962) pinout on GM Global B / VIP (Silverado EV)
| Pin | Function on Global B | Use? | Confidence | Source |
|---|---|---|---|---|
| 4 | Chassis ground | yes | high (J1962) | standard |
| 5 | Signal ground | yes | high (J1962) | standard |
| 6 / 14 | Diagnostic CAN-H / CAN-L, terminated in the SDGM (120 Ω measured at DLC) and isolated from vehicle buses. 29-bit ISO 15765-4 at 500 kbit/s works for reads (legacy mode); the same pair reportedly runs CAN FD (about 5 Mbit/s DLC side, 2 Mbit/s vehicle side) for MDI 2 | yes (classic 500k only) | high for 500k 29-bit; low for FD rates | https://diag.net/msg/m1f2v9xm5s4xp8agm94j4c0t3i , https://surrealdev.com/gretio-global-b-status/ , https://github.com/JeremyWhittaker/hummer_obdII |
| 16 | +12 V battery, unswitched (always powered; Hummer: IP fuse block right F14 'DLC') | yes (power) | high | https://raw.githubusercontent.com/JeremyWhittaker/hummer_obdII/main/docs/ELECTRICAL.md , https://github.com/Coldarn/hacs_gm_ultium |
| 1 | Global A: SW-GMLAN 33.3k. Global B: 'secondary CAN-FD network (not used for diagnostics)' | NO | low-medium | https://surrealdev.com/gretio-global-b-status/ , https://diag.net/msg/m1f2v9xm5s4xp8agm94j4c0t3i |
| 3, 11, 12, 13 (+8 activation per ISO 13400-4 option 1) | Possibly 100/1000 Mbit Ethernet DoIP ('four pins'); GM mapping unverified | NO | low | https://surrealdev.com/gretio-global-b-status/ , https://www.iso.org/standard/57317.html |
| 9 | Unknown on VIP (historically GM UART/ALDL) | NO | low | https://pinoutguide.com/CarElectronics/gm_car_obd_ii_pinout.shtml |

### Table 2: Diagnostic addressing (29-bit) of BT1/BEV3 modules (observed on 2024 Hummer EV unless noted)
| ECU addr | Module name (census 090A) | Request ID (pri 0x18) | Response (pri 0x18) | Request ID (pri 0x14) | Response (pri 0x14) | Answers 22 at | Notes / source |
|---|---|---|---|---|---|---|---|
| CB | BSM-BatterySysMngr (likely GM 'K16 BECM') | 18DACBF1 | 18DAF1CB | 14DACBF1 | 142AF1CB | 0x14 and 0x18 | Main battery DIDs. 2025 Lyriq/Equinox did not answer 22 (unknown priority). https://raw.githubusercontent.com/JeremyWhittaker/hummer_obdII/main/docs/GM_MODULE_MAP.md |
| CD | BSM (second) | 18DACDF1 | 18DAF1CD | 14DACDF1 | 142AF1CD | 7F 22 31 to all | Unscanned. Candidate for per-cell data |
| 17 | DMCM-DriveMotorCtrl | 18DA17F1 | 18DAF117 | 14DA17F1 | 142AF117 | both | Pack V 2885, pack I 2414, 12 V 33E5 |
| 1D | DMC2-DriveMotorCtrl2 | 18DA1DF1 | 18DAF11D | 14DA1DF1 | 142AF11D | both | 33E5 12 V; 2885 cross-check |
| 1E | DMC3 (Hummer 3-motor; may be absent on Silverado EV) | 18DA1EF1 | 18DAF11E | 14DA1EF1 | 142AF11E | both | |
| 28 | BSCM-BrakeSystem | — | 7F 22 11 | 14DA28F1 | 142AF128 | 0x14 only | Wheel speeds 4A7A, brake pressure 4A7C, steering 4C2D, lat/long g 4C2F/4C30 |
| 40 | BCM-BodyControl | 18DA40F1 | 18DAF140 | (NO DATA) | — | 0x18 only | Battery and coolant temperatures, EVSE pilot, odometer, TPMS |
| 45 | Gateway Module GWM | 18DA45F1 | 18DAF145 | not tried | — | 0x18 (7F 22 31) | Answers OBD 03/07/0A (DTC reads) |
| functional | all OBD emission-relevant | 18DB33F1 | 18DAF1xx (seen: 45, 40, CB, 17, 28, 1D) | — | — | n/a (OBD 01/09 only) | https://github.com/OBDb/Chevrolet-Equinox-EV/blob/main/tests/test_cases/2025/commands/DB33.0142.yaml |
| 7DF / 7E0–7E7 (11-bit) | Bolt-era (7E4/7EC = Bolt BECM) | — | — | — | — | NOT answered on Ultium | https://github.com/OBDb/Cadillac-LYRIQ/pull/22 |

ELM/STN preamble (Hummer DEEP_SCAN.md; WiCAN bt1.json): `ATZ, ATE0, ATH1, ATSP7, ATST64 (or 96), ATCP14, ATSHDACBF1, ATCRA142AF1CB, ATFCSH14DACBF1, ATFCSD300000, ATFCSM1` → then `2227C6`. For priority 0x18 use `ATCP18`, `ATCRA18DAF1CB`, `ATFCSH18DACBF1`. Some clones need the 3-byte `ATSH DACBF1` form.

### Table 3: Ultium-confirmed Mode 0x22 DIDs (B0 = first data byte after 62 DID)
| Signal | ECU | DID | Bytes | Formula | Example raw → value | Confirmed on | Source |
|---|---|---|---|---|---|---|---|
| HV SoC (high-res, matches dash) | CB | 27C6 | u16 | x×100/65535 % (= x/655.35) | `05 62 27 C6 CE FA` → 80.85 %; Lyriq `18DAF1CB 05 62 27 C6 83 B7` → 51.45 % | Hummer 24, Lyriq 24, Equinox 24, BrightDrop 24, Bolt 27 | https://raw.githubusercontent.com/JeremyWhittaker/hummer_obdII/main/docs/GM_ENHANCED_CANDIDATES.md , https://github.com/OBDb/Cadillac-LYRIQ/blob/main/tests/test_cases/2024/commands/DACB.2227C6.yaml |
| Energy remaining | CB | 27AF | u16 | x/100 kWh | `05 62 27 AF 3C 52` → 154.42 kWh | Hummer, BrightDrop | same + https://github.com/Coldarn/hacs_gm_ultium |
| Estimated range | CB | 27C7 | u24 | x/103 mi | `06 62 27 C7 00 6C 3F` → 269.04 mi | Hummer, BrightDrop | same |
| Distance since full charge | CB | 27C0 | u24 | x/16.09344 mi (Bolt27: ×0.060849+0.354) | `06 62 27 C0 00 00 FE` → 15.78 mi | Hummer (level 2), Bolt 27 | same, https://github.com/meatpiHQ/wican-fw/issues/884 |
| Pack temperature (semantics unconfirmed) | CB | 0046 | u8 | B0−40 °C | `04 62 00 46 51` → 41 °C | Hummer (read) | GM_ENHANCED_CANDIDATES.md |
| Cell voltage avg / min / max | CB | 2AF5 | 10 B | [B0:B1], [B2:B3], [B4:B5] /10000 V; B6..B9 = (cell#, module#)×2 (INFERENCE) | Lyriq `18DAF1CB 10 0D 62 2A F5 92 70 92 / 21 57 92 81 5A 0C 37 07` → 3.7488 / 3.7463 / 3.7505 V; cell 90 (module 12), cell 55 (module 7) | Hummer, Lyriq 24, Equinox 24, BrightDrop | https://github.com/OBDb/Cadillac-LYRIQ/blob/main/tests/test_cases/2024/commands/DACB.222AF5.yaml |
| Per-module SoC-like array | CB | 2B43 | 26 B | B0 (and B1 on dual-layer) ≈ SoC ×100/255; B2..B25 per module (scaling not exact) | Lyriq `10 1D 62 2B 43 87 00 86 / 21 87 87 87 86 86 87 87 / 22 87 86 87 87 86 00 00 00 …` → 52.9 %, 12 module values | Hummer (24 values), Lyriq/Equinox (12/10) | https://raw.githubusercontent.com/JeremyWhittaker/hummer_obdII/main/docs/SCAN_RESULTS_2026-09-20.md |
| Per-module temperatures | CB | 2AF1 | 12–24+ B | DISPUTED: (x−40)/2 °C vs x−80 °C | BrightDrop: 24.5/25.0 °C min/max | Hummer (24 B raw), BrightDrop, Bolt 27 | https://github.com/Coldarn/hacs_gm_ultium , https://github.com/meatpiHQ/wican-fw/issues/884 |
| Charge state code | CB | 5401 | u8 | enum: 00 idle/driving; 91–99 charging (Hummer); 0D AC charging (BrightDrop); 0C/10/14/17/1A/1E other | `04 62 54 01 00` | Hummer, BrightDrop | TELEMETRY_CATALOG.md |
| Thermal energy accumulator | CB | 27BB | u24 | Bolt27: ×0.009560+0.1733 kWh; Hummer raw | — | Hummer (read), Bolt 27 | same |
| 12 V rail | CB / 17 / 1D | 33E5 | u8 | x/10 V | `18DAF11D 04 62 33 E5 7A` → 12.2 V | Lyriq 25, Equinox 25, Hummer, BrightDrop | https://github.com/OBDb/Cadillac-LYRIQ/blob/main/tests/test_cases/2025/commands/DA1D.2233E5.yaml |
| HV pack voltage | 17 (also 1D/1E) | 2885 | u16 | x/100 V | Hummer 388.60 V; BrightDrop 312.35 V | Hummer, BrightDrop, Bolt 27 | TELEMETRY_CATALOG.md |
| HV pack current | 17 | 2414 | s16 | x/20 A (+ discharge, − charge) | Lyriq −22.75 A @ 9.22 kW AC | Hummer, Lyriq 25, BrightDrop | https://github.com/OBDb/Cadillac-LYRIQ/pull/14 |
| 'Nominal pack V' vs torque (DISPUTED) | 17 | 2429 | u16 | Lyriq: x/64 V (352.1 constant); Hummer: torque, zero at 22534 | — | conflict | PR #14 vs GM_ENHANCED_CANDIDATES.md |
| HV battery temperature | 40 | 434F | u8 | x−40 °C | matched dash | Lyriq 25 | PR #14 |
| Battery temp sensor A / B | 40 | 4127 / 4124 | u16 | x/32 °C | — | Lyriq 25 (Hummer: 4127 may be a state) | PR #14 |
| Battery coolant temp 1 / 2 | 40 | 40E5 / 40E6 | u16 | x/32 °C | rose while charging | Lyriq 25, Hummer (raw 905–1250 / 413–819) | PR #14 |
| EVSE pilot current | 40 | 4149 | u16 | x/10 A | 38.8 A plugged; 10.0 A unplugged | Lyriq 25 | PR #14 |
| 'Battery group' V 1–3 | 40 | 416C/416D/416E | u16 | x/100 V (~51 V; 0 when off) | 51.14 → 50.98 V | Lyriq 25 | PR #14 |
| Odometer | 40 | 448F | u32 | x/64 km | 2,031,186/64 = 31,737 km | Lyriq 25 | PR #14 |
| Tire pressures FL/FR/RR/RL | 40 | 5005–5008 | u16 | x/16 kPa | — | candidates only | https://raw.githubusercontent.com/OBDb/Chevrolet/main/signalsets/v3/default.json |
| Wheel speeds ×4 | 28 (pri 14) | 4A7A | 4×u8 | km/h | `142AF128 07 62 4A 7A 64 64 64 64` → 100 km/h each | Lyriq 24, Hummer | DA28 test cases |
| OBD standard | functional 18DB33F1 | 01 0D / 01 42 / 01 A6 / 09 02 / 09 0A | — | SAE J1979 | Hummer advertises 01 0D 1C 1F 20 21 30 31 40 42 60 80 A0 A6; no 5B | Hummer | PASSIVE_CAN_VALIDATION.md |

### Table 4: Bolt EV (non-Ultium, 11-bit) battery DIDs, for comparison only; NOT answered on Ultium
| Signal | Req/Resp | DID | Formula | Example | Source |
|---|---|---|---|---|---|
| Pack voltage | 7E4/7EC | 432D | x×13/25 V | `7EC 05 62 43 2D 02 98` → 345.28 V | https://raw.githubusercontent.com/OBDb/Chevrolet-Bolt-EV/main/signalsets/v3/default.json |
| Cell min V / max V | 7E4/7EC | 4329 / 432B | x×3/5000 V | `7EC 05 62 43 29 17 70` → 3.60 V | same |
| Lowest / highest cell number | 7E4/7EC | 432A / 432C | u8 | | same |
| Pack max / min temp, hottest/coolest module | 7E4/7EC | 4349 / 434A / 434B / 434C | B−40 °C / index | | same |
| HV current | 7E4/7EC | 4356 | s16 × −40/267 A | | same |
| Capacity Ah (2017–18 / 2019+) | 7E4/7EC | 41A3 / 45F9 | x/10 / x/100 Ah | | same (NRC 31 on Lyriq 25: PR #14) |
| Display SoC | 7E4/7EC | 8334 | x×100/255 % | `7EC 04 62 83 34 A7` → 65.49 % | same |
| Isolation resistance | 7E4/7EC | 41EC | u16 Ω | | same |
| Contactor commands | 7E4/7EC | 430E | u8 | | same |
| HV charge count | 7E4/7EC | 43A5 | u16 | | same |
| Charger AC V / A, HV V / A | 7E4/7EC | 4368 / 4369 / 436B / 436C | ×2 V / /5 A / /2 V / s16/20 A | | same (NRC 31 on Lyriq 25) |
| Last charge kWh AC / HV | 7E4/7EC | 437D / 437F | x/100 kWh | | same |
| Cell-group voltages ×96 | 7E7/7EF | 4181–41A4, 4200–4240 | x×5/65535 V | `7EF 05 62 41 81 D6 03` → 4.18 V | same |
| Pack temps 1–6 | 7E7/7EF | 40D7…40E1 | B−40 °C | | same |
| HV current HD / MG voltage (same family as Ultium 0x17) | 7E1/7E9 | 2414 / 2885 | s16/20 A; u16/100 V | | same |

### Table 5: Silverado EV pack configurations
| Pack (trims) | Modules | Cells | Series×parallel | Nominal V | Pack Ah (400 V mode) | Usable kWh | Gross kWh | Confidence / source |
|---|---|---|---|---|---|---|---|---|
| Max Range (2024 RST/First Edition; 2025–26 WT/LT/Trail Boss/RST Max) | 24 (2 layers × 12), each 8s3p | 576 × 103 Ah NCMA pouch | 96s6p driving; 192s3p for 800 V DCFC | 345.6 V @3.6 V/cell (355 V @3.7) | ≈618 Ah (309 Ah in 800 V mode) | ≈205 (GM); 204 (InsideEVs) | 213.7 (InsideEVs estimate) | medium: https://insideevs.com/news/720662/chevy-silverado-ev-battery-explained/ , https://en.wikipedia.org/wiki/Ultium , https://en.wikipedia.org/wiki/Chevrolet_Silverado_EV |
| Extended Range (WT/LT/Trail Boss Ext., 2025–26) | 20 | 480 (if 24-cell modules) | unknown (≈80s6p? inference) | unknown | unknown | ≈170 | unknown | low: Wikipedia Silverado EV / Hummer EV |
| Standard Range (WT/LT Std., 2025–26) | 14 (secondary source only) | ? | unknown | unknown | unknown | ≈119 | ≈125 (greencarscompare) | low |
| LFP fleet pack | — | LFP | — | — | — | — | — | reportedly moved to MY2027: https://gmauthority.com/blog/2025/04/2026-chevy-silverado-ev-3wt-gets-lfp-battery-pack/ |
| Reference: 2024 Hummer EV (BT1) | 24 | 576 | 96s (measured), 2 layers in parallel | — | — | 191.9 measured (27AF/SoC); 212.7 (Wikipedia) | 246.8 | high (measured): PACK_ARCHITECTURE.md |
BMS: Analog Devices wireless BMS, one wireless cell-monitoring controller (wCMC) per module plus a wireless manager (https://investor.analog.com/news-releases/news-release-details/analog-devices-introduces-automotive-industrys-first-wireless). Cells come from Ultium Cells LLC (GM + LG Energy Solution).

### Table 6: UDS/OBD service safety classification
| Service | Name | Class | Note |
|---|---|---|---|
| 0x22 | ReadDataByIdentifier (default session, physical addressing) | ALLOWED | the only enhanced service needed |
| OBD 0x01 / 0x02 / 0x06 / 0x09 | Current data / freeze frame / monitor results / vehicle info (0902 VIN, 090A ECU names) | ALLOWED | functional 18DB33F1 OK; mask VIN in logs |
| OBD 0x03 / 0x07 / 0x0A | Read stored / pending / permanent DTCs | ALLOWED | address 0x45 (Hummer) |
| 0x19 | ReadDTCInformation (sub-fn 01/02) | read-only, untested on Ultium | optional |
| 0x3E | TesterPresent | AVOID | unnecessary in default session (Hummer forbids it) |
| 0x23, 0x2A, 0x2C, 0xAA | ReadMemoryByAddress, periodic DID, dynamic DID definition, GMLAN DPID | AVOID | change ECU behaviour or are security-sensitive |
| OBD 0x04 / 0x08 | Clear DTCs / on-board test control | FORBIDDEN | |
| 0x10 | DiagnosticSessionControl (non-default) | FORBIDDEN | 7F 22 7E/7F means stop, never open a session |
| 0x11 / 0x14 | ECUReset / ClearDiagnosticInformation | FORBIDDEN | |
| 0x27 / 0x29 | SecurityAccess / Authentication | FORBIDDEN | 7F 22 33/34 is the finding; do not pursue |
| 0x28 / 0x85 / 0x87 / 0x83 / 0x84 | CommunicationControl / ControlDTCSetting / LinkControl / timing / secured transmission | FORBIDDEN | |
| 0x2E / 0x3B / 0x3D | WriteDataByIdentifier / GMLAN write / WriteMemoryByAddress | FORBIDDEN | |
| 0x2F / 0x31 | InputOutputControl / RoutineControl | FORBIDDEN | includes SPS2 'Hybrid/EV Battery Data Retrieval' (PIP6009B) |
| 0x34–0x38 | Request download/upload, transfer data, file transfer | FORBIDDEN | |
Source: https://raw.githubusercontent.com/JeremyWhittaker/hummer_obdII/main/docs/SAFETY.md , https://raw.githubusercontent.com/JeremyWhittaker/hummer_obdII/main/docs/DEEP_SCAN.md

### Table 7: NRC / adapter reply interpretation (Hummer)
| Reply | Meaning | Action |
|---|---|---|
| 62 xx xx … | positive | decode |
| 7F 22 31 | requestOutOfRange (DID not implemented) | record, continue |
| 7F 22 11 | serviceNotSupported at this priority | try the other priority (0x14/0x18) |
| 7F 22 22 | conditionsNotCorrect | vehicle state; note |
| 7F 22 33 / 34 | securityAccessDenied / authenticationRequired | record, never unlock |
| 7F 22 78 | responsePending | wait (up to 3) |
| 7F 22 21 | busyRepeatRequest | stop, do not retry into a storm |
| 7F 22 7E / 7F | not supported in active session | record, never send 0x10 |
| NO DATA | nothing answered (asleep, wrong priority or wrong address) | back off; ATRV only |
Source: https://raw.githubusercontent.com/JeremyWhittaker/hummer_obdII/main/docs/DEEP_SCAN.md , https://raw.githubusercontent.com/JeremyWhittaker/hummer_obdII/main/docs/ACCESS_MATRIX.md

### Table 8: Adapter options for reading the DLC
| Adapter | Chip | Link | CAN FD | Proven on Ultium | Notes | Source |
|---|---|---|---|---|---|---|
| OBDLink MX+ | STN2255 (ELM327 v1.4b compatible) | BT Classic SPP | No | 2024 Hummer EV | supports ATCP/ATCRA/ATFCSx; has SW-CAN on pin 1 (never use) | hummer_obdII README |
| Veepeak BLE (ELM327 v1.5 clone) | clone | BLE (svc FFF0, write FFF2, notify FFF1) | No | 2024 BrightDrop | rejects 4-byte ATSH; one BLE central at a time | https://github.com/Coldarn/hacs_gm_ultium |
| Generic ELM327 + RPi BLE bridge | — | BLE | No | 2025 Lyriq | ATSP7 forced; auto-detect fails | https://github.com/OBDb/Cadillac-LYRIQ/pull/14 |
| WiCAN / WiCAN Pro (meatpi) | ESP32 | WiFi/BLE/USB | No (classic) | profiles only (bt1.json; 2027 Bolt #884) | AutoPID profiles | https://github.com/meatpiHQ/wican-fw |
| MDI 2 + GDS2 (Techline Connect) | GM | USB/Eth/WLAN | Yes + DoIP | dealer tool | bidirectional; not for unattended use | OEM_DIAGNOSTIC_WORKFLOW.md |
| red panda / PiCAN FD / Waveshare CAN FD HAT | — | USB/SPI | Yes | not needed | no reachable FD segment identified | CAN_FD_EXPANSION.md |

### Table 9: Suggested polling tiers (my synthesis from OBDb freq, hacs_gm_ultium and the Hummer recorder)
| Tier | Period | Requests |
|---|---|---|
| Fast | 0.5–1 s | 17:2414 (current), 17:2885 (pack V) → kW = V×A/1000 |
| Medium | 1–2 s | CB:2AF5 (cell avg/min/max + location), CB:27C6 (SoC) |
| Slow | 5–10 s | 40:434F, 40:4127, 40:4124, 40:40E5, 40:40E6, CB:2AF1, CB:2B43, CB:5401, 40:4149 |
| Very slow | 30–60 s | CB:27AF, CB:27C7, CB/1D:33E5, CB:0046 |
| Once per session | — | 0100 support, 090A ECU census, 0902 VIN (masked), DTC baseline 03/07/0A to 0x45 |
| Vehicle silent | back off to 60–300 s | adapter-local ATRV only; stop below ~12.0–12.2 V |
Strictly serial, at least 50–100 ms between requests, each DID once per cycle (hacs_gm_ultium: repeating the same DID made the BEC time out).