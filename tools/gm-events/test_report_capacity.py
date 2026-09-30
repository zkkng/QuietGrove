"""Parser/gate fixtures only; these generated values are never runtime capacity evidence."""
import math
import tempfile
import unittest
from pathlib import Path
from report_capacity import complete_profiles, evaluate, frames, server_capture


class CapacityReportTests(unittest.TestCase):
    def test_headless_and_missing_data_never_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            result = evaluate(dict(active=1000, visible=1000, repeats=100, soakMinutes=100), Path(folder))
            self.assertFalse(result["passing"])
            self.assertIn("no actual render client capture", result["problems"])
            self.assertEqual([], complete_profiles([result]))

    def test_invalid_frame_samples_and_percentiles(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "fixture.csv"
            path.write_text("frameTimeMs\n" + "16\n" * 1000, encoding="utf-8")
            self.assertEqual(62.5, frames(path)["onePercentLowFps"])
            path.write_text("frameTimeMs\n" + "nan\n" * 1000, encoding="utf-8")
            with self.assertRaises(ValueError):
                frames(path)

    def test_distinct_three_repeats_and_additional_soak_required(self):
        def trial(identity, soak=False, capture=None):
            return dict(active=25, visible=30, mode="mixed", renderClients=1, passing=True,
                        trialId=identity, durationSeconds=3600 if soak else 600, soak=soak,
                        frames=[dict(sha256=capture or identity)])
        self.assertEqual([], complete_profiles([trial("a"), trial("b"), trial("c")]))
        self.assertEqual([], complete_profiles([trial("a"), trial("b"), trial("c", capture="a"), trial("soak", True)]))
        self.assertEqual(1, len(complete_profiles([trial("a"), trial("b"), trial("c"), trial("soak", True)])))

    def test_legacy_gdi_uses_displayed_frames_not_discarded_presents(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "fixture.csv"
            path.write_text("msBetweenPresents,msBetweenDisplayChange,Dropped\n"
                            + "2,0,1\n2,16,0\n" * 1000, encoding="utf-8")
            result = frames(path)
            self.assertEqual(1000, result["frames"])
            self.assertEqual("msBetweenDisplayChange", result["metric"])
            self.assertEqual(62.5, result["onePercentLowFps"])

    def test_raw_server_capture_requires_one_continuous_pid_and_monotonic_counters(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "server.csv"
            header = "utc,elapsed_ms,pid,start_ticks,proc_cpu_percent,rss_bytes,threads,net_rx_bytes,net_tx_bytes,tcp_retrans_total,established_game_sockets,game_socket_send_queue_bytes\n"
            def sample(index, pid=7, rx=None):
                return f"2026-09-30T00:00:{index:02d}Z,{index*1000},{pid},10,5,4096,4,{index if rx is None else rx},{index*2},0,1,0\n"
            path.write_text(header + "".join(sample(i) for i in range(11)), encoding="utf-8")
            result = server_capture(path)
            self.assertEqual((11, 10, 7, 1), (result["samples"], result["seconds"],
                                                result["pid"], result["maxGameSockets"]))
            path.write_text(header + "".join(sample(i, pid=8 if i == 10 else 7) for i in range(11)), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "process changed"):
                server_capture(path)
            path.write_text(header + "".join(sample(i, rx=0 if i == 10 else i) for i in range(11)), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "reset server counter"):
                server_capture(path)


if __name__ == "__main__":
    unittest.main()
