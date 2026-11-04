"""Transport interfaces.

A ``Transport`` opens ``Channel`` objects, one per module (request/response CAN
ID pair). The channel's IDs are checked when it is created, and
``Channel.request`` is the only way to transmit: it always runs the payload
safety check first. Subclasses implement ``_transmit``/``_receive`` and never
expose an unchecked send.
"""

from __future__ import annotations

from abc import ABC, abstractmethod

from .. import safety


class TransportError(Exception):
    pass


class FatalTransportError(TransportError):
    """The link is in an unknown state; stop the run rather than send anything else."""


class Channel(ABC):
    """Request/response path to one module (physical) or to all (functional)."""

    def __init__(self, tx_id: int, rx_id: int, *, functional: bool = False,
                 limiter: safety.RateLimiter | None = None) -> None:
        safety.check_address(tx_id, rx_id, functional)
        self.tx_id = tx_id
        self.rx_id = rx_id
        self.functional = functional
        self.limiter = limiter or safety.RateLimiter()

    def request(self, payload: bytes, timeout: float) -> list[bytes]:
        """Send one guarded request; return every complete response payload seen.

        Physical channels normally return one payload. Functional channels may
        return one per responding module.
        """
        if self.functional:
            safety.check_functional(payload)
        else:
            safety.check_physical(payload)
        self.limiter.wait()
        self._transmit(bytes(payload))
        return self._receive(timeout)

    def receive_more(self, timeout: float) -> list[bytes]:
        """Wait for further responses (used after NRC 0x78 'response pending')."""
        return self._receive(timeout)

    @abstractmethod
    def _transmit(self, payload: bytes) -> None: ...

    @abstractmethod
    def _receive(self, timeout: float) -> list[bytes]: ...

    def close(self) -> None:  # pragma: no cover - default no-op
        pass


class Transport(ABC):
    name = "transport"

    @abstractmethod
    def open(self, tx_id: int, rx_id: int, *, functional: bool = False) -> Channel: ...

    def describe(self) -> str:
        return self.name

    def adapter_voltage(self) -> float | None:
        """12 V at the OBD port as measured by the adapter itself (no bus traffic)."""
        return None

    def close(self) -> None:  # pragma: no cover - default no-op
        pass
