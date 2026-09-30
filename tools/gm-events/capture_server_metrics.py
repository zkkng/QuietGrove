#!/usr/bin/env python3
"""Capture raw Linux process, network and game-socket samples for GM crowd trials.

Run inside the CT202 guest beside the Java service. This collector never infers
client FPS, gameplay success, database latency or an approved crowd profile.
"""

import argparse
import csv
import os
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path


GAME_PORTS = {8484, 8488, 7575, 7576, 7577}
FIELDS = (
    "utc", "elapsed_ms", "pid", "start_ticks", "proc_cpu_percent", "rss_bytes", "threads",
    "net_rx_bytes", "net_tx_bytes", "net_rx_bytes_per_second", "net_tx_bytes_per_second",
    "tcp_retrans_total", "tcp_retrans_per_second", "established_game_sockets",
    "game_socket_send_queue_bytes", "cgroup_cpu_percent", "cgroup_memory_bytes",
)


def process_stat(raw):
    # The parenthesized comm field may itself contain spaces or parentheses.
    match = re.match(r"^(\d+) \((.*)\) (.*)$", raw.strip())
    if not match:
        raise ValueError("Invalid /proc/PID/stat")
    fields = match.group(3).split()  # begins at Linux field 3 (state)
    if len(fields) < 22:
        raise ValueError("Short /proc/PID/stat")
    return {
        "pid": int(match.group(1)), "cpu_ticks": int(fields[11]) + int(fields[12]),
        "threads": int(fields[17]), "start_ticks": int(fields[19]), "rss_pages": int(fields[21]),
    }


def network_dev(raw):
    rx = tx = 0
    for line in raw.splitlines()[2:]:
        if ":" not in line:
            continue
        name, counters = line.split(":", 1)
        if name.strip() == "lo":
            continue
        numbers = counters.split()
        if len(numbers) < 16:
            raise ValueError("Short /proc/net/dev row")
        rx += int(numbers[0])
        tx += int(numbers[8])
    return rx, tx


def retransmissions(raw):
    lines = raw.splitlines()
    for index in range(len(lines) - 1):
        names, values = lines[index].split(), lines[index + 1].split()
        if names and values and names[0] == values[0] == "Tcp:" and "RetransSegs" in names:
            return int(values[names.index("RetransSegs")])
    raise ValueError("TCP RetransSegs unavailable")


def game_sockets(*tables):
    count = queued = 0
    for table in tables:
        for line in table.splitlines()[1:]:
            parts = line.split()
            if len(parts) < 5 or ":" not in parts[1] or parts[3] != "01":
                continue
            if int(parts[1].rsplit(":", 1)[1], 16) not in GAME_PORTS:
                continue
            count += 1
            queued += int(parts[4].split(":", 1)[0], 16)
    return count, queued


def cgroup_cpu(raw):
    for line in raw.splitlines():
        key, *values = line.split()
        if key == "usage_usec" and len(values) == 1:
            return int(values[0])
    return None


def optional_int(path):
    try:
        return int(path.read_text().strip())
    except (OSError, ValueError):
        return None


def optional_text(path):
    try:
        return path.read_text()
    except OSError:
        return ""


def sample(pid, proc_root=Path("/proc"), cgroup_root=Path("/sys/fs/cgroup")):
    base = proc_root / str(pid)
    stat = process_stat((base / "stat").read_text())
    if stat["pid"] != pid:
        raise RuntimeError("PID changed during capture")
    rx, tx = network_dev((proc_root / "net/dev").read_text())
    sockets, send_queue = game_sockets(optional_text(base / "net/tcp"), optional_text(base / "net/tcp6"))
    return {
        **stat, "net_rx_bytes": rx, "net_tx_bytes": tx,
        "tcp_retrans_total": retransmissions((proc_root / "net/snmp").read_text()),
        "established_game_sockets": sockets, "game_socket_send_queue_bytes": send_queue,
        "cgroup_cpu_usec": cgroup_cpu(optional_text(cgroup_root / "cpu.stat")),
        "cgroup_memory_bytes": optional_int(cgroup_root / "memory.current"),
        "monotonic": time.monotonic(), "utc": datetime.now(timezone.utc).isoformat(),
    }


def rate(previous, current, key, seconds):
    if previous is None or seconds <= 0:
        return ""
    delta = current[key] - previous[key]
    if delta < 0:
        raise RuntimeError(f"Counter reset during capture: {key}")
    return round(delta / seconds, 3)


def row(previous, current, began, ticks_per_second, page_size):
    elapsed = "" if previous is None else current["monotonic"] - previous["monotonic"]
    if previous is not None and current["start_ticks"] != previous["start_ticks"]:
        raise RuntimeError("The service PID was recycled during capture")
    process_rate = rate(previous, current, "cpu_ticks", elapsed)
    cgroup_rate = ""
    if previous is not None and current["cgroup_cpu_usec"] is not None and previous["cgroup_cpu_usec"] is not None:
        cgroup_rate = rate(previous, current, "cgroup_cpu_usec", elapsed)
        cgroup_rate = round(cgroup_rate / 10_000, 3)
    return {
        "utc": current["utc"], "elapsed_ms": round((current["monotonic"] - began) * 1000),
        "pid": current["pid"], "start_ticks": current["start_ticks"],
        "proc_cpu_percent": "" if process_rate == "" else round(process_rate * 100 / ticks_per_second, 3),
        "rss_bytes": current["rss_pages"] * page_size, "threads": current["threads"],
        "net_rx_bytes": current["net_rx_bytes"], "net_tx_bytes": current["net_tx_bytes"],
        "net_rx_bytes_per_second": rate(previous, current, "net_rx_bytes", elapsed),
        "net_tx_bytes_per_second": rate(previous, current, "net_tx_bytes", elapsed),
        "tcp_retrans_total": current["tcp_retrans_total"],
        "tcp_retrans_per_second": rate(previous, current, "tcp_retrans_total", elapsed),
        "established_game_sockets": current["established_game_sockets"],
        "game_socket_send_queue_bytes": current["game_socket_send_queue_bytes"],
        "cgroup_cpu_percent": cgroup_rate, "cgroup_memory_bytes": current["cgroup_memory_bytes"],
    }


def capture(pid, duration, interval, output):
    if not Path(f"/proc/{pid}/stat").is_file():
        raise ValueError("Target PID is not alive in this Linux process namespace")
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise FileExistsError("Refusing to overwrite a prior raw capture")
    ticks = os.sysconf("SC_CLK_TCK")
    page_size = os.sysconf("SC_PAGE_SIZE")
    started = time.monotonic()
    previous = None
    count = 0
    with output.open("x", newline="", encoding="utf-8") as destination:
        writer = csv.DictWriter(destination, fieldnames=FIELDS)
        writer.writeheader()
        while True:
            current = sample(pid)
            writer.writerow(row(previous, current, started, ticks, page_size))
            destination.flush()
            count += 1
            previous = current
            due = started + count * interval
            if due > started + duration:
                break
            time.sleep(max(0, due - time.monotonic()))
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--duration-seconds", type=int, required=True)
    parser.add_argument("--interval-seconds", type=float, default=1)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.pid < 1 or not 10 <= args.duration_seconds <= 7200 or not .2 <= args.interval_seconds <= 5:
        parser.error("PID, 10..7200 second duration and .2..5 second interval required")
    count = capture(args.pid, args.duration_seconds, args.interval_seconds, args.output)
    print(f"Captured {count} actual server samples to {args.output}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        print(f"Server capture failed: {error}", file=sys.stderr)
        sys.exit(1)
