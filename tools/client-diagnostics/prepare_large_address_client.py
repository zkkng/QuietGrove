"""Prepare the pinned playable v83 EXE for a 4 GB address space on 64-bit Windows.

Only IMAGE_FILE_LARGE_ADDRESS_AWARE changes. No code, resources, signature,
launcher or trainer DLL is modified. Installation and backup are separate.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

ORIGINAL_SHA256 = "ed5a699407b9705528b6a653cdee7395e6e1b7317b9711dcfb34681092072848"


def prepare(data):
    if hashlib.sha256(data).hexdigest() != ORIGINAL_SHA256:
        raise ValueError("Unexpected client identity; refusing patch")
    if data[:2] != b"MZ":
        raise ValueError("Missing DOS header")
    pe = struct.unpack_from("<I", data, 60)[0]
    if data[pe:pe+4] != b"PE\0\0":
        raise ValueError("Missing PE header")
    if struct.unpack_from("<H", data, pe+4)[0] != 0x14c:
        raise ValueError("Not an x86 client")
    if struct.unpack_from("<H", data, pe+24)[0] != 0x10b:
        raise ValueError("Not PE32")
    at = pe+22
    before = struct.unpack_from("<H", data, at)[0]
    if before != 0x10f:
        raise ValueError("Unexpected image characteristics")
    patched = bytearray(data)
    struct.pack_into("<H", patched, at, before | 0x20)
    differences = [i for i, pair in enumerate(zip(data, patched)) if pair[0] != pair[1]]
    if differences != [at] or patched[at] != data[at] | 0x20:
        raise ValueError("Unexpected patch extent")
    restored = bytearray(patched)
    struct.pack_into("<H", restored, at, before)
    if bytes(restored) != data:
        raise ValueError("Patch does not reverse to exact original")
    return bytes(patched), at


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.source.resolve() == args.output.resolve():
        raise ValueError("Preparation must not overwrite the playable EXE")
    patched, at = prepare(args.source.read_bytes())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(patched)
    print(json.dumps({"sourceSha256": ORIGINAL_SHA256, "candidateSha256": hashlib.sha256(patched).hexdigest(),
                      "changedBytes": 1, "offset": at, "characteristicsBefore": "0x010F",
                      "characteristicsAfter": "0x012F", "reversible": True}, indent=2))
