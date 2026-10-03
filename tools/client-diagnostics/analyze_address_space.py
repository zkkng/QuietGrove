"""Summarize MINIDUMP_MEMORY_INFO_LIST without publishing captured memory."""
import argparse
import json
from pathlib import Path
import struct


def analyze(path, limit):
    data = path.read_bytes()
    if data[:4] != b"MDMP":
        raise ValueError("Not a minidump")
    count, directory = struct.unpack_from("<II", data, 8)
    for i in range(count):
        kind, size, rva = struct.unpack_from("<III", data, directory+12*i)
        if kind == 16:
            break
    else:
        raise ValueError("Dump has no memory information stream")
    header, entry, count = struct.unpack_from("<IIQ", data, rva)
    if header < 16 or entry < 48 or header+count*entry > size:
        raise ValueError("Invalid memory information stream bounds")
    totals = {"free": 0, "reserved": 0, "committed": 0}
    largest = 0
    for i in range(count):
        base, allocation, allocation_protect, alignment, length, state, protect, kind, alignment = struct.unpack_from("<QQIIQIIII", data, rva+header+i*entry)
        if base >= limit:
            continue
        length = min(length, limit-base)
        label = {0x10000: "free", 0x2000: "reserved", 0x1000: "committed"}.get(state)
        if label:
            totals[label] += length
        if label == "free":
            largest = max(largest, length)
    return {"limit": limit, "bytes": totals, "largestFreeRegionBytes": largest}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dump", type=Path)
    args = parser.parse_args()
    print(json.dumps([analyze(args.dump, limit) for limit in (0x80000000, 0x100000000)], indent=2))
