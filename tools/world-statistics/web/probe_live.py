import hashlib,json,subprocess,tarfile
from pathlib import Path
def run(*args):
 r=subprocess.run(args,capture_output=True,text=True)
 return {'code':r.returncode,'output':r.stdout.strip()}
root=Path('/opt/quietgrove')
print(json.dumps({'web':run('systemctl','is-active','quietgrove'),'game':run('systemctl','is-active','solomapling'),'gameSha':hashlib.sha256(Path('/opt/solomapling/Server.jar').read_bytes()).hexdigest(),'health':json.loads(Path('/opt/solomapling/data/world-statistics/health.json').read_text()),'timers':run('systemctl','list-timers','--all','--no-pager')},indent=2))
with tarfile.open('/tmp/world-stats-web-base.tar','w') as tar:
 for folder,names in {'backend':['app.py','world_snapshots.py','ranking_service.py','requirements.txt'],'site':['ledger.js','portal.html','portal.css','portal.js','auth.js']}.items():
  for name in names:
   file=root/folder/name
   if file.exists():tar.add(file,arcname=folder+'/'+name)
r=subprocess.run(['mariadb','cosmic','--batch','--skip-column-names','-e',"SELECT metric_key,availability,activation_at FROM stats_metric; SELECT COUNT(*) FROM stats_batch_receipt; SELECT COUNT(*) FROM stats_aggregate; SELECT kind,COUNT(*) FROM stats_entity GROUP BY kind"],capture_output=True,text=True)
print('STATS_DATABASE\\n'+r.stdout)
if r.returncode: print('DB_PROBE_FAILED code='+str(r.returncode))
