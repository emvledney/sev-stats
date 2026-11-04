"""ELM327 / OBDLink (STN) / WiCAN transport over USB serial, Bluetooth RFCOMM or TCP.

Only a fixed allowlist of adapter-configuration commands is ever sent; the
only thing that reaches the vehicle bus is the hex payload of a request, which
goes through the same safety checks as every other transport. The protocol is
pinned (ATSP7 = ISO 15765-4 CAN 29-bit/500k, what Ultium trucks use) so the
adapter never runs its protocol auto-search.

Link discipline (an ELM drops the first character it receives while busy,
which can turn '22xxxx' into a different service): after a timeout or any
unexpected reply the link is marked dirty, and nothing is written until the
adapter has shown its '>' prompt and gone quiet. If that doesn't happen, or the
adapter reset, errored or rejected a setting, the run stops (FatalTransportError).
"""

from __future__ import annotations

import os
import re
import select
import socket
import termios
import time

from .. import safety
from .base import Channel, FatalTransportError, Transport, TransportError

# Adapter-local configuration commands this tool may send (prefix match).
ALLOWED_AT = (
    "ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP7", "ATCAF1", "ATAT1", "ATST", "ATSH",
    "ATCRA", "ATCP", "ATFCSH", "ATFCSD", "ATFCSM", "ATI", "AT@1", "ATRV", "ATDPN", "STI",
)
# Settings whose reply must be exactly OK (anything else = unknown adapter state).
REQUIRED_OK = ("ATE0", "ATH1", "ATSP7", "ATSH", "ATCRA", "ATCP", "ATFCSH", "ATFCSD", "ATFCSM")
# Replies that mean the adapter reset, was interrupted or lost the bus.
ERROR_MARKERS = ("LV RESET", "STOPPED", "ERR", "CAN ERROR", "BUS ERROR", "BUFFER FULL",
                 "BUS BUSY", "FB ERROR", "DATA ERROR", "<RX ERROR", "ACT ALERT")
# A power-on banner where none was asked for also means the adapter reset.
BANNER_MARKERS = ("ELM327", "OBDLINK", "STN", "WICAN")
FATAL_MARKERS = ERROR_MARKERS + BANNER_MARKERS
IDENTITY_COMMANDS = ("ATZ", "ATI", "AT@1", "STI")


def _fatal_in(text: str, markers=FATAL_MARKERS) -> str | None:
    up = text.upper()
    return next((m for m in markers if m in up), None)

HEX_LINE = re.compile(r"^[0-9A-F]+$")


class ElmLink:
    """Line-oriented connection to the adapter (prompt is '>')."""

    def __init__(self, fd: int | None = None, sock: socket.socket | None = None) -> None:
        self.fd = fd
        self.sock = sock
        self.dirty = False
        self.echo_off = False   # set once ATE0 is confirmed; an echo afterwards means a reset

    @classmethod
    def serial(cls, path: str, baud: int) -> "ElmLink":
        try:
            fd = os.open(path, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
        except OSError as e:
            raise TransportError(f"cannot open {path}: {e}") from e
        attrs = termios.tcgetattr(fd)
        speed = getattr(termios, f"B{baud}")
        attrs[0] = 0                                      # iflag
        attrs[1] = 0                                      # oflag
        attrs[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
        attrs[3] = 0                                      # lflag (raw)
        attrs[4] = attrs[5] = speed
        termios.tcsetattr(fd, termios.TCSANOW, attrs)
        termios.tcflush(fd, termios.TCIOFLUSH)
        return cls(fd=fd)

    @classmethod
    def rfcomm(cls, mac: str, channel: int) -> "ElmLink":
        try:
            s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
            s.settimeout(10)
            s.connect((mac, channel))
        except OSError as e:
            raise TransportError(f"cannot reach Bluetooth adapter {mac}: {e}") from e
        return cls(sock=s)

    @classmethod
    def tcp(cls, host: str, port: int) -> "ElmLink":
        """Wi-Fi adapters (WiCAN PRO / most clones: 192.168.0.10:35000)."""
        try:
            s = socket.create_connection((host, port), timeout=10)
        except OSError as e:
            raise TransportError(f"cannot reach adapter at {host}:{port} ({e}); "
                                 "is the laptop joined to the adapter's Wi-Fi?") from e
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        return cls(sock=s)

    def _write(self, data: bytes) -> None:
        try:
            if self.sock:
                self.sock.sendall(data)
            else:
                os.write(self.fd, data)
        except OSError as e:
            raise FatalTransportError(f"adapter link failed: {e}") from e

    def _read_available(self, timeout: float) -> bytes:
        try:
            target = self.sock if self.sock else self.fd
            ready, _, _ = select.select([target], [], [], timeout)
            if not ready:
                return b""
            data = self.sock.recv(4096) if self.sock else os.read(self.fd, 4096)
        except OSError as e:
            raise FatalTransportError(f"adapter link failed: {e}") from e
        if self.sock and not data:
            raise FatalTransportError("adapter closed the connection")
        return data

    def _resync(self, budget: float = 10.0, quiet: float = 0.3) -> None:
        """Without writing anything, wait until the adapter shows '>' and then stays quiet.

        Whatever arrives meanwhile is checked: a late error or a reset banner is fatal.
        """
        deadline = time.monotonic() + budget
        seen = b""
        while time.monotonic() < deadline:
            chunk = self._read_available(quiet)
            if chunk:
                seen += chunk
                marker = _fatal_in(seen.decode(errors="replace"))
                if marker:
                    self.close()
                    raise FatalTransportError(f"adapter reported {marker!r} while recovering; stopping")
                continue
            if seen.rstrip().endswith(b">"):
                self.dirty = False
                return
        self.close()
        raise FatalTransportError("adapter did not return to its prompt; link closed")

    def settle(self, quiet: float = 0.3, budget: float = 10.0) -> None:
        """At connect: if the adapter is still talking (busy from an earlier run), wait it out."""
        first = self._read_available(quiet)
        if first:
            self.dirty = True
            deadline = time.monotonic() + budget
            seen = first
            while time.monotonic() < deadline:
                chunk = self._read_available(quiet)
                if not chunk:
                    if seen.rstrip().endswith(b">"):
                        self.dirty = False
                        return
                    continue
                seen += chunk
            self.close()
            raise FatalTransportError("adapter kept talking at connect; replug it and retry")

    def command(self, line: str, timeout: float) -> list[str]:
        if not line.strip():
            # A bare CR makes an ELM repeat its previous command on the bus.
            raise TransportError("refusing to send an empty line to the adapter")
        if self.dirty:
            self._resync()
        stale = b""
        while chunk := self._read_available(0):
            stale += chunk
        if stale.strip(b"\r\n >"):
            # Nothing should arrive between commands; output here means a reset or a late reply.
            self.dirty = True
            raise FatalTransportError(f"unexpected adapter output {stale!r}; stopping")
        self.dirty = True              # until the prompt comes back (also covers Ctrl-C)
        self._write(line.encode() + b"\r")
        buf = b""
        deadline = time.monotonic() + timeout
        while b">" not in buf:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TransportError(f"adapter timed out on {line!r} (got {buf!r})")
            buf += self._read_available(remaining)
        head, _, tail = buf.partition(b">")
        if tail.strip(b"\r\n >"):
            # Something followed the prompt in the same read (a reset banner, a late reply):
            # the adapter's state is unknown, so stop rather than write again.
            raise FatalTransportError(f"unexpected adapter output after the prompt {tail!r}; stopping")
        self.dirty = False
        text = head.decode(errors="replace")
        lines = [ln.strip() for ln in re.split(r"[\r\n]+", text) if ln.strip()]
        if any("STOPPED" in ln.upper() for ln in lines):
            raise FatalTransportError(f"adapter was interrupted (STOPPED) on {line!r}; stopping")
        if self.echo_off and any(ln.upper() == line.upper() for ln in lines):
            raise FatalTransportError("adapter echo came back on: it reset; stopping")
        return lines

    def close(self) -> None:
        sock, fd, self.sock, self.fd = self.sock, self.fd, None, None
        try:
            if sock:
                sock.close()
            elif fd is not None:
                os.close(fd)
        except OSError:
            pass


def reassemble(lines: list[str], header_len: int) -> list[tuple[int, bytes]]:
    """Turn ELM 'headers on, spaces off' frame lines into ISO-TP payloads.

    Returns (response_id, payload) for every complete message.
    """
    done: list[tuple[int, bytes]] = []
    partial: dict[int, tuple[int, bytearray]] = {}
    for ln in lines:
        ln = ln.replace(" ", "")
        if not HEX_LINE.match(ln) or len(ln) <= header_len:
            continue
        arb = int(ln[:header_len], 16)
        body = ln[header_len:]
        data = bytes.fromhex(body if len(body) % 2 == 0 else body[:-1])
        if not data:
            continue
        pci = data[0] >> 4
        if pci == 0:
            n = data[0] & 0x0F
            done.append((arb, data[1:1 + n]))
        elif pci == 1 and len(data) >= 2:
            total = ((data[0] & 0x0F) << 8) | data[1]
            partial[arb] = (total, bytearray(data[2:]))
        elif pci == 2 and arb in partial:
            total, buf = partial[arb]
            buf.extend(data[1:])
            if len(buf) >= total:
                done.append((arb, bytes(buf[:total])))
                del partial[arb]
    return done


class ElmChannel(Channel):
    def __init__(self, transport: "ElmTransport", tx_id: int, rx_id: int, *, functional: bool,
                 limiter: safety.RateLimiter) -> None:
        super().__init__(tx_id, rx_id, functional=functional, limiter=limiter)
        self.t = transport
        self._pending: list[bytes] = []

    def _select(self) -> None:
        if self.t.selected == (self.tx_id, self.rx_id, self.functional):
            return
        self.t.selected = None
        self.t.at(f"ATCP{(self.tx_id >> 24) & 0x1F:02X}")
        self.t.at(f"ATSH{self.tx_id & 0xFFFFFF:06X}")
        if self.functional:
            self.t.at("ATCRA")  # bare ATCRA clears the receive filter: hear every module
            self.t.at("ATFCSM0")
        else:
            self.t.at(f"ATCRA{self.rx_id:08X}")
            # Flow control for multi-frame answers goes back to this module.
            self.t.at(f"ATFCSH{self.tx_id:08X}")
            self.t.at("ATFCSD300000")
            self.t.at("ATFCSM1")
        self.t.selected = (self.tx_id, self.rx_id, self.functional)

    def _transmit(self, payload: bytes) -> None:
        self._select()
        lines = self.t.link.command(payload.hex().upper(), timeout=self.t.response_timeout)
        upper = [ln.upper() for ln in lines]
        if any(ln == "?" for ln in upper) or any(_fatal_in(ln) for ln in upper):
            self.t.selected = None
            raise FatalTransportError(f"adapter reported {lines}; stopping")
        frames = reassemble(lines, 8)
        self._pending = [p for arb, p in frames if self.functional or arb == self.rx_id]

    def _receive(self, timeout: float) -> list[bytes]:
        # The adapter returns every frame it saw before its prompt, so all
        # responses were already collected in _transmit. (Never send a bare CR
        # to wait for more: on an ELM that repeats the last bus request.)
        out, self._pending = self._pending, []
        return out


class ElmTransport(Transport):
    name = "elm"

    def __init__(self, link: ElmLink, *, response_timeout: float = 2.0,
                 min_interval_s: float = 0.075) -> None:
        self.link = link
        self.response_timeout = response_timeout
        self.limiter = safety.RateLimiter(min_interval_s)
        self.selected: tuple | None = None
        self.link.settle()                           # never write to an adapter that is still busy
        banner = " ".join(self.link.command("ATZ", timeout=5))   # reset; the reply is the banner
        if not _fatal_in(banner, BANNER_MARKERS):
            raise FatalTransportError(f"no adapter banner after ATZ (got {banner!r}); replug it and retry")
        for cmd in ("ATE0", "ATH1", "ATSP7"):
            self.at(cmd)
        self.link.echo_off = True
        for cmd in ("ATL0", "ATS0", "ATCAF1", "ATAT1", "ATST64"):
            self._try(cmd)                             # nice to have; not every clone knows them
        self.ident = " ".join(self._try("ATI")) + " / " + " ".join(self._try("STI"))
        self.voltage = " ".join(self._try("ATRV"))

    def _try(self, cmd: str) -> list[str]:
        try:
            return [ln for ln in self.at(cmd) if ln != "?"]
        except FatalTransportError:
            raise
        except TransportError:
            return []

    def at(self, cmd: str, timeout: float = 2.0) -> list[str]:
        up = cmd.upper()
        if not up.startswith(ALLOWED_AT):
            raise safety.UnsafeRequestError(f"adapter command {cmd!r} is not on the allowlist")
        lines = self.link.command(cmd, timeout=timeout)
        markers = ERROR_MARKERS if up.startswith(IDENTITY_COMMANDS) else FATAL_MARKERS
        bad = next((m for ln in lines if (m := _fatal_in(ln, markers))), None)
        if bad:
            self.selected = None
            raise FatalTransportError(f"adapter reported {bad!r} on {cmd!r}; stopping")
        if up.startswith(REQUIRED_OK) and [ln.upper() for ln in lines if ln.upper() != up] != ["OK"]:
            self.selected = None
            raise FatalTransportError(f"adapter rejected {cmd!r} (replied {lines}); stopping")
        return lines

    def open(self, tx_id: int, rx_id: int, *, functional: bool = False) -> Channel:
        if tx_id <= 0x7FF:
            raise TransportError("the ELM transport is pinned to 29-bit CAN (ATSP7)")
        return ElmChannel(self, tx_id, rx_id, functional=functional, limiter=self.limiter)

    def describe(self) -> str:
        return f"elm ({self.ident}; adapter sees {self.voltage})"

    def adapter_voltage(self) -> float | None:
        try:
            text = " ".join(self.at("ATRV"))
            return float(text.strip().rstrip("Vv"))
        except FatalTransportError:
            raise
        except (TransportError, ValueError):
            return None

    def close(self) -> None:
        self.link.close()
