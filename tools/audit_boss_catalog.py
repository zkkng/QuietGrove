"""Extract exact local boss variants, maps, phases and attack metadata (no online IDs)."""
from pathlib import Path
import xml.etree.ElementTree as ET
import json

ROOT = Path(__file__).resolve().parents[1]
ids = {2220000, 3220000, 5220000, 5220001, 6130101, 8130100, 8180000,
       8180001, 6220000, 6300005, 9400121, 9400549, 9400571, 9400575,
       8150000, 8500000, 8500001, 8500002}
strings = ET.parse(ROOT / 'wz/String.wz/Mob.img.xml').getroot()
for node in strings:
    name = node.find("string[@name='name']")
    if name is not None and 'blue mushmom' in name.get('value', '').lower():
        ids.add(int(node.get('name')))
maps = {i: [] for i in ids}
for path in (ROOT / 'wz/Map.wz/Map').glob('Map*/*.img.xml'):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as error:
        print('Unreadable map:', path.relative_to(ROOT), error)
        continue
    for life in root.findall("imgdir[@name='life']/imgdir"):
        mid = life.find("string[@name='id']")
        kind = life.find("string[@name='type']")
        if mid is not None and kind is not None and kind.get('value') == 'm':
            mobid = int(mid.get('value'))
            if mobid in maps:
                maps[mobid].append(int(path.name.split('.')[0]))
out = []
for mid in sorted(ids):
    path = ROOT / f'wz/Mob.wz/{mid:07d}.img.xml'
    if not path.exists():
        continue
    root = ET.parse(path).getroot()
    info = root.find("imgdir[@name='info']")
    values = {n.get('name'): n.get('value') for n in info if n.tag != 'imgdir'}
    attacks = []
    for attack in root:
        if attack.get('name', '').startswith('attack'):
            ainfo = attack.find("imgdir[@name='info']")
            if ainfo is not None:
                attacks.append(ET.tostring(ainfo, encoding='unicode'))
    name = strings.find(f"imgdir[@name='{mid}']/string[@name='name']")
    out.append(dict(id=mid, name=name.get('value') if name is not None else '',
                    maps=sorted(set(maps[mid])), stats=values, attacks=attacks,
                    skills=ET.tostring(info.find("imgdir[@name='skill']"), encoding='unicode')
                    if info.find("imgdir[@name='skill']") is not None else ''))
dest = ROOT / 'docs/boss-catalog-audit.json'
dest.write_text(json.dumps(out, indent=2), encoding='utf-8')
for entry in out:
    s = entry['stats']
    print(entry['id'], entry['name'], 'level=' + s.get('level','?'),
          'hp=' + s.get('maxHP','?'), 'maps=' + str(entry['maps']),
          'attacks=' + str(len(entry['attacks'])))
