import io
import json
import os
import re
import socket
import threading
import unittest
from contextlib import redirect_stderr, redirect_stdout

from ultium_probe import addressing, cli, profile, safety
from ultium_probe.transports.base import Channel
from ultium_probe.transports.elm import ALLOWED_AT, ElmLink, ElmTransport, reassemble
from ultium_probe.transports.sim import SimTransport
from ultium_probe.uds import NegativeResponse, NoResponse, UdsClient, mask_vin

HERE = os.path.dirname(__file__)
PROFILE = os.path.join(HERE, "..", "dids", "silverado_ev.toml")


class RecordingChannel(Channel):
    def __init__(self, functional=False):
        ids = (0x18DB33F1, 0x18DAF100) if functional else (0x14DACBF1, 0x142AF1CB)
        super().__init__(*ids, functional=functional, limiter=safety.RateLimiter(0.05))
        self.sent = []

    def _transmit(self, payload):
        self.sent.append(payload)

    def _receive(self, timeout):
        return []


class SafetyTests(unittest.TestCase):
    def test_only_reads_pass_physical(self):
        ch = RecordingChannel()
        ch.request(bytes([0x22, 0x27, 0xC6]), 0.01)
        for mode in (0x03, 0x07, 0x0A):
            ch.request(bytes([mode]), 0.01)
        for sid in range(0x100):
            if sid == 0x22:
                continue
            with self.assertRaises(safety.UnsafeRequestError):
                ch.request(bytes([sid, 0x01, 0x02]), 0.01)
        self.assertEqual(len(ch.sent), 4)

    def test_dangerous_requests_rejected(self):
        ch = RecordingChannel()
        for payload in ([0x10, 0x03], [0x11, 0x01], [0x14, 0xFF, 0xFF, 0xFF], [0x27, 0x01],
                        [0x28, 0x03, 0x01], [0x2E, 0x40, 0x28, 0x00], [0x31, 0x01, 0x02, 0x03],
                        [0x3E, 0x00], [0x85, 0x02], [0x04], [0x08], [0x14], [0x19, 0x02, 0xFF],
                        [0x23, 0x00, 0x00], [0x2C, 0x01], [0x22], [0x22, 0x40],
                        [0x22, 0x40, 0x28, 0x40, 0x29], [0x03, 0x00], []):
            with self.assertRaises(safety.UnsafeRequestError, msg=bytes(payload).hex()):
                ch.request(bytes(payload), 0.01)
        self.assertEqual(ch.sent, [])

    def test_functional_only_modes_01_09(self):
        ch = RecordingChannel(functional=True)
        ch.request(bytes([0x01, 0x0D]), 0.01)
        ch.request(bytes([0x09, 0x02]), 0.01)
        for payload in ([0x04], [0x03], [0x07], [0x08, 0x01], [0x0A], [0x22, 0x40, 0x28],
                        [0x01, 0x0D, 0x0C], [0x10, 0x01], [0x03, 0x00]):
            with self.assertRaises(safety.UnsafeRequestError):
                ch.request(bytes(payload), 0.01)
        self.assertEqual(len(ch.sent), 2)

    def test_rate_limiter_floor(self):
        for bad in (0.001, 0.01, 0.049):
            with self.assertRaises(ValueError):
                safety.RateLimiter(bad)

    def test_only_diagnostic_addresses(self):
        safety.check_address(0x14DACBF1, 0x142AF1CB, False)
        safety.check_address(0x18DA40F1, 0x18DAF140, False)
        safety.check_address(0x18DB33F1, 0x18DAF100, True)
        for tx, rx, fn in ((0x18DB33F1, 0x18DAF1CB, False),   # broadcast as physical
                           (0x7E4, 0x7EC, False), (0x7DF, 0x7E8, True),  # 11-bit
                           (0x0CF00400, 0x18DAF1CB, False),   # J1939-style ID
                           (0x14DACBF1, 0x18DAF1CB, False),   # mismatched pair
                           (0x10DACBF1, 0x102AF1CB, False),   # unknown priority
                           (0x14DACBF2, 0x142AF1CB, False),   # not the tester address
                           (0x18DA33F1, 0x18DAF133, False),
                           (0x14DACBF1, 0x142AF1CB, True),    # physical as functional
                           (0x18DAF1F1, 0x18DAF1F1, False),   # the tester's own address
                           (0x1_14DACBF1, 0x142AF1CB, False)):
            with self.assertRaises(safety.UnsafeRequestError, msg=f"{tx:#x}/{rx:#x}"):
                safety.check_address(tx, rx, fn)

    def test_channel_rejects_bad_address(self):
        with self.assertRaises(safety.UnsafeRequestError):
            SimTransport().open(0x18DB33F1, 0x18DAF1CB)
        with self.assertRaises(safety.UnsafeRequestError):
            SimTransport().open(0x7E4, 0x7EC)

    def test_elm_allowlist_excludes_bus_writers(self):
        for cmd in ("ATMA", "ATBD", "ATWM", "ATSW", "ATD", "STPX", "STM", "ATPP", "ATBRD",
                    "ATSD", "3E00", "1003", "ATSP0", "ATSPA", "ATSP6", "ATAR", "ATWS", "ATCSM0"):
            self.assertFalse(cmd.startswith(ALLOWED_AT), cmd)

    def test_elm_refuses_bare_cr(self):
        link = ElmLink(sock=None)
        with self.assertRaises(Exception):
            link.command("", 0.1)


class AddressingTests(unittest.TestCase):
    def test_ultium_ids(self):
        self.assertEqual(addressing.ids(0xCB, 0x14), (0x14DACBF1, 0x142AF1CB))
        self.assertEqual(addressing.ids(0x40, 0x18), (0x18DA40F1, 0x18DAF140))
        self.assertEqual(addressing.flow_control_target(0x142AF1CB), 0x14DACBF1)
        self.assertEqual(addressing.flow_control_target(0x18DAF117), 0x18DA17F1)
        self.assertEqual(addressing.flow_control_target(0x7EC), 0x7E4)
        with self.assertRaises(ValueError):
            addressing.ids(0xCB, 0x10)


class ElmReassemblyTests(unittest.TestCase):
    def test_11bit_single_and_multi_frame(self):
        lines = ["7EC0562415B0A1B", "7EC101462400D010203", "7EC2104050607080910",
                 "7EC220A0B0C0D0E0F10", "NO DATA"]
        out = reassemble(lines, 3)
        self.assertEqual(out[0], (0x7EC, bytes.fromhex("62415B0A1B")))
        self.assertEqual(len(out[1][1]), 0x14)

    def test_29bit_ultium_frames(self):
        # Single frame as quoted in WiCAN #606 (44.19 % SoC) and a 2AF5 multi-frame.
        lines = ["142AF1CB056227C67123",
                 "142AF1CB100D622AF59D759D62", "142AF1CB219D91B5179113AAAA"]
        out = reassemble(lines, 8)
        self.assertEqual(out[0], (0x142AF1CB, bytes.fromhex("6227C67123")))
        self.assertEqual(out[1][1], bytes.fromhex("622AF59D759D629D91B5179113"))
        soc = profile.Field("becm", 0x27C6, "soc", "soc", type="u16",
                            scale=0.0015259021896696422).decode(out[0][1][3:])
        self.assertAlmostEqual(soc, 44.19, places=2)


class ProfileTests(unittest.TestCase):
    def test_silverado_profile_against_sim(self):
        prof = profile.load(PROFILE)
        t = SimTransport()
        clients = {k: UdsClient(t.open(m.tx, m.rx)) for k, m in prof.modules.items()}
        values = {}
        for (mod, did), fields in prof.reads().items():
            try:
                r = clients[mod].read_did(did)
            except NegativeResponse:
                continue  # e.g. the unverified fallback DIDs
            for f in fields:
                values[f.name] = {"v": f.decode(r.data), "unit": f.unit}
        d = cli.derive(values)
        self.assertEqual(d["modules"], 24)
        self.assertEqual(d["cell_groups_read"], 192)
        self.assertAlmostEqual(d["series_groups"], 96.0, places=0)
        self.assertEqual(values["cell_group_modules_2ae1"]["v"], [1] * 8 + [2] * 4)
        cells = [c for k, v in values.items() if k.startswith("cells_") for c in v["v"]]
        self.assertAlmostEqual(min(cells), d["cell_min_v"], delta=0.001)  # sim drifts
        self.assertEqual(cells.index(min(cells)) + 1, values["cell_min_index_a"]["v"])
        self.assertGreater(values["cell_min_index_b"]["v"], 96)
        self.assertEqual(d["array_min_group"], values["cell_min_index_a"]["v"])

    def test_stride_and_signed_decode(self):
        f = profile.Field("m", 1, "x", "x", type="u16", stride=3, count=0, scale=0.0001)
        self.assertEqual(f.decode(bytes.fromhex("9D7501 9D6202 9D9103")), [4.0309, 4.029, 4.0337])
        f = profile.Field("m", 1, "x", "x", type="s16", scale=0.05)
        self.assertAlmostEqual(f.decode(bytes.fromhex("FF38")), -10.0)

    def test_wrong_priority_is_silent(self):
        t = SimTransport()
        with self.assertRaises(NoResponse):
            UdsClient(t.open(*addressing.ids(0x40, 0x14)), timeout=0.05).read_did(0x434F)
        UdsClient(t.open(*addressing.ids(0x40, 0x18))).read_did(0x434F)

    def test_negative_response(self):
        client = UdsClient(SimTransport().open(*addressing.ids(0xCB, 0x14)))
        with self.assertRaises(NegativeResponse) as cm:
            client.read_did(0xBEEF)
        self.assertEqual(cm.exception.nrc, 0x31)

    def test_derive_ignores_padding(self):
        vals = {"cells_001_012": {"v": [3.9] * 12}, "cells_013_024": {"v": [3.8] + [0.0] * 11}}
        d = cli.derive(vals)
        self.assertEqual(d["cell_groups_read"], 13)
        self.assertEqual((d["array_min_group"], d["array_min_v"]), (13, 3.8))

    def test_profile_rejects_fast_period(self):
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".toml", delete=False) as fh:
            fh.write('[modules.b]\necu = 0xCB\n[[did]]\nmodule = "b"\nid = 1\nname = "x"\nperiod = 0\n')
        with self.assertRaises(ValueError):
            profile.load(fh.name)
        os.unlink(fh.name)

    def test_vin_mask(self):
        self.assertEqual(mask_vin("1GCSIMULATED00000"), "1GCSIMULAT*******")


class CliTests(unittest.TestCase):
    def run_cli(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            rc = cli.main(list(argv))
        self.assertIn("SIMULATOR", out.getvalue().splitlines()[0] if out.getvalue() else "SIMULATOR")
        body = "\n".join(ln for ln in out.getvalue().splitlines() if not ln.startswith("#"))
        return rc, body, err.getvalue()

    def test_scan_requires_parked(self):
        rc, _, _ = self.run_cli("scan", "--start", "0x2AE0", "--end", "0x2AE2")
        self.assertEqual(rc, 2)

    def test_scan_with_dtc_bracket(self):
        rc, out, err = self.run_cli("scan", "--parked", "--start", "0x2AE0", "--end", "0x2AE3",
                                    "--delay", "0")
        self.assertEqual(rc, 0)
        self.assertEqual(len(out.strip().splitlines()), 3)
        self.assertIn("DTCs after", err)

    def test_obd_masks_vin(self):
        rc, out, _ = self.run_cli("obd")
        self.assertIn("1GCSIMULAT*******", out)
        self.assertIn("BECM-B+EnergyCtrl", out)

    def test_read_masks_vin_did_and_decodes_by_ecu(self):
        rc, out, _ = self.run_cli("read", "--ecu", "17", "--pri", "both", "--did", "F190", "2885",
                                  "--profile", PROFILE)
        self.assertNotIn("SIMULATED00000", out.replace(" ", ""))
        recs = [json.loads(x) for x in out.splitlines()]
        self.assertTrue(all("pack_voltage" in r for r in recs if r["did"] == "0x2885"))

    def test_dtc_ok_and_failure_exit_codes(self):
        rc, out, _ = self.run_cli("dtc")
        self.assertEqual(rc, 0)
        rc, _, err = self.run_cli("dtc", "--ecu", "CB", "--pri", "14")
        self.assertEqual(rc, 1)
        self.assertIn("DTC READ FAILED", err)

    def test_hex_did_parsing(self):
        rc, out, _ = self.run_cli("read", "--did", "27C6")
        self.assertEqual(json.loads(out.splitlines()[0])["did"], "0x27C6")


class SleepyTransport(SimTransport):
    """Sim whose modules can be switched silent, with a settable adapter voltage."""

    def __init__(self):
        super().__init__(min_interval_s=0.05)
        self.limiter.wait = lambda: None   # simulated clock; don't really sleep in tests
        self.silent = False
        self.volts = 12.6
        self.sent = 0
        outer = self

        class Ch(type(self.open(*addressing.ids(0xCB, 0x14)))):
            def _transmit(self, payload):
                outer.sent += 1
                if outer.silent:
                    self._out = []
                else:
                    super()._transmit(payload)
        self.Ch = Ch

    def open(self, tx_id, rx_id, *, functional=False):
        if not hasattr(self, "Ch"):
            return super().open(tx_id, rx_id, functional=functional)
        return self.Ch(self.sim, tx_id, rx_id, functional=functional, limiter=self.limiter)

    def adapter_voltage(self):
        return self.volts


class PollerSleepTests(unittest.TestCase):
    def make(self, volts=12.6, **kw):
        now = [1000.0]
        t = SleepyTransport()
        t.volts = volts
        poller = cli.Poller(profile.load(PROFILE), t, clock=lambda: now[0], **kw)
        return now, t, poller

    def run_until(self, now, poller, state, step=1.0, limit=10000):
        for _ in range(limit):
            if poller.state == state:
                return
            now[0] += step
            poller.cycle()
        self.fail(f"never reached {state}")

    def test_falling_asleep_costs_few_unanswered_requests(self):
        now, t, poller = self.make(stop_when_asleep=False)
        for _ in range(300):              # five minutes of normal polling
            now[0] += 1
            poller.cycle()
        t.silent = True
        before = t.sent
        self.run_until(now, poller, "asleep", step=0.5)
        self.assertLessEqual(t.sent - before, 5, "too many requests into a sleeping truck")

    def test_quiet_while_asleep_then_single_probe_on_wake(self):
        now, t, poller = self.make(stop_when_asleep=False)
        poller.cycle()
        self.assertGreater(t.sent, 30)
        t.silent = True
        self.run_until(now, poller, "asleep")
        before = t.sent
        for _ in range(30):               # 30 minutes asleep at flat voltage
            now[0] += poller.WATCH_S
            poller.cycle()
        self.assertEqual(t.sent, before, "nothing may be sent while asleep")
        t.volts = 13.9                    # DC-DC came on
        t.silent = False
        now[0] += poller.WATCH_S
        poller.cycle()
        self.assertEqual(t.sent, before + 1, "exactly one probe on wake")
        self.assertEqual(poller.state, "awake")

    def test_high_resting_voltage_does_not_probe(self):
        now, t, poller = self.make(volts=13.2, stop_when_asleep=False)
        t.silent = True
        self.run_until(now, poller, "asleep")
        before = t.sent
        for _ in range(20):
            now[0] += poller.WATCH_S
            poller.cycle()
        self.assertEqual(t.sent, before)

    def test_failed_voltage_read_does_not_probe(self):
        now, t, poller = self.make(stop_when_asleep=False)
        t.silent = True
        self.run_until(now, poller, "asleep")
        before = t.sent
        t.volts = None                    # one ATRV hiccup
        now[0] += poller.WATCH_S
        poller.cycle()
        self.assertEqual(t.sent, before)
        for _ in range(poller.VOLTAGE_FAILURES_LIMIT):
            now[0] += poller.WATCH_S
            poller.cycle()
        self.assertEqual(poller.state, "stopped")
        self.assertEqual(t.sent, before)

    def test_failed_probe_needs_a_fresh_rise(self):
        now, t, poller = self.make(stop_when_asleep=False)
        t.silent = True
        self.run_until(now, poller, "asleep")
        before = t.sent
        t.volts = 13.9                    # rise, but modules still silent
        for _ in range(10):
            now[0] += poller.WATCH_S
            poller.cycle()
        self.assertEqual(t.sent, before + 1, "one probe per voltage rise, not one per minute")
        t.volts = 12.5                    # back to rest...
        now[0] += poller.WATCH_S
        poller.cycle()
        t.volts, t.silent = 13.9, False   # ...then a real wake
        now[0] += poller.WATCH_S
        poller.cycle()
        self.assertEqual(poller.state, "awake")

    def test_no_voltage_adapter_probes_on_schedule_then_stops(self):
        now, t, poller = self.make(volts=None, stop_when_asleep=False)
        t.silent = True
        self.run_until(now, poller, "asleep")
        before = t.sent
        self.run_until(now, poller, "stopped", step=10.0)
        self.assertEqual(t.sent - before, len(poller.NO_VOLTAGE_PROBES_S))

    def test_one_silent_did_does_not_look_like_sleep(self):
        now, t, poller = self.make(stop_when_asleep=False)
        silent_key = ("bcm", 0x4149)
        orig = poller.clients["bcm"].read_did

        def read(did):
            if did == silent_key[1]:
                raise NoResponse("silent DID")
            return orig(did)
        poller.clients["bcm"].read_did = read
        for _ in range(120):
            now[0] += 1
            poller.cycle()
        self.assertEqual(poller.state, "awake")
        self.assertGreater(poller.period[silent_key], 10)

    def test_poll_stops_when_asleep_by_default(self):
        now, t, poller = self.make()
        t.silent = True
        self.run_until(now, poller, "stopped")
        self.assertIsNone(poller.next_wait())


class FakeElm(threading.Thread):
    """Minimal ELM327-over-TCP stand-in (like a WiCAN) answering one Ultium DID."""

    def __init__(self, stall_first=None, reject=None, reset_on=None):
        super().__init__(daemon=True)
        self.srv = socket.socket()
        self.srv.bind(("127.0.0.1", 0))
        self.srv.listen(1)
        self.port = self.srv.getsockname()[1]
        self.received = []
        self.interrupted = []
        self.stall_first = stall_first   # (command, seconds): answer late once
        self.reject = reject             # command answered with '?'
        self.reset_on = reset_on         # command answered with 'LV RESET'
        self.busy_until = 0.0

    def run(self):
        conn, _ = self.srv.accept()
        with conn, self.srv:
            self._serve(conn)

    def _serve(self, conn):
        hdr, cp = "", ""
        buf = b""
        while True:
            data = conn.recv(1024)
            if not data:
                return
            buf += data
            while b"\r" in buf:
                line, buf = buf.split(b"\r", 1)
                cmd = line.decode().strip().upper()
                import time as _t
                if _t.monotonic() < self.busy_until:
                    self.interrupted.append(cmd)  # a real ELM would drop a character here
                self.received.append(cmd)
                if self.stall_first and cmd == self.stall_first[0]:
                    delay, self.stall_first = self.stall_first[1], None
                    self.busy_until = _t.monotonic() + delay
                    threading.Timer(delay, lambda c=conn: c.sendall(b"142AF1CB056227C67123\r\r>")).start()
                    continue
                if cmd == self.reject:
                    conn.sendall(b"?\r\r>")
                    continue
                if cmd == self.reset_on:
                    conn.sendall(b"LV RESET\r\r>")
                    continue
                if cmd.startswith("ATSH"):
                    hdr = cmd[4:]
                if cmd.startswith("ATCP"):
                    cp = cmd[4:]
                if cmd == "ATZ":
                    reply = "ATZ\r\rELM327 v1.5"
                elif cmd == "ATI":
                    reply = "ELM327 v1.5"
                elif cmd == "ATRV":
                    reply = "12.6V"
                elif cmd.startswith(("AT", "ST")):
                    reply = "OK" if not cmd.startswith("STI") else "?"
                elif cmd == "2227C6" and cp + hdr == "14DACBF1":
                    reply = "142AF1CB056227C67123"
                elif cmd == "222AF5" and cp + hdr == "14DACBF1":
                    reply = "142AF1CB100D622AF59D759D62\r142AF1CB219D91B5179113AAAA"
                else:
                    reply = "NO DATA"
                conn.sendall(reply.encode() + b"\r\r>")


class ElmTcpEndToEnd(unittest.TestCase):
    def test_read_over_tcp(self):
        fake = FakeElm()
        fake.start()
        t = ElmTransport(ElmLink.tcp("127.0.0.1", fake.port), min_interval_s=0.05)
        client = UdsClient(t.open(*addressing.ids(0xCB, 0x14)), timeout=0.5)
        self.assertEqual(client.read_did(0x27C6).data, bytes.fromhex("7123"))
        self.assertEqual(client.read_did(0x2AF5).data, bytes.fromhex("9D759D629D91B5179113"))
        with self.assertRaises(NoResponse):
            UdsClient(t.open(*addressing.ids(0xCB, 0x18)), timeout=0.2).read_did(0x27C6)
        self.assertEqual(t.adapter_voltage(), 12.6)
        t.close()
        # Everything the adapter was sent is an allowlisted AT command or a read.
        for cmd in fake.received:
            ok = cmd.startswith(ALLOWED_AT) or re.fullmatch(r"22[0-9A-F]{4}", cmd)
            self.assertTrue(ok, cmd)
        self.assertIn("ATSP7", fake.received)
        self.assertIn("ATCRA142AF1CB", fake.received)
        self.assertIn("ATFCSH14DACBF1", fake.received)

    def test_never_writes_to_busy_adapter(self):
        from ultium_probe.transports.base import FatalTransportError
        fake = FakeElm(stall_first=("2227C6", 0.8))
        fake.start()
        t = ElmTransport(ElmLink.tcp("127.0.0.1", fake.port), response_timeout=0.3,
                         min_interval_s=0.05)
        client = UdsClient(t.open(*addressing.ids(0xCB, 0x14)))
        with self.assertRaises(Exception):
            client.read_did(0x27C6)       # times out; link marked dirty
        try:
            client.read_did(0x27C6)       # must wait for the late prompt before writing
        except FatalTransportError:
            pass
        t.close()
        self.assertEqual(fake.interrupted, [], "wrote to the adapter while it was busy")

    def test_rejected_setting_is_fatal(self):
        from ultium_probe.transports.base import FatalTransportError
        fake = FakeElm(reject="ATCRA142AF1CB")
        fake.start()
        t = ElmTransport(ElmLink.tcp("127.0.0.1", fake.port), min_interval_s=0.05)
        with self.assertRaises(FatalTransportError):
            UdsClient(t.open(*addressing.ids(0xCB, 0x14))).read_did(0x27C6)
        t.close()
        self.assertNotIn("2227C6", fake.received, "request sent with unconfirmed addressing")

    def test_output_after_prompt_is_fatal(self):
        from ultium_probe.transports.base import FatalTransportError
        fake = FakeElm(reset_on="2227C6")
        fake.start()
        t = ElmTransport(ElmLink.tcp("127.0.0.1", fake.port), min_interval_s=0.05)
        ch = t.open(*addressing.ids(0xCB, 0x14))
        # Simulate a reply with a banner glued after its prompt.
        orig = t.link._read_available
        state = {"n": 0}

        def glued(timeout):
            data = orig(timeout)
            if data.endswith(b">") and b"142AF1CB" in data:
                return data + b"\rELM327 v1.5\r>"
            return data
        fake.reset_on = None
        t.link._read_available = glued
        with self.assertRaises(FatalTransportError):
            UdsClient(ch).read_did(0x27C6)
        t.close()

    def test_adapter_reset_is_fatal(self):
        from ultium_probe.transports.base import FatalTransportError
        fake = FakeElm(reset_on="2227C6")
        fake.start()
        t = ElmTransport(ElmLink.tcp("127.0.0.1", fake.port), min_interval_s=0.05)
        with self.assertRaises(FatalTransportError):
            UdsClient(t.open(*addressing.ids(0xCB, 0x14))).read_did(0x27C6)
        t.close()


class SocketCanGuardTests(unittest.TestCase):
    def check(self, **over):
        from unittest import mock
        from ultium_probe.transports import socketcan
        info = {"name": "can0", "up": True, "kind": "gs_usb", "state": "ERROR-ACTIVE",
                "ctrlmode": ["ONE-SHOT"], "listen_only": False, "fd": False, "bitrate": 500000,
                "dbitrate": None, "restart_ms": 0, "one_shot": True}
        info.update(over)
        with mock.patch.object(socketcan, "iface_info", return_value=info):
            return socketcan.SocketCanTransport("can0")

    def test_good_interface_accepted(self):
        self.check()

    def test_bad_interfaces_refused(self):
        from ultium_probe.transports.base import TransportError
        for over in ({"bitrate": 250000}, {"fd": True}, {"state": "ERROR-PASSIVE"},
                     {"restart_ms": 100}, {"one_shot": False}, {"up": False},
                     {"listen_only": True}):
            with self.assertRaises(TransportError, msg=str(over)):
                self.check(**over)


@unittest.skipUnless(os.path.exists("/sys/class/net/vcan0"), "needs vcan0 (see docs)")
class KernelIsoTpTests(unittest.TestCase):
    """End-to-end over the kernel ISO-TP stack on a virtual CAN bus, 29-bit IDs."""

    def test_multi_frame_over_vcan(self):
        from ultium_probe.transports.socketcan import SocketCanTransport

        tx, rx = addressing.ids(0xCB, 0x14)
        ecu = socket.socket(socket.AF_CAN, socket.SOCK_DGRAM, socket.CAN_ISOTP)
        ecu.bind(("vcan0", tx | socket.CAN_EFF_FLAG, rx | socket.CAN_EFF_FLAG))
        ecu.settimeout(2)

        def serve():
            req = ecu.recv(4095)
            ecu.send(bytes([0x62]) + req[1:3] + bytes(range(36)))

        th = threading.Thread(target=serve)
        th.start()
        t = SocketCanTransport("vcan0")
        r = UdsClient(t.open(tx, rx)).read_did(0x2AE1)
        th.join()
        ecu.close()
        self.assertEqual(r.data, bytes(range(36)))


if __name__ == "__main__":
    unittest.main()
