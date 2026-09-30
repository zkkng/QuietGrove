"""Offline candidate inventory and content catalog audit; no game writes or deployment."""
import hashlib, json, re, xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "tools/world-statistics/output"
OUT.mkdir(parents=True, exist_ok=True)

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def save(name, value):
    raw = (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode()
    (OUT / name).write_bytes(raw)

def read_xml(relative):
    raw = (ROOT / relative).read_bytes()
    return ET.fromstring(raw), {"path": relative, "sha256": digest(raw)}

candidates, inputs = [], []
patterns = {
    "inventory_removal": re.compile(r"(?:\bremoveFromSlot|\bremoveById|\.removeItem|\.removeSlot)\s*\("),
    "script_item_mutation": re.compile(r"\.(?:gainItem|removeAll|consumeItem|gainItemById)\s*\("),
    "quest_transition": re.compile(r"\.(?:forceComplete|updateQuestStatus|completeQuest)\s*\("),
    "activity_transition": re.compile(r"\.(?:setEventCleared|playerKilled|disposeInstance)\s*\("),
    "monster_transition": re.compile(r"\.(?:killMonster|killBy|setHpZero)\s*\("),
}
for directory, glob in [("src/main/java", "*.java"), ("scripts", "*.js")]:
    for path in sorted((ROOT / directory).rglob(glob)):
        raw = path.read_bytes()
        text = raw.decode("utf-8", errors="replace")
        rows = []
        lines = text.splitlines()
        for number, line in enumerate(lines, 1):
            if line.lstrip().startswith(("//", "*", "/*")):
                continue
            for kind, pattern in patterns.items():
                for match in pattern.finditer(line):
                    rows.append({
                        "kind": kind, "line": number, "column": match.start() + 1,
                        "excerpt": line.strip()[:320], "adapterState": "unmapped",
                        "requiresSemanticReview": True,
                        "explicitUseNearby": any("InventoryType.USE" in value for value in lines[max(0,number-4):number+3])
                    })
        if rows:
            relative = path.relative_to(ROOT).as_posix()
            inputs.append({"path": relative, "sha256": digest(raw)})
            for row in rows:
                row.update(path=relative, sourceSha256=digest(raw))
                candidates.append(row)
activities = []
for path in sorted((ROOT / "scripts/event").glob("*.js")):
    raw = path.read_bytes()
    text = raw.decode("utf-8", errors="replace")
    key = path.stem
    hint = "pq_candidate" if re.search(r"PQ|GuildQuest", key) else "other_event_candidate"
    activities.append({"id": key, "kindHint": hint, "path": path.relative_to(ROOT).as_posix(),
                       "sha256": digest(raw), "adapterState": "unmapped",
                       "signals": sorted(set(re.findall(r"\b(?:setEventCleared|playerEntry|playerDead|playerExit|clearPQ|dispose)\b", text)))})
for key, names in {
    "shumi": ["1052008","1052009","1052010"],
    "sabitrama": ["1043000","1043001"],
    "john": ["1063000","1063001","1063002"],
    "puppeteer": ["1063016","1063017"],
}.items():
    sources = []
    for name in names:
        path = ROOT / f"scripts/npc/{name}.js"
        if path.exists():
            sources.append({"path": path.relative_to(ROOT).as_posix(), "sha256": digest(path.read_bytes())})
    activities.append({"id": "jq."+key, "kindHint": "jq_candidate", "sources": sources,
                       "adapterState": "unmapped", "requiresSemanticReview": True})

item_root, item_source = read_xml("wz/String.wz/Consume.img.xml")
quest_root, quest_source = read_xml("wz/Quest.wz/QuestInfo.img.xml")
category_root, category_source = read_xml("wz/Etc.wz/QuestCategory.img.xml")
mob_root, mob_source = read_xml("wz/String.wz/Mob.img.xml")
categories = {node.get("name"): node.get("value") for node in category_root}
anomalies, items, quests, monsters = [], [], [], []
seen = set()
for node in item_root.findall("imgdir"):
    ident = node.get("name","")
    name = node.find("string[@name='name']")
    if not ident.isdigit():
        anomalies.append({"kind":"non_numeric_use_id","id":ident,"source":item_source}); continue
    item_id = str(int(ident))
    if item_id in seen:
        anomalies.append({"kind":"duplicate_use_id","id":item_id,"source":item_source})
    seen.add(item_id)
    label = name.get("value") if name is not None else None
    if not label:
        anomalies.append({"kind":"missing_use_name","id":item_id,"source":item_source})
    items.append({"kind":"item","id":item_id,"name":label or "Unknown USE item #"+item_id,
                  "inventory":"USE","category":"unclassified_use","categoryState":"unmapped","region":"unknown"})
# Verify every actual Consume asset has a catalog name; preserve string-only entries as warnings.
asset_ids = set()
asset_sources = []
for path in sorted((ROOT / "wz/Item.wz/Consume").glob("*.xml")):
    raw = path.read_bytes()
    asset_sources.append({"path":path.relative_to(ROOT).as_posix(),"sha256":digest(raw)})
    try:
        tree = ET.fromstring(raw)
        for node in tree.findall("imgdir"):
            ident = node.get("name","")
            if ident.isdigit():
                asset_ids.add(str(int(ident)))
    except ET.ParseError as error:
        anomalies.append({"kind":"consume_asset_parse_error","source":asset_sources[-1],"detail":str(error)})
for ident in sorted(asset_ids - seen, key=int):
    anomalies.append({"kind":"consume_asset_missing_string","id":ident,"source":item_source})
    items.append({"kind":"item","id":ident,"name":"Unknown USE item #"+ident,
                  "inventory":"USE","category":"unclassified_use","categoryState":"unmapped","region":"unknown"})
for ident in sorted(seen - asset_ids, key=int):
    anomalies.append({"kind":"consume_string_without_asset","id":ident,"source":item_source})
quest_seen = set()
for node in quest_root.findall("imgdir"):
    ident = node.get("name","")
    if not ident.isdigit():
        anomalies.append({"kind":"non_numeric_quest_id","id":ident,"source":quest_source}); continue
    ident = str(int(ident))
    if ident in quest_seen:
        anomalies.append({"kind":"duplicate_quest_id","id":ident,"source":quest_source})
    quest_seen.add(ident)
    name = node.find("string[@name='name']")
    area = node.find("int[@name='area']")
    category_id = area.get("value") if area is not None else None
    category_label = categories.get(category_id)
    if category_id is not None and category_label in (None, "", "empty"):
        anomalies.append({"kind":"quest_category_unresolved","id":ident,"categoryId":category_id,"source":quest_source})
    label = name.get("value") if name is not None else None
    if not label:
        anomalies.append({"kind":"quest_name_missing","id":ident,"source":quest_source})
    quests.append({"kind":"quest","id":ident,"name":label or "Unknown quest #"+ident,
                   "categoryId":category_id,"category":category_label,
                   "region":"unknown","regionState":"unmapped","regionProvenance":"quest_category_is_not_a_continent_assignment"})
for node in mob_root.findall("imgdir"):
    ident = node.get("name","")
    label = node.find("string[@name='name']")
    if ident.isdigit():
        monsters.append({"kind":"monster","id":str(int(ident)),
                         "name":label.get("value") if label is not None else "Unknown monster #"+ident})
catalog = {"schemaVersion":1,"catalogVersion":"ws0-"+digest(json.dumps([items,quests,monsters],sort_keys=True).encode())[:16],
           "sources":[item_source,quest_source,category_source,mob_source]+asset_sources,
           "items":sorted(items,key=lambda row:int(row["id"])),
           "quests":sorted(quests,key=lambda row:int(row["id"])),
           "monsters":sorted(monsters,key=lambda row:int(row["id"])),"anomalies":anomalies}
manifest = {"schemaVersion":1,"goalIds":["SITE-06","CONTENT-01"],"coverageState":"unmapped",
            "note":"Static candidates only. Dynamic script calls, negative quantities and direct mutation wrappers require semantic review. No adapter is connected.",
            "sources":inputs,"candidates":candidates,"activities":activities}
save("source-coverage.json", manifest)
save("content-catalog.json", catalog)
summary = {"mutationAndTransitionCandidates":len(candidates),"sourceFiles":len(inputs),
           "activityCandidates":len(activities),"useCatalogItems":len(items),"consumeAssetItems":len(asset_ids),
           "quests":len(quests),"monsters":len(monsters),"catalogAnomalies":len(anomalies),"catalogVersion":catalog["catalogVersion"]}
save("audit-summary.json", summary)
print(json.dumps(summary, indent=2))
