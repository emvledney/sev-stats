"""Minimal read-only diagnostic client (UDS 0x22, OBD 01/09 and DTC reads)."""

from __future__ import annotations

import time
from dataclasses import dataclass

from .transports.base import Channel

NRC_NAMES = {
    0x10: "generalReject",
    0x11: "serviceNotSupported",
    0x12: "subFunctionNotSupported",
    0x13: "incorrectMessageLengthOrInvalidFormat",
    0x14: "responseTooLong",
    0x21: "busyRepeatRequest",
    0x22: "conditionsNotCorrect",
    0x31: "requestOutOfRange",
    0x33: "securityAccessDenied",
    0x34: "authenticationRequired",
    0x78: "requestCorrectlyReceivedResponsePending",
    0x7E: "subFunctionNotSupportedInActiveSession",
    0x7F: "serviceNotSupportedInActiveSession",
}


class NegativeResponse(Exception):
    def __init__(self, sid: int, nrc: int) -> None:
        self.sid, self.nrc = sid, nrc
        super().__init__(f"NRC 0x{nrc:02X} {NRC_NAMES.get(nrc, '')} for service 0x{sid:02X}")


class NoResponse(Exception):
    pass


@dataclass
class DidResult:
    did: int
    data: bytes
    elapsed_ms: float


class UdsClient:
    """ReadDataByIdentifier against one module.

    NRC 0x78 (response pending): keep listening. NRC 0x21 (busy): one retry
    after a short pause, which is routine on Ultium while driving. Everything
    else is reported to the caller; nothing ever escalates to another service.
    """

    def __init__(self, channel: Channel, *, timeout: float = 1.0, pending_timeout: float = 5.0,
                 busy_retry_delay: float = 0.15) -> None:
        self.ch = channel
        self.timeout = timeout
        self.pending_timeout = pending_timeout
        self.busy_retry_delay = busy_retry_delay

    def read_did(self, did: int) -> DidResult:
        try:
            return self._read_once(did)
        except NegativeResponse as e:
            if e.nrc != 0x21:
                raise
        time.sleep(self.busy_retry_delay)
        return self._read_once(did)

    def _read_once(self, did: int) -> DidResult:
        req = bytes([0x22, (did >> 8) & 0xFF, did & 0xFF])
        start = time.monotonic()
        responses = self.ch.request(req, self.timeout)
        deadline = start + self.pending_timeout
        while True:
            for r in responses:
                if len(r) >= 3 and r[0] == 0x62 and r[1:3] == req[1:3]:
                    return DidResult(did, r[3:], (time.monotonic() - start) * 1000)
                if len(r) >= 3 and r[0] == 0x7F and r[1] == 0x22 and r[2] != 0x78:
                    raise NegativeResponse(0x22, r[2])
            pending = any(len(r) >= 3 and r[0] == 0x7F and r[2] == 0x78 for r in responses)
            if not pending or time.monotonic() >= deadline:
                break
            responses = self.ch.receive_more(max(0.05, deadline - time.monotonic()))
        raise NoResponse(f"no answer from 0x{self.ch.rx_id:X} for DID 0x{did:04X}")

    def read_dtcs(self, mode: int) -> list[str]:
        """OBD mode 03/07/0A against this module; returns codes like 'P0AA6'."""
        responses = self.ch.request(bytes([mode]), self.timeout)
        for r in responses:
            if r and r[0] == mode + 0x40:
                body = r[2:] if len(r) % 2 == 0 else r[1:]
                return [_dtc(body[i:i + 2]) for i in range(0, len(body) - 1, 2)
                        if body[i:i + 2] != b"\x00\x00"]
            if len(r) >= 3 and r[0] == 0x7F:
                raise NegativeResponse(mode, r[2])
        raise NoResponse(f"no DTC answer from 0x{self.ch.rx_id:X}")


def _dtc(b: bytes) -> str:
    return "PCBU"[b[0] >> 6] + f"{(b[0] >> 4) & 3}{b[0] & 0xF:X}{b[1]:02X}"


def obd_query(channel: Channel, mode: int, pid: int, timeout: float = 0.5) -> list[bytes]:
    """Functional OBD request; returns the data bytes from every positive answer.

    An empty list means nobody answered. Adapter or bus errors raise TransportError.
    """
    responses = channel.request(bytes([mode, pid]), timeout)  # adapter errors propagate
    return [r[2:] for r in responses if len(r) >= 2 and r[0] == mode + 0x40 and r[1] == pid]


def vehicle_speed_kmh(channel: Channel) -> int | None:
    """OBD mode 01 PID 0D. None if nothing answered."""
    answers = obd_query(channel, 0x01, 0x0D)
    return answers[0][0] if answers and answers[0] else None


def mask_vin(vin: str) -> str:
    """Keep make/model/year characters (1-10), hide the serial part."""
    return vin[:10] + "*" * max(0, len(vin) - 10)
