"""Build a narrow overlay on the exact live JAR, with unchanged-entry proofs and a guarded payload."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--baseline-sha256', required=True)
    parser.add_argument('--source-base', required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--byte-buddy', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    git = ['git', '-c', f'safe.directory={root.as_posix()}', '-C', str(root)]
    if subprocess.check_output(git + ['status', '--porcelain']).strip():
        raise ValueError('Commit reviewed source before packaging')
    commit = subprocess.check_output(git + ['rev-parse', 'HEAD'], text=True).strip()
    base_bytes = args.baseline.read_bytes()
    assert digest(base_bytes) == args.baseline_sha256.lower(), 'Live baseline hash mismatch'
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    classes = output / 'classes'
    classes.mkdir()
    changed_sources = subprocess.check_output(git + ['diff', '--name-only', args.source_base, 'HEAD', '--', 'src/main/java'], text=True).splitlines()
    sources = [root / p for p in changed_sources]
    assert sources and all(p.is_file() and p.suffix == '.java' for p in sources)
    javac, java, jar_tool = [str(args.jdk / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))) for name in ('javac', 'java', 'jar')]
    subprocess.run([javac, '--release', '21', '-proc:none', '-encoding', 'UTF-8', '-cp', str(args.baseline), '-d', str(classes), *map(str, sources)], check=True)
    helper = output / 'helpers'
    helper.mkdir()
    cp = os.pathsep.join(map(str, (classes, args.baseline, args.byte_buddy)))
    subprocess.run([javac, '--release', '21', '-proc:none', '-encoding', 'UTF-8', '-cp', cp, '-d', str(helper),
                    *map(str, (root / 'tools/hybrid-pilot').glob('*.java'))], check=True)
    # Only the registration method changes in this live class. Existing statistics advice stays byte-for-byte elsewhere.
    subprocess.run([java, '-cp', os.pathsep.join((str(helper), str(args.byte_buddy))), 'hybrid.build.RegisterCommand',
                    str(args.baseline), str(classes / 'client/command/CommandsExecutor.class')], check=True)
    patches = {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob('*.class')}
    candidate = output / 'Server.jar'
    with zipfile.ZipFile(io.BytesIO(base_bytes)) as baseline, zipfile.ZipFile(candidate, 'w') as target:
        assert len(baseline.namelist()) == len(set(baseline.namelist())), 'Duplicate baseline entries'
        assert not any(n.startswith('META-INF/') and n.endswith(('.SF', '.RSA', '.DSA')) for n in baseline.namelist()), 'Signed baseline'
        for info in baseline.infolist():
            target.writestr(info, patches.get(info.filename, baseline.read(info.filename)))
        for name in sorted(set(patches) - set(baseline.namelist())):
            target.writestr(name, patches[name])
    with zipfile.ZipFile(io.BytesIO(base_bytes)) as baseline, zipfile.ZipFile(candidate) as target:
        assert target.testzip() is None
        actual = sorted(n for n in target.namelist() if n not in baseline.namelist() or target.read(n) != baseline.read(n))
        assert set(actual) <= set(patches)
        for name in set(baseline.namelist()) - set(patches):
            assert target.read(name) == baseline.read(name), f'Unrelated entry changed: {name}'
    changed_list = output / 'changed-classes.txt'
    changed_list.write_text('\n'.join(actual) + '\n')
    subprocess.run([java, '-Xverify:all', '-cp', os.pathsep.join((str(helper), str(candidate))), 'hybrid.build.LinkSmoke', str(changed_list)], check=True)
    agent_manifest = output / 'agent.mf'
    agent_manifest.write_text('Manifest-Version: 1.0\nAgent-Class: hybrid.build.TrialProbe\n\n')
    subprocess.run([jar_tool, '--create', '--file', str(output / 'trial-probe.jar'), '--manifest', str(agent_manifest),
                    '-C', str(helper), 'hybrid/build/TrialProbe.class', '-C', str(helper), 'hybrid/build/AttachTrial.class'], check=True)
    manifest = {'sourceCommit': commit, 'sourceBase': args.source_base, 'baselineSha256': digest(base_bytes),
                'jarSha256': digest(candidate.read_bytes()), 'changedClasses': actual,
                'sourceFiles': {p: digest((root / p).read_bytes()) for p in changed_sources},
                'files': [], 'replaceCount': 0, 'missingOnlyCount': 0, 'requiredMigrationIds': []}
    data = (json.dumps(manifest, indent=2) + '\n').encode()
    (output / 'deployment-manifest.json').write_bytes(data)
    payload = output / 'payload.tar.gz'
    with tarfile.open(payload, 'w:gz') as archive:
        for name, contents in {'Server.jar': candidate.read_bytes(), 'deployment-manifest.json': data,
                               'SHA256SUMS': (manifest['jarSha256'] + '  Server.jar\n').encode(),
                               'replace.list': b'', 'missing-only.list': b''}.items():
            info = tarfile.TarInfo(name)
            info.size = len(contents)
            archive.addfile(info, io.BytesIO(contents))
    print(json.dumps({'commit': commit, 'jar': manifest['jarSha256'], 'payload': digest(payload.read_bytes()),
                      'changedClasses': len(actual), 'unchangedEntriesVerified': True}, indent=2))


if __name__ == '__main__':
    main()
