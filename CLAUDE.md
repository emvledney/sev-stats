# Silverado Stats: working notes

Android Automotive app for the owner's Silverado EV. Package `com.vincel.silveradoevstats` since 2026-11-11
(a new Play app starting at 0.1.0, code 1; it was `com.vincel.silveradoev` up to 0.22.0, code 26), Play **internal
testing only**, never a production track. The app is in `android/`. `PLAN.md` and `docs/` hold the
background and research; this file is what a new session needs to work here.

## How the owner works

- **Show a real screenshot before building a release.** Implement in the working copy, run it on the
  automotive emulator, show the screenshot, and wait for a go-ahead. Conceptual mockups are not enough.
- **Commit and push only when asked.** Same for building a release bundle.
- **The owner uploads to Play.** Bundles are about 40 MB, too large for browser upload tools.
- **Each release bumps both numbers:** `versionCode` by 1 and the minor version by 1 (0.2.0 is code 2 on the
  new package; the old package ended at 0.22.0, code 26).
  Check with the owner what is already on Play before choosing a number.
- Development also happens on another machine. Pull before working; push when asked.

## Building on this Windows PC (Git Bash)

```bash
cd android
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

- Do **not** set `MSYS_NO_PATHCONV` when running `./gradlew`: the wrapper then fails to find its main class.
- Line endings are LF everywhere (`.gitattributes`). Tests compare source text and the shipped
  `profile.json` byte for byte.
- `android/local.properties` (untracked) holds `sdk.dir`, `MAPS_API_KEY`, `OCM_API_KEY` and `UPLOAD_URL`
  (the stats server, `https://api.vl-development.com/sev`; without it the build has no upload). The Google
  project needs Navigation SDK, Places API (New) and Routes API enabled.
- The Unraid server has Docker but no JDK, SDK or emulator: `android/scripts/docker-test.sh` runs the unit
  tests and a debug build there. Screenshots still need this PC's emulator.

## Tests are part of the design

`SourceSafetyTest`, `AndroidWiringTest`, `LogWiringTest` and `DashLayoutTest` pin source text, the
manifest, permissions and layout maths. A deliberate behaviour change usually needs a matching test
change: update the test to state the new rule, with the owner's decision and date in the comment.
Never weaken the vehicle-safety rules (no vehicle writes, read-only car API, permission allowlist).

## Releasing

1. Bump `versionCode` and `versionName` in `android/app/build.gradle.kts`.
2. `./gradlew :app:testDebugUnitTest :app:bundleRelease` (release lint must pass).
3. Sign `app/build/outputs/bundle/release/app-release.aab` with `jarsigner`, using
   `silveradostats-upload.jks` and `silveradostats-upload.properties` in the repo root. Both are
   untracked and must never be committed. The `storeFile` path inside the properties file is from the
   other machine: ignore it and use the keystore in the repo root. Read the passwords into environment
   variables (`-storepass:env`), never onto the command line or into output.
4. Verify with `jarsigner -verify`, then tell the owner where the signed bundle is.

`android/scripts/release-bundle.sh` does the same on the Linux machine.

## Stats server (`server/`, 2026-11-13)

Runs on the owner's Unraid server as compose project `sev-stats`; `server/README.md` has the layout and the
upload protocol. The truck posts to `https://api.vl-development.com/sev/v1/` (nginx to the `ingest`
container); the dashboard is `http://10.0.0.15:8791`, LAN only, and must never be proxied.
Tests: `docker compose --profile test run --rm test` in `server/`.

## Automotive emulator

AVD `Automotive_1408p_landscape`. The app runs its demo data there.

```bash
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd Automotive_1408p_landscape -no-snapshot-save -no-boot-anim -dns-server 8.8.8.8,1.1.1.1
```

- Without `-dns-server` the emulator often has no DNS and the map stays blank.
- Other emulators may be running: always use `adb -s emulator-5554`.
- Install: `adb install -r -g app/build/outputs/apk/debug/app-debug.apk`.
- Location: `adb shell cmd location set-location-enabled true --user 10`, then `adb emu geo fix <lng> <lat>` twice.
- Driving state: `adb shell cmd car_service emulate-driving-state drive` (and `park`).
- Screenshots need the display id: `adb shell dumpsys SurfaceFlinger --display-id`, then `adb exec-out screencap -d <id> -p`.
- Find buttons by label with `uiautomator dump` (write to `//sdcard/u.xml` in Git Bash); positions change with the layout.
- The emulator has no Google Maps app, so the navigation hand-off cannot be tested there.

## Decisions already made (2026-11-10 to 12)

- **Navigation rail** on the left, always all shown, one size in every gear: Map, OBD, Cells, and a gear at the foot
  for Settings (2026-11-18, 0.13.0). Map is first and the app opens on it; the API tab went (a saved tab whose name is
  gone opens on Map). In code `AppTab.MAP`, `AppTab.LIVE` and `AppTab.CELLS`; OBD and Cells are one screen
  (`LiveScreen`, `cells = true` for the second). Map's rail foot has the MX+ button only: no range, no mi/kWh.
- **The car API's figures are a card on the map (2026-11-18):** top right (`ui/simple/BatteryCard.kt`): remaining
  and capacity kWh large on top, a battery filled to the charge, then the state of charge smaller, one decimal each.
  No mi/kWh, range, trips or Reset anywhere for the API: its battery level drifts as the truck wakes. The map's camera
  has no padding: the truck sits in the middle, between search and card (2026-11-18; padding pushed it right and low). Map wants a chosen MX+ connected, as the API tab did, so the log runs on
  the default screen (`UiSettings.wantsObd`).
- **No Trends tab** (2026-11-15): the log is reviewed on the stats server; the app only writes and uploads it.
  A saved "TRENDS" tab falls back to Map. The rail's MX+ status has no text while it works: the chip and
  the data age are enough (demo mode keeps its "Simulated truck" warning).
- **Settings page** (2026-11-12, was Diagnostics; still `AppTab.DIAGNOSTICS` so the saved tab survives):
  permissions (one line when all granted), last crash, OBDLink MX+ link, upload. Park only.
- **No report (2026-11-17):** "Send to laptop" and everything only it used went: the owner reads the truck's data
  on the stats server. With it went the all-properties read, the system and display info, the warm-up
  ("measured") efficiency in `SessionTracker`, and plain HTTP: `network_security_config.xml` is HTTPS only.
  `./probe receive` on the laptop has nothing left to receive.
- **Negatives are shown (2026-11-17):** net regen gives a negative mi/kWh and a negative kWh, in the OBD windows and the
  energy band, not a dash or a zero. Range stays a dash then, and "Other" in the band never goes below zero.
- **Carbon styling** (2026-11-11), from `docs/mockups/style-2-carbon.html`: near-black panels with hairline
  edges, green accent. The Simple tiles are plain panels like Expert's (2026-11-12; the green / red / amber
  washes went). Colours and the card shape live in `ui/theme/Theme.kt`.
  Not taken from the mockup yet: monospace figures and upper-case tile labels (they change the layout maths).
- **Energy breakdown (2026-11-16), on OBD since 2026-11-18:** three window cards (this drive, last 15 min, since
  charge) across the foot of the page: each its mi/kWh beside its miles, then its kWh used over a bar and driving,
  climate and other side by side (`docs/mockups/render-obd-windows.png`, "perfect" per the owner). From the MX+ only: the BECM's totals since its last full charge (`27B9` total, `27BA` driving and
  accessories, `27BB` thermal management, Car Scanner's mapping), differenced per window in `vehicle/EnergySplit.kt`.
  "Climate" is `27BB`; "Other" is the total minus the two. The car API has nothing for it: no MX+, dashes.
  This drive and last 15 min are plain deltas from their first reading: a total going down (regen) restarts nothing
  (2026-11-17, after two builds where that rule put the raw totals in every window on the truck). Since charge is the
  truck's totals as read, no delta and nothing saved (2026-11-17: the app's own base still read wrong on the truck).
  The miles too (2026-11-18): `27C0`, distance since the last full charge (Car Scanner's), as read for since charge
  and by difference for the other two; the session's distance only where a window's ends lack it. `27B9` is scaled
  0.1 as Car Scanner has it (it was 0.01, and read below `27BA` + `27BB` on the truck: Car Scanner showed 15.9 kWh
  total, 11.2 driving, 3.7 thermal, 24.85 mi).
- **The session's energy follows the truck's count (2026-11-17):** while the MX+ reads the totals, Used, mi/kWh and
  range go by the change of `EnergyCounters.usedWh` (the since-charge total), not the battery level, which drifts
  (`SessionTracker.addCounter`, `EnergySource.MX_COUNTER`). No reading for a minute: the level and power signals
  again, as before. Real MX+ only: the demo session is untouched, so the emulator cannot show it.
- **While driving:** Map, OBD and Cells are fully usable. Settings shows the
  "Available in Park" panel. System permission dialogs still wait for Park.
- **No page has a header row.** Their status and buttons are drawn at the foot of the rail
  (`toRail` in `AppShell.kt`, `DashLayout.withoutHeader()`).
- **OBD page:** five tiles on top (charge, remaining, capacity kWh, capacity Ah, deviation) with no
  descriptions and no "unverified" tags. Under them the pack tiles in two columns of three rows (charges,
  voltages and cells on the left; temperatures on the right), then the three window cards; the cell grid is on Cells
  (2026-11-18). Capacity Ah and deviation are BECM `2B44` (Car Scanner's mapping in
  `carscanner/`); Ah is a dash when the truck does not report it, never a calculation. The pack tiles are `DashboardModel.packTiles`.
  Battery 1 / 2 temperatures are `2AF3` / `2AF4` (min, mean, max); the strip under the grid is one figure
  per module from the sensor pairs in `2AF1` (battery 1) and `2AF2` (battery 2, an inference). No current, power, range, 12 V or adapter voltage. Per-battery voltage is the sum of that half's
  cell groups, not a reading. The cell grid toggles between voltage and module temperature; no captions.
- **Map tab:** Google's Navigation SDK is used only for its bundled map view, because the Maps SDK from
  Play services does not work on cars with Google built-in. Chargers come from Open Charge Map. The
  SDK's guidance permissions are stripped in the manifest and pinned in `SourceSafetyTest`;
  `FOREGROUND_SERVICE` and `RECEIVE_BOOT_COMPLETED` are the app's own and must stay.
- **Map behaviour:** north-up, no zoom buttons, GPS button bottom-left (beside the destination card when
  one shows) shows about 50 miles around the truck, tapping the map closes the card, search
  uses the app's own keyboard (the system keyboard refuses input while driving), voice search is recognised in the app
  (`SpeechRecognizer`, `RECORD_AUDIO`, 2026-11-18: the system's voice screen is refused while driving), and "Navigate in
  Google Maps" hands the destination to the car's navigation app.

- **Upload (2026-11-13):** the log's rows go to the owner's server over HTTPS, from the hourly job after its
  rows and every 5 minutes while a screen is up (`upload/Uploader.kt`, pure logic in `log/UploadModel.kt`).
  It keeps the id of the newest row the server answered for and sends what is after it; the server ignores
  repeats. Paired once with a code from the dashboard, typed on Settings (Park only). MX+ rows also carry
  every cell group's voltage and every module's temperature (`log_arrays` table), for the upload only: not
  in the CSV. No position, no VIN, never demo data.

## Checked on the truck, and not

- **Confirmed by the owner:** sending a destination to Google Maps works.
- **Emulator only so far:** the energy totals `27B9` / `27BA` / `27BB` and `27C0` behind OBD's windows at their new
  scales (2026-11-18: compare with Car Scanner's Charge Cycle screen on the truck), `2B44` capacity and deviation, the `2AF3`/`2AF4` battery temperatures, the simplified
  icon (meant to fix the generic icon in the truck's sidebar), the car marker on the map, the rail, the reworked API and OBD pages, per-battery voltages and cell
  statistics against real adapter data, the range outline, and the Map tab staying usable while moving.
- **Voice search while driving (2026-11-18):** listens in Drive on the emulator; real speech only on the truck.
- **Not even on the emulator yet:** the Upload block on Settings and a real upload from the app (written on
  the Unraid server, unit tests only).
