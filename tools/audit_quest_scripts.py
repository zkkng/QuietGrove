"""Inventory WZ-script links without starting the server or a database.

This is a triage report, not proof that every listed quest is playable. It
recognizes QuestScriptManager's medal fallback and ignores data-only quests.
"""

import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def child(node, name):
    return next((entry for entry in node if entry.get("name") == name), None) if node is not None else None


def value(node, name, default=None):
    entry = child(node, name)
    return entry.get("value", default) if entry is not None else default


def audit(root, as_of):
    quest_wz = root / "wz" / "Quest.wz"
    checks = ET.parse(quest_wz / "Check.img.xml").getroot()
    info = {entry.get("name"): entry for entry in ET.parse(quest_wz / "QuestInfo.img.xml").getroot()}
    scripts = root / "scripts" / "quest"
    rows = []
    for quest in checks:
        quest_id = quest.get("name")
        metadata = info.get(quest_id)
        end = value(child(quest, "0"), "end")
        expired = False
        if end:
            try:
                expired = datetime.strptime(end, "%Y%m%d%H") < as_of
            except ValueError:
                pass  # Keep malformed dates visible for manual investigation.
        for stage, entry in (("0", "start"), ("1", "end")):
            script_flag = value(child(quest, stage), entry + "script")
            if not script_flag:
                continue
            script = scripts / (quest_id + ".js")
            status = "dedicated_script"
            if not script.exists():
                if value(metadata, "viewMedalItem", "0") != "0":
                    script = scripts / "medalQuest.js"
                    status = "generic_medal_fallback"
                elif expired:
                    status = "expired_event_missing_script"
                else:
                    status = "missing_script_review"
            if status in ("dedicated_script", "generic_medal_fallback"):
                if not script.exists() or not re.search(
                    r"\bfunction\s+" + entry + r"\s*\(", script.read_text(encoding="utf-8-sig")
                ):
                    status = "missing_entry_function"
            rows.append({
                "id": int(quest_id), "name": value(metadata, "name", ""),
                "stage": entry, "wz_script": script_flag, "status": status,
                "wz_end": end,
                "script": script.relative_to(root).as_posix() if script.exists() else None,
            })
    return {
        "as_of": as_of.isoformat(), "quest_count": len(checks),
        "scripted_stages": len(rows), "counts": dict(Counter(row["status"] for row in rows)),
        "stages": rows,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--as-of", default=datetime.now().strftime("%Y-%m-%d"), help="YYYY-MM-DD")
    parser.add_argument("--output", type=Path, help="Optional JSON report path")
    args = parser.parse_args()
    report = audit(args.root.resolve(), datetime.strptime(args.as_of, "%Y-%m-%d"))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: val for key, val in report.items() if key != "stages"}, indent=2))
    for row in report["stages"]:
        if row["status"] in ("missing_script_review", "missing_entry_function"):
            print(f'{row["id"]} {row["stage"]}: {row["name"]} ({row["status"]})')


if __name__ == "__main__":
    main()
