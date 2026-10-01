"""Create a one-byte repair of the verified installed v5 proxy, no broad rebuild."""
from pathlib import Path
import hashlib,struct,json
root=Path(__file__).resolve().parent
source=Path('C:/Users/Lupert/Games/SoloMapling-v83/dinput8.dll')
data=source.read_bytes()
old='a4df286af3058c2afbaf2b421f2c8982e392e19f474697086d2e1bb278d30348'
assert hashlib.sha256(data).hexdigest()==old
pe=struct.unpack_from('<I',data,60)[0]
n=struct.unpack_from('<H',data,pe+6)[0];opt=struct.unpack_from('<H',data,pe+20)[0]
offset=None
for i in range(n):
    at=pe+24+opt+40*i
    vsize,va,rsize,raw=struct.unpack_from('<IIII',data,at+8)
    if va<=0xc0cc and 0xc0cc+11<=va+rsize:offset=raw+0xc0cc-va
assert offset is not None
expected=bytes.fromhex('8b773485f60f89ec000000')
assert data[offset:offset+len(expected)]==expected
fixed=bytearray(data);fixed[offset+6]=0x84
assert [i for i,(a,b) in enumerate(zip(data,fixed)) if a!=b]==[offset+6]
out=root/'build/dinput8-v5-errorfix.dll';out.write_bytes(fixed)
report=dict(source=str(source),originalSha256=old,sha256=hashlib.sha256(fixed).hexdigest(),artifact=str(out),rva='0xC0D2',fileOffset=offset+6,oldByte='89',newByte='84',meaning='JNS to JZ after TEST ESI: handle every nonzero native ZException code',changedBytes=1)
(root/'v5-errorfix-manifest.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
