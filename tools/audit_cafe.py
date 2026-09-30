"""Check every restored cafe offer, Premium Road map, and eraser quest material against WZ XML."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def props(node):
    return {n.get('name'): n.get('value') for n in node} if node is not None else {}


def audit(root):
    issues, offers, items = [], [], {}
    for line in (root/'scripts/npc/data/pc-cafe-rewards.tsv').read_text().splitlines():
        if not line or line.startswith('#'): continue
        currency, item, qty, price, limit = line.split('|')
        offers.append(dict(currency=currency, item=int(item), quantity=int(qty), price=int(price), limit=int(limit)))
    wanted = {o['item'] for o in offers} | {4000047}
    for path in (root/'wz/Item.wz').glob('*/*.img.xml'):
        category = int(path.name.split('.')[0]) if path.name.split('.')[0].isdigit() else -1
        if not any(i//10000 == category for i in wanted): continue
        for node in ET.parse(path).getroot():
            if node.get('name','').isdigit() and int(node.get('name')) in wanted:
                items[int(node.get('name'))] = props(node.find("./imgdir[@name='info']"))
    for item in wanted:
        if not items.get(item): issues.append(f'Missing item data: {item}')
    for offer in offers:
        item = items.get(offer['item'], {})
        if offer['quantity'] > int(item.get('slotMax', 1 if offer['item']//1000000 == 3 else 100)):
            issues.append(f'Stack overflow: {offer}')
        if offer['currency'] == 'MESOS' and float(item.get('price',0))*offer['quantity'] >= offer['price']:
            issues.append(f'Vending resale loop: {offer}')
    roads = [190000000,190000001,190000002,191000000,191000001,192000000,192000001,
             195000000,195010000,195020000,195030000,196000000,196010000,197000000,197010000]
    maps = []
    for id in roads:
        path = root/f'wz/Map.wz/Map/Map1/{id}.img.xml'
        if not path.exists(): issues.append(f'Missing map {id}'); continue
        data = ET.parse(path).getroot()
        info = props(data.find("./imgdir[@name='info']"))
        lives = [props(n) for n in data.find("./imgdir[@name='life']")]
        portals = [props(n) for n in data.find("./imgdir[@name='portal']")]
        mobs = [life for life in lives if life.get('type') == 'm']
        if not mobs: issues.append(f'No monsters on map {id}')
        if info.get('returnMap') != '193000000': issues.append(f'No cafe return scroll destination for {id}')
        if not any(p.get('pn') == 'sp' for p in portals): issues.append(f'No arrival spawn for {id}')
        for portal in portals:
            target = int(portal.get('tm',999999999))
            if target != 999999999 and not (root/f'wz/Map.wz/Map/Map{target//100000000}/{target:09}.img.xml').exists():
                issues.append(f'Missing portal destination {target} from {id}')
        maps.append(dict(map=id,spawn_count=len(mobs),mob_ids=sorted({int(m['id']) for m in mobs})))
    hub = ET.parse(root/'wz/Map.wz/Map/Map1/193000000.img.xml').getroot()
    ids = {int(props(n).get('id',0)) for n in hub.find("./imgdir[@name='life']")}
    if not {1052013,1052014,1052015}.issubset(ids): issues.append('Cafe is missing a core NPC')
    checks = ET.parse(root/'wz/Quest.wz/Check.img.xml').getroot()
    for quest in [9414,9415]:
        needed = {int(props(n)['id']) for n in checks.findall(f"./imgdir[@name='{quest}']/imgdir/imgdir[@name='item']/imgdir")}
        if not needed.issubset(wanted): issues.append(f'Quest {quest} still has unavailable materials')
    return dict(offers=offers,validated_items=len(items),maps=maps,issues=issues)


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path('.'))
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args(); report=audit(args.root)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2)+'\n')
    print(f"Cafe audit: {len(report['offers'])} offers, {report['validated_items']} WZ items, {len(report['maps'])} hunting maps")
    print(json.dumps(report['issues'],indent=2))
    raise SystemExit(bool(report['issues']))
