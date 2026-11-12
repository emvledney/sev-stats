# Car Scanner GM Ultium PID map

Recovered offline on 2026-11-11 from the supplied **Car Scanner 2.2.42**, Android
package `com.ovz.carscanner`, version code `32002042`. The APK manifest was checked
with Android's `aapt2`; the version is not inferred solely from its filename.

For the owner's **2024 Silverado EV**, use the **2021–2025** profile:

- [Complete readable map: 197 sensor definitions](gm-ultium-2021-2025.md)
- [CSV for filtering/searching](gm-ultium-2021-2025.csv)
- [JSON with normalized fields and original source records](gm-ultium-2021-2025.json)
- [2026 onward map](gm-ultium-2026-onward.md), [CSV](gm-ultium-2026-onward.csv),
  [JSON](gm-ultium-2026-onward.json)

These are the app's actual definitions, including its duplicate entries and apparent
mistakes. They establish **what Car Scanner maps**, not that every signal works or
has the claimed physical meaning on this truck. The existing app and probe have
not been changed, and this investigation sent no vehicle traffic.

## The useful discoveries for this truck

All entries below are from BECM address `CB`. Send service `22` followed by the DID;
for example, `222B44`. Offsets are zero based in the data **after** `62 <DID>`.
`u16(n)` and `u24(n)` mean unsigned big-endian values starting at offset `n`.

| DID | Value in Car Scanner | Exact decode, simplified | Unit |
|---|---|---|---|
| `27C6` | Battery energy storage remaining / state of charge | `u16(0) * 100 / 65535` | % |
| `27AF` | Battery energy storage remaining | `u16(0) / 100` | kWh |
| `27AE` | Battery energy storage capability | `u16(0) / 100` | kWh |
| **`2B44`** | **Battery total energy storage capacity** | **`u16(0) / 100`** | **Ah** |
| **`2B44`** | **Capacity deviation** | **byte at offset 2** | **Ah** |
| **`4676`** | **Long term discharge power limit** | **`u16(0) / 100`** | **kW** |
| `2AF8` | Battery pack 1 current | `u24(0) / 5000 - 2000` | A |
| `2AF8` | Battery pack 2 current | `u24(3) / 5000 - 2000` | A |
| `2AF8` | Battery pack total current | `u24(6) / 5000 - 2000` | A |
| `2AF7` | Battery pack 1 voltage | `u16(0) / 100` | V |
| `2AF7` | Battery pack 2 voltage | `u16(2) / 100` | V |
| `2AF3` | Battery 1 average / minimum / maximum temperature | bytes 0 / 1 / 2, each minus 80 | °C |
| `2AF4` | Battery 2 average / minimum / maximum temperature | bytes 0 / 1 / 2, each minus 80 | °C |
| `2AF1` | Module 1 temperature sensors 1 and 2, through module 12 sensors 1 and 2 | bytes 0–23, each minus 80; two consecutive bytes per module | °C |
| `2AF5` | Average / minimum / maximum cell voltage | `u16(0)`, `u16(2)`, `u16(4)`, each `/10000` | V |
| `2B43` | Battery pack 1 state of charge | byte 0 `*100/255` | % |
| `0046` | Ambient air temperature | byte 0 minus 40 | °C |
| `2ADB` | Chiller power feedback | byte 0 `*31/255` | kW |
| `2ADA` | Chiller power requested | byte 0 `*31/255` | kW |
| `27B9` | Total charge-cycle energy usage | `u24(0) / 10` | kWh |
| `4E68` | Battery-pack coolant temperature | byte 0 minus 40 | °C |
| `27BE` | Time since last full charge | `u16(0)` | minutes |
| `27C0` | Distance since last full charge | `u24(0) / 10` | km |

**The Ah result resolves an existing guess.** The current
[`silverado_ev.toml`](../tools/probe/dids/silverado_ev.toml) tentatively assigns
`4676` to capacity in Ah. Car Scanner assigns that DID to a power limit, and assigns
capacity and its deviation to `2B44`. A plausible number near 600 is not sufficient
to distinguish those quantities. Match `2B44` with Car Scanner on the truck before
treating the resulting capacity as vehicle validated.

**The current formula has unusually good corroboration in the saved truck data.**
[`session1-results.md`](../docs/session1-results.md) records the nine bytes
`98 99 77 98 99 78 98 9C 6E` for `2AF8`. With the recovered formula they decode to
**0.1518 A, 0.1520 A, and 0.3036 A**. The first two sum to the third within 0.0002 A.
This explains the previously unknown record much better than the old voltage
interpretation. Current direction during acceleration, regeneration, and charging
still needs a vehicle capture.

**Temperature scale and placement are separate questions.** The app supports the
`raw - 80` scale, but its `2AF1` labels specify two sensors for each of modules 1–12.
That conflicts with interpreting its 24 bytes as one sensor for each of 24 modules.
Neither Ultium profile defines `2AF2`; assigning it to modules 13–24 remains an
inference, not a recovered Car Scanner definition.

## How to read the complete mapping

There are **197 sensor rows but only 123 distinct ECU/request pairs** per profile.
One DID can contain multiple values; some rows are duplicate presentations of the
same bytes. All 197 entries use read service `22`.

| ECU / app label | Sensor rows | Distinct requests | Full request CAN ID | Response CAN ID |
|---|---:|---:|---|---|
| `CB` / BECM | 145 | 74 | `14DACBF1` | `142AF1CB` |
| `17` / DMCM1 | 29 | 29 | `14DA17F1` | `142AF117` |
| `1D` / DMCM2 | 15 | 15 | `14DA1DF1` | `142AF11D` |
| `28` / brake module | 8 | 5 | `14DA28F1` | `142AF128` |

The `DAxxF1` header stored in each sensor is only part of the CAN address. The
sensor's preceding `ATCP14` supplies the first byte. The exports also retain receive
filters and flow-control settings, so a DID is not detached from its target ECU.
They are reference data, not an instruction to replay every initialization command
in the existing app; its adapter discipline and allowlists still apply.

Car Scanner's large decimal `Id` values, such as `15225142`, identify **app sensors**.
They are not OBD PIDs and must not be transmitted. `15225142` maps to request
`222B44`, for example.

For the 195 byte-set rows, decoding is:

```text
value = integer(data[offset : offset + length]) * multiplier / divider + addend
```

Respect signedness and byte order. The two formula rows use their `FR` expression
instead: summed pack voltage on `2AF7`, and compressor current on `2708`. Their
byte-set fields are not the active formula. A/B/C in expressions begin at data byte
0/1/2, consistent with the app's [custom PID documentation](https://www.carscanner.info/custompids/).

## Source inconsistencies and limits

The exports preserve these exactly so they can be reviewed without silently
substituting guessed corrections:

- **`17:2885` has length 1**, scaled by 0.01, although this truck's captured HV
  voltage and existing confirmed decoder use two bytes. Do not replace the working
  decoder with this source row.
- **`CB:27AE` appears twice with different byte locations.** The ordinary capacity
  row reads two bytes at offset 0. A second "Total Energy Storage Capability" row
  reads one byte at offset 2, which cannot represent the same ~200 kWh quantity at
  the source's 0.01 scale.
- **`CB:27C7` has two slightly different factors:** 0.01562 and 0.015625 km per raw
  count. Both remain in the export. The latter is exactly 1/64 km.
- **`CB:5401` is labeled charger DC power**, `byte0 * 15 / 255` kW. Existing truck
  notes call it a charge-state code. The actual charging behavior is unresolved;
  neither the name nor an idle zero settles it.
- **`CB:2982` is named charger AC input power but has amperes as its unit.** Other
  A/C and charger rows also reuse a DID with incompatible names/scales. Treat them
  as source conflicts to check against actual captures.
- **`CB:2AF5` has broad pack labels**, but the owner's recorded truck session found
  that it covers half B. Keep that vehicle evidence. The app profiles do not include
  the complementary `2AF6` summary or the `2AE1–2AF0` cell-group sweep already read
  successfully by this project.
- **Adding the two `2AF7` voltages is a software calculation.** The app includes a
  sum-voltage sensor, but a sum of both halves is not the terminal pack voltage
  while this truck's halves are operating in parallel.
- **`17:2429` is labeled axle torque**, `u16(0) - 22534` Nm. That explains why a
  constant `0x5806` can represent zero torque rather than nominal pack voltage.

The two model-year profiles contain the same multiset of physical sensor decodes
and per-sensor adapter settings. They differ in internal sensor IDs/order/naming
and connection setup: 2021–2025 uses `0100` and mode prefix `01`; 2026 onward uses
`22F400`, prefix `22F4`, and explicit BECM initialization. This matches the existence
of separate generations in the official
[profile changelog](https://www.carscanner.info/profile-database-changelog/).

## What was recovered and how

The apparent database is embedded inside a managed .NET assembly:

```text
APK
  lib/x86/libassembly-store.so
    XABA assembly store / XALZ LZ4-compressed CarScannerMaui.dll
      CarScannerMaui.EmbeddedFiles.profiles7.db
        LZMA-alone decompression
          native ShiftStream variant 2 decode
            BSON array of profiles
              ProfilePIDs: JSON array of sensor definitions
```

The native routine `n7q3v1` in `libvndctx.so` supplies the read transformation. For
the absolute byte index `i` in the decompressed stream:

```python
plain = (encoded + 168 - i) & 255  # even i
plain = (encoded + 69 + i) & 255   # odd i
```

The full 76,983,107-byte BSON document parsed successfully into 1,330 profiles.
Entries 824 and 825 are the two Ultium generations. Only their mapping data is
exported here. Decompiled files and the complete intermediate database remain in
the git-ignored `work/` directory. `assets/cs_profiles.dat` is an integrity/hash
manifest, not the PID database.

The extractor in [extract_ultium.py](extract_ultium.py) reproduces the extraction
from the APK; [validate_mapping.py](validate_mapping.py) checks the delivered maps
and known decoding examples. From the repository root, with Python 3.10 or later:

```powershell
python -m pip install --target carscanner/work/python-libs dnfile==0.18.0
python carscanner/extract_ultium.py
python carscanner/validate_mapping.py
```

The dependency is already present in the local work directory from this
investigation. By default, extraction selects the sole APK in `carscanner/` and
regenerates the six map files. `--output` selects another export directory;
`--work` selects the intermediate directory. No Android installation is required.

Validation passed for the two 197-row exports, 123 distinct ECU/request pairs per
profile, CSV/JSON correspondence, normalized-to-source field fidelity, matching
decodes across the two model-year profiles, and the captured idle-current/SOC
examples. Capacity, temperature, and torque fixtures also check arithmetic, but
those particular fixture bytes are synthetic and are explicitly labeled in the
validator. They are not new measurements from the truck.

Source APK SHA-256:

```text
9e4ad3adbded727573929437534e7893a0862f9a90fdfc0419146be53804371f
```

This is a snapshot of the **bundled** 2.2.42 definitions. The app can download newer
profiles; no online database update was fetched during extraction. Local source
records, the app's decode methods, the prior truck session, and independent review
were used to distinguish recovered definitions from vehicle-confirmed behavior.
