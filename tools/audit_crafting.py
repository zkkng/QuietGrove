"""Validate the TCG catalog and constant legacy recipe tables against server WZ XML.

Run from the server directory: python tools/audit_crafting.py --output audit.json
This is a data audit, not a substitute for playing the NPC dialogs in a v83 client.
"""
import argparse
import ast
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET


def array_at(text, start):
    depth = 0
    for i in range(start, len(text)):
        if text[i] == '[':
            depth += 1
        elif text[i] == ']':
            depth -= 1
            if depth == 0:
                return ast.literal_eval(text[start:i + 1])
    raise ValueError('Unclosed recipe array')


def run(root):
    issues, offers, legacy, required = [], [], [], set()
    for number, line in enumerate((root / 'scripts/npc/data/tcg-catalog.tsv').read_text().splitlines(), 1):
        text = line.split('#', 1)[0].strip()
        if not text:
            continue
        group, item, count, cost, days, level, ingredients = text.split('|')
        item, count, cost = int(item), int(count), int(cost)
        mats = [list(map(int, p.split(':'))) for p in ingredients.split(',') if p]
        offers.append(dict(line=number, group=group, item=item, quantity=count, mesos=cost, ingredients=mats))
        required.add(item)
        required.update(i for i, q in mats)

    for path in sorted((root / 'scripts/npc').glob('*.js')):
        text = re.sub(r'/\*.*?\*/|//[^\n]*', '', path.read_text(encoding='utf-8-sig'), flags=re.S)
        if 'var matSet' not in text:
            continue
        arrays = []
        for match in re.finditer(r'\b(?:var\s+)?(matSet|matQtySet)\s*=\s*(\[)', text):
            try:
                value = array_at(text, match.start(2))
            except (ValueError, SyntaxError):
                continue
            arrays.append((match.group(1), value))
        tables = 0
        for index, (name, mats) in enumerate(arrays):
            if name != 'matSet' or index + 1 >= len(arrays):
                continue
            next_name, quantities = arrays[index + 1]
            if next_name != 'matQtySet':
                continue
            tables += 1
            if len(mats) != len(quantities):
                issues.append(f'{path.name} recipe table {tables}: item/quantity row count mismatch')
            for ids, counts in zip(mats, quantities):
                ids = ids if isinstance(ids, list) else [ids]
                counts = counts if isinstance(counts, list) else [counts]
                if len(ids) != len(counts):
                    issues.append(f'{path.name} recipe table {tables}: ingredient quantity mismatch {ids}/{counts}')
                required.update(ids)
                if any(not isinstance(q, int) or q <= 0 for q in counts):
                    issues.append(f'{path.name}: invalid ingredient quantity {counts}')
        legacy.append(dict(npc=path.stem, constant_recipe_tables=tables))

    exercised_routes = 0
    for path in (root / 'target/crafting-routes').glob('*.json'):
        for transfers in json.loads(path.read_text()):
            exercised_routes += 1
            required.update(item for item, quantity in transfers if isinstance(item, int))

    # Only open images containing required IDs; do not parse every visual frame in Character.wz.
    data = {}
    for path in (root / 'wz/Character.wz').glob('*/*.img.xml'):
        stem = path.name.split('.')[0]
        if stem.isdigit() and int(stem) in required:
            data[int(stem)] = ET.parse(path).getroot()
    for path in (root / 'wz/Item.wz').glob('*/*.img.xml'):
        stem = path.name.split('.')[0]
        if path.parent.name == 'Pet':
            if stem.isdigit() and int(stem) in required:
                data[int(stem)] = ET.parse(path).getroot()
        elif any(str(i).zfill(8).startswith(stem) for i in required if i >= 2000000):
            for node in ET.parse(path).getroot():
                name = node.get('name', '')
                if name.isdigit() and int(name) in required:
                    data[int(name)] = node
    for item in sorted(required - data.keys()):
        issues.append(f'Missing WZ item {item}')
    for offer in offers:
        info = data.get(offer['item'])
        if info is None:
            continue
        info = info.find("imgdir[@name='info']")
        if info is None:
            issues.append(f"No info for {offer['item']}")
            continue
        props = {p.get('name'): p.get('value') for p in info}
        slot_max = int(props.get('slotMax', 1 if offer['item'] < 2000000 else 100))
        if offer['quantity'] > slot_max:
            issues.append(f"Offer {offer['line']} exceeds WZ stack maximum {slot_max}")
        sell = float(props.get('price', 0)) * offer['quantity']
        if offer['group'].startswith('Buy') and sell >= offer['mesos']:
            issues.append(f"Offer {offer['line']} can be sold back for at least its price: {sell}")

    map_path = root / 'wz/Map.wz/Map/Map1/100000000.img.xml'
    town = ET.parse(map_path).getroot()
    life = town.find("imgdir[@name='life']")
    spawns = [n for n in life if n.find("string[@name='id']") is not None and n.find("string[@name='id']").get('value') == '9201082']
    if len(spawns) != 1:
        issues.append('Henesys must contain exactly one Spindle')
    else:
        s = {p.get('name'): p.get('value') for p in spawns[0]}
        fh = town.find("imgdir[@name='foothold']").find(f".//imgdir[@name='{s['fh']}']")
        f = {p.get('name'): int(p.get('value')) for p in fh}
        if not f['x1'] <= int(s['x']) <= f['x2'] or int(s['cy']) != f['y1'] or f['y1'] != f['y2']:
            issues.append('Spindle position is not on its declared flat foothold')
    return dict(shop_offers=sum(o['group'].startswith('Buy') for o in offers),
                crafting_recipes=sum(o['group'].startswith('Craft') for o in offers),
                validated_wz_items=len(data), exercised_legacy_routes=exercised_routes,
                legacy_npcs=legacy, issues=issues)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    report = run(args.root)
    result = json.dumps(report, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(result + '\n', encoding='utf-8')
    print(result)
    raise SystemExit(bool(report['issues']))
