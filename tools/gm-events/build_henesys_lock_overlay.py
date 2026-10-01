"""Replace only BotSM.class in the exact live statistics JAR."""

from __future__ import annotations

import hashlib
import sys
import zipfile
from pathlib import Path


LIVE_SHA256 = "92627c55c4c092178c392779fe62bdb7c7374e24481e32c0072b321ed9e78693"
ENTRY = "soloMapling/ArtificialPlayer/BotSM.class"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    baseline, replacement, output = map(Path, sys.argv[1:4])
    assert digest(baseline) == LIVE_SHA256, "baseline is not the pinned live JAR"
    new_class = replacement.read_bytes()
    assert new_class.startswith(b"\xca\xfe\xba\xbe")
    with zipfile.ZipFile(baseline) as original, zipfile.ZipFile(output, "w") as candidate:
        assert original.testzip() is None
        assert ENTRY in original.namelist()
        for info in original.infolist():
            payload = new_class if info.filename == ENTRY else original.read(info.filename)
            candidate.writestr(info, payload)
    with zipfile.ZipFile(baseline) as original, zipfile.ZipFile(output) as candidate:
        assert candidate.testzip() is None
        assert original.namelist() == candidate.namelist()
        changed = [name for name in original.namelist() if original.read(name) != candidate.read(name)]
        assert changed == [ENTRY], f"unexpected changed entries: {changed}"
        print(f"changed_entries={changed}")
        print(f"baseline_sha256={digest(baseline)}")
        print(f"candidate_sha256={digest(output)}")


if __name__ == "__main__":
    main()
