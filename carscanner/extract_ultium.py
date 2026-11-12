#!/usr/bin/env python3
"""Extract the two GM Ultium profiles from the supplied Car Scanner Android APK.

This reads files only: it does not run the APK, load native libraries, contact a
vehicle, or change the Silverado Stats profile. Python 3.10+ and dnfile are needed.
Install dnfile in an environment of your choice, or in work/python-libs with:
    python -m pip install --target carscanner/work/python-libs dnfile==0.18.0

The supported packaging is XABA v3 / XALZ, LZMA, native ShiftStream variant 2,
and BSON. The transform and field meanings were traced in this supplied APK.
The result describes the app's definitions; it does not prove truck support.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import logging
import lzma
from pathlib import Path
import re
import struct
import sys
import zipfile


HERE = Path(__file__).resolve().parent
RESOURCE_NAME = "CarScannerMaui.EmbeddedFiles.profiles7.db"
ASSEMBLY_NAME = "CarScannerMaui.dll"
# CarScannerMaui.OBD2.PIDS.UnitsHelper.Units in the supplied 2.2.42 assembly.
# Unknown IDs are rejected instead of guessing their meaning.
UNITS = {
    1: "km/h", 3: "km", 5: "kPa", 6: "rpm", 7: "deg", 10: "V",
    13: "Nm", 14: "%", 15: "degC", 18: "min", 43: "m/s2",
    46: "kW", 59: "A", 60: "kWh", 62: "Ah",
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def lz4_decode(src: bytes, expected_size: int) -> bytes:
    """Decode the raw LZ4 block following a .NET Android XALZ header."""
    out = bytearray()
    pos = 0
    while pos < len(src):
        token = src[pos]
        pos += 1
        count = token >> 4
        if count == 15:
            while True:
                extra = src[pos]
                pos += 1
                count += extra
                if extra != 255:
                    break
        if pos + count > len(src):
            raise ValueError("LZ4 literal extends past block")
        out.extend(src[pos:pos + count])
        pos += count
        if pos == len(src):
            break
        distance = int.from_bytes(src[pos:pos + 2], "little")
        pos += 2
        count = (token & 15) + 4
        if (token & 15) == 15:
            while True:
                extra = src[pos]
                pos += 1
                count += extra
                if extra != 255:
                    break
        if not distance or distance > len(out):
            raise ValueError("Invalid LZ4 match distance")
        for _ in range(count):
            out.append(out[-distance])
        if len(out) > expected_size:
            raise ValueError("LZ4 output exceeds declared size")
    if len(out) != expected_size:
        raise ValueError("LZ4 output length does not match XALZ declaration")
    return bytes(out)


def extract_assembly(wrapper: bytes) -> tuple[bytes, dict]:
    """Read the XABA store without loading or executing its ELF wrapper."""
    base = wrapper.index(b"XABA")
    data = wrapper[base:]
    _, version, count, index_count, index_size = struct.unpack_from("<5I", data)
    if version & 0xFFFF != 3:
        raise ValueError(f"Unsupported assembly store version {version:#x}")
    if index_count != count * 2 or index_size != index_count * 9:
        raise ValueError("Unexpected XABA v3 index layout")
    descriptors = 20 + index_size
    names = descriptors + count * 28
    for number in range(count):
        descriptor = struct.unpack_from("<7I", data, descriptors + number * 28)
        name_length, = struct.unpack_from("<I", data, names)
        names += 4
        name = data[names:names + name_length].decode("utf-8")
        names += name_length
        if name != ASSEMBLY_NAME:
            continue
        offset, size = descriptor[1:3]
        assembly = data[offset:offset + size]
        if len(assembly) != size:
            raise ValueError("Assembly extends past store")
        if assembly.startswith(b"XALZ"):
            _, expanded_size = struct.unpack_from("<2I", assembly, 4)
            assembly = lz4_decode(assembly[12:], expanded_size)
        if not assembly.startswith(b"MZ"):
            raise ValueError("Extracted assembly is not a PE image")
        return assembly, {
            "elf_payload_offset": base, "store_version": version,
            "assembly_count": count, "assembly_store_offset": offset,
            "assembly_stored_size": size, "assembly_size": len(assembly),
            "assembly_sha256": sha256(assembly),
        }
    raise ValueError(f"{ASSEMBLY_NAME} was not in assembly store")


def extract_resource(assembly: bytes) -> bytes:
    try:
        import dnfile
    except ImportError as exc:
        raise SystemExit("Missing dnfile; see installation command in script docstring") from exc
    # dnfile warns on unrelated obfuscated string-heap entries. The resource
    # extraction below checks the named raw resource and never interprets code.
    logger = logging.getLogger("dnfile")
    old_level = logger.level
    logger.setLevel(logging.CRITICAL)
    try:
        pe = dnfile.dnPE(data=assembly)
        for resource in pe.net.resources:
            if str(resource.name) == RESOURCE_NAME:
                if not isinstance(resource.data, bytes):
                    raise ValueError("Profile resource is not raw bytes")
                return resource.data
    finally:
        logger.setLevel(old_level)
    raise ValueError(f"Missing resource {RESOURCE_NAME}")


def decode_shift_stream2(encoded: bytes) -> bytes:
    """Native variant 2; absolute stream position starts at zero."""
    return bytes(
        ((value + 69 + pos) if pos & 1 else (value + 168 - pos)) & 255
        for pos, value in enumerate(encoded)
    )


def bson_document(data: bytes, start: int = 0) -> tuple[dict, int]:
    """Parse only the BSON types used by this database; reject other types."""
    length, = struct.unpack_from("<i", data, start)
    end = start + length
    if length < 5 or end > len(data) or data[end - 1] != 0:
        raise ValueError(f"Invalid BSON document at {start}")
    pos = start + 4
    result = {}
    while pos < end - 1:
        kind = data[pos]
        pos += 1
        name_end = data.index(0, pos, end)
        name = data[pos:name_end].decode("utf-8")
        pos = name_end + 1
        if kind == 1:
            value, = struct.unpack_from("<d", data, pos)
            pos += 8
        elif kind == 2:
            size, = struct.unpack_from("<i", data, pos)
            pos += 4
            if size < 1 or pos + size > end or data[pos + size - 1] != 0:
                raise ValueError("Invalid BSON string length")
            value = data[pos:pos + size - 1].decode("utf-8")
            pos += size
        elif kind in (3, 4):
            value, pos = bson_document(data, pos)
            if kind == 4:
                value = [value[str(index)] for index in range(len(value))]
        elif kind == 8:
            if data[pos] not in (0, 1):
                raise ValueError("Invalid BSON boolean")
            value = bool(data[pos])
            pos += 1
        elif kind == 10:
            value = None
        elif kind == 16:
            value, = struct.unpack_from("<i", data, pos)
            pos += 4
        elif kind in (9, 18):
            value, = struct.unpack_from("<q", data, pos)
            pos += 8
        else:
            raise ValueError(f"Unsupported BSON type {kind} at {pos} ({name})")
        if name in result:
            raise ValueError(f"Duplicate BSON key {name}")
        result[name] = value
    if pos != end - 1:
        raise ValueError("BSON element crosses document boundary")
    return result, end


def normalize_signal(raw: dict) -> dict:
    """Keep source fields and add readable decoding and address information."""
    command = raw["CMD"].upper()
    if not re.fullmatch(r"22[0-9A-F]{4}", command):
        raise ValueError(f"Unexpected non-single-DID read: {command}")
    before = raw["BCM"]
    priority = re.search(r"(?:^|;)ATCP([0-9A-F]{2})(?:;|$)", before)
    response = re.search(r"(?:^|;)ATCRA([0-9A-F]{8})(?:;|$)", before)
    if not priority or not response or not re.fullmatch(r"DA[0-9A-F]{2}F1", raw["HDR"]):
        raise ValueError(f"Cannot resolve CAN IDs for {raw['Id']}")
    if raw["UN"] not in UNITS:
        raise ValueError(f"Unknown unit ID: {raw['UN']}")
    if raw["TP"] == 0:
        formula = raw["FR"]
        decoder = "Formula"
    elif raw["TP"] == 1:
        decoder = "ByteSet"
        if not 1 <= raw["DL"] <= 4 or raw["SBI"] < 0 or raw["DIV"] == 0:
            raise ValueError("Unexpected ByteSet decoding fields")
        value = ("s" if raw["SIG"] else "u") + str(raw["DL"] * 8)
        value += ("le" if raw["RBS"] else "be") + f"({raw['SBI']})"
        formula = f"{value} * {raw['MUL']:g} / {raw['DIV']:g} + {raw['OFS']:g}"
    else:
        raise ValueError(f"Unexpected decoder type: {raw['TP']}")
    return {
        "id": raw["Id"], "name": raw["NM"], "command": command,
        "did": command[2:], "header": raw["HDR"],
        "ecu": raw["HDR"][2:4], "request_can_id": priority[1] + raw["HDR"],
        "response_can_id": response[1], "decoder": decoder,
        "offset": raw["SBI"], "length": raw["DL"], "signed": raw["SIG"],
        "little_endian": raw["RBS"], "multiplier": raw["MUL"],
        "divider": raw["DIV"], "addend": raw["OFS"], "formula": formula,
        "unit": UNITS[raw["UN"]], "before_command": before,
        "after_command": raw["ACM"], "raw": raw,
    }


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def export_profile(profile: dict, provenance: dict, output: Path) -> dict:
    if "2021-2025" in profile["Name"]:
        suffix = "2021-2025"
    elif "2026-n.d." in profile["Name"]:
        suffix = "2026-onward"
    else:
        raise ValueError(f"Unknown Ultium profile range: {profile['Name']}")
    signals = [normalize_signal(raw) for raw in json.loads(profile["ProfilePIDs"])]
    ids = [record["id"] for record in signals]
    if len(set(ids)) != len(ids):
        raise ValueError("Duplicate sensor IDs in profile")
    base = output / f"gm-ultium-{suffix}"
    metadata = {key: value for key, value in profile.items() if key not in ("ProfilePIDs", "Tweaks")}
    write_json(base.with_suffix(".json"), {
        "provenance": provenance, "profile": metadata,
        "omitted_profile_fields": ["Tweaks (dashboard presentation and app settings)"],
        "signal_count": len(signals),
        "unique_ecu_did_count": len({(row["ecu"], row["did"]) for row in signals}),
        "signals": signals,
    })
    # UTF-8 BOM lets Windows CSV readers display any non-ASCII labels correctly.
    with base.with_suffix(".csv").open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.DictWriter(stream, fieldnames=[key for key in signals[0] if key != "raw"], lineterminator="\n")
        writer.writeheader()
        writer.writerows({key: value for key, value in row.items() if key != "raw"} for row in signals)
    lines = [
        f"# {profile['Name']}", "", f"{len(signals)} sensor definitions extracted from the supplied APK.", "",
        "These are Car Scanner's definitions, not a statement that every reading is supported or validated on your truck.", "",
        "Offsets are zero-based within the DID data, after ISO-TP reassembly and removal of `62` and the two DID bytes.",
        "`u16be(0)` means an unsigned 16-bit big-endian value at data offset 0; `s` means signed.",
        "For Formula rows, `A`, `B`, `C`, ... are data bytes 0, 1, 2, ...; `@&` is bitwise AND.",
        "The raw scaling is preserved as `raw * multiplier / divider + addend`; units are the profile's native units.",
        "Formula rows use their formula directly; their ByteSet offset/length fields are not decoding instructions.", "",
        "Full adapter setup/restore strings and original sensor fields are in the adjacent JSON/CSV files.",
        "See [the investigation report](README.md) for truck-specific findings and source anomalies.", "",
    ]
    ecu_names = {"CB": "BECM — battery energy control module", "17": "DMCM1 — drive motor control module 1", "1D": "DMCM2 — drive motor control module 2", "28": "EBCM — brake control module"}
    for ecu in ("CB", "17", "1D", "28"):
        rows = [row for row in signals if row["ecu"] == ecu]
        lines.extend([
            f"## {ecu}: {ecu_names[ecu]}", "",
            f"Request CAN ID `{rows[0]['request_can_id']}`; response CAN ID `{rows[0]['response_can_id']}`.", "",
            "| ID | DID | Name | Formula | Unit |", "|---|---|---|---|---|",
        ])
        for row in rows:
            name = row["name"].replace("|", "\\|").replace("\n", " ")
            lines.append(f"| {row['id']} | `{row['did']}` | {name} | `{row['formula']}` | {row['unit']} |")
        lines.append("")
    base.with_suffix(".md").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return {"file_stem": base.name, "name": profile["Name"], "signal_count": len(signals)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("apk", nargs="?", type=Path, help="APK path; defaults to the sole APK beside this script")
    parser.add_argument("--output", type=Path, default=HERE)
    parser.add_argument("--work", type=Path, default=HERE / "work" / "extractor")
    parser.add_argument("--python-libs", type=Path, default=HERE / "work" / "python-libs")
    args = parser.parse_args()
    if args.python_libs.exists():
        sys.path.insert(0, str(args.python_libs.resolve()))
    apk = args.apk
    if apk is None:
        candidates = list(HERE.glob("*.apk"))
        if len(candidates) != 1:
            parser.error("Specify an APK when the script directory does not contain exactly one")
        apk = candidates[0]
    args.output.mkdir(parents=True, exist_ok=True)
    args.work.mkdir(parents=True, exist_ok=True)
    source = apk.read_bytes()
    with zipfile.ZipFile(apk) as archive:
        member = "lib/x86/libassembly-store.so"
        assembly, metadata = extract_assembly(archive.read(member))
    resource = extract_resource(assembly)
    encoded = lzma.decompress(resource)
    decoded = decode_shift_stream2(encoded)
    profiles, end = bson_document(decoded)
    if end != len(decoded):
        raise ValueError("BSON root did not consume entire decoded resource")
    # The source BSON root is an array encoded with sequential decimal keys.
    if list(profiles) != [str(index) for index in range(len(profiles))]:
        raise ValueError("Unexpected BSON root array keys")
    selected = [profile for profile in profiles.values() if "ultium" in profile.get("Name", "").lower()]
    if len(selected) != 2:
        raise ValueError(f"Expected two Ultium profiles; found {len(selected)}")
    provenance = {
        "source_apk": apk.name, "source_apk_sha256": sha256(source),
        "archive_member": member, "resource": RESOURCE_NAME,
        "resource_sha256": sha256(resource), "resource_size": len(resource),
        "decoded_bson_sha256": sha256(decoded), "decoded_bson_size": len(decoded),
        "source_profile_count": len(profiles), "extraction": "XABA v3 -> XALZ/LZ4 -> managed resource -> LZMA -> ShiftStream2 -> BSON -> ProfilePIDs JSON",
        "validation_scope": "Definitions recovered from APK; no vehicle commands sent and no truck verification performed",
        **metadata,
    }
    # The complete database remains a local work artifact, never a public export.
    (args.work / "profiles7.bson").write_bytes(decoded)
    write_json(args.work / "provenance.json", provenance)
    result = [export_profile(profile, provenance, args.output) for profile in selected]
    print(json.dumps({"profiles": result, "apk_sha256": provenance["source_apk_sha256"]}, indent=2))


if __name__ == "__main__":
    main()
