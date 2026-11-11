# Silverado EV battery dashboard: plan and status

Goal: a live dashboard on the Silverado EV's 17.7" center screen showing pack
voltage and current, power in and out, temperatures, per-cell-group voltages,
SoC and estimated capacity in kWh and Ah. Absolute constraint: nothing may
change or impair any truck function.

How to build, test and run everything, and the map of the repo: [`README.md`](README.md).

---

## 1. Status (2026-11-04)

**0.5.0 (version code 8) is the MVP**, on the internal track since 2026-11-04; `release/silverado-ev-v8-source.tgz` (git-ignored) holds its exact source. It is 0.4.0 with dead code removed, plus the owner's changes after the first drive: kW readouts removed, capacity on Simple and Live, session energy measured from power in and out, and a Connect/Disconnect MX+ button that also works while driving. None of those changes is confirmed on the truck yet. Tag `v0.4.0` holds the exact source of the last build tested on the truck. 0.6.0 (code 9) added the battery log and Trends tab, and 0.7.0 (code 10, the newest on the internal track; `release/silverado-ev-v10-source.tgz`) the Truck API / MX+ views of Trends; neither has run on the truck.

Confirmed by the owner on the truck on 2026-11-04, on 0.4.0:
- The app stays up in Drive.
- Live shows real data from the MX+.

Also on the truck:
- MX+ pairing from the head unit: owner report 2026-11-04, on the build before 0.4.0; the working Live link on 0.4.0 shows the connection holds.
- The owner's photo of Diagnostics in Park, Permissions section: every row in frame reads GRANTED, including the runtime CAR_ENERGY, CAR_SPEED, BLUETOOTH_SCAN and BLUETOOTH_CONNECT; the section runs off the bottom of the photo.

Session 1 (2026-11-03, laptop and MX+, parked) confirmed 192 cell groups, pack V/A and SoC, with no trouble codes (§5). The tests (363 app, 50 probe) run on the JVM and the simulator, so they say nothing about how the truck behaves. A drive is only measured while the app or its background service (from 0.10.0) is running.

Everything else about the truck is unconfirmed, and listed next.

---

## 2. Open work

Commands run from the repo root. Before any `-t elm-bt:auto` command, do the setup in `docs/truck-connection.md` §0–2. New runs write to `logs/session2/`, never to `logs/` itself: session 1's raw logs are git-ignored and can't be restored.

### With the laptop and the MX+

1. **Truck-off sleep timing** (session 1 skipped it). With the truck on, take the trouble-code baseline that §3.8's diff needs, then start the poll. Turn the truck off and follow `docs/truck-connection.md` §3.7–3.8. **Pass:** the poller exits by itself within 10 minutes, the time is noted, and there are no new DTCs or messages. Otherwise press Ctrl-C and note it: polling keeps the truck awake.
   ```bash
   ./probe dtc -t elm-bt:auto | tee logs/session2/02-dtc-before.json
   ./probe poll -t elm-bt:auto --profile tools/probe/dids/silverado_ev.toml --out logs/session2/08-poll.jsonl
   ```

2. **12 V drain test (never run).** Measure with an independent instrument: a logging multimeter or a clip-on 12 V battery monitor on the 12 V battery.
   1. Baseline: adapter unplugged, truck off, 2–3 hours.
   2. MX+ plugged in, laptop awake and within Bluetooth range:
      ```bash
      ./probe poll -t elm-bt:auto --profile tools/probe/dids/silverado_ev.toml --keep-watching --out logs/session2/drain.jsonl
      ```
      Turn the truck off. The probe goes bus-silent once modules stop answering. When the MX+ itself sleeps, the run ends with `stopped: adapter link failed`. That's expected; note the time. Then leave it for the rest of the 2–3 hours, measured by the meter only.
   3. The everyday case: MX+ left plugged in, no laptop, the app closed, truck off, 2–3 hours, measured by the meter only.
   4. **Pass** if there's no extra 12 V drop or wake-up compared with the baseline, in steps 2 and 3.
   5. If there's extra drain, adjust the MX+ sleep settings in the OBDLink phone app, or unplug for long parking. `poll` logs the adapter's voltage every 30 s, so a drive log also shows whether the MX+ ever drops out on the road. Some Ultium trucks hold 11.7–12.2 V in Ready.

3. **Temperature scaling (`2AF1`, `2AF2`).** After the truck has sat for several hours, compare with the outside temperature and coolant (`40E5`/`40E6`), never with `40:434F`, which reads 60 °C with a 17 °C pack. **Pass:** with u8 − 80, the module temperatures sit within a few °C of both. Then set `status = "confirmed"` in the profile and re-export it (README).
   ```bash
   ./probe read -t elm-bt:auto --profile tools/probe/dids/silverado_ev.toml --module becm --did 2AF1 2AF2 0046 | tee logs/session2/temps-becm.jsonl
   ./probe read -t elm-bt:auto --profile tools/probe/dids/silverado_ev.toml --module bcm --did 40E5 40E6 | tee logs/session2/temps-bcm.jsonl
   ```

### With the app on the truck

4. **Pack current under load (`17:2414`), sign and scale.** On Live while driving, or from the passenger seat with `./probe poll -t elm-bt:auto --profile tools/probe/dids/silverado_ev.toml --out logs/session2/drive.jsonl`. **Pass:** positive pulling away, negative in regen, and a size that is believable against the truck's own power display.

5. **VHAL charge rate, sign and liveness while driving.** The owner's first drive showed kW with swapped signs and a long delay, which an inverted rate and the old 30 s level-based estimate would both explain. After a drive, in Park: Diagnostics › Report › **Send to laptop**, with `./probe receive` running. Read `chargeRateCheck` in the report under `logs/reports/`: `sign` 1 is the AOSP sign, -1 the opposite, 0 not trusted, with `signalWh` and `levelWh` behind it. **Pass:** `sign` isn't 0 and `energySource` is `CHARGE_RATE` without the MX+. If it stays 0, the rate isn't usable and the Simple tab's energy needs the MX+; record that.

6. **Trip and range accuracy.** On Simple, compare Trip's miles and kWh with the truck's own trip figures for the same drive. **Pass:** within about 10 %. The report's `miPerKwh` keeps the efficiency that leaves out the first 5 minutes.
   - **Range tile.** The energy left x the "Last 15 min" mi/kWh at the end of its row, by the owner's choice on 2026-11-06, with no warm-up, moving every 10 s. That is the whole drive for its first 15 minutes. It starts as "—" and reads low and jumps around at first, and it moves more than a whole-drive figure would: a hill or a fast stretch shows within minutes. On the move it is "—" while regen has put back all that was used over those 15 minutes, or above 6 mi/kWh. Standing still it keeps the figure it took as it stopped, so it holds through a long wait and climbs at a charger, even once the 15-minute tile has nothing left to divide. **Pass:** near "Truck says" on an ordinary drive once past the first few miles.
   - **mi/kWh cells.** "This drive" is Trip's miles over Used's kWh and "Last 15 min" the same over the last 15 minutes, both with no warm-up and moving every 10 s. "Last 15 min" is the figure Range uses, and it follows the clock: once stopped it covers less and less driving and goes to "—" after 15 minutes, while Range holds. They match each other for the first 15 minutes, read low and jump around at first, and are "—" while regen has put back all that was used, or above 20 mi/kWh. **Pass:** near the truck's own mi/kWh once past the first few miles.
   - **Last charge row.** The truck reports no such figure, so `vehicle/ChargeWatch.kt` works it out from level readings, from the session, which the background service keeps running with no screen up, and the log's hourly job. A charge is the level rising 0.5 kWh or more while the plug or charge state says charging, with the head unit up, or rising 5 kWh or more above its lowest since the truck last moved (10 kWh if already moving again). "Last charged to" is where the level was when the readings stopped saying charging. With the head unit asleep through a charge, the first reading after it wakes stands in for that.
   - **"Used since" is a running total, not "last charged to" minus the level now, by the owner's choice on 2026-11-06.** The level moves by itself: 104 kWh when a charge ended has read 102 or 106 the next time the truck was on (owner, Car Scanner). What the owner wants to see is how the energy used compares with what the truck thought it had at the end of the charge. So it adds up what the level falls, net of regen, between two readings with the truck on and the head unit up in between. It leaves out anything with the truck off or the head unit asleep (parked drain included), charging, the first 2 minutes after the truck comes on unless it moves, and any step larger than 1.5 kWh plus 150 kW over the time since the level last moved. **Drift**, also the owner's choice, sits beside it: the level now minus (last charged to minus used), + above that and - below. 104 kWh, 2 used and a level of 97 reads -5.0. Whatever the running total leaves out ends up in it, so it says how far the truck's own figure has moved with no usage behind it. **Check:** charge overnight with the app closed, read the line next morning before driving, then after two drives with a night between. **Pass:** "Last charged to" is near what Car Scanner showed when the charge finished, it stays put when the level reads differently on getting in, "used since" starts at 0 and stays there until the truck is used, and it comes close to the truck's own kWh figure for those drives. Depends on `IGNITION_STATE` reading ON with the truck on and something else with it off, not confirmed on this truck (item 15). If it never reads, a parked head unit that is up counts as on. Not seen: a charge driven back below the previous reading with no reading in between, and a charge under 5 kWh with the head unit asleep throughout, which leaves the last charge as it was.
   - **mi/kWh since**, also the owner's choice on 2026-11-06, is the distance driven since the charge over "used since". The miles come from the session's distance, the same as Trip, at the readings "used since" counts, so the two cover the same stretches, across any number of drives. It is "—" for a charge saved before the miles were counted (0.12.0 or earlier) and after 0.5 kWh or more went with nothing measuring the miles, until the next charge, since kWh with no miles against them would read low for the rest of that charge. **Pass:** over a few drives, near the truck's own mi/kWh for the same miles.
   - **New drive.** Trip and Range restart when the truck leaves Park after 5 minutes parked or off, or after a charge. **Pass:** they restart after a longer stop, and carry on after a shorter one, after a long wait in Drive, and after time with Maps in front. If not, read `gear` and `samples` in the report.

7. **A 30-minute drive** on Live. Read the codes before and after, with the truck on and **Disconnect MX+** tapped, because the MX+ serves one host. **Pass:** stable data, no app crashes or truck warnings, and no new codes between the two reads.
   ```bash
   ./probe dtc -t elm-bt:auto | tee logs/session2/dtc-before-drive.json
   ./probe dtc -t elm-bt:auto | tee logs/session2/dtc-after-drive.json
   ```

8. *Dropped on 2026-11-07: the owner had Battery Glance removed, so there is nothing to check. The number stays so that references to the later items hold.*

9. **Permission dialog after leaving Park.** At a standstill, foot on the brake, one permission revoked: tap **Request runtime permissions**, then shift out of Park. **Pass:** note whether the OS covers the dialog; the app opens no further one.

10. **The 0.5 m/s parked-speed allowance on real sensors** (`DrivingPolicy.PARK_SPEED_NOISE_MPS`). **Pass:** Diagnostics stays offered over several parked sessions with the truck on. If it locks in Park, photograph the "Available in Park" panel's last line (`Gear … · speed … · restrictions …`).

11. **Charging sessions**, attended, with `./probe dtc -t elm-bt:auto` before and after, and `poll` with `--out logs/session2/charge-ac.jsonl` or `charge-dc.jsonl`. **Pass:** `pack_current` is negative while charging, `4149` matches the charger's amps, the `5401` codes are written down, and there are no new DTCs or warnings.
    - AC: pilot current (`40 4149`), `2414` sign, `5401` codes.
    - DC on an 800 V charger: how `2885` and `2AF5` behave when the pack halves switch to series.
    - Whether the head unit and the app keep running while charging with the truck off. Cold-weather behavior, when the season allows.

### Facts to record

12. **Infotainment build, window size, font scale.** The owner's photo shows only the Permissions section, so these are unknown. In Park, photograph Diagnostics › System & display, or use Send to laptop: `Android`, `Build.DISPLAY`, `Window size (dp)`, `Density`, `Font scale`, the insets. Or Settings → System → About, a string containing `SQBR…` (Android 12L) or `UQBM` (Android 14). Note whether the Android update is installed: GM Update 662.30 on MY2024, possibly a different 66x number on later years, all field action N25-253201. Don't toggle Developer Options. **Read the window figures before any layout tweak** (§5).

13. **Is a pin-limited extension fitted?** The repo doesn't record it; a question for the owner (§3, item 5).

### Battery log and Trends (0.6.0 and 0.7.0, never run on the truck)

Tests and the emulator cover the rows, charts, job and file picker. What only the truck can answer:

14. **Real logging cadence.** After a week of normal use, export the CSV and read the `trigger` column. **Pass:** `job` rows about hourly while the truck is on, `car_screen` rows every 5 min while the app is on screen, and at least one row every day it was used. If Android runs the job far less often (app standby, or the head unit's power states), the fix to weigh is a foreground service, which raises the risk in R11.
15. **The hourly MX+ visit.** With the MX+ chosen, stay on another app for over an hour with the truck on. **Pass:** an `mx_visit` row, the MX+ free afterwards, and no new codes with `./probe dtc -t elm-bt:auto` before and after. Also confirm `IGNITION_STATE` reads 4 with the truck on: if the truck doesn't offer it, no visit ever happens (fail safe), and this needs another "truck is on" signal. Note whether the job and the MX+ still run with the truck off and charging (with item 11).
16. **USB export.** In Park, plug a FAT32 stick into the head unit, Trends › Export CSV, pick the stick. **Pass:** the file opens on the laptop. Unverified on Android 14 (`docs/research/gm-platform.verify.md`). If there's no picker or no stick in it, the app says so; fall back to extending Send to laptop.
17. **Find the Ah deviation DID.** Car Scanner's "battery total energy storage capacity deviation" (22 Ah on this truck, 0–50, usually 10–30) has no known DID. It needs an attended read-only scan of more BECM ranges (§3), or Car Scanner's own log. Then add it to the profile as `capacity_deviation`: the Live tile and the log's `ah_deviation` column pick it up with no code change, from that day on.
18. **The background service.** It reads the truck's API with no screen up (`vehicle/VehicleWatchService.kt`); the emulator shows it working, but only the truck can show what GM's head unit allows. **Check, in this order:**
    - A drive with Maps in front the whole way, the app opened only at the end. **Pass:** Trip within about 10 % of the truck's own trip figures, and `gaps` 0 under Diagnostics › Report.
    - A night off, then a drive with the app not opened until a few minutes in. **Pass:** Trip includes the start of the drive, so the service came back by itself. **If not:** the head unit restarts in a way that stops it, and opening the app before pulling out is the way around.
    - An overnight charge with the app closed. **Pass:** "Last charged to" is near the truck's own figure.
    - The truck still sleeps as it did: no new codes with `./probe dtc -t elm-bt:auto` before and after, no messages on the cluster, and the 12 V drain test (§2) if it is run.

### Known defects

From an independent review of 0.5.0 (2026-11-04) and a review of the Simple redesign (2026-11-05), on fake adapters and simulated readings, never on the truck. Fixed in 0.8.0: saved capacity forgetting where it came from, the energy sign settling wrong, "Read X ago", the 27AE fit check, and "Usable now" while the car service is down. Fixed after the code 11 bundle: the charge-rate step across a battery-level outage, which now goes by the level's own step.

- **Minor: Simple doesn't say why a link stopped.** Its MX+ button changes to Connect MX+, which retries, but the reason shows only on Live and Diagnostics, and `LinkEngine.attach` clears the stop on the next visit more than 15 s later.
- **Minor: Disconnect and Cancel connect aren't always immediate.** A Bluetooth connect already running finishes first, up to 10 s (`CONNECT_TIMEOUT_MS`, the same in 0.4.0), and a running Test link finishes first too. Nothing is written after the tap, except the rest of that Test link's adapter setup.
- **Minor, left as it is:** when pack readings stop, up to 10 s of distance counts with no energy, at most about 0.1 kWh per dropped link.
- **A saved estimate from before 0.8.0** still shows as "last read" until the MX+ next reads a real capacity.
- **Risks only the truck can answer:** the poller has no ignition check and goes quiet only once modules stop answering. Simple is the default tab and now polls, so items 1 and 11 and the 12 V drain test matter more. Android may freeze the process before the 15 s grace ends, which could hold the MX+ socket. Paging an absent MX+ from Simple, up to once a minute, might disturb phone audio.

### Low-priority unknowns

What each of the four `2AF7` values means; what the `2B43` per-module values mean (only the module count is established); the rest of the `5401` enum; the layouts of `2AF3`, `2AF4`, `2AF8` and `2AF9`.

### Not built, only if asked

- Ah by coulomb counting, with persistence across sessions, and capacity-estimate quality over several charges.
- A poll-tier setting in the app.
- BLE as a second transport.
- Discovery `scan` of more BECM ranges for isolation resistance and similar (GM's tool shows these): parked, attended, rate-limited, with DTC bracketing.
- Contributing the confirmed Silverado EV DIDs back to OBDb (its Silverado EV signal set is currently empty).

---

## 3. Safety model: how "no truck impact" is enforced

Tests that enforce this: `tools/probe/tests/test_probe.py`, and in the app `SafetyTest`, `ElmLinkHardeningTest`, `SourceSafetyTest`, `AndroidWiringTest`, `DrivingStateTest`, `DashLayoutTest` and `PollerSleepTest`.

1. **Read-only protocol allowlist** (`tools/probe/src/ultium_probe/safety.py`, mirrored in the app in `obd/Safety.kt` and `obd/ElmTransport.kt`). Only these may be sent:
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
   - At connect, and after a timeout or Ctrl-C, the probe never writes to the adapter until it has shown its prompt and gone quiet. A busy ELM drops a character and can turn a read into another service. The app's `ElmLink.settle` applies the same rule.
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
   - In watch mode (`--keep-watching`, and the app: `link/LinkEngine.kt`), **nothing is sent on the bus**. Once a minute it reads only the adapter's own voltage, which is adapter-local. It sends a single read only when that voltage rises 0.5 V above its lowest value since sleep, meaning the truck's DC-DC came on. A failed voltage reading never triggers a read. Unit tests check zero requests while asleep, and exactly one per rise (`PollerSleepTest`, `test_probe.py`).
   - The app polls while Live or Diagnostics in Park is visible, and on Simple with a chosen MX+ that isn't disconnected (`UiSettings.wantsObd`). With none of them visible nothing is sent; the socket stays open, silent, for at most 15 s in case a screen comes back, then closes and releases the MX+ (`link/LinkService.kt` header, `LinkEngine.GRACE_MS`). Disconnect closes it at once and nothing reopens it until Connect, app restarts included (`LinkEngine.setDisconnected`).
   - The battery log (`log/LogHub.kt`) adds one thing: once an hour, from a job, a **visit** connects to the MX+ for one poll cycle, keeps one row and lets go, through the same `LinkEngine`, guards and read-only allowlist. Only when the truck positively reports ignition ON or START, an MX+ is chosen and not disconnected, and demo data is off; unknown ignition means no visit (`LogPolicy.visitAllowed`, `LogModelTest`, `LogWiringTest`). So a visit can't wake a sleeping truck or keep it awake. Otherwise MX+ rows only listen to what a screen is already reading, one row a minute.
   - Limits:
     - A wake can be missed if the 12 V doesn't rise at least 0.5 V. Then restart `poll` by hand.
     - An adapter that can't report voltage gets a single read at 1, 5 and 30 minutes, then polling stops.
   - The MX+ has its own low-power sleep, about 2 mA asleep (research table). The 12 V drain test in §2 checks the whole setup; it has not been run.
4. **DTC bracketing.** Scans read the gateway's OBD-mode trouble codes (03/07/0A) before and after.
   - A scan refuses to start if the baseline read fails.
   - It reads the codes again after Ctrl-C, a busy storm or a silence stop. After an adapter or bus error it stops writing and tells you to run `dtc` yourself.
   - It reports any new code.
   - It can't see UDS-only codes inside individual modules, so also watch the cluster and center screen for messages.
5. **Physical:**
   - Connect the adapter through a **pin-limited extension or breakout box that passes only pins 4, 5, 6, 14 and 16**. Check it with a meter before first use. The hardware list calls it optional, and the repo doesn't record whether one is fitted (§2).
   - The MX+, like most multi-protocol adapters, has contacts on other pins for GM's older single-wire (pin 1) and medium-speed CAN. On Global B, pin 1 reportedly carries a secondary CAN-FD network (secondary source) and four pins may carry Ethernet. The probe never selects those protocols. A pin-limited extension keeps them physically disconnected; the Hummer EV project ran without one, but it's cheap insurance.
   - The adapter's protocol is pinned to 29-bit CAN at 500 kbit/s, so it never runs a protocol search.
   - No splicing, taps or splitters with electronics.
   - The extension also keeps the adapter away from the driver's knee and pedals.
6. **Head unit:**
   - No exploits, sideloading, developer options, firmware or calibration changes.
   - **The background service, the owner's choice on 2026-11-06** (`vehicle/VehicleWatchService.kt`): a foreground service, because Android freezes anything else within minutes. It only keeps `VehicleSession` sampling through the same read-only `VehicleReader`. It never touches the OBD link, so it adds nothing on the bus through the MX+, and it holds no wake lock, so nothing in it asks the head unit to stay up. Whether the truck still sleeps exactly as before has to be seen on the truck (§2, item 18). In Park with nothing on screen it reads every 4 s instead of stopping (`VehicleSession.WATCH_TICK_MS`); moving or on screen, the usual 2 Hz. It starts from a visible screen, at boot and after an update (`VehicleWatchStarter`), and not with demo data. `SourceSafetyTest` allows this one start of this one service; every other service start stays forbidden. Unknown: how often GM's API readings go out over the truck's own networks, which is why the parked rate is slow.
   - The app never calls `CarPropertyManager.setProperty` or any write API.
   - It never calls `insert`, `update`, `delete` or `call` on any GM content provider (e.g. `com.gm.vehicleinfo.*`), some of which accept writes from any app, and it never binds GM private services. `SourceSafetyTest`, a unit test, enforces this.
   - A Play internal-test install is an ordinary app install and is removable at any time.
7. **Driver distraction:** the main screen is marked distraction-optimized (decision in §7), so the app locks itself down out of Park.
   - `DrivingPolicy.restricted` (`vehicle/DrivingState.kt`) is fail-safe: on a car device the app is restricted unless the UX restrictions service positively says otherwise, and a gear out of Park or a speed also restricts (in Park, up to 0.5 m/s counts as sensor noise).
   - Diagnostics shows only "Available in Park" (`ui/AppShell.kt`). No pairing, system dialogs or tap details, and on Live no Retry.
   - **Exception, the owner's choice on 2026-11-06:** Simple keeps one layout in every gear (`DashLayout.ofSimple`), so shifting moves nothing on it, and its header works in every gear: Reset session with its confirmation, and all four tabs. The Trends and Diagnostics tabs lead to the "Available in Park" panel out of Park. The tabs and the MX+ button are 76 dp tall in Park too. A notice has no button out of Park, the one thing on the page that changes. This goes further against Google's car-app guidelines than the rest of the moving mode.
   - **Header and units, the owner's choice on 2026-11-06:** the header is the tabs and the buttons, with no app name, "Read-only" or DEMO DATA chip on any tab. Simple also drops its drive summary and the caption line under the header, so nothing on it says when figures are simulated, paused or charging; only a notice about the data feed still appears above its tiles. Live and Trends keep their status text. Every screen is in °F and miles (`Units.TEMP`, `Units.DIST`), with no toggle, locale default or saved setting; the CSV export keeps its metric columns, named as such.
   - Live has no cell heatmap (`ui/live/LiveScreen.kt`), and values change at most once a second.
   - Tests: `DrivingStateTest`, `DashLayoutTest.drivingLocksEverythingElseAway`, `AndroidWiringTest.diagnosticsAndPairingAreReachableOnlyInPark`, `AndroidWiringTest.diagnosticsStartersRefuseWhileRestricted`, `AndroidWiringTest.simpleDoesNotChangeWithTheGearAndItsControlsWorkInDrive`, `SourceSafetyTest.distractionOptimizedActivitiesAreExactlyTheAllowlist`.
8. **Privacy:** the VIN appears in several DIDs (`F190`, `4193`). `obd` and `read` mask VIN-shaped replies unless you pass `--show-vin`, and `scan` and `poll` always mask them. The app masks them too (`obd/Vin.kt`, `report/Report.kt`). `logs/` is git-ignored.

Residual risks, stated plainly:
- Any OBD device on an always-powered port can drain 12 V if misconfigured. The sleep logic mitigates it, but the drain test has not been run, so this is still untested.
- GM OTA updates can change module behavior. The app detects silent modules and falls back.
- Unplug the adapter before service visits.

---

## 4. Data map: dashboard field → source

`tools/probe/dids/silverado_ev.toml` is the source of truth: 47 entries `confirmed`, 10 `unverified` (`2AF1`, `2AF2`, `2AF3`, `2AF4`, `2AF8`, `2AF9`, `0046`, `434F`, `4127`, `4124`). `confirmed` means the read works on this truck and the value is plausible; the table says where a meaning is still open. Figures are from session 1 (truck idle, in Park).

Addressing: module `ecu` at priority 0x14 sends `0x14DA<ecu>F1` and gets the answer on `0x142AF1<ecu>`. At priority 0x18 it sends `0x18DA<ecu>F1` and gets `0x18DAF1<ecu>`.

| Dashboard field | Source | Decode | This truck |
|---|---|---|---|
| SoC (matches dash) | BECM `CB` @0x14, DID `27C6` | u16 × 100/65535 % | confirmed: 41.88 % (dash 42 %) |
| Energy remaining | `CB 27AF` | u16 / 100 kWh | confirmed: 85.3 kWh |
| BMS energy at 100 % | derived `27AF ÷ (27C6/100)` | — | about 203.5 kWh (175–216 kWh seen across other trucks, by pack) |
| Range (BMS) | `CB 27C7` | u24 / 103 mi | confirmed: 187.6 mi; compare with the dash |
| Distance since full | `CB 27C0` | u24 × 0.0621371 mi | confirmed |
| Cell group avg / min / max | `CB 2AF6` (modules 1–12) and `CB 2AF5` (modules 13–24), bytes 0–5 | u16 / 10000 V each | confirmed: avg 3.6729 / 3.6732 V |
| Location of min / max group | `CB 2AF6`, `CB 2AF5` bytes 6–9 | (group #, module #) × 2 | confirmed: `2AF6` matched the array |
| **All cell-group voltages** | `CB 2AE1…2AF0`, 12 records of 3 bytes each | u16 / 10000 V + module # | confirmed: 192 groups, 3.6694–3.6771 V; each half sums to the pack voltage |
| Pack voltage | Drive unit `17 2885` (and `1D 2885`) | u16 / 100 V | confirmed: 352.7 / 353.6 V |
| Pack voltage (BECM, 4 points) | `CB 2AF7` | u16 / 100 V each | read works, 352.5–352.7 V; meaning per point TBD |
| **Pack current** | `17 2414` | s16 / 20 A, + = discharge | answers: 0.0 to +0.6 A idle; sign under load open. `1D 2414` is not on this truck (NRC 0x31) |
| Power in/out | derived V × A | kW | not displayed: it measures the session's energy (`SessionTracker.addPack`); the truck's dash has the kW |
| Module temperatures (24) | `CB 2AF1` | u8 − 80 °C (disputed: (x−40)/2) | unverified: 16–17 °C, likely x−80 |
| Module temperatures, second set | `CB 2AF2` | u8 − 80 °C | unverified: all 17 °C |
| Temp stats with location | `CB 2AF3`, `2AF4` | raw for now | unverified, layout unknown |
| Pack temp candidate | `CB 0046` | u8 − 40 °C | unverified: 20 °C, plausible |
| HV battery temp | BCM `40 434F` @0x18 | u8 − 40 °C (Lyriq) | **wrong here:** 60 °C with a 17 °C pack. Polled, never displayed (`Profile.NOT_DISPLAYED`) |
| Battery temp A / B | `40 4127`, `4124` @0x18 | u16 / 32 °C | unverified: 32.75 / 13.06 °C, meaning unclear. Polled, never displayed |
| Coolant temps 1/2 | `40 40E5`, `40E6` @0x18 | u16 / 32 °C | confirmed: 21.6 / 19.6 °C, plausible |
| Charge pilot current | `40 4149` @0x18 | u16 / 10 A | confirmed: 10.0 A (unplugged default); charging open |
| Module count | non-zero bytes 2–25 of `CB 2B43` | count | confirmed: 24 (others: 20 on a 2024 truck, 24 on a 2025 Sierra); per-module meaning open |
| BMS-internal SoC-like value | `CB 2B43` byte 0 | u8 × 100/255 % | read works; does not equal dash SoC |
| Series count | `2885 ÷ 2AF5 avg` | ≈ 96 (24 modules), ≈ 80 (20 modules, inferred), unknown (14); detected at runtime | 96 |
| Charge state code | `CB 5401` | enum (partly known) | confirmed: 0 idle; others open |
| 12 V system | `CB 33E5`, `17 33E5` (1D also answers) | u8 / 10 V | confirmed: 12.4 V |
| Unknown | `CB 2AF8`, `2AF9` | raw | unverified: meaning unknown |
| Capacity kWh | BECM `27AE` (`battery_capability`) ÷ 100, kept in `capacity_kwh` while within 15 % of energy ÷ SoC | kWh | unverified: compare with Car Scanner's Battery Capability |
| Capacity Ah | BECM `4676` (`pack_capacity_ah`) ÷ 100 is a guess, kept in `capacity_ah` only if it fits the pack voltage; else `ah_nominal_est` = kWh ÷ (series groups × 3.6 V), labelled as calculated | Ah | unverified. Coulomb counting (∫ `2414` dt ÷ ΔSoC, ΔSoC ≥ ~10 %) is not built |
| Capacity deviation | Car Scanner's "battery total energy storage capacity deviation": integer, 0–50, 22 on the owner's screen. DID unknown; a profile field named `capacity_deviation` shows it | Ah | to find with `./probe scan` on the BECM |
| SoC / kWh / capacity (VHAL) | `EV_BATTERY_LEVEL` 0x11600309, `INFO_EV_BATTERY_CAPACITY` 0x11600106 | Wh | read live on this truck (also Blazer EV) |
| Range (VHAL) | `RANGE_REMAINING` 0x11600308 | m | read live on this truck (also Blazer EV) |
| Charge rate (VHAL) | `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` 0x1160030C | mW ± | used for energy only once it agrees with the battery level (`SignalVerdict`); sign and liveness while driving still to read from the report |
| Outside temp | `ENV_OUTSIDE_TEMPERATURE` 0x11600703 | °C | read live on this truck (also Blazer EV) |
| Speed | `PERF_VEHICLE_SPEED` 0x11600207 | m/s | used by Simple and the driving state; 0.5 m/s noise allowance in Park unconfirmed |
| Driving restrictions (UI gating) | `CarUxRestrictionsManager` (primary); `GEAR_SELECTION` 0x11400400 as a display hint | — | AOSP standard; the moving mode stays up in Drive |
| Usable capacity now | `EV_CURRENT_BATTERY_CAPACITY` 0x1160030D | Wh | not offered on this truck (photo, 2026-11-03; build unknown). Reports elsewhere: Android 14 only, not exposed on 12L. The app falls back to nominal capacity |

The battery log's columns (`log/LogModel.kt`, one row per CSV line):

| Truck API (`source=car`): hourly, and every 5 min while a screen is up | MX+ (`source=mx`): every minute while connected, including on Trends in Park |
|---|---|
| `outside_c`, `car_soc_pct` (level ÷ capacity), `car_energy_kwh`, `car_capacity_kwh`, `car_capacity_nominal`, `car_nominal_kwh`, `car_usable_kwh`, `car_range_km`, `car_plugged`, `car_port_open`, `car_charge_kw`, `car_charge_state`, `car_charge_limit_pct`, `car_draw_limit_a`, `car_charge_minutes`, `car_battery_temp_c`, `car_regen_state`, `car_ignition`, `car_gear`, `car_speed_kmh` | `soc_pct`, `bms_soc_pct`, `energy_kwh`, `bms_range_km`, `since_full_km`, `capacity_kwh` + raw `becm_27ae_kwh`, `implied_kwh` (energy ÷ SoC), `capacity_ah` + raw `becm_4676_ah`, `nominal_ah`, `ah_deviation`, `module_temp_min_c`/`max`, `cell_min_v`/`max`, `cell_spread_mv`, `cell_avg_a_v`/`b`, `cell_min_group`/`max`, `coolant_1_c`/`2`, `pack_temp_c`, `battery_temp_a_c`/`b`, `hv_battery_temp_c`, `pack_v`, `pack_a`, `pack_kw`, `pack_v_du2`, `aux_12v_v`, `aux_12v_du_v`, `charge_state_code`, `evse_pilot_a`, raw `becm_27b9_raw`, `becm_2984_raw`, `becm_270d_raw`, `becm_28af_raw`, `becm_2770_raw` |

`car_nominal_kwh` is a fixed figure, so it's a flat line whatever the pack's health. `car_usable_kwh` stays empty until the truck offers `EV_CURRENT_BATTERY_CAPACITY`. So the truck API can't show capacity changing on this truck: that takes the MX+. `car_capacity_kwh` is whichever of the two the SoC was worked out from. The `becm_*_raw` columns are short BECM values of unknown meaning: compared with Car Scanner, they may identify the deviation PID. `module_temp_*` are `2AF1` sensors at u8 − 80, still unverified; if the scale changes, the logged values can be converted back. `nominal_ah` is `implied_kwh` rescaled, not an Ah measurement. `27AE` and `4676` are kept raw as well as checked, so history stays usable if either turns out wrong.

Not reachable: isolation resistance, contactor state, power limits, SoH %. GM's dealer tool shows isolation resistance from the BECM, but no DID is publicly known. Discovery may find some, read-only (§2, not built).

---

## 5. Truck findings

The dated record, with every value and the raw-log references, is [`docs/session1-results.md`](docs/session1-results.md). The headlines:

- **The pack:** 2024 truck, 24 modules (Max Range), 96 series groups: two 400 V halves of 12 modules, 192 cell-group records. Each half sums exactly to the pack voltage. Cell groups within a module agree within about 0.3 mV, while module averages differ by up to 7.7 mV.
- **Who answers:** battery `CB` at both 0x14 and 0x18, drive unit `17` at both, body controller `40` at 0x18. `1D:2414` doesn't exist here. The census named only the gateway.
- **Traps:** `2AF5`'s min/max cover half B only; pack min/max need `2AF6` + `2AF5` or the full array. `40:434F` reads 60 °C and is not displayed.
- **Safety:** about 400 requests, all read-only, at least 75 ms apart. No gateway trouble codes before or after, and no warnings.

**Window width (observation for layout work, from the owner's photo of Diagnostics in Park, 2026-11-04, on 0.4.0).** The header showed the tabs but no "Silverado Stats" title and no "Read-only" note. `DashLayout.of` then dropped the note, then the title, when its budget for the widest header (the Simple tab with demo chip and long summary) didn't fit the window, whichever tab was showing. At font scale 1.0, parked, the title needed a window at least 2032 dp wide when it was 720 dp or taller, 1715 dp when it was 560–719 dp tall, and 1409 dp when shorter. So either the truck's window is narrower in dp than both proxy examples in `DashLayoutTest.knownWindows` (2331 x 735 and 2103 x 602 dp, which both drew the title), or its font scale is well above 1.0. The full-size screenshots in `docs/images/` may therefore show a larger layout than the truck gets. By the owner's choice on 2026-11-06 the header draws neither any more, so a new photo can't tell this way. The real window size, density and font scale are still unknown (§2, item 12).

---

## 6. Architecture and design rules

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
 │  │ Head unit (AAOS, arm64; build not recorded yet)      │    │
 │  │  Silverado Stats app (Play internal test)            │    │
 │  │   ├─ OBD client: allowlist, Ultium addressing,       │    │
 │  │   │  tiered poller, sleep back-off                   │    │
 │  │   ├─ VHAL reader: CarPropertyManager, read-only      │    │
 │  │   ├─ Model: derived kW, spreads, measured mi/kWh     │    │
 │  │   └─ UI: Simple and Live (Park and Drive),           │    │
 │  │      Diagnostics (Park)                              │    │
 │  └──────────────────────────────────────────────────────┘    │
 └──────────────────────────────────────────────────────────────┘
   Laptop: discovery and logging with tools/probe (same MX+,
   paired over Bluetooth: elm-bt:auto). Builds and uploads the app.
   Receives diagnostics reports from the app (`probe receive`).
```

Design rules:
- One DID profile is shared by the probe and the app. `tools/probe/dids/silverado_ev.toml` is the source of truth and is exported to JSON for the app.
- The read-only allowlist is enforced at the lowest transport layer in both the probe and the app, and unit-tested so nothing else can be sent.
- The app is the Bluetooth client and does the pairing itself, because GM's Bluetooth settings may only list phones. The MX+ serves one host at a time, so the phone's OBD app must not be holding it, and the laptop and the head-unit app exclude each other.
- Use integer property IDs, because GM strips the `VehiclePropertyIds` field names at runtime.
- The battery log lives in SQLite in the app's private storage, some tens of MB a year at most, far under CarWatchdog's limits. **Uninstalling the app deletes it**, so export first. Demo mode shows simulated rows on Trends, but they are never stored or exported. Trends is parked only and fits one page.
- There's no logcat on the truck, so the Diagnostics screen is how we learn what the truck exposes. **Send to laptop** posts the full report to `./probe receive`, which saves it under `logs/reports/`; the head unit dials out to it. It is untried on this truck. It needs a hotspot both the head unit and the laptop can join, and some GM EVs reportedly can't join Wi-Fi (`docs/play-console.md`). Otherwise, photograph the screen, though some report figures appear on no screen (§2, item 5).
- Simple and Live fit one page without scrolling, sized from the window the app actually got (`ui/layout/DashLayout.kt`, `DashLayoutTest`, `AndroidWiringTest.simpleAndLiveNeverScroll`).
- Kotlin and Jetpack Compose, minSdk 32, targetSdk 35, compileSdk 36, no native code (arm64 head unit).

Emulator notes:
- Google AAOS API 34 and 35 images. The current SDK channel lists no API 32 image, so API 32 can't be emulated here. What the truck runs is still unrecorded (§2, item 12).
- Use no-Play images for distraction-optimization tests. Play images honor it only for Play-installed apps, and the custom 17.7" profile also needs a no-Play image.
- 2914×1134 px at 200 dpi, the Blazer EV panel, is the best public proxy for the 17.7" (but see the window observation in §5).
- Optionally GM's Lyriq image, from the GM SDK update site.

---

## 7. Limits and decisions

### What is and isn't possible

| Want | Possible? | How |
|---|---|---|
| App on the infotainment screen | **Yes** | Your own Play Console app on the **regular internal testing track**, installed from the Play website onto the truck. Owners install internal-test apps today on 2024–2025 Silverado EV, Sierra EV, Blazer EV and Equinox EV, including Android 14. |
| `adb install` from the laptop | **No** | GM production head units gate ADB behind GM certificates. The in-cab USB ports are host-only, and wireless debugging is unreachable. Every workaround needs radio hardware or EEPROM mods, which are ruled out. |
| SoC, kWh remaining, nominal capacity, range, charge-port state, outside temp | **Yes, no extra hardware** | Standard Android vehicle properties (VHAL) via `CarPropertyManager`. Read live by another owner's app on a 2024 Blazer EV (same head-unit family), and on your truck. |
| HV pack power in/out from VHAL | **Maybe** | `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` (mW, + charging / − discharging in AOSP). Only 0.00 kW was seen parked; while driving the owner saw kW with the signs swapped and a long delay, which an inverted rate and the old level-based estimate would both explain. The session now checks the rate against the battery level for sign and scale before using it for energy, and the diagnostics report says what it found (`chargeRateCheck`). |
| Pack V and A, cell-group voltages, module temps, coolant temps | **Partly confirmed, with your OBDLink MX+** | Read-only UDS `0x22` requests through the OBD-II port. Session 1 confirmed pack voltage, idle pack current, the cell-group array and coolant temps. Still open: the `2AF1` scaling and the `2414` sign under load. |
| Per-cell voltages | **Per cell group, confirmed** | Ultium cells are wired 3 in parallel per group, so the BMS reports groups: 192 on a 24-module pack (96 in series per 400 V half), 160 on 20 modules, 112 on 14. Your truck has 192, in DIDs `2AE1…2AF0`. Individual parallel cells are never visible. |
| Capacity in kWh and Ah, SoH | **Being confirmed** | The owner's Car Scanner shows Battery Capability 208.44 kWh, Total Energy Storage 602.2 Ah and a capacity deviation of 22 Ah, so they are PIDs. `27AE` is very likely the kWh (public Car Scanner log of another Silverado EV). `4676` is a guess for the Ah, shown only if it fits the pack voltage; otherwise kWh ÷ (series × 3.6 V), labelled. The deviation's DID is unknown and its tile says so. To find them: `./probe scan` over the BECM on the truck, then compare with Car Scanner. |
| Cabin / HVAC temperature | **No** | `HVAC_TEMPERATURE_CURRENT` needs a privileged permission. |
| Driver cluster or HUD widget | **No** | Rendered by the QNX host, and GM kills third-party cluster services. |
| Display while driving | **Yes, limited** | Only Play-installed apps may show distraction-optimized screens. See the decision below. |
| CarPlay as a fallback | **No** | This truck has no CarPlay (`docs/research/hardware-connectivity.md`). |

### Hardware

| Item | Why | Approx. |
|---|---|---|
| **OBDLink MX+** (you have it) | The only adapter. STN2255, Bluetooth Classic. Proven on a 2024 Hummer EV (BT1, the Silverado's twin). Low-power sleep. Used for laptop discovery and by the head-unit app. | owned |
| *Optional:* **OBD-II breakout box with per-pin switches**, or a passive extension wired **only** on pins 4/5/6/14/16 | Keeps the adapter's other contacts (pin 1, Ethernet pins) disconnected and the adapter clear of the driver's knee. Check with a meter. | $15–40 |
| Not needed | Wi-Fi adapters (ruled out), CAN-FD interfaces (PCAN-FD, red panda), Raspberry Pi bridges, splitters. The laptop has no CAN interface. | — |

The CAN-FD gear isn't needed because every public Ultium read works over classic 29-bit CAN at 500 kbit/s on pins 6/14. The gateway isolates the port, so passive sniffing captures nothing; only requests get answers.

### Decisions

- **2026-11-03, Wi-Fi adapters ruled out** by the owner. The MX+ over Bluetooth Classic is the path, and it works from the head unit.
- **2026-11-03, regular internal-testing track.** The dedicated Android Automotive OS track, on `com.vincel.silveradostats`, stayed Inactive, and that choice is irreversible per package, so it was abandoned. The regular track on `com.vincel.silveradoev`, with `android.hardware.type.automotive` not required, works. Details: `docs/play-console.md`.
- **2026-11-03, while-driving display: option B.** Android Automotive blocks any screen that isn't marked "distraction-optimized" whenever the truck is out of Park, including R or D at a standstill.
  - The owner first chose option A, a templated glance screen with the Car App Library and a Park-only dashboard, then switched to option B, a Compose screen marked distraction-optimized, because Park-only made the app nearly useless.
  - The moving mode is locked down: no Diagnostics, pairing, Retry or tap details, and no cell heatmap on Live (§3, item 7). The exception is Simple's own header, by the owner's choice on 2026-11-06 (§3, item 7). The owner confirmed on 2026-11-04 that it stays up in Drive.
  - **The app must stay on internal testing.** Option B goes against Google's car-app guidelines, which are only enforced at review, and internal tests aren't reviewed. Never a production track.
  - GM could disable it in an OTA, and there is no fallback: the owner had Battery Glance, the templated screen, removed on 2026-11-07 so that only Silverado Stats is in the launcher. The app would then work in Park only. Battery Glance's exact source is in `release/silverado-ev-v17-source.tgz`, if it is ever wanted back.
  - Known limit: a system permission dialog opened in Park stays up if the truck then leaves Park, and Android gives the app no way to close it (§2, item 9). The emulator's OS didn't cover it; the truck's is untested.

---

## 8. Open risks

| # | Risk | Likelihood | Mitigation |
|---|---|---|---|
| R2 | GM OTA closes internal-test installs or distraction-optimized behavior | Low–medium | If only distraction optimization goes, the app works in Park only until Battery Glance is restored from `release/silverado-ev-v17-source.tgz`. If installs close, the same app can also be built for a phone or tablet. |
| R3 | A GM OTA changes module behavior, e.g. BECM silent at both priorities (as some MY2025 reports at 0x18 only) | Low for Silverado | Re-test after each GM update. Fall back to drive-unit and BCM DIDs plus VHAL, a reduced dashboard. |
| R5 | 12 V drain from the always-on port | Medium if misconfigured | Bus-silent sleep watch, adapter sleep threshold, independent-meter drain test (not yet run); unplug for long parking. |
| R6 | Another host holds the MX+ (one host at a time: phone, laptop or head unit) | Medium, now handled in the app | Disconnect in the app before a phone or laptop session; unpair the MX+ from the phone if it grabs it first. The app says the MX+ isn't reachable and retries in the background (`bt/BtErrors.kt`, `RetryPolicy`). |
| R9 | A DID's meaning is wrong, now the `2AF1` scaling and the `2414` sign under load | Medium | "Unverified" badges in the UI until confirmed against outside temperature, coolant or the dash. |
| R10 | Android freezes a process with no visible screen (cached-app freezer, 10 s to about 6 min on API 34), which stops the measuring | Handled by the background service, untested on the truck (§2, item 18) | If the service is not running, a frozen stretch becomes a gap the session leaves out, with nothing on Simple to say so: `gaps` in the report shows it. Nothing can run while the head unit itself is off; the first reading after it wakes sees any charge. |
| R11 | The system disables a Play app after a day or weeks, as other GM owners saw (`docs/research/vhal-properties.tables.md`, Table 5) | Unknown; not seen on this truck | Note it if it happens; reinstalling from Play is an ordinary install. |

---

## 9. History

- **Research (2026-11-03):** 10 agents, adversarially verified; `docs/research/`, whose [README](docs/research/README.md) says what was overtaken.
- **Phase 0, prerequisites:** done in practice, except that the infotainment build was never recorded (§2, item 12).
- **Phase 1, first truck session, laptop only:** done 2026-11-03, except truck-off (§2, item 1). Runbook: `docs/truck-connection.md`.
- **Phase 2, confirm and extend data:** profile confirmed as far as a parked truck allows; the rest is §2.
- **Phase 3, app without an adapter, and distribution:** done 2026-11-03; diagnostics app installed from Play and running on the truck.
- **Phase 4, OBD inside the app:** Kotlin port of the probe, tiered poller, Live, Simple, Battery Glance, moving mode; 0.4.0 (code 7, tag `v0.4.0`) published 2026-11-04. Not built: §2.
- **Phase 5, hardening:** reconnect strategy built (`RetryPolicy`). Cold-weather and 800 V behavior remain in §2.
- **0.5.0 (2026-11-04):** removed the Wi-Fi TCP link test, the BLE scan and their permissions from the app, and SocketCAN, TCP and USB-serial transports, `sniff`, `iface` and `serve` from the probe. All of it is still in `v0.4.0`.
  - The same release, after the owner's first drive: kW readouts removed (the truck's dash has them). Session energy now comes from pack V × A (MX+), else the charge rate once it agrees with the battery level, else the level, which stays in the report and on screen for comparison. Capacity tiles replace them: the car API's kWh on Simple, the BECM's kWh, Ah and deviation on Live. Simple uses the MX+ when it is connected. A Disconnect / Connect button on Simple, Live and Diagnostics, usable while driving at the owner's request, keeps the MX+ free until Connect, across app restarts.

- **0.6.0 (code 9, published 2026-11-04):** battery log (hourly job and MX+ rows), the Trends tab and CSV export.
- **0.7.0 (code 10, published 2026-11-04):** Trends shows one source at a time, Truck API or MX+: capacity tiles with their PID and the change over the range, one chart with low, high and change under it, and every logged PID in groups, with its latest value and why it has none, L and R putting it on the chart. The log gained every single-value PID of both sources, nominal and usable capacity in columns of their own, a built-in row every 5 min while a screen is up, and the MX+ stays connected on Trends in Park. Older rows are kept and sorted into the new capacity columns when the log opens. Export no longer loads the whole log into memory.
- **0.8.1 (code 12, published 2026-11-05):** the Simple redesign, the MX+ button work and review fixes. Code 11 was built but never uploaded.
- **0.9.0 (code 13, published 2026-11-06, untested on the truck):** Simple gets mi/kWh for this drive and the last 15 minutes with no warm-up, a last charge line, and a larger battery %. It keeps one layout in every gear, and its header works in every gear (§3, item 7). Signed bundle and source: `release/silverado-ev-v13.aab` and `-v13-source.tgz`.
- **0.10.0 (code 14, published 2026-11-06, untested on the truck):** the header is the tabs and the buttons, Simple loses its drive summary and caption line, every screen is in °F and miles (§3, item 7), and a background service keeps measuring with no screen up (§3, item 6; §2, item 18). Signed bundle and source: `release/silverado-ev-v14.aab` and `-v14-source.tgz`.
- **0.11.0 (code 15, published 2026-11-06, untested on the truck):** "used since" on Simple is a running total with the truck on, so the level settling while it is off counts for nothing, and a level-only rise has to reach 5 kWh to be a charge (§2, item 6). A new app icon, a battery of cells in the Live heatmap's colors, replaces Android Studio's default; the unused bitmap icons are gone, since `mipmap-anydpi` serves every supported Android version. Signed bundle and source: `release/silverado-ev-v15.aab` and `-v15-source.tgz`.
- **0.12.0 (code 16, published 2026-11-06, untested on the truck):** Simple's bottom row adds the drift, the level now against what the last charge and the energy used say it should be, + above and - below (§2, item 6). Signed bundle and source: `release/silverado-ev-v16.aab` and `-v16-source.tgz`.
- **0.13.0 (code 17, published 2026-11-06, untested on the truck):** the owner's choices from mockups: Range goes by the last 15 minutes' mi/kWh instead of the whole drive's, keeping its figure while stopped; Simple's upper columns become six cells alike, with the figures of a row lined up; the bottom row puts the words under the figures and adds the mi/kWh since the last charge (§2, item 6); and every tab takes the Cell Pack colors, the app icon's blue and coral on navy. Signed bundle and source: `release/silverado-ev-v17.aab` and `-v17-source.tgz`.
- **0.14.0 (code 18, published 2026-11-07, untested on the truck):** Battery Glance removed at the owner's request, so the app has one launcher entry, and the Car App Library went with it. Play kept all 29 supported cars at upload, without the template tag. While-driving use now rests on MainActivity's distraction-optimized tag alone (§7, §8 R2).
- **0.15.0 (code 19, published 2026-11-07, untested on the truck):** the owner's choice from a mockup: Simple's battery ring is gone, and the kWh left and the state of charge are cells like the others, so the page is four even columns of two. The last charge's row runs the full width, with a part under each column: what the charge reached in kWh and %, then the energy used since, the mi/kWh since and the drift. On windows too narrow for "last charged to" and "of capacity" side by side, the row's words step down a size before its figures shrink. Signed bundle and source: `release/silverado-ev-v19.aab` and `-v19-source.tgz`.

The full earlier plan, with every phase's steps: `git show v0.4.0:PLAN.md`.
