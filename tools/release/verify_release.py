"""Verify a release before test deployment or approval of the same bytes for prod."""
import argparse,hashlib,json,pathlib,tarfile

def verify(package,expected_sha,expected_commit):
    digest=hashlib.sha256(package.read_bytes()).hexdigest()
    if digest!=expected_sha.lower():raise ValueError('Package SHA256 mismatch')
    with tarfile.open(package,'r:gz') as tar:
        members=tar.getmembers();names=[x.name for x in members]
        if len(names)!=len(set(names)):raise ValueError('Duplicate archive paths')
        if any(not x.isfile() or pathlib.PurePosixPath(x.name).is_absolute() or '..' in pathlib.PurePosixPath(x.name).parts for x in members):raise ValueError('Unsafe archive entry')
        manifest=json.load(tar.extractfile('release-manifest.json'))
        if manifest['sourceCommit']!=expected_commit:raise ValueError('Source commit mismatch')
        expected={x['path']:x for x in manifest['files']}
        if set(names)!=(set(expected)|{'release-manifest.json'}):raise ValueError('Manifest coverage mismatch')
        for name,record in expected.items():
            b=tar.extractfile(name).read()
            if len(b)!=record['bytes'] or hashlib.sha256(b).hexdigest()!=record['sha256']:raise ValueError('Runtime file mismatch: '+name)
    print(json.dumps({'verified':True,'commit':expected_commit,'sha256':digest,'files':len(expected)}))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--package',type=pathlib.Path,required=True);p.add_argument('--sha256',required=True);p.add_argument('--commit',required=True);a=p.parse_args();verify(a.package,a.sha256,a.commit)
