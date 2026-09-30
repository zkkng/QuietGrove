"""Build the reviewed x86 client adapter with one canonical PATH key.

Codex's host currently exposes both Path and PATH. MSBuild's CL task rejects
that inherited environment, so only the child build environment is normalized.
"""

import hashlib
import os
from pathlib import Path
import subprocess
import sys


root = Path(__file__).resolve().parent
solution = root / "MapleEzorsia-v2" / "Ezorsia V2.sln"
builder = Path(r"C:\BuildTools\MSBuild\Current\Bin\MSBuild.exe")
path_value = os.environ.get("PATH") or os.environ.get("Path")
environment = {key: value for key, value in os.environ.items() if key.casefold() != "path"}
if path_value:
    environment["PATH"] = path_value

result = subprocess.run(
    [str(builder), str(solution), "/p:Configuration=Release", "/p:Platform=x86",
     "/m:1", "/verbosity:minimal"],
    env=environment,
    check=False,
)
if result.returncode:
    sys.exit(result.returncode)

artifact = root / "MapleEzorsia-v2" / "out" / "Release" / "dinput8.dll"
print(f"{artifact} SHA256 {hashlib.sha256(artifact.read_bytes()).hexdigest().upper()}")
