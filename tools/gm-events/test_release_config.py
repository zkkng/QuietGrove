"""Narrow live-config correction checks for the combined release installer."""

import importlib.util
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch


INSTALLER = Path(__file__).resolve().parents[3] / "ops" / "gm-combined-release-guest.py"
spec = importlib.util.spec_from_file_location("gm_combined_release_guest", INSTALLER)
installer = importlib.util.module_from_spec(spec)
with patch.dict(sys.modules, {"fcntl": types.ModuleType("fcntl"), "pwd": types.ModuleType("pwd")}):
    spec.loader.exec_module(installer)


class RespawnConfigTest(unittest.TestCase):
    def test_only_respawn_line_changes(self):
        with tempfile.TemporaryDirectory() as folder:
            config = Path(folder) / "config.yaml"
            original = (b"rates:\n  exp: 10\n  meso: 8\n  drop: 2\n  boss: 3\n"
                        b"    RESPAWN_INTERVAL: 4000  #old experiment\n"
                        b"    USE_ENABLE_FULL_RESPAWN: false\n")
            config.write_bytes(original)
            with patch.object(installer, "LIVE", Path(folder)):
                target, target_hash = installer.respawn_config(True)
            self.assertEqual(target, original.replace(
                b"    RESPAWN_INTERVAL: 4000  #old experiment\n",
                b"    RESPAWN_INTERVAL: 10000             #Stock Cosmic/HeavenMS respawn pass: 10 seconds; individual WZ mobTime remains authoritative.\n"))
            self.assertEqual(target_hash, installer.hashlib.sha256(target).hexdigest())
            self.assertEqual(config.read_bytes(), original)

    def test_refuses_ambiguous_or_unexpected_baseline(self):
        with tempfile.TemporaryDirectory() as folder:
            config = Path(folder) / "config.yaml"
            with patch.object(installer, "LIVE", Path(folder)):
                for raw in (b"RESPAWN_INTERVAL: 10000\n",
                            b"RESPAWN_INTERVAL: 4000\nRESPAWN_INTERVAL: 4000\n"):
                    config.write_bytes(raw)
                    with self.assertRaises(ValueError):
                        installer.respawn_config(True)

    def test_disabled_preserves_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            config = Path(folder) / "config.yaml"
            config.write_bytes(b"RESPAWN_INTERVAL: 4000\r\n")
            with patch.object(installer, "LIVE", Path(folder)):
                target, digest = installer.respawn_config(False)
            self.assertIsNone(target)
            self.assertEqual(digest, installer.hashlib.sha256(config.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
