"""Record the exact WZ spawn layout for WORLD-01's ordinary-map client fixture."""

from __future__ import annotations

import argparse
import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path


DEFAULT_MAPS = (100020000, 102020000, 100040000)


def audit_map(root: Path, map_id: int) -> dict:
    path = root / f"wz/Map.wz/Map/Map{map_id // 100000000}/{map_id}.img.xml"
    raw = path.read_bytes()
    tree = ET.fromstring(raw)
    life = tree.find("./imgdir[@name='life']")
    if life is None:
        raise ValueError(f"Missing life section: {path}")
    mobs = []
    for node in life:
        values = {entry.get("name"): entry.get("value") for entry in node}
        if values.get("type") == "m":
            mobs.append(values)
    return {
        "mapId": map_id,
        "path": path.relative_to(root).as_posix(),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "staticMonsterPoints": len(mobs),
        "zeroMobTimePoints": sum(int(mob.get("mobTime", "0")) == 0 for mob in mobs),
        "mobIds": sorted({int(mob["id"]) for mob in mobs}),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=Path)
    parser.add_argument("map_ids", nargs="*", type=int, default=DEFAULT_MAPS)
    args = parser.parse_args()
    result = {"kind": "world01-static-spawn-fixture", "maps":
              [audit_map(args.root.resolve(), map_id) for map_id in args.map_ids]}
    encoded = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    else:
        print(encoded, end="")


if __name__ == "__main__":
    main()
