"""Transport factory: ``elm-bt:auto`` / ``elm-bt:MAC[:ch]`` (OBDLink MX+), ``sim``."""

from __future__ import annotations

from .base import Channel, Transport, TransportError


def open_transport(spec: str, *, min_interval_s: float = 0.075) -> Transport:
    kind, _, rest = spec.partition(":")
    if kind == "sim":
        from .sim import SimTransport
        return SimTransport(min_interval_s=min(min_interval_s, 0.05))
    if kind == "elm-bt":
        from .elm import ElmLink, ElmTransport
        parts = rest.split(":") if rest and rest != "auto" else []
        channel = 1
        if len(parts) == 7:
            channel = int(parts.pop())
        mac = ":".join(parts) if parts else find_paired_obdlink()
        return ElmTransport(ElmLink.rfcomm(mac, channel), min_interval_s=min_interval_s)
    raise TransportError(f"unknown transport {spec!r}")


def find_paired_obdlink() -> str:
    """MAC of the one paired Bluetooth device whose name looks like an OBD adapter."""
    import re
    import subprocess
    try:
        out = subprocess.run(["bluetoothctl", "devices", "Paired"], capture_output=True,
                             text=True, timeout=10).stdout
    except (OSError, subprocess.TimeoutExpired) as e:
        raise TransportError(f"can't list paired Bluetooth devices: {e}") from e
    found = [(m.group(1), m.group(2)) for m in
             re.finditer(r"^Device ([0-9A-F:]{17}) (.*)$", out, re.M)
             if re.search(r"obdlink|obd|elm327|vlink|veepeak", m.group(2), re.I)]
    if len(found) != 1:
        names = ", ".join(f"{n} ({mac})" for mac, n in found) or "none"
        raise TransportError(f"expected exactly one paired OBD adapter, found {names}; "
                             "pair it first (docs/truck-connection.md section 2) or pass elm-bt:MAC")
    return found[0][0]


__all__ = ["Channel", "Transport", "TransportError", "open_transport"]
