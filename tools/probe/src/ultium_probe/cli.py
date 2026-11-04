"""ultium-probe: read-only battery diagnostics for GM Ultium trucks (Silverado EV).

Every command that talks to the truck can only send UDS 0x22
(ReadDataByIdentifier, one DID at a time) and OBD DTC reads (03/07/0A) to
physical diagnostic addresses, plus OBD mode 01/09 broadcasts; see safety.py.
Nothing here can write, reset, clear codes, open a diagnostic session or unlock
anything.
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import addressing
from . import profile as profile_mod
from .transports import TransportError, open_transport
from .transports.base import FatalTransportError
from .uds import (NegativeResponse, NoResponse, UdsClient, mask_vin, obd_query,
                  vehicle_speed_kmh)

BANNER = "read-only: UDS 0x22, OBD 01/09 and DTC reads only"
DTC_MODES = {0x03: "stored", 0x07: "pending", 0x0A: "permanent"}
VIN_DIDS = {0xF190, 0x4193}


def _hex_range(limit: int, what: str):
    def parse(s: str) -> int:
        try:
            v = int(s, 16)          # hex with or without 0x: '27C6', '0x27C6'
        except ValueError:
            raise argparse.ArgumentTypeError(f"{s!r} is not a hex {what}") from None
        if not 0 <= v <= limit:
            raise argparse.ArgumentTypeError(f"{what} {s} is out of range (0-{limit:X})")
        return v
    return parse


_did = _hex_range(0xFFFF, "DID")
_ecu = _hex_range(0xFF, "module address")


def _interval(s: str) -> float:
    v = float(s)
    if v < 0.05:
        raise argparse.ArgumentTypeError("minimum interval is 0.05 s")
    return v


def _log(path: str | None):
    if not path:
        return None
    import os
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    return open(path, "a", buffering=1)


def _functional(t):
    return t.open(addressing.FUNCTIONAL_29, addressing.FUNCTIONAL_RESP_29[0], functional=True)


def _targets(a, prof=None) -> list[tuple[str, int, int]]:
    """Resolve --module or --ecu/--pri into (label, tx, rx) pairs."""
    if getattr(a, "module", None):
        if prof is None:
            raise SystemExit("--module needs --profile")
        if a.module not in prof.modules:
            raise SystemExit(f"unknown module {a.module!r}; profile has {sorted(prof.modules)}")
        m = prof.modules[a.module]
        return [(a.module, m.tx, m.rx)]
    pris = (0x14, 0x18) if a.pri == "both" else (int(a.pri, 16),)
    return [(f"{a.ecu:02X}@{p:02X}", *addressing.ids(a.ecu, p)) for p in pris]


def _mask_payload(did: int, data: bytes, show_vin: bool) -> bytes:
    """Hide VIN-bearing replies (F190, 4193, or anything VIN-shaped) unless asked."""
    if show_vin:
        return data
    text = data.decode("ascii", errors="replace")
    if did in VIN_DIDS or (len(data) == 17 and text.isalnum()):
        return mask_vin(text).encode()
    return data


def _banner(t) -> None:
    sim = "  *** SIMULATOR: not connected to a truck ***" if t.name == "sim" else ""
    print(f"# {t.describe()} ({BANNER}){sim}", flush=True)


def _meta(t, extra: dict | None = None) -> dict:
    return {"meta": {"transport": t.describe(), "simulator": t.name == "sim",
                     "started": round(time.time(), 3), **(extra or {})}}


def cmd_iface(a) -> int:
    from .transports.socketcan import iface_info
    print(json.dumps(iface_info(a.name), indent=2))
    return 0


def cmd_sniff(a) -> int:
    from .transports.socketcan import sniff
    out = open(a.out, "a", buffering=1) if a.out else sys.stdout
    n = sniff(a.iface, a.seconds, out, require_listen_only=not a.allow_active_iface)
    print(f"captured {n} frames", file=sys.stderr)
    return 0


def cmd_obd(a) -> int:
    t = open_transport(a.transport, min_interval_s=a.min_interval)
    _banner(t)
    ch = _functional(t)
    try:
        for base in (0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0):
            answers = obd_query(ch, 0x01, base)
            if not answers:
                break
            for ans in answers:
                bits = int.from_bytes(ans[:4], "big")
                pids = [base + i + 1 for i in range(32) if bits & (1 << (31 - i))]
                print(f"mode 01 supported from 0x{base:02X}: " + " ".join(f"{p:02X}" for p in pids))
        speed = vehicle_speed_kmh(ch)
        print(f"vehicle speed: {speed} km/h" + ("  (no answer; normal for some trucks)" if speed is None else ""))
        for ans in obd_query(ch, 0x09, 0x0A, timeout=1.5):
            print("module:", ans[1:].decode("ascii", errors="replace").strip("\x00 "))
        for ans in obd_query(ch, 0x09, 0x02, timeout=1.5):
            vin = ans[1:].decode("ascii", errors="replace").strip("\x00 ")
            print("VIN:", vin if a.show_vin else mask_vin(vin))
    finally:
        ch.close()
        t.close()
    return 0


def cmd_read(a) -> int:
    prof = profile_mod.load(a.profile) if a.profile else None
    t = open_transport(a.transport, min_interval_s=a.min_interval)
    _banner(t)
    rc = 0
    try:
        for label, tx, rx in _targets(a, prof):
            client = UdsClient(t.open(tx, rx), timeout=a.timeout)
            ecu = (tx >> 8) & 0xFF
            for did in a.did:
                rec = {"module": label, "tx": f"{tx:X}", "did": f"0x{did:04X}"}
                try:
                    r = client.read_did(did)
                    data = _mask_payload(did, r.data, a.show_vin)
                    rec.update({"len": len(r.data), "hex": data.hex(" "), "ms": round(r.elapsed_ms)})
                    if prof:
                        for f in prof.fields:
                            if f.did == did and (prof.modules[f.module].tx >> 8) & 0xFF == ecu:
                                rec[f.name] = f.decode(data)
                except (NegativeResponse, NoResponse) as e:
                    rec["error"] = str(e)
                    rc = 1
                print(json.dumps(rec))
    finally:
        t.close()
    return rc


def _read_dtcs(t, tx: int, rx: int) -> dict[str, list[str] | str]:
    ch = t.open(tx, rx)
    client = UdsClient(ch)
    out: dict[str, list[str] | str] = {}
    try:
        for mode, name in DTC_MODES.items():
            try:
                out[name] = client.read_dtcs(mode)
            except (NegativeResponse, NoResponse) as e:
                out[name] = f"error: {e}"
    finally:
        ch.close()
    return out


def _dtc_ok(result: dict) -> bool:
    return all(isinstance(v, list) for v in result.values())


def cmd_dtc(a) -> int:
    t = open_transport(a.transport, min_interval_s=a.min_interval)
    _banner(t)
    rc = 0
    try:
        for label, tx, rx in _targets(a):
            result = _read_dtcs(t, tx, rx)
            print(json.dumps({"module": label, **result}))
            if not _dtc_ok(result):
                print(f"DTC READ FAILED for {label}: this result can't be used as a baseline",
                      file=sys.stderr)
                rc = 1
    finally:
        t.close()
    return rc


def derive(values: dict) -> dict:
    """Values computed from several DIDs (all estimates; see docs)."""
    v = {k: x["v"] for k, x in values.items()}
    d: dict = {}
    if isinstance(v.get("pack_voltage"), float) and isinstance(v.get("pack_current"), float):
        d["power_kw"] = round(v["pack_voltage"] * v["pack_current"] / 1000, 2)
    # BT1 packs report two 400 V halves: 2AF6 = modules 1-12 (_a), 2AF5 = modules 13-24 (_b).
    mins = [v[k] for k in ("cell_min_a", "cell_min_b", "cell_min") if v.get(k)]
    maxs = [v[k] for k in ("cell_max_a", "cell_max_b", "cell_max") if v.get(k)]
    avgs = [v[k] for k in ("cell_avg_a", "cell_avg_b", "cell_avg") if v.get(k)]
    if mins and maxs:
        d["cell_min_v"], d["cell_max_v"] = min(mins), max(maxs)
        d["cell_spread_mv"] = round((max(maxs) - min(mins)) * 1000, 1)
    if v.get("pack_voltage") and avgs:
        d["series_groups"] = round(v["pack_voltage"] / (sum(avgs) / len(avgs)), 1)
    if isinstance(v.get("module_soc_raw"), list):
        d["modules"] = sum(1 for x in v["module_soc_raw"] if x)
    if v.get("energy_remaining") and v.get("soc"):
        d["bms_energy_at_100_kwh"] = round(v["energy_remaining"] / (v["soc"] / 100), 1)
    cells = [c for k in sorted(v) if k.startswith("cells_") and isinstance(v[k], list)
             for c in v[k]]
    real = [(i + 1, c) for i, c in enumerate(cells) if c > 0.5]  # 0 = padding on smaller packs
    if real:
        lo = min(real, key=lambda x: x[1])
        hi = max(real, key=lambda x: x[1])
        d.update({"cell_groups_read": len(real), "array_min_v": lo[1], "array_min_group": lo[0],
                  "array_max_v": hi[1], "array_max_group": hi[0]})
    return d


class Poller:
    """Tiered polling that goes completely quiet on the bus when the truck sleeps.

    awake    each DID is read at its own period. When a read gets no answer at
             all, one read of a known-good DID (probe) tells whether just that
             DID is unsupported (its period is stretched) or the truck went
             quiet. From then on only the probe is sent, at most one every
             PROBE_GAP_S, and after SILENT_PROBES unanswered probes the truck is
             considered asleep. So at most ~4 requests go unanswered while the
             truck falls asleep.
    asleep   nothing is sent on the bus. Every WATCH_S the adapter's own voltage
             reading is checked (adapter-local). A single probe is sent only
             when that voltage rises WAKE_RISE_V above its lowest value since
             sleep began or since the last probe (the truck's DC-DC came on).
             An adapter that can't read voltage gets one probe at 1, 5 and
             30 minutes, then polling stops.
    stopped  nothing more will be sent.
    Transport errors (bus errors, adapter resets, timeouts) are not treated as
    silence: they propagate and end the run.
    """

    SILENT_PROBES = 3
    PROBE_GAP_S = 2.0
    WATCH_S = 60.0
    WAKE_RISE_V = 0.5
    NO_VOLTAGE_PROBES_S = (60.0, 300.0, 1800.0)
    VOLTAGE_FAILURES_LIMIT = 5
    VOLTS_EVERY_S = 30.0

    def __init__(self, prof, t, *, stop_when_asleep: bool = True, clock=time.monotonic) -> None:
        self.prof = prof
        self.t = t
        self.clock = clock
        self.stop_when_asleep = stop_when_asleep
        self.clients = {key: UdsClient(t.open(m.tx, m.rx)) for key, m in prof.modules.items()}
        self.reads = prof.reads()
        self.period = {k: max(0.5, min(f.period for f in fs)) for k, fs in self.reads.items()}
        self.due = {k: 0.0 for k in self.reads}
        self.probe_key = ("becm", 0x27C6) if ("becm", 0x27C6) in self.reads else next(iter(self.reads))
        self.latest: dict = {}
        self.state = "awake"
        self.unanswered = 0          # consecutive unanswered probes while awake
        self.next_probe = 0.0
        self.has_voltage: bool | None = None
        self.baseline_v: float | None = None
        self.voltage_failures = 0
        self.next_watch = 0.0
        self.failed_probes = 0
        self.next_volts = 0.0

    def _read(self, key, sample) -> bool:
        """One read; True if the module answered at all (even with an NRC)."""
        mod, did = key
        try:
            r = self.clients[mod].read_did(did)
        except NoResponse as e:
            sample["errors"][f"{mod}:0x{did:04X}"] = str(e)
            return False
        except NegativeResponse as e:
            sample["errors"][f"{mod}:0x{did:04X}"] = str(e)
            return True
        data = _mask_payload(did, r.data, False)
        for f in self.reads[key]:
            sample["values"][f.name] = {"v": f.decode(data), "unit": f.unit, "status": f.status}
        return True

    def _fall_asleep(self, now: float, sample: dict) -> None:
        v = self.t.adapter_voltage()
        self.has_voltage = v is not None
        self.baseline_v = v
        sample["adapter_volts"] = v
        self.state = "stopped" if self.stop_when_asleep else "asleep"
        self.next_watch = now + self._watch_interval()

    def _wake(self, now: float) -> None:
        self.state, self.unanswered, self.failed_probes = "awake", 0, 0
        # Bring DIDs back spread out rather than all at once.
        self.due = {k: now + 0.2 * i for i, k in enumerate(self.reads)}

    def cycle(self) -> dict:
        now = self.clock()
        sample: dict = {"t": round(time.time(), 3), "values": {}, "errors": {}}
        if self.state == "awake":
            self._cycle_awake(now, sample)
            if self.state == "awake" and now >= self.next_volts:
                self.next_volts = now + self.VOLTS_EVERY_S
                sample["adapter_volts"] = self.t.adapter_voltage()  # adapter-local, no bus traffic
        elif self.state == "asleep" and now >= self.next_watch:
            self._cycle_asleep(now, sample)
        self.latest.update(sample["values"])
        sample["derived"] = derive(self.latest)
        sample["state"] = self.state
        sample["asleep"] = self.state != "awake"
        sample["bus_silent"] = self.state != "awake" and self.has_voltage is not False
        return sample

    def _cycle_awake(self, now: float, sample: dict) -> None:
        if self.unanswered:
            # The truck may be going to sleep: only the probe, spaced out.
            if now < self.next_probe:
                return
            if self._read(self.probe_key, sample):
                self.unanswered = 0
                self._wake(now)
                return
            self.unanswered += 1
            self.next_probe = now + self.PROBE_GAP_S
            if self.unanswered >= self.SILENT_PROBES:
                self._fall_asleep(now, sample)
            return
        for key in [k for k, due in self.due.items() if due <= now]:
            self.due[key] = now + self.period[key]
            if self._read(key, sample):
                continue
            if key != self.probe_key and self._read(self.probe_key, sample):
                # Only this DID is silent: ask for it less often.
                self.period[key] = min(self.period[key] * 4, 300.0)
                continue
            self.unanswered = 1
            self.next_probe = now + self.PROBE_GAP_S
            return

    def _cycle_asleep(self, now: float, sample: dict) -> None:
        v = self.t.adapter_voltage()
        sample["adapter_volts"] = v
        self.next_watch = now + self._watch_interval()
        if self.has_voltage:
            if v is None:
                self.voltage_failures += 1
                if self.voltage_failures >= self.VOLTAGE_FAILURES_LIMIT:
                    self.state = "stopped"
                return
            self.voltage_failures = 0
            self.baseline_v = v if self.baseline_v is None else min(self.baseline_v, v)
            if v < self.baseline_v + self.WAKE_RISE_V:
                return
        if self._read(self.probe_key, sample):
            self._wake(now)
            return
        self.failed_probes += 1
        if self.has_voltage:
            self.baseline_v = v  # need a fresh rise before the next probe
        elif self.failed_probes >= len(self.NO_VOLTAGE_PROBES_S):
            self.state = "stopped"
        self.next_watch = now + self._watch_interval()

    def _watch_interval(self) -> float:
        if self.has_voltage is False:  # no voltage reading: growing gaps between single probes
            return self.NO_VOLTAGE_PROBES_S[min(self.failed_probes, len(self.NO_VOLTAGE_PROBES_S) - 1)]
        return self.WATCH_S

    def next_wait(self) -> float | None:
        now = self.clock()
        if self.state == "stopped":
            return None
        if self.state == "asleep":
            return max(0.05, self.next_watch - now)
        if self.unanswered:
            return max(0.05, self.next_probe - now)
        return max(0.05, min(self.due.values()) - now)


def _print_sample(sample: dict) -> None:
    for name, v in sample["values"].items():
        val = v["v"]
        if isinstance(val, list) and val and all(isinstance(x, (int, float)) for x in val):
            val = f"[{len(val)}] min={min(val)} max={max(val)}"
        print(f"{name:28} {val} {v['unit']}  ({v['status']})")
    for k, e in sample["errors"].items():
        print(f"{k:28} ERROR {e}")
    if sample["derived"]:
        print("derived:", json.dumps(sample["derived"]))
    if sample["state"] != "awake":
        quiet = ("nothing more is sent on the bus" if sample.get("bus_silent")
                 else "only single probes at 1, 5 and 30 min (this adapter can't read voltage)")
        print(f"truck appears asleep: {quiet} "
              f"(adapter sees {sample.get('adapter_volts')} V; state {sample['state']})")
    print("-" * 60)


def cmd_poll(a) -> int:
    prof = profile_mod.load(a.profile)
    t = open_transport(a.transport, min_interval_s=a.min_interval)
    _banner(t)
    poller = Poller(prof, t, stop_when_asleep=not a.keep_watching)
    log = _log(a.out)
    if log:
        log.write(json.dumps(_meta(t, {"profile": prof.name})) + "\n")
    n = 0
    try:
        while a.count is None or n < a.count:
            sample = poller.cycle()
            if sample["values"] or sample["errors"] or sample["state"] != "awake":
                if log:
                    log.write(json.dumps(sample) + "\n")
                if not a.quiet:
                    _print_sample(sample)
                n += 1
            wait = poller.next_wait()
            if wait is None:
                print("polling stopped (truck asleep)", file=sys.stderr)
                break
            time.sleep(wait)
    except KeyboardInterrupt:
        pass
    finally:
        t.close()
    return 0


def cmd_scan(a) -> int:
    if not a.parked:
        print("scan sends many requests; run it parked and attended with --parked", file=sys.stderr)
        return 2
    t = open_transport(a.transport, min_interval_s=a.min_interval)
    _banner(t)
    try:
        if not a.skip_speed_check:
            speed = vehicle_speed_kmh(_functional(t))  # adapter errors abort the scan below
            if speed is None:
                print("no OBD speed answer; confirm the truck is in Park and rerun with "
                      "--skip-speed-check", file=sys.stderr)
                return 2
            if speed > 0:
                print(f"vehicle speed is {speed} km/h; scanning only while parked", file=sys.stderr)
                return 2
        gw = addressing.ids(0x45, 0x18)
        before = None if a.skip_dtc_bracket else _read_dtcs(t, *gw)
        if before is not None:
            print("DTCs before:", json.dumps(before), file=sys.stderr)
            if not _dtc_ok(before):
                print("DTC baseline failed; not scanning (rerun with --skip-dtc-bracket to override)",
                      file=sys.stderr)
                return 4
        rc = _scan_range(a, t)
        if before is not None:
            after = _read_dtcs(t, *gw)
            print("DTCs after: ", json.dumps(after), file=sys.stderr)
            if not _dtc_ok(after):
                print("DTC bracket incomplete: run `dtc` again before unplugging", file=sys.stderr)
                return 4
            new = {k: sorted(set(after[k]) - set(before[k])) for k in after}
            if any(new.values()):
                print(f"WARNING: new DTCs appeared during the scan: {new}", file=sys.stderr)
                return 3
        return rc
    except TransportError as e:
        print(f"scan aborted ({e}). Run `dtc` before unplugging.", file=sys.stderr)
        return 5
    finally:
        t.close()


def _scan_range(a, t) -> int:
    (label, tx, rx), = _targets(a)[:1]
    ch = t.open(tx, rx)
    client = UdsClient(ch, timeout=a.timeout)
    log = _log(a.out)
    found = misses = busy = 0
    try:
        for did in range(a.start, a.end + 1):
            if a.delay:
                time.sleep(a.delay)
            try:
                r = client.read_did(did)
            except NegativeResponse as e:
                if e.nrc == 0x21:
                    busy += 1
                    if busy >= 5:
                        print("module keeps answering 'busy'; stopping", file=sys.stderr)
                        break
                elif e.nrc not in (0x31, 0x11, 0x12):
                    rec = {"module": label, "did": f"0x{did:04X}", "nrc": f"0x{e.nrc:02X}"}
                    print(json.dumps(rec))
                    if log:
                        log.write(json.dumps(rec) + "\n")
                misses = 0
                continue
            except NoResponse:
                misses += 1
                if misses >= a.max_silent:
                    print(f"{misses} DIDs in a row without any answer; stopping", file=sys.stderr)
                    break
                continue
            misses = busy = 0
            found += 1
            data = _mask_payload(did, r.data, False)
            rec = {"module": label, "did": f"0x{did:04X}", "len": len(r.data), "hex": data.hex(" ")}
            print(json.dumps(rec))
            if log:
                log.write(json.dumps(rec) + "\n")
    except KeyboardInterrupt:
        pass
    finally:
        ch.close()
    print(f"{found} DIDs answered", file=sys.stderr)
    return 0


def cmd_export(a) -> int:
    """Write the profile as JSON for the head-unit app (same decoding rules)."""
    prof = profile_mod.load(a.profile)
    doc = {"name": prof.name,
           "modules": {k: {"tx": m.tx, "rx": m.rx, "description": m.description}
                       for k, m in prof.modules.items()},
           "fields": [vars(f) for f in prof.fields]}
    text = json.dumps(doc, indent=1)
    if a.out:
        with open(a.out, "w") as fh:
            fh.write(text + "\n")
    else:
        print(text)
    return 0


def cmd_serve(a) -> int:
    """Poll a profile and publish the latest sample as JSON / server-sent events."""
    prof = profile_mod.load(a.profile)
    t = open_transport(a.transport, min_interval_s=a.min_interval)
    poller = Poller(prof, t, stop_when_asleep=False)
    latest: dict = {}
    cond = threading.Condition()
    log = _log(a.out)
    started = time.monotonic()

    def publish(snapshot: dict) -> None:
        nonlocal latest
        with cond:
            latest = snapshot
            cond.notify_all()

    def loop():
        last_good = None
        try:
            while True:
                if a.max_runtime and time.monotonic() - started > a.max_runtime:
                    publish({**latest, "stale": True, "stopped": True, "error": "max runtime reached"})
                    print("serve: max runtime reached; polling stopped", file=sys.stderr)
                    return
                sample = poller.cycle()
                if sample["values"]:
                    last_good = sample["t"]
                if log and (sample["values"] or sample["errors"] or sample["state"] != "awake"):
                    log.write(json.dumps(sample) + "\n")
                publish({"t": sample["t"], "last_good": last_good, "values": dict(poller.latest),
                         "derived": sample["derived"], "state": sample["state"],
                         "asleep": sample["asleep"], "stale": sample["state"] != "awake",
                         "stopped": False})
                wait = poller.next_wait()
                if wait is None:
                    publish({**latest, "stale": True, "stopped": True})
                    print("serve: truck asleep and polling stopped", file=sys.stderr)
                    return
                time.sleep(wait)
        except Exception as e:  # noqa: BLE001 - publish any failure, stop polling
            publish({**latest, "stale": True, "stopped": True, "error": str(e)})
            print(f"stopped: {e}", file=sys.stderr)
        finally:
            t.close()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def _headers(self, ctype: str) -> None:
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()

        def do_GET(self):
            if self.path == "/latest.json":
                self._headers("application/json")
                with cond:
                    self.wfile.write(json.dumps(latest).encode())
            elif self.path == "/events":
                self._headers("text/event-stream")
                try:
                    while True:
                        with cond:
                            cond.wait(timeout=5)
                            body = json.dumps(latest)
                        self.wfile.write(f"data: {body}\n\n".encode())
                        self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    return
            else:
                self.send_response(404)
                self.end_headers()

    try:
        server = ThreadingHTTPServer((a.host, a.port), Handler)   # bind before touching the bus
    except OSError:
        t.close()
        raise
    _banner(t)
    if log:
        log.write(json.dumps(_meta(t, {"profile": prof.name})) + "\n")
    threading.Thread(target=loop, daemon=True).start()
    print(f"serving http://{a.host}:{a.port}/latest.json and /events", file=sys.stderr)
    server.serve_forever()
    return 0


def cmd_receive(a) -> int:
    """Accept diagnostics reports POSTed by the head-unit app (no truck traffic)."""
    import os
    os.makedirs(a.dir, exist_ok=True)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            if self.path.rstrip("/") != "/report":
                self.send_response(404)
                self.end_headers()
                return
            n = int(self.headers.get("Content-Length") or 0)
            if not 0 < n <= 20_000_000:
                self.send_response(413)
                self.end_headers()
                return
            body = self.rfile.read(n)
            name = os.path.join(a.dir, f"report-{time.strftime('%Y%m%d-%H%M%S')}.json")
            with open(name, "wb") as fh:
                fh.write(body)
            print(f"saved {len(body)} bytes from {self.client_address[0]} -> {name}", flush=True)
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"saved\n")

    server = ThreadingHTTPServer((a.host, a.port), Handler)
    print(f"waiting for reports on http://{a.host}:{a.port}/report (Ctrl-C to stop); "
          f"in the app use http://<this laptop's IP>:{a.port}/report", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="ultium-probe", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    def transport_args(sp):
        sp.add_argument("-t", "--transport", default="sim",
                        help="elm-bt:auto (the paired OBDLink MX+) | elm-bt:MAC[:channel] | "
                             "elm:/dev/ttyUSB0[@baud] | elm-tcp:HOST[:PORT] | socketcan:can0 | "
                             "sim (default)")
        sp.add_argument("--min-interval", type=_interval, default=0.075,
                        help="minimum seconds between bus requests (default 0.075, floor 0.05)")

    def target_args(sp, default_ecu, default_pri="14"):
        sp.add_argument("--ecu", type=_ecu, default=default_ecu,
                        help="module address in hex: CB (battery), 17/1D (drive units), 40 (body), 45 (gateway)")
        sp.add_argument("--pri", default=default_pri, choices=["14", "18", "both"],
                        help="CAN priority (CB/17/1D answer at 14; 40/45 only at 18)")

    sp = sub.add_parser("iface", help="show a SocketCAN interface's configuration")
    sp.add_argument("name")
    sp.set_defaults(fn=cmd_iface)

    sp = sub.add_parser("sniff", help="passively log all frames (never transmits)")
    sp.add_argument("--iface", default="can0")
    sp.add_argument("--seconds", type=float)
    sp.add_argument("--out")
    sp.add_argument("--allow-active-iface", action="store_true",
                    help="allow sniffing on an interface that is not in listen-only mode (500k only)")
    sp.set_defaults(fn=cmd_sniff)

    sp = sub.add_parser("obd", help="standard OBD-II census: supported PIDs, speed, module names, VIN")
    transport_args(sp)
    sp.add_argument("--show-vin", action="store_true", help="print the full VIN (masked by default)")
    sp.set_defaults(fn=cmd_obd)

    sp = sub.add_parser("read", help="read one or more DIDs from one module")
    transport_args(sp)
    target_args(sp, default_ecu=0xCB)
    sp.add_argument("--profile", help="decode with this profile")
    sp.add_argument("--module", help="module key from the profile (e.g. becm); needs --profile")
    sp.add_argument("--did", type=_did, nargs="+", required=True, help="DIDs in hex, e.g. 27C6 or 0x27C6")
    sp.add_argument("--timeout", type=float, default=1.0,
                    help="seconds to wait for an answer (socketcan; ELM adapters use their own timing)")
    sp.add_argument("--show-vin", action="store_true", help="don't mask VIN-bearing replies")
    sp.set_defaults(fn=cmd_read)

    sp = sub.add_parser("dtc", help="read stored/pending/permanent DTCs (default: gateway 45)")
    transport_args(sp)
    target_args(sp, default_ecu=0x45, default_pri="18")
    sp.set_defaults(fn=cmd_dtc)

    sp = sub.add_parser("poll", help="poll every DID in a profile (tiered); stops when the truck sleeps")
    transport_args(sp)
    sp.add_argument("--profile", required=True)
    sp.add_argument("--count", type=int, help="stop after this many samples")
    sp.add_argument("--out", help="append JSON lines here")
    sp.add_argument("--quiet", action="store_true")
    sp.add_argument("--keep-watching", action="store_true",
                    help="when the truck sleeps, keep watching the adapter's voltage (no bus traffic) "
                         "and resume when it wakes, instead of exiting")
    sp.set_defaults(fn=cmd_poll)

    sp = sub.add_parser("scan", help="discover which DIDs a module answers (parked, attended)")
    transport_args(sp)
    target_args(sp, default_ecu=0xCB)
    sp.add_argument("--start", type=_did, required=True, help="first DID (hex)")
    sp.add_argument("--end", type=_did, required=True, help="last DID (hex)")
    sp.add_argument("--timeout", type=float, default=0.5)
    sp.add_argument("--delay", type=float, default=0.075, help="extra pause between DIDs")
    sp.add_argument("--max-silent", type=int, default=20,
                    help="stop after this many DIDs in a row with no answer at all")
    sp.add_argument("--out")
    sp.add_argument("--parked", action="store_true", help="confirm the truck is in Park and attended")
    sp.add_argument("--skip-speed-check", action="store_true")
    sp.add_argument("--skip-dtc-bracket", action="store_true")
    sp.set_defaults(fn=cmd_scan)

    sp = sub.add_parser("export-profile", help="write a DID profile as JSON for the head-unit app")
    sp.add_argument("--profile", required=True)
    sp.add_argument("--out")
    sp.set_defaults(fn=cmd_export)

    sp = sub.add_parser("serve", help="poll a profile and serve JSON/SSE for dashboards")
    transport_args(sp)
    sp.add_argument("--profile", required=True)
    sp.add_argument("--host", default="127.0.0.1")
    sp.add_argument("--port", type=int, default=8765)
    sp.add_argument("--max-runtime", type=float, help="stop polling after this many seconds")
    sp.add_argument("--out")
    sp.set_defaults(fn=cmd_serve)

    sp = sub.add_parser("receive", help="save diagnostics reports sent by the head-unit app (no truck traffic)")
    sp.add_argument("--host", default="0.0.0.0")
    sp.add_argument("--port", type=int, default=8766)
    sp.add_argument("--dir", default="logs/reports")
    sp.set_defaults(fn=cmd_receive)

    a = p.parse_args(argv)
    if a.cmd == "scan" and a.end < a.start:
        p.error("--end must be >= --start")
    if getattr(a, "pri", None) == "both" and a.cmd in ("scan", "dtc"):
        p.error("--pri both is only for 'read'")
    try:
        return a.fn(a)
    except FatalTransportError as e:
        print(f"stopped: {e}", file=sys.stderr)
        return 1
    except (TransportError, OSError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
