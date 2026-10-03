"""Run production x86 hooks against native null/valid optional output cases."""
import os, subprocess, hashlib, json
from pathlib import Path
root=Path(__file__).resolve().parent
vc=Path('C:/BuildTools/VC/Tools/MSVC/14.44.35207')
sdk=Path('C:/Program Files (x86)/Windows Kits/10')
version='10.0.26100.0'
env={k:v for k,v in os.environ.items() if k.lower() not in ('path','include','lib')}
env['PATH']=str(vc/'bin/Hostx64/x86')+os.pathsep+(os.environ.get('PATH') or os.environ.get('Path',''))
env['INCLUDE']=';'.join(map(str,[vc/'include']+[sdk/'Include'/version/p for p in ('ucrt','shared','um','winrt')]))
env['LIB']=';'.join(map(str,[vc/'lib/x86']+[sdk/'Lib'/version/p/'x86' for p in ('ucrt','um')]))
out=root/'critical-tests';out.mkdir(exist_ok=True)
source=root/'MapleEzorsia-v2/ezorsia/TrainerClientAdapter.cpp'
subprocess.run([str(vc/'bin/Hostx64/x86/cl.exe'),'/nologo','/std:c++17','/EHsc','/O2','/Oy-','/MD','/DTRAINER_NATIVE_TEST',str(source),'/Fe:FlyHookTest.exe','/link','/DYNAMICBASE:NO','/BASE:0x00400000','Advapi32.lib','User32.lib'],cwd=out,env=env,check=True)
run=subprocess.run([str(out/'FlyHookTest.exe')],cwd=out,capture_output=True,text=True,check=True)
report=dict(passed=True,stdout=run.stdout,productionSourceSha256=hashlib.sha256(source.read_bytes()).hexdigest())
(out/'test-result.json').write_text(json.dumps(report,indent=2))
print(run.stdout)
