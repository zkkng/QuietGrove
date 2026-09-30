"""Generate clearly isolated, synthetic v1 snapshots for the website contract tests."""
import hashlib, json, os
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "tools/world-statistics/fixtures"
(OUT / "shards").mkdir(parents=True,exist_ok=True)
(OUT / "manifests").mkdir(exist_ok=True)
SNAPSHOT = "ws1-fixture-v1"
SCOPE = {"world":"all","population":"all","assistance":"all"}
PERIOD = {"kind":"since_start","from":"2026-09-30T00:00:00Z","to":"2026-09-30T00:00:05Z"}
COVERAGE = {"state":"complete","sourceCoverage":"isolated-synthetic-fixture","gaps":[]}
METRICS = [
    ("use.units_consumed","USE items consumed","units","item",[("2000000","Red Potion","recovery_hp",9007199254741000),("2060000","Arrow for Bow","ammunition_arrows",27)]),
    ("monster.defeats","Monsters defeated","deaths","monster",[("100100","Snail","ordinary",7)]),
    ("quest.completions","Quests completed","completions","quest",[("1000","Borrowing Sera's Mirror","quest",3)]),
    ("player.deaths","Adventurer deaths","deaths","cause",None),
    ("pq.runs_cleared","PQ runs cleared","runs","activity",[("1","Kerning Party Quest","pq",5)]),
    ("pq.participant_clears","PQ participant completions","participations","activity",[("1","Kerning Party Quest","pq",20)]),
    ("jq.finishes","JQ finishes","finishes","activity",[]),
]
def descriptor(payload, metric=None, projection=None):
    payload = {"schemaVersion":1,"snapshotId":SNAPSHOT,"fixture":True,**payload}
    raw = (json.dumps(payload,sort_keys=True,separators=(",",":"),ensure_ascii=False)+"\n").encode()
    sha = hashlib.sha256(raw).hexdigest()
    path = OUT / "shards" / (sha+".json")
    if path.exists() and path.read_bytes()!=raw:
        raise RuntimeError("Immutable shard mismatch")
    path.write_bytes(raw)
    return {"path":"shards/"+sha+".json","sha256":sha,"bytes":len(raw),"metricId":metric,
            "projection":projection,"scope":SCOPE,"period":PERIOD}
metric_catalog, entities, cards, shards = [], [], [], {}
for key,title,unit,kind,observations in METRICS:
    coverage = COVERAGE if observations is not None else {"state":"not_connected","sourceCoverage":"unavailable-fixture-source","gaps":[]}
    availability = "available" if observations is not None else "not_connected"
    metric_catalog.append({"id":key,"title":title,"unit":unit,"entityKind":kind,"definitionVersion":1,
                          "availability":availability,"coverage":coverage,
                          "supportedFilters":{"world":["all"],"population":["all"],"assistance":["all"],"period":["since_start"]},
                          "supportedBreakdowns":["entity","category"] if observations is not None else []})
    total = str(sum(row[3] for row in observations)) if observations is not None else None
    cards.append({"metric":key,"value":total,"unit":unit,"availability":availability,"coverage":coverage})
    rows = []
    for ident,name,category,value in observations or []:
        entities.append({"kind":kind,"id":ident,"name":name,"category":category,"region":"unknown"})
        rows.append({"entityId":ident,"name":name,"category":category,"value":str(value)})
    rows.sort(key=lambda row:(-int(row["value"]),int(row["entityId"])))
    shards[key] = {
        "entities":descriptor({"metric":key,"unit":unit,"total":total,"availability":availability,
                               "coverage":coverage,"scope":SCOPE,"period":PERIOD,"rows":rows},key,"entity"),
        "series":descriptor({"metric":key,"unit":unit,"availability":availability,"coverage":coverage,"bucket":"day",
                             "scope":SCOPE,"period":PERIOD,
                             "rows":[{"from":PERIOD["from"],"to":PERIOD["to"],"value":total}] if total is not None else []},key,"total")}
catalog = descriptor({"catalogVersion":"ws1-fixture-catalog-v1","metrics":metric_catalog,"entities":entities},None,"catalog")
overview = descriptor({"scope":SCOPE,"period":PERIOD,"cards":cards,"coverage":COVERAGE},None,"overview")
manifest = {"schemaVersion":1,"snapshotId":SNAPSHOT,"epoch":"isolated-fixture",
            "fixture":True,"generatedAt":"2026-09-30T00:00:06Z","dataThrough":PERIOD["to"],
            "trackingStartedAt":PERIOD["from"],"definitionVersion":1,"catalogVersion":"ws1-fixture-catalog-v1",
            "coverage":COVERAGE,"scope":SCOPE,"period":PERIOD,
            "shards":{"catalog":catalog,"overview":overview,"metrics":shards}}
raw = (json.dumps(manifest,indent=2)+"\n").encode()
archive = OUT / "manifests" / (SNAPSHOT+".json")
archive.write_bytes(raw)
pending = OUT / "manifest.json.pending"
pending.write_bytes(raw)
os.replace(pending,OUT/"manifest.json")
print(json.dumps({"fixture":True,"snapshotId":SNAPSHOT,"metrics":len(METRICS),"path":str(OUT)},indent=2))
