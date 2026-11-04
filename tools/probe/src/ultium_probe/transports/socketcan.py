"""Linux SocketCAN transport (CAN and CAN-FD) using the kernel ISO-TP stack.

Works with any SocketCAN adapter: candleLight/CANable (gs_usb), PEAK PCAN-USB
(peak_usb), etc. (not slcan: it can't do one-shot mode). Classic CAN only: the Ultium OBD port answers classic
29-bit frames at 500 kbit/s, and CAN FD frames on a classic segment would be
destroyed by error frames. The interface must be set up beforehand, exactly:

    sudo ip link set can0 type can bitrate 500000 restart-ms 0 one-shot on
    sudo ip link set can0 up

(one-shot stops the controller from retransmitting an un-ACKed frame forever;
restart-ms 0 keeps it from silently recovering from bus-off). The transport
refuses to transmit on anything else. See tools/probe/README.md.
"""

from __future__ import annotations

import json
import socket
import struct
import subprocess
import time

from .. import addressing, safety
from .base import Channel, Transport, TransportError

SOL_CAN_ISOTP = socket.SOL_CAN_BASE + socket.CAN_ISOTP
CAN_ISOTP_OPTS = 1
CAN_ISOTP_RECV_FC = 2
CAN_ISOTP_LL_OPTS = 5
CAN_ISOTP_TX_PADDING = 0x0004
CAN_MTU = 16
CANFD_MTU = 72
CAN_RAW_FILTER = 1

CAN_FRAME = struct.Struct("=IB3x8s")
CANFD_FRAME = struct.Struct("=IBB2x64s")


def iface_info(name: str) -> dict:
    """Return the interface's state from ``ip -details -json link show``."""
    out = subprocess.run(["ip", "-details", "-json", "link", "show", name],
                         capture_output=True, text=True)
    if out.returncode != 0:
        raise TransportError(out.stderr.strip() or f"no such interface {name}")
    link = json.loads(out.stdout)[0]
    info = link.get("linkinfo", {}).get("info_data", {})
    return {
        "name": name,
        "up": "UP" in link.get("flags", []),
        "kind": link.get("linkinfo", {}).get("info_kind"),
        "state": info.get("state"),
        "ctrlmode": info.get("ctrlmode", []),
        "listen_only": "LISTEN-ONLY" in info.get("ctrlmode", []),
        "fd": "FD" in info.get("ctrlmode", []),
        "bitrate": info.get("bittiming", {}).get("bitrate"),
        "dbitrate": info.get("data_bittiming", {}).get("bitrate"),
        "restart_ms": info.get("restart_ms"),
        "one_shot": "ONE-SHOT" in info.get("ctrlmode", []),
    }


def _can_id(arb_id: int) -> int:
    return arb_id | socket.CAN_EFF_FLAG if arb_id > 0x7FF else arb_id


class IsoTpChannel(Channel):
    """Physical request/response pair handled by the kernel ISO-TP module."""

    def __init__(self, iface: str, tx_id: int, rx_id: int, *, pad: int | None,
                 limiter: safety.RateLimiter) -> None:
        super().__init__(tx_id, rx_id, limiter=limiter)
        self.sock = socket.socket(socket.AF_CAN, socket.SOCK_DGRAM, socket.CAN_ISOTP)
        flags = CAN_ISOTP_TX_PADDING if pad is not None else 0
        self.sock.setsockopt(SOL_CAN_ISOTP, CAN_ISOTP_OPTS,
                             struct.pack("=IIBBBB", flags, 0, 0, pad or 0, 0, 0))
        # Ask senders for everything in one block with no separation time.
        self.sock.setsockopt(SOL_CAN_ISOTP, CAN_ISOTP_RECV_FC, struct.pack("=BBB", 0, 0, 0))
        # No CAN_ISOTP_LL_OPTS: the kernel default is classic CAN frames (CAN_MTU).
        self.sock.bind((iface, _can_id(rx_id), _can_id(tx_id)))

    def _drain(self) -> int:
        """Discard replies that arrived after an earlier request timed out."""
        n = 0
        self.sock.setblocking(False)
        try:
            while True:
                self.sock.recv(4095)
                n += 1
        except BlockingIOError:
            pass
        except OSError as e:  # e.g. ECOMM: an earlier transmission failed
            raise TransportError(f"ISO-TP link to 0x{self.tx_id:X} reported an error: {e}") from e
        return n

    def _transmit(self, payload: bytes) -> None:
        self._drain()
        try:
            self.sock.send(payload)
        except OSError as e:
            raise TransportError(f"ISO-TP send to 0x{self.tx_id:X} failed: {e}") from e

    def _receive(self, timeout: float) -> list[bytes]:
        self.sock.settimeout(timeout)
        try:
            return [self.sock.recv(4095)]
        except TimeoutError:
            return []
        except OSError as e:
            raise TransportError(f"ISO-TP receive from 0x{self.rx_id:X} failed: {e}") from e

    def close(self) -> None:
        self.sock.close()


class FunctionalChannel(Channel):
    """OBD broadcast (0x18DB33F1 or 0x7DF) with answers from every module.

    The kernel ISO-TP socket is 1:1, so broadcast is done by hand on a raw
    socket: one single frame out, then single-frame answers, plus multi-frame
    ones for which we send the ISO-TP flow-control frame the protocol requires.
    """

    def __init__(self, iface: str, *, extended: bool, pad: int,
                 limiter: safety.RateLimiter) -> None:
        if extended:
            tx, (rid, rmask) = addressing.FUNCTIONAL_29, addressing.FUNCTIONAL_RESP_29
        else:
            tx, (rid, rmask) = addressing.FUNCTIONAL_11, addressing.FUNCTIONAL_RESP_11
        super().__init__(tx, rid, functional=True, limiter=limiter)
        self.pad = pad
        self.sock = socket.socket(socket.AF_CAN, socket.SOCK_RAW, socket.CAN_RAW)
        eff = socket.CAN_EFF_FLAG if extended else 0
        self.sock.setsockopt(socket.SOL_CAN_RAW, CAN_RAW_FILTER,
                             struct.pack("=II", rid | eff, rmask | eff))
        self.sock.bind((iface,))

    def _send_frame(self, arb_id: int, data: bytes) -> None:
        data = data.ljust(8, bytes([self.pad]))
        self.sock.send(CAN_FRAME.pack(_can_id(arb_id), 8, data))

    def _transmit(self, payload: bytes) -> None:
        self._send_frame(self.tx_id, bytes([len(payload)]) + payload)

    def _receive(self, timeout: float) -> list[bytes]:
        deadline = time.monotonic() + timeout
        done: list[bytes] = []
        partial: dict[int, tuple[int, bytearray]] = {}
        while (remaining := deadline - time.monotonic()) > 0:
            self.sock.settimeout(remaining)
            try:
                raw = self.sock.recv(CANFD_MTU)
            except TimeoutError:
                break
            arb_id, length = struct.unpack_from("=IB", raw)
            arb_id &= socket.CAN_EFF_MASK
            data = raw[8:8 + length]
            if not data:
                continue
            pci = data[0] >> 4
            if pci == 0:  # single frame
                n = data[0] & 0x0F or (data[1] if len(data) > 1 else 0)
                start = 1 if data[0] & 0x0F else 2
                done.append(bytes(data[start:start + n]))
            elif pci == 1:  # first frame -> flow control back to that module
                total = ((data[0] & 0x0F) << 8) | data[1]
                partial[arb_id] = (total, bytearray(data[2:]))
                self._send_frame(addressing.flow_control_target(arb_id), b"\x30\x00\x00")
            elif pci == 2 and arb_id in partial:
                total, buf = partial[arb_id]
                buf.extend(data[1:])
                if len(buf) >= total:
                    done.append(bytes(buf[:total]))
                    del partial[arb_id]
        return done

    def close(self) -> None:
        self.sock.close()


class SocketCanTransport(Transport):
    name = "socketcan"

    def __init__(self, iface: str, *, pad: int | None = 0x00, min_interval_s: float = 0.075) -> None:
        self.iface = iface
        info = iface_info(iface)
        if not info["up"]:
            raise TransportError(f"{iface} is down; set it up first (see tools/probe/README.md)")
        if info["listen_only"]:
            raise TransportError(f"{iface} is in listen-only mode; it cannot send requests.")
        if info["kind"] != "vcan":
            problems = []
            if info["bitrate"] != 500000:
                problems.append(f"bitrate is {info['bitrate']}, must be 500000")
            if info["fd"]:
                problems.append("CAN FD is on; the OBD port is classic CAN")
            if info["state"] != "ERROR-ACTIVE":
                problems.append(f"controller state is {info['state']}, must be ERROR-ACTIVE")
            if info["restart_ms"] not in (0, None):
                problems.append(f"restart-ms is {info['restart_ms']}, must be 0")
            if not info["one_shot"]:
                problems.append("one-shot is off (un-ACKed frames would retransmit forever)")
            if problems:
                raise TransportError(f"refusing to transmit on {iface}: " + "; ".join(problems)
                                     + ". Use: ip link set " + iface
                                     + " type can bitrate 500000 restart-ms 0 one-shot on")
        self.pad = pad
        self.limiter = safety.RateLimiter(min_interval_s)

    def open(self, tx_id: int, rx_id: int, *, functional: bool = False) -> Channel:
        if functional:
            return FunctionalChannel(self.iface, extended=tx_id > 0x7FF,
                                     pad=self.pad or 0, limiter=self.limiter)
        return IsoTpChannel(self.iface, tx_id, rx_id, pad=self.pad, limiter=self.limiter)

    def describe(self) -> str:
        return f"socketcan:{self.iface} (classic CAN 500k)"


def sniff(iface: str, seconds: float | None, out, *, require_listen_only: bool = True) -> int:
    """Passively log every frame on ``iface`` in candump format. Never transmits."""
    info = iface_info(iface)
    if require_listen_only and not info["listen_only"]:
        raise TransportError(
            f"{iface} is not in listen-only mode. Reconfigure it with 'listen-only on' "
            "(see tools/probe/README.md) or pass --allow-active-iface.")
    if not info["listen_only"] and info["kind"] != "vcan" and info["bitrate"] != 500000:
        raise TransportError(f"{iface} is active at {info['bitrate']} bit/s; an active controller "
                             "at the wrong bitrate disturbs the bus")
    sock = socket.socket(socket.AF_CAN, socket.SOCK_RAW, socket.CAN_RAW)
    sock.setsockopt(socket.SOL_CAN_RAW, socket.CAN_RAW_FD_FRAMES, 1)
    sock.bind((iface,))
    count = 0
    end = None if seconds is None else time.monotonic() + seconds
    try:
        while end is None or time.monotonic() < end:
            sock.settimeout(1.0 if end is None else max(0.01, min(1.0, end - time.monotonic())))
            try:
                raw = sock.recv(CANFD_MTU)
            except TimeoutError:
                continue
            arb_id, length = struct.unpack_from("=IB", raw)
            data = raw[8:8 + length]
            ext = bool(arb_id & socket.CAN_EFF_FLAG)
            ident = arb_id & (0x1FFFFFFF if ext else 0x7FF)
            sep = "##0" if len(raw) == CANFD_MTU else "#"
            out.write(f"({time.time():.6f}) {iface} "
                      f"{ident:0{8 if ext else 3}X}{sep}{data.hex().upper()}\n")
            count += 1
    finally:
        sock.close()
    return count
