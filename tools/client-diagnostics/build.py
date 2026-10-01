"""Build diagnostic sidecar and isolated x86 harness, leaving the game untouched."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent
repo = root.parent.parent
vc = Path(r'C:\BuildTools\VC\Tools\MSVC\14.44.35207')
sdk = Path(r'C:\Program Files (x86)\Windows Kits\10')
version = '10.0.26100.0'
out = root / 'build'
out.mkdir(exist_ok=True)
env = {k: v for k, v in os.environ.items() if k.lower() not in ('path', 'include', 'lib')}
env['PATH'] = str(vc / 'bin/Hostx64/x86') + os.pathsep + (os.environ.get('PATH') or os.environ.get('Path', ''))
env['INCLUDE'] = ';'.join(map(str, [vc/'include'] + [sdk/'Include'/version/p for p in ('ucrt','shared','um','winrt')]))
env['LIB'] = ';'.join(map(str, [vc/'lib/x86'] + [sdk/'Lib'/version/p/'x86' for p in ('ucrt','um')]))
cl = str(vc/'bin/Hostx64/x86/cl.exe')
adapter = repo/'tools/client-adapter/MapleEzorsia-v2'
common = [cl,'/nologo','/std:c++17','/EHsc','/O2','/Oy-','/Zi','/MD','/W4','/D_CRT_SECURE_NO_WARNINGS']
subprocess.run(common + ['/LD',str(root/'ClientDiagnostics.cpp'),'/I'+str(adapter/'ezorsia'),
                        '/Fe:SoloClientDiagnostics.dll','/link',str(adapter/'detours/detours.lib'),
                        'Ws2_32.lib','Psapi.lib','Bcrypt.lib','User32.lib','/PDB:SoloClientDiagnostics.pdb'],
               cwd=out,env=env,check=True)
subprocess.run(common + [str(root/'NativeDiagnosticsHarness.cpp'),'/Fe:NativeDiagnosticsHarness.exe',
                        '/link','Ws2_32.lib','/PDB:NativeDiagnosticsHarness.pdb'],cwd=out,env=env,check=True)
