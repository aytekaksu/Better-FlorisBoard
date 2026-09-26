#!/usr/bin/env python3
"""Check that every source ART profile rule names code in a built APK."""

import argparse
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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, action="append", required=True, help="APK to inspect; repeat for each variant")
    parser.add_argument("--profile", type=Path, required=True, help="source baseline-prof.txt")
    parser.add_argument("--pruned-output", type=Path, help="write live rules to a new file")
    args = parser.parse_args()

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
    for line in args.profile.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
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
