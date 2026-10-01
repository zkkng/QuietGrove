import argparse,hashlib,json,os,shutil,subprocess,time
from pathlib import Path
LIVE=Path('/opt/solomapling')
def run(*args):
 r=subprocess.run(args,text=True,capture_output=True,check=True);return r.stdout.strip()
def digest(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
p=argparse.ArgumentParser()
p.add_argument('mode',choices=['stage','apply','inspect']);p.add_argument('--jar');p.add_argument('--sha')
p.add_argument('--base',default='4836efab22de7d198c2683dd6ec49a1f5403fdb554b56dfe924f8b796680a140')
p.add_argument('--config',default='2220f488f3d8d82700de48b902e1c36eb8b6f267e52c44119bec9d5188779457')
args=p.parse_args()
def cli(jar,mode):
 r=subprocess.run(['runuser','-u','solomapling','--','/usr/bin/java','-cp',str(jar),'server.statistics.WorldStatistics',mode],cwd=LIVE,text=True,capture_output=True)
 print(r.stdout,end='')
 if r.returncode:print(r.stderr);raise RuntimeError('Statistics CLI failed')
if args.mode=='inspect':
 print('live_sha='+digest(LIVE/'Server.jar'));print(run('systemctl','is-active','solomapling'))
 health=LIVE/'data/world-statistics/health.json'
 if health.exists():print(health.read_text())
 cli(LIVE/'Server.jar','inspect')
 print('LISTENING_PORTS\n'+run('ss','-ltnH'))
 print('SERVICE\n'+run('systemctl','show','solomapling','--property=MainPID,ActiveEnterTimestamp,MemoryCurrent'))
 logs=run('journalctl','-u','solomapling','--since','-5 minutes','--no-pager','-o','cat')
 print('\n'.join(line for line in logs.splitlines() if 'WORLD_STATS' in line or 'World statistics' in line or any(word in line for word in ['ERROR','Exception','VerifyError','Started']))[-8000:])
else:
 jar=Path(args.jar)
 if digest(jar)!=args.sha:raise RuntimeError('Staged JAR changed')
 print('staged_sha='+args.sha)
 if args.mode=='stage':cli(jar,'db-check');print('WORLD_STATS_STAGE_PASS')
 else:
  if digest(LIVE/'Server.jar')!=args.base or digest(LIVE/'config.yaml')!=args.config:raise RuntimeError('Live baseline/config changed')
  backup=LIVE/'backups'/('world-statistics-'+args.sha[:12]);backup.mkdir(parents=True,exist_ok=False)
  shutil.copy2(LIVE/'Server.jar',backup/'Server.jar');shutil.copy2(LIVE/'config.yaml',backup/'config.yaml')
  before_config=digest(LIVE/'config.yaml')
  print('game_connections='+str(sum(1 for line in run('ss','-tnH').splitlines() if any(':'+str(port)+' ' in line for port in [8484,8488]+list(range(7575,7776))))))
  run('systemctl','stop','solomapling')
  try:
   target=LIVE/'Server.jar.next';shutil.copy2(jar,target);shutil.chown(target,user='solomapling',group='solomapling');target.chmod(0o640)
   os.replace(target,LIVE/'Server.jar');started=int(time.time()*1000);run('systemctl','start','solomapling')
   for i in range(45):
    time.sleep(2);health=LIVE/'data/world-statistics/health.json'
    if health.exists():
     state=json.loads(health.read_text())
     if state.get('enabled') and state.get('at',0)>=started and run('systemctl','is-active','solomapling')=='active':
      if digest(LIVE/'config.yaml')!=before_config:raise RuntimeError('Config changed')
      print('WORLD_STATS_RELEASE_PASS backup='+str(backup));cli(LIVE/'Server.jar','inspect');break
   else:raise RuntimeError('Collector did not become active')
  except Exception:
   run('systemctl','stop','solomapling');shutil.copy2(backup/'Server.jar',LIVE/'Server.jar');run('systemctl','start','solomapling')
   print('WORLD_STATS_ROLLED_BACK');raise
