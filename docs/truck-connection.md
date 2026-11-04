# First truck session: laptop and your OBDLink MX+ (Phase 1)

Goal: in about an hour, parked and attended, confirm which battery data your truck answers and how. Nothing is installed on the truck.

Every request is a read to a diagnostic address. This is enforced in `tools/probe/src/ultium_probe/safety.py`, and anything else is refused before it reaches the adapter.

You need:
- the laptop with this repo; its Bluetooth works with the MX+
- your **OBDLink MX+**. It's Bluetooth Classic, and a 2024 Hummer EV project, the Silverado's platform twin, used one for all its battery reads.
- optional but recommended: an **OBD breakout box or extension that passes only pins 4, 5, 6, 14 and 16**. The MX+ has contacts on other pins for GM's older single-wire and medium-speed CAN. The probe never selects those, but a pin-limited extension removes the possibility entirely and keeps the adapter clear of your knee.
- the truck at home, in Park

Every command runs from the repo root with the `./probe` wrapper. It's a bash script and works from fish. `-t elm-bt:auto` finds the paired MX+ by itself.

---

## 0. Before you go (at your desk, 5 minutes)

```bash
./probe poll --profile tools/probe/dids/silverado_ev.toml --count 1
```

This runs against the built-in simulator, so you can see what the output looks like.

```bash
mkdir -p logs
```

## 1. Truck state

1. Park somewhere the truck can sit for an hour. Use Park and the parking brake.
2. Note the infotainment build: Settings → System → About, a string with `SQBR…` or `UQBM`. Also note the dash SoC %.
3. Find the OBD-II port, under the driver-side dash. Check the owner's manual if it's behind a panel.
4. Don't do session 1 while charging. Charging gets its own session later.

## 2. Pair the MX+ with the laptop (first time only, about 5 minutes; truck can be off)

1. Plug the MX+ into the OBD port, through the extension or breakout if you have one. Its lights come on.
2. **Keep your phone off the MX+.** Close the OBD app, or turn the phone's Bluetooth off for the session. The MX+ talks to one device at a time, and your phone will otherwise grab it.
3. On the laptop, open `bluetoothctl` and pair. Commands after the first line are typed inside `bluetoothctl`:
   ```bash
   bluetoothctl
   ```
   ```
   scan on
   ```
   **Press the button on the MX+** so it accepts a new device. When `OBDLink MX+` appears with its address (`XX:XX:XX:XX:XX:XX`), run these with that address:
   ```
   pair XX:XX:XX:XX:XX:XX
   trust XX:XX:XX:XX:XX:XX
   scan off
   quit
   ```
   Don't press "Connect" in `bluetoothctl` or KDE's Bluetooth panel. The MX+ only offers a serial port, so that always "fails"; the probe opens the serial channel itself.
4. Check that the probe finds it: `./probe obd -t elm-bt:auto` should print a banner like `# elm (… / STN… ; adapter sees 12.xV)`. It's fine if it then reports no modules while the truck is off.
5. **Don't use any write or clear-codes features of the OBDLink phone app on this truck.** Only `./probe` should talk to it during the session.

## 3. The session

Turn the truck **on**: foot on the brake, press the power button, wait until the screens are up. Modules answer nothing while the truck is off.

Run the steps in order. Stop if anything in §5 happens.

### 3.1 Census: who answers?
```bash
./probe obd -t elm-bt:auto | tee logs/01-census.txt
```

Check that:
- The first line (`# elm (…; adapter sees …V)`) shows the adapter's firmware and voltage. Expect about 13–14.5 V just after power-on, though 11.7–12.6 V can also be normal on Ultium in Ready. Note the value. This line is also saved in each log.
- `module:` lines appear for gateway `45` (GWM), drive units `17` (DMCM) and `1D` (DMC2), body controller `40` (BCM, may be missing from this list), battery `CB` (BECM-B+EnergyCtrl) and brakes `28` (BSCM).
- `vehicle speed:` reads `0`. `None` is also fine in Park.
- A masked VIN appears.

If you get nothing: check the truck is on, wait 10 s and retry.

### 3.2 Trouble-code baseline
```bash
./probe dtc -t elm-bt:auto | tee logs/02-dtc-before.json
```

This reads the stored, pending and permanent codes the gateway (0x45) reports. Whatever is there now is your baseline; some codes are normal.

If it prints `DTC READ FAILED`, the baseline is unusable. Note that, and treat §5's cluster-message check as your only signal.

### 3.3 Which priority does the battery module answer?
```bash
./probe read -t elm-bt:auto --ecu CB --pri both --did 27C6 --profile tools/probe/dids/silverado_ev.toml | tee logs/03-priority.jsonl
```

Expected: `CB@14` answers with `"soc"` within about 1 % of the dash. `CB@18` may or may not answer. Both results are useful.

### 3.4 Pack voltage and current
```bash
./probe read -t elm-bt:auto --ecu 17 --pri both --did 2885 2414 --profile tools/probe/dids/silverado_ev.toml | tee logs/04-pack-vi.jsonl
./probe read -t elm-bt:auto --ecu 1D --pri both --did 2885 2414 --profile tools/probe/dids/silverado_ev.toml | tee -a logs/04-pack-vi.jsonl
```

What to expect:
- `pack_voltage` (2885) is about 330–400 V on a 24-module pack, or about 270–335 V on a 20-module pack, lower at low SoC. A 14-module pack is lower still; just note it.
- `pack_current` (2414) moves when you turn the HVAC or seat heaters on and off. It's positive while the truck draws power.
- If 2414 doesn't answer on `17`, the second command tries `1D`.

### 3.5 Battery stats, then the cell-group array
```bash
./probe read -t elm-bt:auto --profile tools/probe/dids/silverado_ev.toml --module becm --did 2AF5 2AF7 2AF1 2AF3 2AF4 2B43 5401 27AF 27C7 33E5 | tee logs/05-becm.jsonl
./probe read -t elm-bt:auto --profile tools/probe/dids/silverado_ev.toml --module becm --did 2AE1 2AE2 2AE3 2AE4 2AE5 2AE6 2AE7 2AE8 2AE9 2AEA 2AEB 2AEC 2AED 2AEE 2AEF 2AF0 | tee logs/06-cell-groups.jsonl
```

You don't need to check `06` by hand; I'll analyze it. At a glance, a good result looks like:
- `len` 36 on the DIDs that answer: 16 DIDs on a 24-module pack, about 14 on a 20-module pack, about 10 on a 14-module pack.
- `cell_group_modules_2ae1` reads `[1,1,1,1,1,1,1,1,2,2,2,2]`.

If 2AE1 answers `NRC 0x31`, the array lives elsewhere on your truck, and Phase 2 looks for it.

### 3.6 Temperatures from the body controller (priority 0x18)
```bash
./probe read -t elm-bt:auto --profile tools/probe/dids/silverado_ev.toml --module bcm --did 434F 40E5 40E6 4127 4124 4149 | tee logs/07-bcm.jsonl
```

### 3.7 Live polling, then let the truck go to sleep
```bash
./probe poll -t elm-bt:auto --profile tools/probe/dids/silverado_ev.toml --out logs/08-poll.jsonl
```

1. Leave it running 5–10 minutes. Toggle HVAC, headlights and seat heaters to see current and power move. The `derived:` line shows kW, cell spread, series count and module count. When `ERROR` lines appear, the `derived:` values are the last good readings.
2. With it still running, **turn the truck off** and note the time.
   - When the modules stop answering (a few unanswered tries, 2 s apart), the poller prints `truck appears asleep: nothing more is sent on the bus` and exits by itself.
   - How long the truck takes to go quiet while being polled is unknown; this step measures it. Note the time.
   - If it hasn't exited after **10 minutes**, press Ctrl-C anyway and note that. It means polling keeps the truck awake, which we design around.
   - It may also end with `stopped: adapter reported 'CAN ERROR'` (or `BUS ERROR`) or `stopped: adapter link failed` (the MX+ went to sleep). After truck-off that's expected too. Note which one and the time, and continue with 3.8.

### 3.8 Trouble codes again
1. Turn the truck back on and wait 10 s. If the MX+ went to sleep, it wakes when the truck's 12 V comes up. If the next command can't reach it, press its button or replug it.
2. Read the codes again and compare:
   ```bash
   ./probe dtc -t elm-bt:auto | tee logs/09-dtc-after.json
   diff logs/02-dtc-before.json logs/09-dtc-after.json && echo "no change"
   ```
   The diff only counts if neither file shows `error`. The first line of each file, the adapter banner, can differ slightly in voltage.
3. Unplug the MX+, or leave it if that's how you normally keep it. It has its own low-power sleep.

## 4. Send back

The `logs/` folder: VINs are masked, and `logs/` is git-ignored. Also send:
- photos of the dash SoC and the About screen
- notes on what you toggled and when
- the time from truck-off to the poller exiting

I'll update `tools/probe/dids/silverado_ev.toml` (status → `confirmed`, scalings fixed) from these.

## 5. Stop conditions: unplug and stop if any of these happen

- A new warning light or message on the cluster or center screen.
- The MX+ or extension gets warm, or the connection at the port is loose.
- `dtc` shows a new code after any step.
- With the truck **on** (steps 3.1–3.6, or 3.7 before truck-off), the probe prints `stopped: …`, `scan aborted …` or `module keeps answering 'busy'`. Still do step 3.8, the trouble-code read, before unplugging, unless a warning is showing on the cluster.
- Anything you're unsure about. Unplugging the adapter always returns the truck to stock.

## 6. Troubleshooting

| Message | Likely cause | Try |
|---|---|---|
| `expected exactly one paired OBD adapter, found none` | MX+ not paired with the laptop | Redo §2; or pass the address: `-t elm-bt:XX:XX:XX:XX:XX:XX` |
| `cannot reach Bluetooth adapter … Host is down` | The MX+ isn't answering at all: asleep, not powered, out of range, or held by your phone | Plug it into the truck and turn the truck on (it sleeps with the truck off); check its lights; sit in or next to the cab; turn the phone's Bluetooth off; retry |
| `Failed to connect` in KDE or `bluetoothctl connect` | Normal for a serial-only device | Ignore; use `./probe … -t elm-bt:auto` |
| `stopped: adapter rejected '…'` / `adapter reported …` | Adapter refused a setting, or reported an error | Note the message and send it; don't retry in a loop |
| `stopped: unexpected adapter output …` / `echo came back on: it reset` / `no adapter banner after ATZ` | The adapter reset or browned out | Check the plug; replug; rerun the step |
| `stopped: adapter did not return to its prompt` / `kept talking at connect` | The adapter is stuck mid-command | Replug it, then rerun |
| `error: adapter timed out on '…'` | A slow reply, or a Bluetooth hiccup | Rerun once; if it repeats, replug the adapter |
| `stopped: adapter link failed` / `adapter closed the connection` | Bluetooth dropped, or the MX+ slept | Press its button or replug; rerun the step |
| `stopped: adapter reported 'CAN ERROR'` / `'BUS ERROR'` with the truck **on** | Bad contact at the port, or the extension isn't passing pins 6/14 | Check the extension or breakout wiring; stop if it repeats |
| `no answer from 0x…` everywhere | Truck off or asleep | Turn the truck on and wait 10 s |
| `CB@14` and `CB@18` both silent | A newer build or OTA change | Still run 3.4 and 3.6 (the fallback data); note the build string |
| `NRC 0x11` from a module | Wrong priority for that module | Use `--pri both` |
| `NRC 0x33` / `NRC 0x34` | Locked DID | Expected for a few; never try to unlock |
| `NRC 0x21` occasionally | Module busy | Normal; the client retries once |
