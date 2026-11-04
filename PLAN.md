# Silverado EV battery dashboard: implementation plan

Goal: a live dashboard on the Silverado EV's 17.7" center screen showing pack
voltage and current, power in and out, temperatures, per-cell-group voltages,
SoC and estimated capacity in kWh and Ah. Absolute constraint: nothing may
change or impair any truck function.

Status (2026-11-04, 07:01): **version 0.4.0 (code 7) published to your internal track**: the main app now runs while driving, with a locked-down moving mode (Simple and Live only), and Simple and Live fit one page without scrolling. Simple tab (built-in data, measured mi/kWh, Efficiency and Battery layouts), live OBD dashboard via the MX+ over Bluetooth Classic, cell-group heatmap, Battery Glance as the fallback. 334 app tests pass. **MX+ pairing from the head unit works** (owner report, 2026-11-04). Next: on-truck test of 0.4.0 out of Park, and live MX+ data on the Live tab. A drive is only measured while the app is on screen. Before that, **the diagnostics app was installed and running on your truck** (Play internal testing, `com.vincel.silveradoev`; permissions and live vehicle data work). **Session 1 done on your truck. 192 cell groups, pack V/A and SoC confirmed, no trouble codes** (`docs/session1-results.md`). Research complete (10 agents, adversarially verified,
sources in `docs/research/`). The read-only laptop probe (`tools/probe`) is
built and tested against a simulator. Nothing has touched the truck yet.

---

## 1. Verdict: what is and isn't possible

| Want | Possible? | How |
|---|---|---|
| App on the infotainment screen | **Yes** | Your own Play Console app on the **Android Automotive OS internal testing track**, installed from the Play website onto the truck. Owners do this today on 2024–2025 Silverado EV, Sierra EV, Blazer EV and Equinox EV, including Android 14. |
| `adb install` from the laptop | **No** | GM production head units gate ADB behind GM certificates. The in-cab USB ports are host-only, and wireless debugging is unreachable. Every workaround needs radio hardware or EEPROM mods, which are ruled out. |
| SoC, kWh remaining, nominal capacity, range, charge-port state, outside temp | **Yes, no extra hardware** | Standard Android vehicle properties (VHAL) via `CarPropertyManager`. Read live by another owner's app on a 2024 Blazer EV (same head-unit family); confirmed on your truck in Phase 3. |
| HV pack power in/out from VHAL | **Maybe** | `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` (mW, + charging / − discharging) exists, but GM has only been seen reporting 0 W in Park. Test on the truck. |
| Pack V and A, cell-group voltages, module temps, coolant temps | **Very likely, with your OBDLink MX+** | Read-only UDS `0x22` requests through the OBD-II port. Other owners' logs from a 2024 Silverado EV and a 2025 Sierra EV (same platform) show SoC, energy, cell min/max/avg, pack voltage and the raw module-temperature bytes. Pack current (`2414`), the cell-group array (`2AE1…`) and coolant temps (BCM `0x40`) were seen only on other Ultium vehicles (Hummer EV, Lyriq, Equinox EV). Session 1 confirms them on your truck. |
| Per-cell voltages | **Per cell group, very likely** | Ultium cells are wired 3 in parallel per group, so the BMS reports groups: 192 on a 24-module pack (96 in series per 400 V half), 160 on 20 modules, 112 on 14. The array (DIDs `2AE1…2AF0`) is decoded on an Equinox EV and strongly inferred for the Silverado. **Session 1 confirms it.** Individual parallel cells are never visible. |
| Capacity in kWh and Ah, SoH | **Estimated, not read** | No public DID gives SoH or Ah. We derive: BMS energy ÷ SoC, and Ah by coulomb counting pack current over any stretch where the app is running and SoC moves at least ~10 % (a drive, or an attended DC fast charge). Both are labelled as estimates. |
| Cabin / HVAC temperature | **No** | `HVAC_TEMPERATURE_CURRENT` needs a privileged permission. |
| Driver cluster or HUD widget | **No** | Rendered by the QNX host, and GM kills third-party cluster services. |
| Display while driving | **Yes, limited** | Only Play-installed apps may show distraction-optimized screens. Plan: a glanceable screen while driving, the full dashboard in Park. See §6. |

Uncertain, to be decided by on-truck tests (Phases 1 and 3):
1. **Whether GM's head unit lets our app pair with your OBDLink MX+ and connect to it over Bluetooth Classic (RFCOMM).**
   - Evidence: a third-party app already runs Bluetooth Classic connections on GM EV head units, but there the phone connects in to the car. An app on the car connecting out to a non-phone device is untested.
   - Wi-Fi adapters are ruled out by your choice.
   - Fallbacks: an OBDLink CX (Bluetooth LE) if LE works where Classic doesn't, or your phone app on CarPlay as today.
2. Which priority each module answers at on *your* truck and build.
3. The cell-group array layout and module count: 14, 20 or 24 modules depending on the pack.

---

## 2. Architecture

```
                   Silverado EV
 ┌──────────────────────────────────────────────────────────────┐
 │  BECM 0xCB, drive units 0x17/0x1D, BCM 0x40, gateway 0x45    │
 │                 │ (internal CAN, behind gateway)             │
 │           OBD-II port: pins 6/14 = 29-bit CAN 500k           │
 │                 │ read-only 0x22 requests                    │
 │      ┌──────────┴──────────┐                                 │
 │      │ OBDLink MX+ (yours) │ STN chip, ELM327 command set    │
 │      └──────────┬──────────┘                                 │
 │                 │ Bluetooth Classic, serial profile (RFCOMM):│
 │                 │ the app pairs with the MX+ and connects    │
 │                 │ out to it (no phone in the loop)           │
 │  ┌──────────────┴───────────────────────────────────────┐    │
 │  │ Head unit (AAOS 12L/14, arm64)                       │    │
 │  │  Silverado Stats app (Play internal test)            │    │
 │  │   ├─ OBD client: allowlist, Ultium addressing,       │    │
 │  │   │  tiered poller, sleep back-off                   │    │
 │  │   ├─ VHAL reader: CarPropertyManager, read-only      │    │
 │  │   ├─ Model: derived kW, spreads, estimates, logs     │    │
 │  │   └─ UI: full dashboard (Park) / glance (Drive)      │    │
 │  └──────────────────────────────────────────────────────┘    │
 └──────────────────────────────────────────────────────────────┘
   Laptop: discovery and logging with tools/probe (same MX+,
   paired over Bluetooth: elm-bt:auto). Builds and uploads the app.
   Receives diagnostics reports from the app (`probe receive`).
```

Design rules:
- One DID profile is shared by the probe and the app. `tools/probe/dids/silverado_ev.toml` is the source of truth and is exported to JSON for the app.
- The read-only allowlist is enforced at the lowest transport layer in both the probe and the app, and unit-tested so nothing else can be sent.
- The app is the Bluetooth client and does the pairing itself, because GM's Bluetooth settings may only list phones. The MX+ serves one host at a time, so the phone's OBD app must not be holding it.

---

## 3. Data map: dashboard field → source

Status key:
- **S** = seen on a Silverado or Sierra EV by another owner
- **U** = seen on another Ultium vehicle (Hummer EV, Lyriq, Equinox EV, BrightDrop)
- **?** = unverified

Addressing: module `ecu` at priority 0x14 sends `0x14DA<ecu>F1` and gets the answer on `0x142AF1<ecu>`. At priority 0x18 it sends `0x18DA<ecu>F1` and gets `0x18DAF1<ecu>`.

| Dashboard field | Source | Decode | Status |
|---|---|---|---|
| SoC (matches dash) | BECM `CB` @0x14, DID `27C6` | u16 × 100/65535 % | S |
| Energy remaining | `CB 27AF` | u16 / 100 kWh | S |
| BMS energy at 100 % | derived `27AF ÷ (27C6/100)` | — | S (varies 175–216 kWh by pack) |
| Range (BMS) | `CB 27C7` | u24 / 103 mi | S |
| Cell group avg / min / max | `CB 2AF6` (modules 1–12) and `CB 2AF5` (modules 13–24), bytes 0–5 | u16 / 10000 V each | ✅ confirmed on your truck |
| Location of min / max group | `CB 2AF5` bytes 6–9 | (group #, module #) × 2 | U (Equinox), S-pattern |
| **All cell-group voltages** | `CB 2AE1…2AF0`, 12 records of 3 bytes each | u16 / 10000 V + module # | U (Equinox, 80 groups); BT1 inferred |
| Pack voltage | Drive unit `17 2885` (and `1D 2885`) | u16 / 100 V | S |
| Pack voltage (BECM, 4 points) | `CB 2AF7` | u16 / 100 V each | S (meaning per point TBD) |
| **Pack current** | `17 2414` | s16 / 20 A, + = discharge | U (Hummer, Lyriq, Equinox) |
| **Power in/out** | derived V × A | kW | depends on `2414` |
| Module temperatures (24) | `CB 2AF1` | u8 − 80 °C (disputed: (x−40)/2) | S (bytes), scaling ? |
| Temp stats with location | `CB 2AF3`, `2AF4` | raw for now | S (layout ?) |
| HV battery temp | BCM `40 434F` @0x18 | u8 − 40 °C | U (Lyriq) |
| Coolant temps 1/2 | `40 40E5`, `40E6` @0x18 | u16 / 32 °C | U |
| Charge pilot current | `40 4149` @0x18 | u16 / 10 A | U |
| Module count | non-zero bytes 2–25 of `CB 2B43` | count | S (20 on a 2024 truck, 24 on 2025 Sierra) |
| Series count | `2885 ÷ 2AF5 avg` | ≈ 96 (24 modules), ≈ 80 (20 modules, inferred), unknown (14); detected at runtime | S |
| Charge state code | `CB 5401` | enum (partly known) | S |
| 12 V system | `CB 33E5`, `17 33E5` (1D also answers) | u8 / 10 V | S |
| Capacity Ah (est.) | ∫ `2414` dt ÷ ΔSoC over any stretch with ΔSoC ≥ ~10 % | Ah | derived |
| SoC / kWh / capacity (VHAL) | `EV_BATTERY_LEVEL` 0x11600309, `INFO_EV_BATTERY_CAPACITY` 0x11600106 | Wh | seen on Blazer EV |
| Range (VHAL) | `RANGE_REMAINING` 0x11600308 | m | Blazer EV |
| Charge rate (VHAL) | `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` 0x1160030C | mW ± | Blazer EV (0 in Park) |
| Outside temp | `ENV_OUTSIDE_TEMPERATURE` 0x11600703 | °C | Blazer EV |
| Driving restrictions (UI gating) | `CarUxRestrictionsManager` (primary); `GEAR_SELECTION` 0x11400400 as a display hint | — | AOSP standard |
| Usable capacity now | `EV_CURRENT_BATTERY_CAPACITY` 0x1160030D | Wh | Android 14 only; GM didn't expose it on 12L |

Not reachable: isolation resistance, contactor state, power limits, SoH %. GM's dealer tool shows isolation resistance from the BECM, but no DID is publicly known. Discovery may find some in Phase 2, read-only.

---

## 4. Safety model: how "no truck impact" is enforced

1. **Read-only protocol allowlist** (`tools/probe/src/ultium_probe/safety.py`, mirrored in the app). Only these may be sent:
   - `22 <DID>` with one DID, to a physical diagnostic address (`0x14DA<ecu>F1` / `0x18DA<ecu>F1` only)
   - OBD `01`/`09` broadcasts, on `0x18DB33F1` only
   - OBD `03`/`07`/`0A` DTC reads

   Everything else is refused before transmission and unit-tested. That includes:
   - services `10`/`11`/`14`/`27`/`28`/`2E`/`2F`/`31`/`34–38`/`3D`/`3E`/`85`
   - OBD `04`/`08`
   - multi-DID reads
   - any other CAN ID, including 11-bit

   The adapter link has its own guards:
   - Its command set is allowlisted. Every protocol and addressing setting (`ATE0`, `ATH1`, `ATSP7`, `ATCP`, `ATSH`, `ATCRA`, `ATFCS*`) must be acknowledged with `OK`. Cosmetic ones (`ATL0`, `ATS0`, `ATCAF1`, `ATAT1`, `ATST`) are best-effort.
   - Protocol auto-search is never run.
   - A bare CR is refused; it makes an ELM repeat its last bus message.
   - At connect, and after a timeout or Ctrl-C, the probe never writes to the adapter until it has shown its prompt and gone quiet. A busy ELM drops a character and can turn a read into another service.
   - Anything else stops the run: an adapter reset (banner, `LV RESET`, echo coming back), a late error, unexpected output between commands, or a rejected setting.

   The MX+ has no autonomous polling. Its phone app's write and clear-codes features are never used on this truck, because the probe's allowlist can't cover another app.
2. **Gentle bus use.**
   - One outstanding request at a time, at least 75 ms apart.
   - Tiered polling: current and voltage about 1 s, SoC and cell stats 2 s, cell array 5 s, temperatures 10 s, energy and range 30 s.
   - A busy reply (`7F 22 21`) gets one retry, then that DID is skipped. Repeated busy replies stop a scan.
   - Bus or adapter errors are never treated as silence. They stop the run.
3. **Let the truck sleep.** The OBD port is always powered.
   - When modules stop answering for 3 polling cycles, `poll` exits.
   - While the truck dozes off, only a single known-good read is retried, every 2 s, so at most about 4–5 requests go unanswered. Tested.
   - In watch mode (`--keep-watching`, `serve`, and later the app), **nothing is sent on the bus**. Once a minute it reads only the adapter's own voltage, which is adapter-local. It sends a single read only when that voltage rises 0.5 V above its lowest value since sleep, meaning the truck's DC-DC came on. A failed voltage reading never triggers a read. Unit tests check zero requests while asleep, and exactly one per rise.
   - Limits:
     - A wake can be missed if the 12 V doesn't rise at least 0.5 V. Then restart `poll` by hand.
     - An adapter that can't report voltage (SocketCAN) gets a single read at 1, 5 and 30 minutes, then polling stops.
   - The MX+ has its own low-power sleep, about 2 mA asleep (research table). The Phase 2 drain test checks the whole setup.
4. **DTC bracketing.** Scans read the gateway's OBD-mode trouble codes (03/07/0A) before and after.
   - A scan refuses to start if the baseline read fails.
   - It reads the codes again after Ctrl-C, a busy storm or a silence stop. After an adapter or bus error it stops writing and tells you to run `dtc` yourself.
   - It reports any new code.
   - It can't see UDS-only codes inside individual modules, so also watch the cluster and center screen for messages.
5. **Physical:**
   - The adapter connects through a **pin-limited extension or breakout box that passes only pins 4, 5, 6, 14 and 16**. Check it with a meter before first use.
   - The MX+, like most multi-protocol adapters, has contacts on other pins for GM's older single-wire (pin 1) and medium-speed CAN. On Global B, pin 1 reportedly carries a secondary CAN-FD network (secondary source) and four pins may carry Ethernet. The probe never selects those protocols. A pin-limited extension keeps them physically disconnected; the Hummer EV project ran without one, but it's cheap insurance.
   - The adapter's protocol is pinned to 29-bit CAN at 500 kbit/s, so it never runs a protocol search.
   - No splicing, taps or splitters with electronics.
   - The extension also keeps the adapter away from the driver's knee and pedals.
6. **Head unit:**
   - No exploits, sideloading, developer options, firmware or calibration changes.
   - The app never calls `CarPropertyManager.setProperty` or any write API.
   - It never calls `insert`, `update`, `delete` or `call` on any GM content provider (e.g. `com.gm.vehicleinfo.*`), some of which accept writes from any app, and it never binds GM private services. A Phase 4 lint check enforces this.
   - A Play internal-test install is an ordinary app install and is removable at any time.
7. **Driver distraction:** see §6. Detailed screens only in Park.
8. **Privacy:** the VIN appears in several DIDs (`F190`, `4193`). `obd` and `read` mask VIN-shaped replies unless you pass `--show-vin`, and `scan`, `poll` and `serve` always mask them. `logs/` is git-ignored.

Residual risks, stated plainly:
- Any OBD device on an always-powered port can drain 12 V if misconfigured. Mitigated by the sleep logic and the drain test.
- GM OTA updates can change module behavior. The app detects silent modules and falls back.
- Unplug the adapter before service visits.

---

## 5. Phases

### Phase 0: prerequisites (you, about 1 hour plus shipping)
- [ ] **Truck facts.** Model year, trim, pack (14/20/24 modules, i.e. Standard/Extended/Max Range), and the infotainment build: Settings → System → About, a string containing `SQBR…` (Android 12L) or `UQBM` (Android 14). Note whether the Android update is installed: GM Update 662.30 on MY2024, possibly a different 66x number on later years, all field action N25-253201. Don't toggle Developer Options.
- [ ] **Connectivity.** Is OnStar / Connected Services data active? Play Store installs depend on it. The truck's Wi-Fi hotspot is only needed to send diagnostics reports to the laptop; photos of the screen work too.
- [ ] **Google accounts.** Which Google account is signed into the truck's Play Store? It must be added as a tester. It can be the same as your developer account.
- [ ] **Play developer account.** Personal accounts must complete identity and Android-device verification. The device step needs a physical Android 10+ phone with the Play Console app, and identity checks can take days. Check it's done.
- [ ] **Hardware** (see §7): your **OBDLink MX+**; nothing to buy. Optional: an **OBD breakout box or extension that passes only pins 4/5/6/14/16**.
- [x] **Android SDK packages.** Installed with your OK (platform 35, build-tools, emulator, AAOS images).

### Phase 1: first truck session, laptop only (about 1 hour, parked, attended)
Full step-by-step: **`docs/truck-connection.md`**. Summary:
1. Pair the MX+ with the laptop over Bluetooth, with the phone's Bluetooth off so it doesn't hold the MX+.
2. At the truck, in Park and on, check the probe finds the MX+.
3. `./probe obd -t elm-bt:auto` lists the responding modules. Expect gateway `45`, drive units `17`/`1D`, body controller `40`, battery `CB` and brakes `28`.
4. `./probe dtc …` records the baseline trouble codes.
5. Find the priorities: `./probe read … --ecu CB --pri both --did 27C6`.
6. Single reads of pack voltage and current, the cell stats, the cell array (`2AE1` …) and the BCM temperatures.
7. Run `poll` for 5–10 minutes, then switch the truck off. The poller exits once the modules go quiet. Note how long that takes, and stop it yourself after 10 minutes if it doesn't.
8. Read DTCs again (no change expected). Unplug.

**Exit criteria:**
- The battery module answers at a known priority.
- Pack current is confirmed, or a fallback is chosen: `1D:2414`, else power estimated from ΔkWh/Δt, plus VHAL charge rate while charging.
- The cell array's layout and module count are known, or its absence is noted.
- The time for modules to go quiet after truck-off with the poller running is recorded.
- No new gateway DTCs and no new cluster messages.
- Logs saved in `logs/`.

### Phase 2: confirm and extend data (1–3 short sessions)
- Promote each profile entry to `status = "confirmed"` with its real scaling. Settle the open ones:
  - `2AF1` temperature scaling, against `40 434F` and the dash
  - what the four `2AF7` values mean
  - the `2B43` per-module values
  - the `5401` codes while AC and DC charging
- Charging sessions:
  - AC charging: pilot current and `2414` sign.
  - DC fast charging on an 800 V charger: how `2885` and `2AF5` behave when the pack halves switch to series.
- Optional, parked and attended, rate-limited: discovery `scan` of more BECM ranges for isolation resistance and similar (GM's tool shows these), with DTC bracketing.
- **12 V drain test.** Measure with an independent instrument: a logging multimeter or a clip-on 12 V battery monitor on the 12 V battery.
  1. Baseline: adapter unplugged, truck off, 2–3 hours.
  2. MX+ plugged in, laptop awake and within Bluetooth range:
     ```bash
     ./probe poll -t elm-bt:auto --profile tools/probe/dids/silverado_ev.toml --keep-watching --out logs/drain.jsonl
     ```
     Turn the truck off. The probe goes bus-silent once modules stop answering. When the MX+ itself sleeps, the run ends with `stopped: adapter link failed`. That's expected; note the time. Then leave it for the rest of the 2–3 hours, measured by the meter only.
  3. Pass if there's no extra 12 V drop or wake-up compared with the baseline.
  4. If there's extra drain, adjust the MX+ sleep settings in the OBDLink phone app, or unplug for long parking. `poll` logs the adapter's voltage every 30 s, so a drive log also shows whether the MX+ ever drops out on the road. Some Ultium trucks hold 11.7–12.2 V in Ready.
- Export the confirmed profile for the app: `./probe export-profile --profile tools/probe/dids/silverado_ev.toml --out android/app/src/main/assets/profile.json`.

**Exit:**
- Profile entries confirmed or explicitly parked.
- Pack current or its fallback decided.
- Drain test passed and sleep thresholds set.
- JSON exported.

### Phase 3: app MVP with no adapter (VHAL only) and the distribution path (1–2 evenings)
0. **Mockups at the real size.** Dashboard and glance screen at 2914×1134. You pick a layout and option A or B (§6).
1. Scaffold the Kotlin app:
   - Kotlin and Jetpack Compose, minSdk 32, targetSdk 35, no native code (arm64 head unit).
   - Manifest: `android.hardware.type.automotive` required=true; `usb.host`, `wifi`, `bluetooth_le` required=false.
   - Permissions: `CAR_INFO`, `CAR_ENERGY` (runtime grant), `CAR_ENERGY_PORTS`, `CAR_EXTERIOR_ENVIRONMENT`, `CAR_POWERTRAIN`, `CAR_SPEED` (runtime), `READ_CAR_DISPLAY_UNITS`, `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `BLUETOOTH_SCAN` (neverForLocation), `BLUETOOTH_CONNECT`.
   - Use integer property IDs, because GM strips the `VehiclePropertyIds` field names at runtime.
2. **Diagnostics screen first** (built: `android/`). It covers:
   - every VHAL property the app can see, with value, status and permission
   - driving-restriction state, display size and insets, build and network info
   - an adapter link test that sends only adapter-local `ATZ`/`ATI`/`ATRV`, so no truck traffic
   - a BLE scan

   There's no logcat on the truck, so this screen is how we learn what the truck exposes. **Send to laptop** posts the full report to `./probe receive`, which saves it under `logs/reports/`. That needs the laptop on the truck's hotspot; otherwise, photograph the screen. The head unit dials out to it.
3. Test in emulators:
   - Google AAOS API 34 and 35 images. The current SDK channel lists no API 32 image; 12L behavior is checked on the truck itself.
   - Use no-Play images for distraction-optimization tests. Play images honor it only for Play-installed apps, and the custom 17.7" profile also needs a no-Play image.
   - A custom profile of 2914×1134 px at 200 dpi, the Blazer EV panel and the best public proxy for the 17.7".
   - Optionally GM's Lyriq image, from the GM SDK update site.
4. **Play Console setup** (you, about 30 minutes; exact clicks in `docs/play-console.md`):
   1. Check Play Console › Device catalog (form factor: Car) for the Silverado EV / GM VCU head unit. If it isn't listed, stop. We'll spike first under a throwaway package name, or use the regular mobile track, which changes the manifest to `automotive` required=false.
   2. Create the app with a permanent package name.
   3. Add the Android Automotive OS form factor with a dedicated track. This is irreversible per package, so it's a dedicated app.
   4. Release to Internal testing (AAOS).
   5. Add the tester email list.
   6. Use the opt-in link.
   7. Install from the Play website by picking the truck.
5. On the truck:
   - Grant `CAR_ENERGY` and `CAR_SPEED` (Settings → Apps → Silverado Stats → Permissions).
   - Open the Diagnostics screen and photograph or export it.
   - Check that the app survives a drive: the Diagnostics screen is blocked out of Park, as expected. The glance screen is tested in Phase 4.
   - Check whether the head unit and the app keep running while charging with the truck off. This decides when Ah can be counted.
6. **Transport test** from the Diagnostics screen:
   - (a) **Bluetooth Classic to your MX+**: the app pairs with it (you press the MX+ button and confirm on screen), opens the serial channel (RFCOMM) and sends only the adapter-local `ATZ`/`ATI`/`ATRV`. That's no truck traffic, and it proves the link.
   - (b) BLE scan. It only matters if we fall back to an OBDLink CX.
   - Phone's Bluetooth off during the test, so it doesn't hold the MX+.

**Exit:**
- The app is installed from Play and updates via a versionCode bump.
- The real VHAL property list and display metrics are known.
- At least one working transport to the adapter.

### Phase 4: OBD inside the app (main build)
- `obd` module, a Kotlin port of the probe:
  - Transport interface with a Bluetooth Classic (RFCOMM) ELM implementation for the MX+, and BLE as an optional second.
  - An ELM session with the AT allowlist.
  - The same read-only guard as `safety.py`, with the same unit tests.
  - Ultium addressing and ISO-TP reassembly from ELM lines.
  - The tiered poller with busy retry and sleep back-off.
  - Decoding from the shared profile JSON.
- Model:
  - Derived kW, cell spread, series and module counts, BMS energy at 100 %.
  - A coulomb counter for Ah, with persistence across sessions in Room or DataStore.
  - Value ages and staleness indicators.
- UI (layout per your feedback on the mockups):
  - Full dashboard in Park: KPI tiles, power history, a temperatures card, a cell-group heatmap that shows its own grid size (112/160/192 groups for 14/20/24 modules), module temperatures, and a charging view.
  - Glance screen while driving (see §6).
  - Settings: units (°F/°C, mi/km), the adapter address, and the poll tiers.
- Logging: a rolling JSONL session log on the head unit, exportable to a USB stick through the system file picker or by HTTP POST to the laptop on the hotspot (`./probe receive`).
- **Exit:** a 30-minute drive and a charging session with stable data, no app crashes, no truck warnings, and no DTCs.

### Phase 5: hardening
- Long-term: OTA resilience (re-test after each GM update), and a reconnect strategy for when Bluetooth drops or the phone grabs the MX+.
- Cold-weather behavior and 800 V DC fast-charge mode.
- Capacity estimate quality (several charges).
- Optional: contribute the confirmed Silverado EV DIDs back to OBDb (its Silverado EV signal set is currently empty).

Fallbacks if a phase fails:
- **Head unit can't reach the MX+.**
  - (a) An OBDLink CX over BLE, if the head unit allows LE.
  - (b) Your phone over CarPlay, as today. The same app can also be built for a phone or tablet.
- **GM closes internal-test installs.** Same phone or tablet fallback.
- **BECM silent after an OTA.** Use drive-unit and BCM DIDs plus VHAL, a reduced dashboard.

---

## 6. While-driving display: a decision for you

Android Automotive blocks any screen that isn't marked "distraction-optimized" whenever the truck is out of Park, including R or D at a standstill. The app gates its own UI on `CarUxRestrictionsManager`. There are two options:

| Option | What it looks like | Trade-offs |
|---|---|---|
| **A. Templated glance screen** (Car App Library) *(your choice)* | Up to 4–6 rows of big text: kW, cell spread, pack and coolant temps, SoC/kWh | Google's while-driving UI. A stats app isn't one of Google's app categories, but that only matters for a public release; internal tests aren't reviewed. Updates are throttled by the host: about 1 s historically, about 7–8 s on newer hosts per one developer, to be measured on the truck. No charts. |
| **B. Custom Compose screen marked distraction-optimized** | The same glance layout, updating every second | Technically works for Play internal-test installs, and other owners' apps do this. It's against Google's car-app guidelines, which are only enforced at review, and internal tests aren't reviewed. Keep it minimal. GM could disable it in an OTA. |

With A, the dense dashboard (cell heatmap, charts) is Park-only, enforced by the OS. **You first chose A, then switched to B on 2026-11-03** because Park-only made the app nearly useless. The main screen is now being marked distraction-optimized with a locked-down moving mode (no Diagnostics, pairing, resets, toggles or tap details; no cell heatmap on Live). The app must stay on internal testing, and Battery Glance remains the fallback if GM blocks it.

---

## 7. Hardware

| Item | Why | Approx. |
|---|---|---|
| **OBDLink MX+** (you have it) | STN2255, Bluetooth Classic. Proven on a 2024 Hummer EV (BT1, the Silverado's twin). Low-power sleep. Used for laptop discovery and by the head-unit app. | owned |
| *Optional:* **OBD-II breakout box with per-pin switches**, or a passive extension wired **only** on pins 4/5/6/14/16 | Keeps the adapter's other contacts (pin 1, Ethernet pins) disconnected and the adapter clear of the driver's knee. Check with a meter. | $15–40 |
| *Only if needed:* **OBDLink CX** | Bluetooth LE. Only if the head unit turns out to allow LE but not Classic connections. | $80 |
| Not needed | Wi-Fi adapters (ruled out), CAN-FD interfaces (PCAN-FD, red panda), Raspberry Pi bridges, splitters | — |

The CAN-FD gear isn't needed because every public Ultium read works over classic 29-bit CAN at 500 kbit/s on pins 6/14. The gateway isolates the port, so passive sniffing captures nothing; only requests get answers.

---

## 8. Laptop environment (status)

| Item | State |
|---|---|
| Python probe (`tools/probe`, stdlib only) | ✅ built; two adversarial review rounds; 39 tests (38 pass, 1 skipped without a `vcan0` interface) |
| JDK 21 (Temurin) in `~/.local/share/jdks` | ✅ installed (user space, checksum-verified) |
| Android command-line tools in `~/Android/Sdk` | ✅ installed (checksum-verified) |
| SDK packages (platform 35, build-tools, emulator, AAOS images) | ✅ installed with your OK |
| Android Studio (optional, AUR `android-studio`) | optional; command-line builds are enough |
| `sudo pacman -S android-tools` | optional (adb for emulators; the SDK's platform-tools also provides it) |

---

## 9. Risk register

| # | Risk | Likelihood | Mitigation |
|---|---|---|---|
| R1 | GM's head unit won't let the app pair with or connect to the MX+ over Bluetooth Classic | Medium | Tested first, in Phase 3. Then an OBDLink CX (BLE). Then your phone app via CarPlay, as today. |
| R2 | GM OTA closes internal-test or distraction-optimized behavior | Low–medium | Phone or tablet fallback; keep the Park-only dashboard. |
| R3 | BECM silent at both priorities (as some MY2025 reports at 0x18 only) | Low for Silverado | Dual-priority probe; fall back to drive-unit and BCM DIDs plus VHAL. |
| R4 | Cell-group array not at `2AE1…` on BT1 | Low–medium | Show min/max/avg with location and per-module values; parked discovery scan. |
| R5 | 12 V drain from the always-on port | Medium if misconfigured | Bus-silent sleep watch, adapter sleep threshold, independent-meter drain test; unplug for long parking. |
| R6 | The phone grabs the MX+ before the head unit does (single host) | High until handled | Turn the phone's Bluetooth off or unpair the MX+ from the phone. The app shows "adapter busy" clearly and retries in the background. |
| R7 | Play account or verification hurdles | Low | Do it in Phase 0. |
| R8 | Template refresh too slow for driving | Medium | Option B (§6), your call. |
| R9 | A DID's meaning is wrong (e.g. the 2AF1 scaling) | Medium | "Unverified" badges in the UI until confirmed against the dash or the second source. |

---

## 10. Repository layout

```
PLAN.md                    this plan
docs/truck-connection.md   step-by-step first truck session (Phase 1)
docs/play-console.md       Play Console internal testing for AAOS (Phase 3)
docs/research/             verified research notes with sources
android/                   the Android Automotive app (Gradle root; module :app)
probe                      wrapper: ./probe <command> (no install needed)
tools/probe/               read-only Python probe (laptop): obd, dtc, read, poll, scan, serve,
                           export-profile, receive, sniff, iface
  dids/silverado_ev.toml   shared DID profile (source of truth for the app)
tools/setup-android-sdk.sh installs the Android SDK packages (after your OK)
```
