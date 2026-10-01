"""Package only the corrected Traveling Around Maple script bindings with the live JAR."""

import argparse
import hashlib
import io
import json
import tarfile
from pathlib import Path


SCRIPT_PATHS = ("scripts/npc/traveling_around_maple.js",) + tuple(
    f"scripts/quest/{quest}.js" for quest in range(8053, 8060)
)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def add_bytes(archive: tarfile.TarFile, name: str, content: bytes) -> None:
    member = tarfile.TarInfo(name)
    member.size = len(content)
    member.mode = 0o644
    member.mtime = 0
    archive.addfile(member, io.BytesIO(content))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--jar-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    repo = args.repo.resolve(strict=True)
    jar = args.jar.resolve(strict=True)
    output = args.output.resolve()
    if digest(jar) != args.jar_sha256.lower():
        raise ValueError("Expected exact live JAR hash")
    if output.exists():
        raise ValueError("Refusing to overwrite an existing release payload")

    files = []
    for name in SCRIPT_PATHS:
        path = (repo / name).resolve(strict=True)
        if not path.is_relative_to(repo) or not path.is_file() or not path.stat().st_size:
            raise ValueError(f"Invalid script source: {name}")
        content = path.read_bytes()
        if b"var talk = null;" not in content:
            raise ValueError(f"Script lacks corrected late binding: {name}")
        files.append((name, path, digest(path)))

    manifest = {
        "jarSha256": digest(jar),
        "replaceCount": len(files),
        "missingOnlyCount": 0,
        "requiredMigrationIds": [],
        "files": [
            {"path": name, "sha256": hash_value, "policy": "replace"}
            for name, _, hash_value in files
        ],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(output, "w:gz") as archive:
        archive.add(jar, arcname="Server.jar", recursive=False)
        for name, path, _ in files:
            archive.add(path, arcname=name, recursive=False)
        add_bytes(
            archive,
            "SHA256SUMS",
            "".join(
                f"{hash_value}  {name}\n"
                for name, hash_value in [("Server.jar", digest(jar))]
                + [(name, hash_value) for name, _, hash_value in files]
            ).encode("ascii"),
        )
        add_bytes(archive, "replace.list", "".join(f"{name}\n" for name, _, _ in files).encode("ascii"))
        add_bytes(archive, "missing-only.list", b"")
        add_bytes(archive, "deployment-manifest.json", (json.dumps(manifest, indent=2) + "\n").encode("utf-8"))
    print(json.dumps({"payload": str(output), "sha256": digest(output), "manifest": manifest}, indent=2))


if __name__ == "__main__":
    main()
