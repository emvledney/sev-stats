"""In-process simulated Silverado EV for developing without the truck.

Emulates the Ultium modules in dids/silverado_ev.toml (BECM 0xCB, drive units
0x17/0x1D at priority 0x14, BCM 0x40 at 0x18, gateway 0x45) with synthetic,
slowly varying values, plus functional OBD 01/09. Modules addressed at the
wrong priority stay silent, like the real truck. Every request still passes
through the same safety checks as the real transports.
"""

from __future__ import annotations

import math
import struct
import time

from .. import addressing, safety
from .base import Channel, Transport

MODULES = 24
GROUPS = MODULES * 8


def _u16(v: float) -> bytes:
    return struct.pack(">H", max(0, min(0xFFFF, int(round(v)))))


class UltiumSim:
    """Synthetic truck state, sampled on every request."""

    def __init__(self) -> None:
        self.t0 = time.monotonic()

    def state(self) -> dict:
        t = time.monotonic() - self.t0
        current = 160 * math.sin(t / 9) + 40          # A, + = discharge
        soc = 72.0 - t / 600
        groups = [3.93 + 0.003 * math.sin(i / 11 + t / 40) - current * 0.00004
                  for i in range(GROUPS)]
        groups[46] -= 0.006
        groups[130] += 0.005
        return {"t": t, "current": current, "soc": soc, "groups": groups,
                "temps": [25 + 2 * math.sin(i / 4 + t / 120) for i in range(MODULES)]}

    def answer(self, ecu: int, pri: int, payload: bytes) -> bytes | None:
        """Return the module's reply, or None to stay silent."""
        answers_at = {0xCB: (0x14, 0x18), 0x17: (0x14, 0x18), 0x1D: (0x14, 0x18),
                      0x40: (0x18,), 0x45: (0x18,)}
        if pri not in answers_at.get(ecu, ()):
            return None
        if payload[0] in safety.OBD_DTC_READ_MODES:
            return bytes([payload[0] + 0x40, 0x00]) if ecu == 0x45 else None
        did = int.from_bytes(payload[1:3], "big")
        data = self._did(ecu, did)
        if data is None:
            return bytes([0x7F, 0x22, 0x31])
        return bytes([0x62]) + payload[1:3] + data

    def _did(self, ecu: int, did: int) -> bytes | None:
        s = self.state()
        g = s["groups"]
        avg = sum(g) / len(g)
        lo, hi = g.index(min(g)), g.index(max(g))
        pack_v = 96 * avg
        if ecu == 0xCB:
            if did == 0x27C6:
                return _u16(s["soc"] * 655.35)
            if did == 0x27AF:
                return _u16(s["soc"] / 100 * 205.4 * 100)
            if did == 0x27C7:
                return int(s["soc"] / 100 * 440 * 103).to_bytes(3, "big")
            if did == 0x27C0:
                return int(42 * 16.09344).to_bytes(3, "big")
            if did in (0x2AF5, 0x2AF6):
                # 2AF6 summarises groups 1-96 (modules 1-12), 2AF5 groups 97-192 (modules 13-24)
                first = 0 if did == 0x2AF6 else GROUPS // 2
                half = g[first:first + GROUPS // 2]
                lo_h = first + half.index(min(half))
                hi_h = first + half.index(max(half))
                return (_u16(sum(half) / len(half) * 1e4) + _u16(min(half) * 1e4) + _u16(max(half) * 1e4)
                        + bytes([lo_h + 1, lo_h // 8 + 1, hi_h + 1, hi_h // 8 + 1]))
            if did == 0x2AF2:
                return bytes(int(max(s["temps"])) + 80 for _ in range(MODULES))
            if did == 0x2AF8:
                return bytes.fromhex("989977989978989c6e")
            if did == 0x2AF9:
                return bytes.fromhex("222222222222111111111111")
            if did == 0x2AF7:
                return b"".join(_u16((pack_v + d) * 100) for d in (-0.1, -0.2, 0.2, 0.2))
            if did == 0x2AF1:
                return bytes(int(x) + 80 for x in s["temps"])
            if did in (0x2AF3, 0x2AF4):
                return bytes([int(max(s["temps"])) + 80, int(min(s["temps"])) + 80, 0x0E, 0x03])
            if did == 0x2B43:
                b = int(s["soc"] * 2.55)
                return bytes([b, b]) + bytes([b + (i % 3) - 1 for i in range(MODULES)])
            if did == 0x5401:
                return b"\x00"
            if did == 0x0046:
                return bytes([int(sum(s["temps"]) / MODULES) + 40])
            if did == 0x33E5:
                return b"\x7d"
            if 0x2AE1 <= did <= 0x2AF0:
                first = (did - 0x2AE1) * 12
                return b"".join(_u16(g[i] * 1e4) + bytes([i // 8 + 1])
                                for i in range(first, min(first + 12, GROUPS)))
        if did == 0xF190 and ecu in (0x17, 0x1D, 0x40, 0x45):
            return b"1GCSIMULATED00000"
        if ecu in (0x17, 0x1D):
            if did == 0x2885:
                return _u16(pack_v * 100)
            if did == 0x2414 and ecu == 0x17:
                return struct.pack(">h", int(s["current"] * 20))
            if did == 0x33E5:
                return b"\x7d"
        if ecu == 0x40:
            if did == 0x434F:
                return bytes([int(sum(s["temps"]) / MODULES) + 40])
            if did in (0x40E5, 0x40E6, 0x4127, 0x4124):
                base = {0x40E5: 21.0, 0x40E6: 24.5, 0x4127: 25.5, 0x4124: 26.0}[did]
                return _u16(base * 32)
            if did == 0x4149:
                return _u16(0)
        return None

    def functional(self, mode: int, pid: int) -> list[bytes]:
        if mode == 0x01 and pid == 0x00:
            return [bytes([0x41, 0x00, 0x00, 0x18, 0x80, 0x01])] * 3
        if mode == 0x01 and pid == 0x0D:
            return [bytes([0x41, 0x0D, 0x00])]
        if mode == 0x09 and pid == 0x0A:
            return [bytes([0x49, 0x0A, 0x01]) + name.encode().ljust(20, b"\x00")
                    for name in ("GWM-Gateway", "DMCM-DriveMotorCtrl", "BECM-B+EnergyCtrl")]
        if mode == 0x09 and pid == 0x02:
            return [bytes([0x49, 0x02, 0x01]) + b"1GCSIMULATED00000"]
        return []


class SimChannel(Channel):
    def __init__(self, sim: UltiumSim, tx_id: int, rx_id: int, *, functional: bool,
                 limiter: safety.RateLimiter) -> None:
        super().__init__(tx_id, rx_id, functional=functional, limiter=limiter)
        self.sim = sim
        self._out: list[bytes] = []

    def _transmit(self, payload: bytes) -> None:
        if self.functional:
            self._out = self.sim.functional(payload[0], payload[1])
            return
        pri, ecu = self.tx_id >> 24, (self.tx_id >> 8) & 0xFF
        if self.tx_id <= 0x7FF or self.rx_id != addressing.ids(ecu, pri)[1]:
            self._out = []
            return
        reply = self.sim.answer(ecu, pri, payload)
        self._out = [reply] if reply else []

    def _receive(self, timeout: float) -> list[bytes]:
        out, self._out = self._out, []
        return out


class SimTransport(Transport):
    name = "sim"

    def __init__(self, min_interval_s: float = 0.05) -> None:
        self.sim = UltiumSim()
        self.limiter = safety.RateLimiter(min_interval_s)

    def open(self, tx_id: int, rx_id: int, *, functional: bool = False) -> Channel:
        return SimChannel(self.sim, tx_id, rx_id, functional=functional, limiter=self.limiter)

    def describe(self) -> str:
        return "sim (synthetic Silverado EV, 24 modules)"
