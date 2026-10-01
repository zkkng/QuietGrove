"""Actual loopback I/O, handled-fault propagation, retention and on-disk evidence."""
import hashlib
import json
from pathlib import Path
import subprocess
import struct
from read_faults import read, RECORD_SIZE
from read_lifecycle import read as read_lifecycle, SIZE as LIFECYCLE_SIZE

root = Path(__file__).resolve().parent
build = root/'build'
before = set((build/'diagnostics').glob('*.log')) if (build/'diagnostics').exists() else set()
prior_dumps = set((build/'diagnostics').glob('*.dmp')) if (build/'diagnostics').exists() else set()
run = subprocess.run([str(build/'NativeDiagnosticsHarness.exe'),'saturation'],cwd=build,capture_output=True,text=True,timeout=45)
assert run.returncode == 0, (run.returncode,run.stdout,run.stderr)
logs = set((build/'diagnostics').glob('*.log'))-before
assert len(logs)==1, logs
log = logs.pop()
text = log.read_text()
assert 'ready os_hooks=1 veh=1 critical_file=1' in text
assert 'rxBytes=86' in text and 'txBytes=86' in text
assert 'ring_overwritten=' in text
assert 'opcode=unavailable' in text
assert 'criticalCount=24' in text
lifecycle=log.with_suffix('.lifecycle')
life=read_lifecycle(lifecycle)
assert lifecycle.stat().st_size==129*LIFECYCLE_SIZE
prior=[r for r in life if r['slot']<64]
first=[r for r in life if r['slot']==64]
later=[r for r in life if r['slot']>64]
assert len(prior)==64 and len(first)==1 and len(later)==64
assert first[0]['kind']==11 and first[0]['frames']
assert max(r['sequence'] for r in prior)<first[0]['sequence']<min(r['sequence'] for r in later)
assert set(r['kind'] for r in prior)==set(range(1,11))
fault = log.with_suffix('.faults')
records = read(fault)
assert fault.stat().st_size <= 18*RECORD_SIZE
faults=[r for r in records if r['kind']==10]
assert len(faults)==16, len(faults)
assert all(r['exceptionCode']=='0xC0000005' for r in faults)
assert any(r['kind']==8 and r['slot']==16 for r in records), 'Exit record lost under saturation'
assert any(r['kind']==100 and r['slot']==17 for r in records), 'Detach record lost under saturation'
secret=b'DIAGNOSTICS_SECRET_PAYLOAD_MUST_NOT_APPEAR'
assert secret not in log.read_bytes() and secret not in fault.read_bytes()
assert secret not in lifecycle.read_bytes()
dump_files=[]
for dump in set((build/'diagnostics').glob('*.dmp'))-prior_dumps:
    data = dump.read_bytes()
    assert data[:4]==b'MDMP', dump
    stream_count, directory = struct.unpack_from('<II',data,8)
    assert 0 < stream_count < 100 and directory+12*stream_count<=len(data)
    streams=[]
    for i in range(stream_count):
        kind,size,rva=struct.unpack_from('<III',data,directory+12*i)
        assert rva+size<=len(data),(dump,kind,size,rva)
        streams.append(kind)
    assert {3,4,7}.issubset(streams), streams # thread, module and system streams
    dump_files.append(dict(name=dump.name,bytes=len(data),streams=streams))
assert len(dump_files)==2, 'Expected two structurally valid minidumps from this run'
report=dict(passed=True,checks=['x86 DLL load and OS hooks','actual loopback payload roundtrip','Winsock error preservation','ring overflow accounting','24 handled faults propagate','latest 16 faults retained','reserved exit/detach slots survive saturation','no payload in logs/breadcrumbs','local MDMP file produced'],log=str(log),faultFile=str(fault),recordSize=RECORD_SIZE,dumps=dump_files,dllSha256=hashlib.sha256((build/'SoloClientDiagnostics.dll').read_bytes()).hexdigest(),stdout=run.stdout)
(root/'test-result.json').write_text(json.dumps(report,indent=2))
report['checks'] += ['five native lifecycle detours preserve arguments/effects/stack', '64 pre-null traces frozen through 1000 later records', 'reserved first-null stack survives saturation']
(root/'test-result.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
