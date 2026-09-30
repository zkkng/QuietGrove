import tempfile
import unittest
from pathlib import Path

import capture_server_metrics as capture


class ServerCaptureTest(unittest.TestCase):
    def test_proc_stat_handles_parentheses_and_process_identity(self):
        fields = ["0"] * 22
        fields[0] = "S"
        fields[11], fields[12], fields[17], fields[19], fields[21] = "120", "30", "48", "777", "2048"
        result = capture.process_stat("19361 (java (game)) " + " ".join(fields))
        self.assertEqual((19361, 150, 48, 777, 2048),
                         (result["pid"], result["cpu_ticks"], result["threads"],
                          result["start_ticks"], result["rss_pages"]))

    def test_network_retransmissions_and_only_established_game_sockets(self):
        dev = "Inter-| Receive | Transmit\n face |bytes packets errs drop fifo frame compressed multicast|bytes packets errs drop fifo colls carrier compressed\n"
        dev += "  lo: 100 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0\n"
        dev += "eth0: 300 0 0 0 0 0 0 0 500 0 0 0 0 0 0 0\n"
        self.assertEqual((300, 500), capture.network_dev(dev))
        snmp = "Tcp: ActiveOpens RetransSegs OutSegs\nTcp: 2 7 100\n"
        self.assertEqual(7, capture.retransmissions(snmp))
        table = "sl local_address rem_address st tx_queue:rx_queue\n"
        table += "0: 00000000:2124 0100007F:AAAA 01 00000020:00000000\n"  # 8484
        table += "1: 00000000:2124 0100007F:AAAB 0A 00000010:00000000\n"  # listening
        table += "2: 00000000:0016 0100007F:AAAC 01 00000010:00000000\n"  # SSH
        self.assertEqual((1, 32), capture.game_sockets(table))

    def test_rates_are_actual_deltas_and_pid_reuse_fails(self):
        first = dict(utc="start", monotonic=1.0, pid=7, start_ticks=99, cpu_ticks=100, rss_pages=2,
                     threads=3, net_rx_bytes=100, net_tx_bytes=200, tcp_retrans_total=5,
                     established_game_sockets=1, game_socket_send_queue_bytes=0,
                     cgroup_cpu_usec=2_000_000, cgroup_memory_bytes=1000)
        second = {**first, "utc": "end", "monotonic": 3.0, "cpu_ticks": 200,
                  "net_rx_bytes": 500, "net_tx_bytes": 800, "tcp_retrans_total": 7,
                  "cgroup_cpu_usec": 3_000_000}
        row = capture.row(first, second, 1.0, 100, 4096)
        self.assertEqual(50, row["proc_cpu_percent"])
        self.assertEqual(200, row["net_rx_bytes_per_second"])
        self.assertEqual(300, row["net_tx_bytes_per_second"])
        self.assertEqual(1, row["tcp_retrans_per_second"])
        self.assertEqual(50, row["cgroup_cpu_percent"])
        self.assertEqual(8192, row["rss_bytes"])
        with self.assertRaisesRegex(RuntimeError, "recycled"):
            capture.row(first, {**second, "start_ticks": 100}, 1.0, 100, 4096)

    def test_sampler_reads_fixture_without_guessing_missing_cgroup_data(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            proc = root / "proc"
            (proc / "7/net").mkdir(parents=True)
            (proc / "net").mkdir(parents=True)
            fields = ["0"] * 22
            fields[0] = "S"
            fields[17], fields[19], fields[21] = "4", "88", "2"
            (proc / "7/stat").write_text("7 (java) " + " ".join(fields))
            (proc / "net/dev").write_text("header\nheader\neth0: 3 0 0 0 0 0 0 0 4 0 0 0 0 0 0 0\n")
            (proc / "net/snmp").write_text("Tcp: RetransSegs\nTcp: 9\n")
            (proc / "7/net/tcp").write_text("header\n0: 00000000:2124 0:0 01 00000000:00000000\n")
            sampled = capture.sample(7, proc, root / "absent-cgroup")
            self.assertEqual((3, 4, 9, 1), (sampled["net_rx_bytes"], sampled["net_tx_bytes"],
                                           sampled["tcp_retrans_total"], sampled["established_game_sockets"]))
            self.assertIsNone(sampled["cgroup_memory_bytes"])


if __name__ == "__main__":
    unittest.main()
