"""Pinned diagnostic comparison: disable v5 trainer startup, keep client patches."""
from pathlib import Path
import hashlib, json, struct

root = Path(__file__).resolve().parent
source = Path('C:/Users/Lupert/Games/SoloMapling-v83/dinput8.dll')
data = source.read_bytes()
expected_sha = 'a4df286af3058c2afbaf2b421f2c8982e392e19f474697086d2e1bb278d30348'
assert hashlib.sha256(data).hexdigest() == expected_sha
pe = struct.unpack_from('<I', data, 60)[0]
n = struct.unpack_from('<H', data, pe + 6)[0]
opt = struct.unpack_from('<H', data, pe + 20)[0]
def offset(rva):
    for i in range(n):
        at = pe + 24 + opt + 40*i
        _, va, size, raw = struct.unpack_from('<IIII', data, at + 8)
        if va <= rva < va + size:
            return raw + rva - va
    raise ValueError(hex(rva))

# Sole direct caller of E590; decoded initialization validates/installs the
# two fly hooks, reports support, and starts the trainer pipe worker.
call = offset(0xcde7)
assert data[call:call+5] == bytes.fromhex('e8a4170000')
assert data[offset(0xe590):offset(0xe590)+9] == bytes.fromhex('538bdc83ec0883e4f0')
assert data[offset(0xc0cc):offset(0xc0cc)+11] == bytes.fromhex('8b773485f60f89ec000000')
fixed = bytearray(data)
fixed[call:call+5] = b'\x90'*5
fixed[offset(0xc0d2)] = 0x84
changed = [i for i, (a,b) in enumerate(zip(data, fixed)) if a != b]
assert changed == sorted([*range(call, call+5), offset(0xc0d2)])
out = root/'build/dinput8-v5-trainer-baseline.dll'
out.write_bytes(fixed)
report = dict(source=str(source), originalSha256=expected_sha,
    artifact=str(out), sha256=hashlib.sha256(fixed).hexdigest(), changedBytes=6,
    changes=[dict(rva='CDE7', before='E8A4170000', after='9090909090',
                  purpose='Skip trainer Start only; no fly hooks or trainer control worker'),
             dict(rva='C0D2', before='89', after='84',
                  purpose='Preserve nonzero native exception handling')],
    status='Prepared diagnostic comparison; not installed; gameplay unverified')
(root/'trainer-baseline-manifest.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
