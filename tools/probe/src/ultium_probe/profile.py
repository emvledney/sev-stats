"""DID profiles: which modules to talk to and how to decode what they return.

Profiles are TOML (see dids/*.toml). Decoding is purely declarative (type,
byte offset, stride, scale, offset, count) so no profile can run code.
"""

from __future__ import annotations

import tomllib
from dataclasses import dataclass, field
from pathlib import Path

from . import addressing

TYPES = {
    "u8": (1, False), "s8": (1, True),
    "u16": (2, False), "s16": (2, True),
    "u24": (3, False), "s24": (3, True),
    "u32": (4, False), "s32": (4, True),
}
STATUSES = ("unverified", "bolt-documented", "community-ultium", "confirmed")


@dataclass(frozen=True)
class Module:
    key: str
    tx: int
    rx: int
    description: str = ""


@dataclass(frozen=True)
class Field:
    module: str
    did: int
    name: str
    label: str
    unit: str = ""
    type: str = "u16"
    start: int = 0
    stride: int = 0          # bytes between array elements (default: element size)
    scale: float = 1.0
    offset: float = 0.0
    count: int = 1           # >1 = array; 0 = as many as the reply holds
    period: float = 1.0      # seconds between reads when polling
    status: str = "unverified"
    source: str = ""
    note: str = ""

    def decode(self, data: bytes):
        if self.type == "ascii":
            return data[self.start:].decode("ascii", errors="replace").strip("\x00 ")
        if self.type == "hex":
            return data[self.start:].hex(" ")
        size, signed = TYPES[self.type]
        step = self.stride or size
        count = self.count or max(0, (len(data) - self.start - size) // step + 1)
        values = []
        for i in range(count):
            lo = self.start + i * step
            chunk = data[lo:lo + size]
            if len(chunk) < size:
                break
            raw = int.from_bytes(chunk, "big", signed=signed)
            if self.scale == 1.0 and self.offset == 0.0:
                values.append(raw)
            else:
                values.append(round(raw * self.scale + self.offset, 6))
        if self.count == 1:
            return values[0] if values else None
        return values


@dataclass
class Profile:
    name: str
    modules: dict[str, Module]
    fields: list[Field] = field(default_factory=list)

    def reads(self) -> dict[tuple[str, int], list[Field]]:
        """Group fields by (module, DID) so each DID is requested once per cycle."""
        out: dict[tuple[str, int], list[Field]] = {}
        for f in self.fields:
            out.setdefault((f.module, f.did), []).append(f)
        return out


def _module(key: str, m: dict) -> Module:
    if "ecu" in m:
        tx, rx = addressing.ids(int(m["ecu"]), int(m.get("pri", 0x14)))
    else:
        tx, rx = int(m["tx"]), int(m["rx"])
    return Module(key, tx, rx, m.get("description", ""))


def load(path: str | Path) -> Profile:
    doc = tomllib.loads(Path(path).read_text())
    modules = {key: _module(key, m) for key, m in doc.get("modules", {}).items()}
    fields = []
    for d in doc.get("did", []):
        f = Field(
            module=d["module"], did=int(d["id"]), name=d["name"], label=d.get("label", d["name"]),
            unit=d.get("unit", ""), type=d.get("type", "u16"), start=int(d.get("start", 0)),
            stride=int(d.get("stride", 0)), scale=float(d.get("scale", 1.0)),
            offset=float(d.get("offset", 0.0)), count=int(d.get("count", 1)),
            period=float(d.get("period", 1.0)), status=d.get("status", "unverified"),
            source=d.get("source", ""), note=d.get("note", ""),
        )
        if f.module not in modules:
            raise ValueError(f"{path}: field {f.name} uses unknown module {f.module!r}")
        if f.type not in TYPES and f.type not in ("ascii", "hex"):
            raise ValueError(f"{path}: field {f.name} has unknown type {f.type!r}")
        if f.period < 0.5:
            raise ValueError(f"{path}: field {f.name} period must be >= 0.5 s")
        if f.status not in STATUSES:
            raise ValueError(f"{path}: field {f.name} has unknown status {f.status!r}")
        fields.append(f)
    pairs = [(m.tx, m.rx) for m in modules.values()]
    if len(pairs) != len(set(pairs)):
        raise ValueError(f"{path}: two modules share the same CAN IDs")
    names = [f.name for f in fields]
    dupes = {n for n in names if names.count(n) > 1}
    if dupes:
        raise ValueError(f"{path}: duplicate field names {sorted(dupes)}")
    return Profile(doc.get("meta", {}).get("name", Path(path).stem), modules, fields)
