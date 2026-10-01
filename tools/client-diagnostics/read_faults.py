"""Decode fixed x86 v1 diagnostics breadcrumbs; never reads client packet payloads."""
import json
from pathlib import Path
import struct
import sys
from datetime import datetime, timedelta, timezone

RECORD_SIZE = 4416
def read(path):
    data = Path(path).read_bytes()
    if len(data) % RECORD_SIZE:
        raise ValueError('Incomplete record: preserve the file and inspect the final write')
    rows = []
    for offset in range(0, len(data), RECORD_SIZE):
        record = data[offset:offset + RECORD_SIZE]
        magic, version, pid, tid, tick, kind = struct.unpack_from('<6I', record)
        if magic == 0:  # unused reserved slot
            continue
        if magic != 0x53444331 or version != 1:
            raise ValueError('Unsupported breadcrumb layout')
        utc = struct.unpack_from('<Q', record, 24)[0]
        code, flags, _, address, parameters = struct.unpack_from('<5I', record, 32)
        eip, esp, ebp = (struct.unpack_from('<I', record, 112 + n)[0] for n in (184, 196, 180))
        count = struct.unpack_from('<I', record, 828)[0]
        if count > 128:
            raise ValueError('Invalid event count')
        events = [dict(zip(('sequence','tick','tid','kind','a','b','c'), struct.unpack_from('<7I', record, 832 + 28*i))) for i in range(count)]
        rows.append(dict(slot=offset//RECORD_SIZE, utc=(datetime(1601,1,1,tzinfo=timezone.utc)+timedelta(microseconds=utc//10)).isoformat(), pid=pid, tid=tid, tick=tick, kind=kind, exceptionCode=f'0x{code:08X}', exceptionAddress=f'0x{address:08X}', eip=f'0x{eip:08X}', esp=f'0x{esp:08X}', ebp=f'0x{ebp:08X}', flags=flags, numberParameters=parameters, exceptionInformation=list(struct.unpack_from('<15I',record,52))[:min(parameters,15)], events=sorted(events,key=lambda e:e['sequence'])))
    return sorted(rows,key=lambda r:r['utc'])
if __name__ == '__main__':
    print(json.dumps(read(sys.argv[1]), indent=2))
