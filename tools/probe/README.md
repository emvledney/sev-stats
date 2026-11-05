# ultium-probe

Read-only battery diagnostics for GM Ultium trucks (Silverado EV), for the laptop. It uses only the Python standard library.

```bash
./probe --help                          # from the repo root
./probe poll --profile tools/probe/dids/silverado_ev.toml --count 1   # simulator
```

Without `-t` every command runs against the built-in simulator. Its banner then reads `*** SIMULATOR: not connected to a truck ***`.

## Safety

`src/ultium_probe/safety.py` is the single gate every transport passes through:

- **CAN IDs.** Physical requests go only to `0x14DA<ecu>F1` / `0x18DA<ecu>F1`, with the matching response ID. Broadcasts go only to `0x18DB33F1`. No 11-bit IDs.
- **Payloads:**
  - UDS `22 <DID>`, one DID per request
  - OBD `03`/`07`/`0A` DTC reads, physical
  - OBD `01`/`09` with one PID, broadcast
- **Pace.** Strictly serial, at least 75 ms between requests by default, with a floor of 50 ms.

The ELM/STN adapter link (e.g. the MX+) has its own guards (`transports/elm.py`):

- The adapter command set is allowlisted.
- Protocol and addressing settings must be answered with `OK`.
- The protocol is pinned to `ATSP7`, so it never runs an auto-search.
- A bare carriage return, which repeats the last bus message, is refused.
- The probe never writes while the adapter may be busy: at connect, after a timeout and after Ctrl-C it waits for the prompt and then silence.
- Any reset, late error or unexpected output stops the run.

`poll` stops when the truck sleeps. With `--keep-watching`, nothing is sent while the truck is asleep; only the adapter's own voltage is read, see `Poller` in `cli.py`.

The tests in `tests/` check all of this.

## Transports
| Spec | Adapter |
|---|---|
| `elm-bt:auto` | The paired OBDLink MX+, found by name via `bluetoothctl`. Pair it first (docs/truck-connection.md §2). Nothing else may hold it: keep the phone off it and the head-unit app disconnected |
| `elm-bt:XX:XX:XX:XX:XX:XX[:channel]` | A specific Bluetooth Classic ELM/STN adapter (RFCOMM channel 1 by default) |
| `sim` | Built-in simulated Silverado EV (default) |

## Commands
| Command | What it does | Exit codes |
|---|---|---|
| `obd` | Census: supported PIDs, speed, module names (09/0A), masked VIN | 0; 1 on adapter error |
| `dtc` | Stored, pending and permanent DTCs (default: gateway 0x45 @ priority 0x18) | 0; 1 if any read failed |
| `read` | Read DIDs from one module: `--ecu CB --pri 14\|18\|both`, or `--module` with `--profile` | 0; 1 on any NRC, no answer or adapter error |
| `poll` | Tiered polling of a profile with decoding, derived values and the adapter voltage every 30 s. Exits when the truck sleeps. `--keep-watching` instead waits bus-silently and resumes on wake | 0; 1 on adapter error |
| `scan` | Discovery over a DID range. Needs `--parked`, checks speed is 0, brackets with DTC reads | 0 ok; 2 not parked or moving; 3 new DTCs; 4 DTC read failed; 5 adapter error |
| `export-profile` | Writes a profile as JSON for the head-unit app | 0 |
| `receive` | Saves diagnostics reports the head-unit app POSTs to `http://<laptop>:8766/report` into `logs/reports/` (no truck traffic). It needs a hotspot both the laptop and the head unit can join (untried on this truck), and the laptop's firewall must allow the port | runs until Ctrl-C |

## Tests
```bash
cd tools/probe && PYTHONPATH=src python3 -m unittest discover -s tests -v
```
