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
- `android/local.properties` (untracked) holds `sdk.dir`, `MAPS_API_KEY` and `OCM_API_KEY`. The Google
  project needs Navigation SDK, Places API (New) and Routes API enabled.

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

## Decisions already made (2026-11-10 and 11)

- **Navigation rail** on the left with five buttons, always all shown, one size in every gear:
  Simple, Expert, Trends, Map, Diag (renamed from API and OBD on 2026-11-11; notes below still say API and
  OBD for those two pages). In code they are `AppTab.SIMPLE` and `AppTab.LIVE`.
- **Carbon styling** (2026-11-11), from `docs/mockups/style-2-carbon.html`: near-black panels with hairline
  edges, green accent. The Simple tiles are plain panels like Expert's (2026-11-12; the green / red / amber
  washes went). Colours and the card shape live in `ui/theme/Theme.kt`.
  Not taken from the mockup yet: monospace figures and upper-case tile labels (they change the layout maths).
- **While driving:** API, OBD and Map are fully usable. Trends and Diagnostics show the
  "Available in Park" panel. System permission dialogs still wait for Park.
- **API and OBD have no header row.** Their status and buttons are drawn at the foot of the rail
  (`toRail` in `AppShell.kt`, `DashLayout.withoutHeader()`).
- **OBD page:** five tiles on top (charge, remaining, capacity kWh, capacity Ah, deviation) with no
  descriptions and no "unverified" tags. Capacity Ah and deviation are BECM `2B44` (Car Scanner's mapping in
  `carscanner/`); Ah is a dash when the truck does not report it, never a calculation. A grid of tiles (`DashboardModel.packTiles`) beside the cell-group
  grid. Battery 1 / 2 temperatures are `2AF3` / `2AF4` (min, mean, max); the strip under the grid is one figure
  per module from the sensor pairs in `2AF1` (battery 1) and `2AF2` (battery 2, an inference). No current, power, range, 12 V or adapter voltage. Per-battery voltage is the sum of that half's
  cell groups, not a reading.
- **Map tab:** Google's Navigation SDK is used only for its bundled map view, because the Maps SDK from
  Play services does not work on cars with Google built-in. Chargers come from Open Charge Map. The
  SDK's guidance permissions are stripped in the manifest and pinned in `SourceSafetyTest`;
  `FOREGROUND_SERVICE` and `RECEIVE_BOOT_COMPLETED` are the app's own and must stay.
- **Map behaviour:** north-up, no zoom buttons, GPS button shows about 50 miles around the truck, search
  uses the app's own keyboard (the system keyboard refuses input while driving), and "Navigate in
  Google Maps" hands the destination to the car's navigation app.

## Checked on the truck, and not

- **Confirmed by the owner:** sending a destination to Google Maps works.
- **Emulator only so far:** `2B44` capacity and deviation, the `2AF3`/`2AF4` battery temperatures, the simplified
  icon (meant to fix the generic icon in the truck's sidebar), the car marker on the map, the rail, the reworked API and OBD pages, per-battery voltages and cell
  statistics against real adapter data, the range outline, and the Map tab staying usable while moving.
