"""Package a tested JAR and committed runtime assets; never reads live credentials."""
import argparse, gzip, hashlib, io, json, pathlib, subprocess, tarfile

ROOT = pathlib.Path(__file__).resolve().parents[2]

def git(*args):
    return subprocess.check_output(['git','-c','safe.directory='+ROOT.as_posix(),*args],cwd=ROOT)

def package(output):
    if git('status','--porcelain','--untracked-files=no').strip():
        raise RuntimeError('Tracked checkout differs from the commit; build from a clean worktree')
    commit=git('rev-parse','HEAD').decode().strip()
    jar=ROOT/'target/Cosmic.jar'
    if not jar.is_file(): raise RuntimeError('Run mvn clean verify before packaging')
    output=output.resolve()
    if output==ROOT or any(output.is_relative_to(ROOT/x) for x in ('src','scripts','wz')):
        raise RuntimeError('Output must not replace source directories')
    output.mkdir(parents=True,exist_ok=True)
    runtime=['scripts','wz','src/main/java/soloMapling']
    if 'server-config' in git('ls-tree','--name-only','HEAD').decode().splitlines(): runtime.append('server-config')
    manifest={'schemaVersion':1,'sourceCommit':commit,'configuration':'environment-owned; not included','files':[]}
    archive=output/'runtime.tar.gz'
    with archive.open('wb') as raw, gzip.GzipFile(fileobj=raw,mode='wb',mtime=0,filename='') as gz, tarfile.open(fileobj=gz,mode='w|') as tar:
        def add(p,b):
            info=tarfile.TarInfo(p); info.size=len(b); info.mode=0o644; info.mtime=0
            tar.addfile(info,io.BytesIO(b))
            manifest['files'].append({'path':p,'sha256':hashlib.sha256(b).hexdigest(),'bytes':len(b)})
        add('Server.jar',jar.read_bytes())
        proc=subprocess.Popen(['git','-c','safe.directory='+ROOT.as_posix(),'archive','HEAD','--',*runtime],cwd=ROOT,stdout=subprocess.PIPE)
        try:
            with tarfile.open(fileobj=proc.stdout,mode='r|') as source:
                for entry in source:
                    if entry.isdir():continue
                    if not entry.isfile():raise RuntimeError('Runtime contains a non-file entry: '+entry.name)
                    add(entry.name,source.extractfile(entry).read())
        finally:
            proc.stdout.close()
            if proc.wait()!=0:raise RuntimeError('Unable to read the committed runtime archive')
        b=json.dumps(manifest,sort_keys=True,indent=2).encode()
        info=tarfile.TarInfo('release-manifest.json'); info.size=len(b); info.mode=0o644; tar.addfile(info,io.BytesIO(b))
    digest=hashlib.sha256(archive.read_bytes()).hexdigest()
    (output/'release-manifest.json').write_text(json.dumps(manifest,indent=2))
    (output/'SHA256SUMS').write_text(digest+'  runtime.tar.gz\n')
    print(json.dumps({'commit':commit,'packageSha256':digest,'runtimeFiles':len(manifest['files'])}))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args();package(a.output)
