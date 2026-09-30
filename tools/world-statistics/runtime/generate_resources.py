import json,re,xml.etree.ElementTree as E
from pathlib import Path
root=Path(__file__).resolve().parents[3]
out=Path(__file__).parent/'resources'/'world-statistics'
out.mkdir(parents=True,exist_ok=True)
sql=(root/'tools/world-statistics/staged/20260930-world-statistics-ws1.sql').read_text(encoding='utf-8')
sql='\n'.join(line for line in sql.splitlines() if not line.lstrip().startswith('--'))
sql=re.sub(r"\s+COMMENT\s+'[^']*'","",sql)
sql=sql[:sql.index('CREATE VIEW')]
sql=sql.replace('CREATE TABLE ','CREATE TABLE IF NOT EXISTS ').replace('INSERT INTO stats_metric','INSERT IGNORE INTO stats_metric')
sql+="\nCREATE TABLE IF NOT EXISTS stats_entity(kind VARCHAR(16) NOT NULL, entity_id INT NOT NULL,name VARCHAR(255) NOT NULL,category VARCHAR(64) NOT NULL,region_id INT NOT NULL DEFAULT 0,PRIMARY KEY(kind,entity_id)) ENGINE=InnoDB;\n"
(out/'schema.sql').write_text(sql,encoding='utf-8')
catalog=json.loads((root/'tools/world-statistics/output/content-catalog.json').read_text(encoding='utf-8'))
rows=[]
quest=E.parse(root/'wz/Quest.wz/QuestInfo.img.xml').getroot()
areas={int(n.attrib['name']):int(n.find("int[@name='area']").attrib['value']) if n.find("int[@name='area']") is not None else 0 for n in quest}
for entity in catalog['items']+catalog['quests']+catalog['monsters']:
 kind=entity['kind'];i=int(entity['id'])
 rows.append([kind,i,entity['name'],entity.get('category',''),areas.get(i,0) if kind=='quest' else 0])
pq=[]
for path in sorted((root/'scripts/event').glob('*.js')):
 if 'PQ' in path.stem and path.stem not in {'HorntailPQ','ZakumPQ','BossRushPQ'}:pq.append(path.stem)
for i,name in enumerate(pq,1):rows.append(['pq',i,name,'party_quest',0])
jqs={1043000:'Sabitrama 1',1043001:'Sabitrama 2',1052008:'Shumi 1',1052009:'Shumi 2',1052010:'Shumi 3',1063000:'John 1',1063001:'John 2',1063002:'John 3'}
for path in sorted((root/'wz/Map.wz/Map/Map1').glob('*.xml')):
 text=path.read_text(encoding='utf-8')
 if not any(f'value="{i}"' in text for i in jqs):continue
 data=E.fromstring(text);life=data.find("imgdir[@name='life']")
 if life is None:continue
 for entry in life:
  npc=entry.find("string[@name='id']")
  if npc is not None and int(npc.attrib['value']) in jqs:
   i=int(npc.attrib['value']);rows.append(['jq',i,jqs[i],'jump_quest',int(path.name.split('.')[0])])
def clean(v):return str(v).replace('\t',' ').replace('\r',' ').replace('\n',' ')[:255]
(out/'catalog.tsv').write_text(''.join('\t'.join(map(clean,row))+'\n' for row in rows),encoding='utf-8')
print(json.dumps({'entities':len(rows),'pq':len(pq),'jq':sum(r[0]=='jq' for r in rows)}))
