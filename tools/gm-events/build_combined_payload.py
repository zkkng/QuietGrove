"""Build an exact, immutable GM/content/server release payload.

The upstream WZ provenance set is missing-only. Reviewed content and GM assets
are replace-if-different. The live config.yaml is never a payload member.
"""

import argparse
import hashlib
import io
import json
import tarfile
from pathlib import Path, PurePosixPath


GM_ASSETS = (
    "scripts/npc/9000001.js", "scripts/npc/9000002.js",
    "scripts/npc/9000003.js", "scripts/npc/9000004.js",
    "scripts/npc/9000005.js", "scripts/npc/9000009.js",
    "scripts/npc/9000011.js", "scripts/npc/9000012.js",
    "scripts/npc/9000015.js", "scripts/portal/rand_ola.js",
    "scripts/reactor/9002002.js", "scripts/reactor/2111001.js",
    "scripts/event/BalrogBattle.js", "scripts/event/BalrogBattle_Easy.js",
)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_file(repo, name):
    if not isinstance(name, str) or not name or "\\" in name:
        raise ValueError(f"Invalid payload path: {name!r}")
    relative = PurePosixPath(name)
    if relative.is_absolute() or name != relative.as_posix() or any(part in ("", ".", "..") for part in relative.parts):
        raise ValueError(f"Unsafe payload path: {name}")
    if name == "config.yaml" or not name.startswith(("scripts/", "server-config/", "wz/")):
        raise ValueError(f"Unapproved payload path: {name}")
    path = (repo / relative).resolve(strict=True)
    if not path.is_relative_to(repo) or not path.is_file() or path.stat().st_size == 0:
        raise ValueError(f"Missing, empty or external payload path: {name}")
    return path


def add_bytes(archive, name, data):
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = 0o644
    info.mtime = 0
    archive.addfile(info, io.BytesIO(data))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--content-manifest", type=Path, required=True)
    parser.add_argument("--content-sha256", required=True)
    parser.add_argument("--upstream-sha256", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--extra-manifest", type=Path,
                        help="Optional JSON object with a reviewed replacePaths array")
    args = parser.parse_args()
    repo = args.repo.resolve(strict=True)
    jar = args.jar.resolve(strict=True)
    if not jar.is_file() or jar.stat().st_size == 0:
        raise ValueError("Missing or empty server JAR")
    if sha256(args.content_manifest.resolve(strict=True)) != args.content_sha256.lower():
        raise ValueError("Content source manifest hash changed")
    content = json.loads(args.content_manifest.read_text(encoding="utf-8"))
    upstream_name = content["missingOnlyUpstreamXmlManifest"]
    if not isinstance(upstream_name, str) or not upstream_name.startswith("docs/") or ".." in PurePosixPath(upstream_name).parts:
        raise ValueError("Invalid upstream manifest path")
    upstream_path = (repo / upstream_name).resolve(strict=True)
    if not upstream_path.is_relative_to(repo) or not upstream_path.is_file():
        raise ValueError("Upstream manifest escaped repository")
    if sha256(upstream_path) != args.upstream_sha256.lower():
        raise ValueError("Upstream provenance manifest hash changed")
    upstream = json.loads(upstream_path.read_text(encoding="utf-8"))
    content_paths = set(content["scripts"] + content["dataAndConfig"] + content["modifiedMapXml"])
    if len(content_paths) != sum(len(content[key]) for key in ("scripts", "dataAndConfig", "modifiedMapXml")):
        raise ValueError("Duplicate reviewed content asset")
    content_hashes = content["sha256ByPath"]
    if set(content_hashes) != content_paths:
        raise ValueError("Content path/hash manifest mismatch")
    replace = content_paths | set(GM_ASSETS)
    migration_ids = {PurePosixPath(name).stem for name in content["requiredMigrationsToVerifyInstalled"]}
    if args.extra_manifest:
        extra = json.loads(args.extra_manifest.read_text(encoding="utf-8"))
        replace.update(extra["replacePaths"])
        migration_ids.update(extra.get("migrationIds", []))
    if any(not name.replace("-", "").isalnum() for name in migration_ids):
        raise ValueError("Invalid migration identifier")
    missing = {entry["path"]: entry["sha256"].lower() for entry in upstream["files"]}
    if len(missing) != len(upstream["files"]) or replace.intersection(missing):
        raise ValueError("Duplicate or conflicting release asset policy")
    entries = []
    for name in sorted(replace):
        path = safe_file(repo, name)
        actual = sha256(path)
        if name in content_hashes and actual != content_hashes[name].lower():
            raise ValueError(f"Content source hash changed: {name}")
        entries.append((name, path, actual, "replace"))
    for name, expected in sorted(missing.items()):
        path = safe_file(repo, name)
        actual = sha256(path)
        if actual != expected:
            raise ValueError(f"Upstream provenance mismatch: {name}")
        entries.append((name, path, actual, "missing-only"))
    jar_hash = sha256(jar)
    if args.verify_only:
        print(f"Verified JAR {jar_hash}; replace {len(replace)}; missing-only {len(missing)}")
        return
    release_dir = args.output_dir.resolve() / f"gm-combined-{jar_hash[:12]}"
    release_dir.mkdir(parents=True, exist_ok=False)
    payload = release_dir / "payload.tar.gz"
    all_hashes = [("Server.jar", jar_hash)] + [(name, digest) for name, _, digest, _ in entries]
    digest_lines = "".join(f"{digest}  {name}\n" for name, digest in all_hashes).encode()
    replace_lines = "".join(f"{name}\n" for name, _, _, policy in entries if policy == "replace").encode()
    missing_lines = "".join(f"{name}\n" for name, _, _, policy in entries if policy == "missing-only").encode()
    manifest = {
        "jarSha256": jar_hash,
        "contentManifestSha256": args.content_sha256.lower(),
        "upstreamManifestSha256": args.upstream_sha256.lower(),
        "replaceCount": len(replace),
        "missingOnlyCount": len(missing),
        "requiredMigrationIds": sorted(migration_ids),
        "files": [{"path": name, "sha256": digest, "policy": policy}
                  for name, _, digest, policy in entries],
    }
    with tarfile.open(payload, "w:gz") as archive:
        archive.add(jar, arcname="Server.jar", recursive=False)
        for name, path, _, _ in entries:
            archive.add(path, arcname=name, recursive=False)
        add_bytes(archive, "SHA256SUMS", digest_lines)
        add_bytes(archive, "replace.list", replace_lines)
        add_bytes(archive, "missing-only.list", missing_lines)
        add_bytes(archive, "deployment-manifest.json", json.dumps(manifest, indent=2).encode() + b"\n")
    (release_dir / "payload.sha256").write_text(f"{sha256(payload)}  payload.tar.gz\n", encoding="ascii")
    (release_dir / "deployment-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"{payload}\nJAR {jar_hash}\nreplace {len(replace)}; missing-only {len(missing)}; payload {sha256(payload)}")


if __name__ == "__main__":
    main()
