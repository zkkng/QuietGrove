"""Read bounded native lifecycle records; addresses and identities only."""
import struct,sys,json,datetime
from pathlib import Path
SIZE=116
NAMES={1:'player destructor enter',2:'player destructor leave',3:'NPC destructor enter',4:'NPC destructor leave',5:'player singleton clear enter',6:'player singleton clear leave',7:'NPC singleton clear enter',8:'NPC singleton clear leave',9:'set_stage enter',10:'set_stage leave',11:'FIRST NULL PLAYER POOL',12:'trace active'}
NAMES[13]='native ZException5 original throw'
def read(path):
    data=Path(path).read_bytes();records=[]
    for offset in range(0,len(data)-SIZE+1,SIZE):
        magic,version,seq,kind,tid,tick,utc,obj,stage,players,npcs,count=struct.unpack_from('<6IQ5I',data,offset)
        if magic!=0x53444c31:continue
        assert version==1 and count<=16
        frames=struct.unpack_from('<16I',data,offset+52)
        records.append(dict(slot=offset//SIZE,sequence=seq,kind=kind,name=NAMES.get(kind,str(kind)),thread=tid,tick=tick,utc=datetime.datetime.fromtimestamp((utc-116444736000000000)/10000000,datetime.timezone.utc).isoformat(),object=f'{obj:08X}',stage=f'{stage:08X}',players=f'{players:08X}',npcs=f'{npcs:08X}',frames=[f'{x:08X}' for x in frames[:count]]))
    return sorted(records,key=lambda r:r['sequence'])
if __name__=='__main__':print(json.dumps(read(sys.argv[1]),indent=2))
