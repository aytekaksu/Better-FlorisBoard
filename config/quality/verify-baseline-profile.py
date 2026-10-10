#!/usr/bin/env python3
"""Check that every source ART profile rule names code in a built APK."""

from __future__ import annotations

import argparse
import hashlib
import re
import struct
import zipfile
from pathlib import Path


def u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def uleb128(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7f) << shift
        if byte < 128:
            return value, offset
    raise ValueError("Invalid DEX ULEB128 value")


def dex_definitions(data: bytes) -> tuple[set[str], set[str]]:
    if not data.startswith(b"dex\n"):
        raise ValueError("APK contains a non-DEX classes entry")
    strings = []
    for index in range(u32(data, 0x38)):
        offset = u32(data, u32(data, 0x3c) + index * 4)
        _, offset = uleb128(data, offset)
        strings.append(data[offset : data.index(b"\0", offset)].decode("utf-8", "replace"))

    types = [strings[u32(data, u32(data, 0x44) + i * 4)] for i in range(u32(data, 0x40))]
    protos = []
    for index in range(u32(data, 0x48)):
        base = u32(data, 0x4c) + index * 12
        parameters = u32(data, base + 8)
        arguments = ""
        if parameters:
            arguments = "".join(
                types[u16(data, parameters + 4 + i * 2)] for i in range(u32(data, parameters))
            )
        protos.append(f"({arguments}){types[u32(data, base + 4)]}")

    method_ids = []
    for index in range(u32(data, 0x58)):
        base = u32(data, 0x5c) + index * 8
        method_ids.append(
            f"{types[u16(data, base)]}->{strings[u32(data, base + 4)]}{protos[u16(data, base + 2)]}"
        )

    classes: set[str] = set()
    methods: set[str] = set()
    for index in range(u32(data, 0x60)):
        base = u32(data, 0x64) + index * 32
        owner = types[u32(data, base)]
        classes.add(owner)
        offset = u32(data, base + 24)
        if not offset:
            continue
        static_fields, offset = uleb128(data, offset)
        instance_fields, offset = uleb128(data, offset)
        direct_methods, offset = uleb128(data, offset)
        virtual_methods, offset = uleb128(data, offset)
        for _ in range(static_fields + instance_fields):
            _, offset = uleb128(data, offset)
            _, offset = uleb128(data, offset)
        for count in (direct_methods, virtual_methods):
            method_index = 0
            for _ in range(count):
                delta, offset = uleb128(data, offset)
                method_index += delta
                _, offset = uleb128(data, offset)  # access flags
                _, offset = uleb128(data, offset)  # code offset
                method = method_ids[method_index]
                if not method.startswith(owner + "->"):
                    raise ValueError(f"DEX method belongs to another class: {method}")
                methods.add(method)
    return classes, methods


def wildcard_owners(lines: list[str], expanded: Path | None) -> dict[str, str]:
    """Check compact selectors against reviewed effective AGP coverage."""
    selectors = {}
    owners = set()
    headers = [line for line in lines if line.startswith("# wildcard-coverage")]
    for line in lines:
        if line.startswith("#") or not any(char in line for char in "*?"):
            continue
        match = re.fullmatch(r"(H?S?P?)(L[A-Za-z0-9_/$]+;)->\*\*\(\*\*\)\*\*", line)
        if not match or not match[1]:
            raise ValueError(f"Unsupported wildcard rule: {line}")
        if match[2] in owners:
            raise ValueError(f"Duplicate wildcard owner: {match[2]}")
        selectors[line] = match[2]
        owners.add(match[2])
    if not selectors:
        if headers:
            raise ValueError("Wildcard coverage header without selectors")
        return selectors
    if len(headers) != 1:
        raise ValueError("Exactly one wildcard coverage header is required")
    expected = re.fullmatch(
        r"# wildcard-coverage-v1 classes=(0|[1-9][0-9]*) methods=(0|[1-9][0-9]*) sha256=([0-9a-f]{64})",
        headers[0],
    )
    if not expected:
        raise ValueError("Malformed wildcard coverage header")
    if expanded is None:
        raise ValueError("Wildcard rules require --expanded-profile from AGP")
    rules = {}
    covered = set()
    for line in expanded.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"(H?S?P?)(L[^;\s*?]+;)(->[^\s*?]+\([^\s*?]*\)[^\s*?]+)?", line)
        if not match or bool(match[1]) != bool(match[3]):
            raise ValueError(f"Malformed expanded rule: {line}")
        if match[2] not in owners:
            continue
        method = match[3] is not None
        if method:
            covered.add(match[2])
        mask = sum(value for flag, value in (("H", 1), ("S", 2), ("P", 4)) if flag in match[1]) if method else 2
        key = f"{'M' if method else 'C'}\t{match[2]}{match[3] or ''}"
        rules[key] = rules.get(key, 0) | mask
    if covered != owners:
        raise ValueError("Wildcard owner has no expanded methods")
    canonical = "".join(f"{key}\t{rules[key]}\n" for key in sorted(rules, key=lambda key: key.encode("utf-8")))
    counts = (sum(key.startswith("C\t") for key in rules), sum(key.startswith("M\t") for key in rules))
    actual = (*counts, hashlib.sha256(canonical.encode("utf-8")).hexdigest())
    if actual != (int(expected[1]), int(expected[2]), expected[3]):
        raise ValueError("Wildcard coverage changed; review a literal control before updating the header")
    return selectors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, action="append", required=True, help="APK to inspect; repeat for each variant")
    parser.add_argument("--profile", type=Path, required=True, help="source baseline-prof.txt")
    parser.add_argument("--expanded-profile", type=Path, help="AGP's exact pre-R8 wildcard expansion")
    parser.add_argument("--pruned-output", type=Path, help="write live rules to a new file")
    args = parser.parse_args()
    lines = args.profile.read_text(encoding="utf-8").splitlines()
    selectors = wildcard_owners(lines, args.expanded_profile)

    classes: set[str] = set()
    methods: set[str] = set()
    for apk_path in args.apk:
        with zipfile.ZipFile(apk_path) as apk:
            dex_names = sorted(name for name in apk.namelist() if re.fullmatch(r"classes\d*\.dex", name))
            if not dex_names:
                raise ValueError(f"No DEX files in {apk_path}")
            for name in dex_names:
                dex_classes, dex_methods = dex_definitions(apk.read(name))
                classes.update(dex_classes)
                methods.update(dex_methods)

    live = []
    stale = []
    for line in lines:
        if not line or line.startswith("#"):
            live.append(line)
            continue
        if line in selectors:
            if selectors[line] not in classes:
                raise ValueError(f"Wildcard owner missing from APKs: {selectors[line]}")
            live.append(line)
            continue
        descriptor = re.sub(r"^[HSP]+", "", line)
        if descriptor in (methods if "->" in descriptor else classes):
            live.append(line)
        else:
            stale.append(line)

    print(f"Baseline profile: {len(live)} live, {len(stale)} stale rules")
    if args.pruned_output:
        if args.pruned_output.exists():
            parser.error(f"refusing to overwrite {args.pruned_output}")
        args.pruned_output.write_text("\n".join(live) + "\n", encoding="utf-8")
        print(f"Wrote {args.pruned_output}")
        return 0
    for rule in stale[:10]:
        print(f"Stale: {rule}")
    return 1 if stale else 0


if __name__ == "__main__":
    raise SystemExit(main())
