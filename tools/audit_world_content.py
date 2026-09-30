"""Inventory town NPC routing and Victoria Island quest dependencies from local v83 data.

Run from the server root. Reports separate static coverage from runtime verification.
"""
import argparse
from collections import Counter, defaultdict
from datetime import datetime
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def child(node, name):
    return next((n for n in node if n.get('name') == name), None) if node is not None else None


def val(node, name, default=''):
    n = child(node, name)
    return n.get('value', default) if n is not None else default


def children(node, name):
    result = child(node, name)
    return list(result) if result is not None else []


def integer(node, name, default=0):
    return int(val(node, name, str(default)))


def strings(path):
    return {int(n.get('name')): {v.get('name'): v.get('value') for v in n if v.tag == 'string'}
            for n in ET.parse(path).getroot().iter('imgdir') if n.get('name', '').isdigit()}


def tuples(path):
    return [tuple(map(int, re.findall(r'-?\d+', line))) for line in path.read_text().splitlines()
            if re.match(r'\s*(?:VALUES\s+)?\(\s*-?\d', line)]


def audit(root, as_of):
    wz = root / 'wz'
    medal_handler = (root / 'scripts/quest/medalQuest.js').read_text(encoding='utf-8')
    medal_placeholder = 'Medals.interact' not in medal_handler or 'forceCompleteQuest' in medal_handler
    npc_names = strings(wz / 'String.wz/Npc.img.xml')
    map_names = strings(wz / 'String.wz/Map.img.xml')
    mob_names = strings(wz / 'String.wz/Mob.img.xml')
    checks = {int(n.get('name')): n for n in ET.parse(wz / 'Quest.wz/Check.img.xml').getroot()}
    info = {int(n.get('name')): n for n in ET.parse(wz / 'Quest.wz/QuestInfo.img.xml').getroot()}
    acts = {int(n.get('name')): n for n in ET.parse(wz / 'Quest.wz/Act.img.xml').getroot()}
    db = root / 'src/main/resources/db/data'
    shops = defaultdict(list)
    for shop, npc in tuples(db / '101-shops-data.sql'):
        shops[npc].append(shop)
    shop_inventory = Counter(row[0] for row in tuples(db / '102-shopitems-data.sql'))
    drops = tuples(db / '152-drop-data.sql')
    reactors = tuples(db / '131-reactordrops-data.sql')
    npc_gates = dict((int(a), int(b)) for a,b in re.findall(r'^\s*(\d+):\s*(\d+)', (root / 'src/main/java/soloMapling/server/npc_versions.yaml').read_text(), re.M))
    map_gates = dict((int(a), int(b)) for a,b in re.findall(r'^\s*(\d+):\s*(\d+)', (root / 'src/main/java/soloMapling/server/portal_versions.yaml').read_text(), re.M))
    version_source = (root / 'src/main/java/soloMapling/server/MapleVersionManager.java').read_text()
    version = int(re.search(r'public static int version\s*=\s*(\d+)', version_source)[1])

    maps, town_npcs, npc_maps, mob_maps = {}, defaultdict(set), defaultdict(set), defaultdict(set)
    victoria_npcs = set()
    for path in sorted((wz / 'Map.wz/Map').glob('Map*/*.img.xml')):
        map_id = int(path.name.split('.')[0])
        data = ET.parse(path).getroot()
        town = integer(child(data, 'info'), 'town') == 1
        maps[map_id] = {'id':map_id, 'name':map_names.get(map_id, {}).get('mapName',''),
                        'street':map_names.get(map_id, {}).get('streetName',''), 'town':town}
        timed = child(child(data, 'info'), 'timeMob')
        if timed is not None and integer(timed, 'id'): mob_maps[integer(timed, 'id')].add(map_id)
        for life in children(data, 'life'):
            id = integer(life, 'id')
            if val(life, 'type') == 'n':
                npc_maps[id].add(map_id)
                if town: town_npcs[id].add(map_id)
                if 100000000 <= map_id < 130000000: victoria_npcs.add(id)
            elif val(life, 'type') == 'm':
                mob_maps[id].add(map_id)

    # Character.raiseQuestMobCount explicitly maps these synthetic WZ counters to real monsters.
    for alias, real in {9101000: [1110100,1110130], 9101001: [2230101,2230131], 9101002: [1140100,1140130], 5220000: [5220001]}.items():
        for mob in real: mob_maps[alias].update(mob_maps[mob])

    quests_by_npc = defaultdict(set)
    for quest, check in checks.items():
        for stage in ('0','1'):
            npc = integer(child(check, stage), 'npc')
            if npc: quests_by_npc[npc].add(quest)

    api = '\n'.join((root / f'src/main/java/scripting/{file}').read_text() for file in
                    ['AbstractPlayerInteraction.java','npc/NPCConversationManager.java'])
    api_names = set(re.findall(r'public\s+(?:static\s+|final\s+|synchronized\s+)*[\w<>?,.\[\] ]+?\s+(\w+)\s*\(', api))
    npc_rows = []
    for npc, locations in sorted(town_npcs.items()):
        path = root / f'scripts/npc/{npc}.js'
        name = npc_names.get(npc, {}).get('name', '')
        findings = []
        script = None
        if npc == 9010009:
            route = 'native_Duey'
        elif 9100100 <= npc <= 9100117:
            route, script = 'shared_gachapon', 'scripts/npc/gachapon.js'
        elif name.endswith('Maple TV'):
            route, script = 'shared_Maple_TV', 'scripts/npc/mapleTV.js'
        elif path.exists():
            script = path.relative_to(root).as_posix()
            source = re.sub(r'/\*.*?\*/|//[^\n]*', '', path.read_text(encoding='utf-8-sig'), flags=re.S)
            calls = set(re.findall(r'\bcm\.(\w+)\s*\(', source))
            if calls <= {'sendOk','sendDefault','dispose'}:
                route = 'flavor_or_quest_dialogue'
            else:
                route = 'scripted'
            for call in sorted(calls - api_names): findings.append('unknown_cm_api:' + call)
            for shop in re.findall(r'cm\.openShop\(\s*(\d+)', source):
                if not shop_inventory[int(shop)]: findings.append('empty_or_missing_shop:' + shop)
            for target in re.findall(r'cm\.warp\(\s*(\d+)', source):
                if int(target) not in maps: findings.append('missing_warp_map:' + target)
        elif shops[npc]:
            route = 'database_shop'
            if not any(shop_inventory[shop] for shop in shops[npc]): findings.append('shop_has_no_seed_items')
        else:
            route, script = 'default_wz_dialogue', 'scripts/npc/default_dialogue.js'
            if not quests_by_npc[npc]: findings.append('no_feature_script_or_shop_review')
        gated = npc_gates.get(npc, 0) > version
        npc_rows.append({'npc':npc,'name':name,'maps':sorted(locations),'route':route,'script':script,
                         'source_version_gated':gated,'release_version':npc_gates.get(npc),
                         'source_portal_gates':{str(m):map_gates[m] for m in locations if map_gates.get(m,0)>version},
                         'quests':sorted(quests_by_npc[npc]),'findings':findings})

    # Keep dynamic spawn references as evidence, not proof that the event completes.
    dynamic_refs = defaultdict(list)
    for script_path in (root / 'scripts').rglob('*.js'):
        text = script_path.read_text(encoding='utf-8-sig')
        for entity in set(map(int, re.findall(r'\b(?:[13569]\d{6}|522000[012]|6220000)\b', text))):
            dynamic_refs[entity].append(script_path.relative_to(root).as_posix())

    item_sources = defaultdict(set)
    for mob,item,low,high,quest,chance in drops:
        if chance > 0: item_sources[item].add('monster_drop')
    for reactor,item,chance,quest in reactors:
        if chance > 0: item_sources[item].add('reactor_drop')
    for shop,item,*rest in tuples(db / '102-shopitems-data.sql'): item_sources[item].add('shop')
    for catalog in ['tcg-catalog.tsv', 'pc-cafe-rewards.tsv']:
        for line in (root / 'scripts/npc/data' / catalog).read_text().splitlines():
            if line.strip() and not line.startswith('#'):
                item_sources[int(line.split('|')[1])].add('custom_shop_or_recipe:' + catalog)
    script_items = set()
    for path in (root / 'scripts').rglob('*.js'):
        script_items.update(map(int,re.findall(r'\b[1-5]\d{6}\b',path.read_text(encoding='utf-8-sig'))))
    for q,data in acts.items():
        for stage in data:
            for item in children(stage, 'item'):
                if integer(item,'count') > 0: item_sources[integer(item,'id')].add('quest_reward')

    req_supported = set(re.findall(r'case "(\w+)"', (root / 'src/main/java/server/quest/QuestRequirementType.java').read_text()))
    quest_rows=[]
    for quest,check in sorted(checks.items()):
        npcs={integer(child(check,stage),'npc') for stage in ('0','1')} - {0}
        if not npcs.intersection(victoria_npcs): continue
        metadata=info.get(quest)
        end=val(child(check,'0'),'end')
        expired=bool(end and len(end)==10 and end < as_of.strftime('%Y%m%d%H'))
        findings=[]
        prerequisites=[]
        required_items=[]
        for stage in ('0','1'):
            requirement=child(check,stage)
            if requirement is None:continue
            for node in requirement:
                if node.get('name') not in req_supported: findings.append('unhandled_requirement:'+node.get('name'))
            for previous in children(requirement, 'quest'):
                id=integer(previous,'id');prerequisites.append(id)
                if id not in checks:findings.append('missing_prerequisite:'+str(id))
            for mob in children(requirement, 'mob'):
                id=integer(mob,'id')
                if id not in mob_names:findings.append('missing_mob_string:'+str(id))
                if id not in mob_maps:findings.append('mob_requires_scripted_spawn:'+str(id))
            for item in children(requirement, 'item'):
                id=integer(item,'id')
                if integer(item,'count') <= 0:continue
                sources=set(item_sources[id])
                if id in script_items:sources.add('script_reference_needs_runtime_check')
                required_items.append({'id':id,'count':integer(item,'count'),'sources':sorted(sources)})
                if not sources:findings.append('item_source_review:'+str(id))
            flag=val(requirement,'startscript' if stage=='0' else 'endscript')
            if flag and not (root / f'scripts/quest/{quest}.js').exists() and not integer(metadata,'viewMedalItem'):
                findings.append('missing_script:'+('start' if stage=='0' else 'end'))
        for npc in npcs:
            if npc not in npc_maps:findings.append('npc_requires_scripted_spawn:'+str(npc))
        quest_rows.append({'quest':quest,'name':val(metadata,'name'),'npcs':sorted(npcs),'expired':expired,
                           'wz_end':end,
                           'source_version_gated_npcs':sorted(n for n in npcs if npc_gates.get(n,0)>version),
                           'source_medal_placeholder':bool(integer(metadata,'viewMedalItem') and not (root / f'scripts/quest/{quest}.js').exists() and medal_placeholder),
                           'medal_requirement_handler':bool(integer(metadata,'viewMedalItem') and not (root / f'scripts/quest/{quest}.js').exists() and not medal_placeholder),
                           'dynamic_references':{f.split(':')[1]:dynamic_refs.get(int(f.split(':')[1]),[]) for f in findings if f.startswith(('mob_requires_scripted_spawn:', 'npc_requires_scripted_spawn:'))},
                           'prerequisites':sorted(set(prerequisites)),
                           'required_items':required_items,'findings':sorted(set(findings))})
    return {'as_of':as_of.isoformat(),'content_version':version,'map_count':len(maps),
            'town_map_count':sum(m['town'] for m in maps.values()),'town_npc_count':len(npc_rows),
            'npc_routes':dict(Counter(n['route'] for n in npc_rows)),
            'victoria_quest_count':len(quest_rows),'victoria_unexpired_quest_count':sum(not q['expired'] for q in quest_rows),
            'maps':list(maps.values()),'npcs':npc_rows,'victoria_quests':quest_rows,
            'limitations':['Static routing/dependency audit, not live gameplay validation.',
                'Victoria scope: quests whose start/end NPC is placed in maps 100000000 through 129999999; includes shared global event NPCs.',
                'Quest item sources and scripted spawns are review candidates; references alone do not prove an obtainable item.',
                'Town coverage uses every WZ map with info/town=1, including interiors and non-major towns.']}


def write_markdown(report, directory):
    """Render the same audited records used by the JSON evidence."""
    directory.mkdir(parents=True, exist_ok=True)
    names = {m['id']: m['name'] for m in report['maps']}
    def escape(value):
        return str(value).replace('|', r'\|').replace('\n', ' ')
    def locations(ids):
        return ', '.join(f"{m} {names.get(m, '')}" for m in ids)
    def table(headers, rows):
        return '\n'.join('| ' + ' | '.join(map(escape, row)) + ' |'
                         for row in [headers, ['---'] * len(headers), *rows]) + '\n'
    def save(name, text):
        (directory / name).write_text(text, encoding='utf-8')

    npc_rows = []
    for npc in report['npcs']:
        restrictions = []
        if npc['source_version_gated']: restrictions.append(f"NPC requires version {npc['release_version']}")
        restrictions.extend(f"Map {m} requires version {v}" for m,v in npc['source_portal_gates'].items())
        npc_rows.append([npc['npc'], npc['name'], npc['route'], locations(npc['maps']),
                         '; '.join(restrictions), '; '.join(npc['findings'])])
    save('town-npcs.md', '# Every town NPC: static routing inventory\n\n'
         + f"Content version **{report['content_version']}**. Generated by `tools/audit_world_content.py` as of {report['as_of'][:10]}. "
         + f"Covers all {report['town_map_count']:,} WZ town-flagged maps and {report['town_npc_count']:,} distinct NPC templates, including interiors, event copies and small settlements. "
         + 'Routes describe implemented handlers, not a promise that every branch has been played. Default WZ dialogue does not implement a missing service.\n\n'
         + table(['NPC','Name','Handler','WZ town locations','Source restriction','Review'], npc_rows))

    quest_rows = []
    for quest in report['victoria_quests']:
        custom = []
        if quest['source_version_gated_npcs']: custom.append('Version-gated NPCs: ' + ', '.join(map(str, quest['source_version_gated_npcs'])))
        if quest['source_medal_placeholder']: custom.append('Medal placeholder')
        if quest['medal_requirement_handler']: custom.append('WZ + server medal requirements; live validation pending')
        quest_rows.append([quest['quest'],quest['name'], 'Expired ' + quest['wz_end'] if quest['expired'] else 'Unexpired',
                           '; '.join(custom), '; '.join(quest['findings'])])
    save('victoria-quests.md', '# Victoria Island quest inventory\n\n'
         + f"Content version **{report['content_version']}**. All {report['victoria_quest_count']:,} quests associated with an NPC placed in maps 100000000-129999999. "
         + f"This conservative scope includes worldwide event NPCs. {report['victoria_unexpired_quest_count']:,} are unexpired as of {report['as_of'][:10]}; "
         + f"{report['victoria_quest_count'] - report['victoria_unexpired_quest_count']:,} retain past WZ event end dates. "
         + 'Unexpired does not mean fully available or gameplay-tested. Medal placeholders and dynamic spawns remain separate findings.\n\n'
         + table(['Quest','Name','Date status','Source customizations','Review'],quest_rows))

    remaining = '# Remaining review findings\n\n'
    remaining += 'These are not all confirmed failures. Missing static spawns may be provided by event scripts; references below require runtime follow-up. '
    remaining += f"At content version {report['content_version']}, {sum(n['source_version_gated'] for n in report['npcs'])} town NPCs are hidden by NPC version rules. "
    remaining += 'See [the current policy and confirmed gaps](../v83-content-policy.md).\n\n'
    remaining += '## Town NPCs with dialogue but no standalone service or associated WZ quest\n\n'
    remaining += table(['NPC','Name','Locations','Version gated'], [
        [n['npc'],n['name'],locations(n['maps']),n['source_version_gated']]
        for n in report['npcs'] if 'no_feature_script_or_shop_review' in n['findings']])
    remaining += '\n## Unexpired quests with static review findings\n\n'
    remaining += table(['Quest','Name','Review','Dynamic script references'], [
        [q['quest'],q['name'],'; '.join(q['findings']), '; '.join(
            f"{entity}: {', '.join(refs) if refs else 'no script reference found'}" for entity,refs in q['dynamic_references'].items())]
        for q in report['victoria_quests'] if not q['expired'] and q['findings']])
    remaining += '\n## Unexpired quests using the source medal placeholder\n\n'
    remaining += table(['Quest','Name'], [[q['quest'],q['name']] for q in report['victoria_quests']
                                          if not q['expired'] and q['source_medal_placeholder']])
    save('remaining-findings.md',remaining)


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path('.'))
    parser.add_argument('--as-of',default='2026-09-26')
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--markdown-dir',type=Path,help='Also render the town, quest and remaining-findings Markdown tables.')
    args=parser.parse_args()
    report=audit(args.root,datetime.strptime(args.as_of,'%Y-%m-%d'))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ['maps','npcs','victoria_quests']},indent=2))
    if args.markdown_dir: write_markdown(report,args.markdown_dir)
    print('NPC findings:',dict(Counter(f.split(':')[0] for n in report['npcs'] for f in n['findings'])))
    print('Active quest findings:',dict(Counter(f.split(':')[0] for q in report['victoria_quests'] if not q['expired'] for f in q['findings'])))
