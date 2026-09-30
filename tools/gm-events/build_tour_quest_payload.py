"""Package only the frozen Traveling Around Maple server assets on the live JAR.

This does not compile or test quest behavior. The resulting archive uses the
existing guarded CT202 installer and leaves Java, SQL, config and other content
at the installed versions.
"""

import argparse
import hashlib
import io
import json
import tarfile
from pathlib import Path


PATHS = (
    "wz/Quest.wz/Check.img.xml",
    *(f"scripts/quest/{quest}.js" for quest in range(8053, 8060)),
    "scripts/npc/1002101.js",
    "scripts/npc/1081100.js",
    "scripts/npc/9120006.js",
    "scripts/npc/2012010.js",
    "scripts/npc/2041004.js",
    "scripts/npc/2020007.js",
    "scripts/npc/2050009.js",
    "scripts/npc/traveling_around_maple.js",
)
LIVE_JAR_SHA256 = "4836efab22de7d198c2683dd6ec49a1f5403fdb554b56dfe924f8b796680a140"
REQUIRED_INSTALLED_MIGRATIONS = (
    "20260926-character-content",
    "20260926-pc-cafe",
    "20260926-quest-custom-data",
    "20260929-drop-fixes",
)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def add_bytes(archive, name, data):
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = 0o644
    info.mtime = 0
    archive.addfile(info, io.BytesIO(data))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--installed-jar", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    repo = args.repo.resolve(strict=True)
    jar = args.installed_jar.resolve(strict=True)
    jar_data = jar.read_bytes()
    if sha256(jar_data) != LIVE_JAR_SHA256:
        raise ValueError("Immutable installed JAR differs from the pinned live hash")
    if len(PATHS) != 16 or len(set(PATHS)) != 16:
        raise ValueError("Expected exactly 16 unique frozen quest assets")

    assets = []
    for name in sorted(PATHS):
        path = repo / name
        if path.is_symlink() or not path.is_file() or not path.resolve(strict=True).is_relative_to(repo):
            raise ValueError(f"Unsafe or missing frozen quest asset: {name}")
        data = path.read_bytes()
        if not data:
            raise ValueError(f"Empty frozen quest asset: {name}")
        assets.append((name, data, sha256(data)))

    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    manifest = {
        "jarSha256": LIVE_JAR_SHA256,
        "scope": "Traveling Around Maple 8053-8059, user-requested untested content release",
        "replaceCount": len(assets),
        "missingOnlyCount": 0,
        "requiredMigrationIds": list(REQUIRED_INSTALLED_MIGRATIONS),
        "files": [{"path": name, "sha256": digest, "policy": "replace"}
                  for name, _, digest in assets],
    }
    manifest_data = (json.dumps(manifest, indent=2) + "\n").encode()
    sums = "".join([f"{LIVE_JAR_SHA256}  Server.jar\n"] +
                   [f"{digest}  {name}\n" for name, _, digest in assets]).encode()
    replace = "".join(f"{name}\n" for name, _, _ in assets).encode()
    payload = output / "payload.tar.gz"
    with tarfile.open(payload, "w:gz") as archive:
        add_bytes(archive, "Server.jar", jar_data)
        for name, data, _ in assets:
            add_bytes(archive, name, data)
        add_bytes(archive, "SHA256SUMS", sums)
        add_bytes(archive, "replace.list", replace)
        add_bytes(archive, "missing-only.list", b"")
        add_bytes(archive, "deployment-manifest.json", manifest_data)
    payload_hash = sha256(payload.read_bytes())
    (output / "deployment-manifest.json").write_bytes(manifest_data)
    (output / "payload.sha256").write_text(f"{payload_hash}  payload.tar.gz\n", encoding="ascii")
    print(json.dumps({"payload": str(payload), "sha256": payload_hash,
                      "installedJarSha256": LIVE_JAR_SHA256,
                      "replaceCount": len(assets), "testsRun": False}))


if __name__ == "__main__":
    main()
