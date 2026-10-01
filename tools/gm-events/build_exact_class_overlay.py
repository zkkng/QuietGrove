"""Apply one verified class replacement to an exact server JAR baseline."""

from __future__ import annotations

import hashlib
import sys
import zipfile
from pathlib import Path


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    baseline, replacement, output = map(Path, sys.argv[1:4])
    expected_sha256, entry = sys.argv[4:6]
    assert digest(baseline) == expected_sha256.lower(), "baseline hash mismatch"
    assert entry.endswith(".class") and not entry.startswith("/") and ".." not in entry
    new_class = replacement.read_bytes()
    assert new_class.startswith(b"\xca\xfe\xba\xbe")
    with zipfile.ZipFile(baseline) as original, zipfile.ZipFile(output, "w") as candidate:
        assert original.testzip() is None
        assert entry in original.namelist()
        for info in original.infolist():
            candidate.writestr(info, new_class if info.filename == entry else original.read(info.filename))
    with zipfile.ZipFile(baseline) as original, zipfile.ZipFile(output) as candidate:
        assert candidate.testzip() is None
        assert original.namelist() == candidate.namelist()
        changed = [name for name in original.namelist() if original.read(name) != candidate.read(name)]
        assert changed == [entry], f"unexpected changed entries: {changed}"
        print(f"changed_entries={changed}")
        print(f"baseline_sha256={digest(baseline)}")
        print(f"candidate_sha256={digest(output)}")


if __name__ == "__main__":
    main()
