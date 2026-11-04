"""Hard allowlist for every frame this tool can put on the vehicle bus.

Every channel checks its CAN IDs when it is created (``check_address``) and
every outbound payload before it is transmitted (``check_physical`` /
``check_functional``). Anything that is not a pure read to a diagnostic
address is rejected here, so a bug elsewhere (or a typo on the command line)
cannot open a diagnostic session, reset a module, clear codes, write data, run
a routine, request security access or talk on a non-diagnostic CAN ID.

Allowed:
  * physical requests to one module, addressed 0x14DA<ecu>F1 or 0x18DA<ecu>F1:
      - UDS 0x22 ReadDataByIdentifier, exactly one 16-bit DID per request;
      - SAE J1979 modes 0x03 / 0x07 / 0x0A (read stored / pending / permanent
        DTCs), used to confirm a session left no trouble codes behind.
  * functional (broadcast 0x18DB33F1) requests: SAE J1979 mode 0x01 (current
    data) and mode 0x09 (vehicle information), one PID per request.

Everything else (0x10, 0x11, 0x14, 0x19, 0x23, 0x27, 0x28, 0x2A, 0x2C, 0x2E,
0x2F, 0x31, 0x34-0x38, 0x3B, 0x3D, 0x3E, 0x83-0x87, OBD 0x04/0x08, 11-bit IDs,
any other CAN ID, ...) raises ``UnsafeRequestError``.
"""

from __future__ import annotations

import threading
import time

from . import addressing

SID_READ_DATA_BY_IDENTIFIER = 0x22
OBD_MODE_CURRENT_DATA = 0x01
OBD_MODE_VEHICLE_INFO = 0x09
OBD_DTC_READ_MODES = frozenset({0x03, 0x07, 0x0A})

ALLOWED_FUNCTIONAL_MODES = frozenset({OBD_MODE_CURRENT_DATA, OBD_MODE_VEHICLE_INFO})

MIN_INTERVAL_FLOOR_S = 0.05


class UnsafeRequestError(Exception):
    """Raised when something tries to transmit a non-read or non-diagnostic frame."""


def check_address(tx: int, rx: int, functional: bool) -> None:
    """Only the Ultium diagnostic addresses: physical 0x14/0x18DA<ecu>F1, functional 0x18DB33F1."""
    if functional:
        if (tx, rx) != (addressing.FUNCTIONAL_29, addressing.FUNCTIONAL_RESP_29[0]):
            raise UnsafeRequestError(f"functional requests only on 0x18DB33F1, not {tx:#x}/{rx:#x}")
        return
    pri, mid, ecu, tester = tx >> 24, (tx >> 16) & 0xFF, (tx >> 8) & 0xFF, tx & 0xFF
    if tx > 0x1FFFFFFF or pri not in (0x14, 0x18) or mid != 0xDA or tester != addressing.TESTER:
        raise UnsafeRequestError(f"{tx:#x} is not a physical diagnostic request ID (0x14/0x18DA<ecu>F1)")
    if ecu in (0x33, addressing.TESTER) or tx == rx or rx != addressing.ids(ecu, pri)[1]:
        raise UnsafeRequestError(f"response ID {rx:#x} does not pair with request {tx:#x}")


def check_physical(payload: bytes) -> None:
    """Allow only ``22 hi lo`` (one DID) or a bare OBD DTC read (03/07/0A)."""
    payload = bytes(payload)
    if len(payload) == 1 and payload[0] in OBD_DTC_READ_MODES:
        return
    if len(payload) == 3 and payload[0] == SID_READ_DATA_BY_IDENTIFIER:
        return
    raise UnsafeRequestError(
        f"physical request {payload.hex(' ') or '<empty>'} is not allowed "
        "(only '22 <DID>' or OBD 03/07/0A)")


def check_functional(payload: bytes) -> None:
    """Allow only ``01 pid`` and ``09 pid`` broadcast OBD requests."""
    payload = bytes(payload)
    if len(payload) != 2 or payload[0] not in ALLOWED_FUNCTIONAL_MODES:
        raise UnsafeRequestError(
            f"functional request {payload.hex(' ') or '<empty>'} is not allowed "
            "(only OBD mode 01/09 with one PID)")


class RateLimiter:
    """Enforces a minimum gap between consecutive bus requests (all modules)."""

    def __init__(self, min_interval_s: float = 0.075) -> None:
        if min_interval_s < MIN_INTERVAL_FLOOR_S:
            raise ValueError(f"refusing to run faster than one request per {MIN_INTERVAL_FLOOR_S * 1000:.0f} ms")
        self.min_interval_s = min_interval_s
        self._last = 0.0
        self._lock = threading.Lock()

    def wait(self) -> None:
        with self._lock:
            now = time.monotonic()
            delay = self._last + self.min_interval_s - now
            if delay > 0:
                time.sleep(delay)
            self._last = time.monotonic()
