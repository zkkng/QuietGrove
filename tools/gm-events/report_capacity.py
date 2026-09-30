"""Summarize captured event trials; incomplete/headless evidence never approves a profile.

Input is a JSON manifest containing observed steps and paths to real frame-time CSVs.
Frame CSV accepts frameTimeMs or PresentMon columns (case insensitive). No generated frames.
"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path


def percentile(values, quantile):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * quantile) - 1)]


def frames(path):
    with Path(path).open(encoding="utf-8-sig", newline="") as source:
        rows = csv.DictReader(source)
        columns = {name.casefold(): name for name in (rows.fieldnames or [])}
        # A legacy GDI client can issue several presents for one displayed frame.
        # Use actual display changes when captured, excluding discarded presents.
        metric = next((name for name in ("frametimems", "msbetweendisplaychange", "msbetweenpresents") if name in columns), None)
        column = columns.get(metric)
        if column is None:
            raise ValueError(f"{path}: no supported measured frame-time column")
        values = []
        for row in rows:
            if metric == "msbetweendisplaychange" and columns.get("dropped") and row[columns["dropped"]] != "0":
                continue
            if not row[column].strip():
                continue
            value = float(row[column])
            if metric == "msbetweendisplaychange" and value == 0:
                continue
            values.append(value)
    if len(values) < 1000 or any(not math.isfinite(v) or v <= 0 for v in values):
        raise ValueError(f"{path}: incomplete/invalid frame capture")
    slowest = sorted(values, reverse=True)[:max(1, math.ceil(len(values) * .01))]
    return dict(frames=len(values), metric=column, seconds=sum(values) / 1000, medianMs=percentile(values, .5),
                p95Ms=percentile(values, .95), p99Ms=percentile(values, .99),
                onePercentLowFps=1000 / (sum(slowest) / len(slowest)),
                stallsOver250Ms=sum(v > 250 for v in values),
                sha256=hashlib.sha256(Path(path).read_bytes()).hexdigest())


def server_capture(path):
    """Check that a raw Linux capture is a continuous single-process observation."""
    required = {"utc", "elapsed_ms", "pid", "start_ticks", "proc_cpu_percent", "rss_bytes",
                "threads", "net_rx_bytes", "net_tx_bytes", "tcp_retrans_total",
                "established_game_sockets", "game_socket_send_queue_bytes"}
    count = 0
    first = last = None
    identity = None
    previous = None
    max_sockets = 0
    with Path(path).open(encoding="utf-8-sig", newline="") as source:
        rows = csv.DictReader(source)
        if not required.issubset(rows.fieldnames or []):
            raise ValueError(f"{path}: incomplete raw server capture columns")
        for row in rows:
            current = {key: int(row[key]) for key in ("elapsed_ms", "pid", "start_ticks", "rss_bytes",
                                                    "threads", "net_rx_bytes", "net_tx_bytes",
                                                    "tcp_retrans_total", "established_game_sockets",
                                                    "game_socket_send_queue_bytes")}
            if any(current[key] < 0 for key in current) or current["pid"] < 1 or current["rss_bytes"] < 1 or current["threads"] < 1:
                raise ValueError(f"{path}: invalid raw server counters")
            if not row["utc"]:
                raise ValueError(f"{path}: missing raw UTC timestamp")
            if identity is None:
                identity = current["pid"], current["start_ticks"]
                first = current["elapsed_ms"]
            elif identity != (current["pid"], current["start_ticks"]):
                raise ValueError(f"{path}: service process changed during trial")
            if previous is not None:
                if current["elapsed_ms"] <= previous["elapsed_ms"]:
                    raise ValueError(f"{path}: non-increasing server sample time")
                if any(current[key] < previous[key] for key in ("net_rx_bytes", "net_tx_bytes", "tcp_retrans_total")):
                    raise ValueError(f"{path}: reset server counter")
            if row["proc_cpu_percent"]:
                cpu = float(row["proc_cpu_percent"])
                if not math.isfinite(cpu) or cpu < 0:
                    raise ValueError(f"{path}: invalid process CPU sample")
            max_sockets = max(max_sockets, current["established_game_sockets"])
            previous = current
            last = current["elapsed_ms"]
            count += 1
    if count < 10 or last - first < 9000:
        raise ValueError(f"{path}: incomplete raw server capture")
    return dict(samples=count, seconds=(last-first)/1000, pid=identity[0],
                maxGameSockets=max_sockets, sha256=hashlib.sha256(Path(path).read_bytes()).hexdigest())


def evaluate(step, root):
    problems = []
    captures = []
    for capture in step.get("renderClients", []):
        if any(not capture.get(key) for key in ("machine", "clientHash", "resolution", "os", "cpu", "gpu", "networkPath")):
            problems.append("renderer machine/build/resolution/OS/CPU/GPU/network metadata missing")
        try:
            data = frames(root / capture["csv"])
            captures.append(data)
            if data["seconds"] < step.get("durationSeconds", 600) - 5:
                problems.append("renderer capture shorter than trial")
            if data["onePercentLowFps"] < 30 or data["stallsOver250Ms"] > 1:
                problems.append("legacy rendering gate failed")
        except (OSError, ValueError, KeyError) as error:
            problems.append(str(error))
    if not captures:
        problems.append("no actual render client capture")
    required = {"actionP95Ms": 100, "actionP99Ms": 200, "clockDriftMs": 250,
                "cpuPercent": 80, "heapPercent": 75, "outboundQueueAgeMs": 200}
    observed = step.get("server", {})
    for key, ceiling in required.items():
        value = observed.get(key)
        if type(value) not in (float, int) or not math.isfinite(value) or value < 0 or value > ceiling:
            problems.append(f"missing/failed {key}")
    for key in ("macroP99Ms", "movementP99Ms", "bytesPerSecond", "packetsPerSecond", "outboundQueueSize", "sendP99Ms", "retransmits", "gcP99PauseMs", "gcMaxPauseMs",
                "allocationBytesPerSecond", "threadBacklog", "lockWaitP99Ms", "dbP99Ms", "maxCoreCpuPercent"):
        if type(observed.get(key)) not in (int, float) or not math.isfinite(observed[key]) or observed[key] < 0:
            problems.append(f"missing {key}")
    if observed.get("queueGrowing") is not False or observed.get("disconnects") != 0:
        problems.append("network queue/disconnect gate failed or absent")
    raw_server = None
    try:
        path = (root / observed["captureFile"]).resolve()
        if not path.is_relative_to(root.resolve()):
            raise ValueError("raw server capture path escaped trial directory")
        raw_server = server_capture(path)
        if raw_server["seconds"] < step.get("durationSeconds", 600) - 5:
            problems.append("raw server capture shorter than trial")
        if observed.get("captureSha256", "").lower() != raw_server["sha256"]:
            problems.append("raw server capture SHA256 absent or mismatched")
    except (KeyError, OSError, ValueError) as error:
        problems.append(f"raw server capture missing/invalid: {error}")
    if step.get("durationSeconds", 0) < 600:
        problems.append("full-round capture incomplete")
    if step.get("mode") not in ("all-active", "mixed") or not step.get("trialId"):
        problems.append("mode or trial identity missing")
    for key in ("uniqueAvatars", "workingSetBytes", "mapLoadMs", "inputP99Ms", "crashes"):
        if type(step.get(key)) not in (int, float) or not math.isfinite(step[key]) or step[key] < 0:
            problems.append(f"missing client/gameplay {key}")
    if step.get("crashes") != 0:
        problems.append("client crash gate failed or absent")
    if step.get("gameplayPassed") is not True or not step.get("gameplayReport") or not (root / step.get("gameplayReport", "")).is_file():
        problems.append("gameplay acceptance evidence missing")
    if step.get("active", 0) < 1 or step.get("visible", 0) < step.get("active", 0):
        problems.append("invalid observed active/visible counts")
    return dict(active=step.get("active"), visible=step.get("visible"), mode=step.get("mode"),
                trialId=step.get("trialId"), durationSeconds=step.get("durationSeconds", 0),
                soak=step.get("soak") is True, renderClients=len(captures), frames=captures,
                serverCapture=raw_server, passing=not problems, problems=problems)


def complete_profiles(results):
    groups = {}
    for result in results:
        if not result["passing"]:
            continue
        key = (result["active"], result["visible"], result["mode"], result["renderClients"])
        groups.setdefault(key, []).append(result)
    complete = []
    for key, trials in groups.items():
        # One capture cannot prove several repeats. Hashes and trial IDs both have to be distinct.
        seen_ids, seen_captures = set(), set()
        repeats, soak = 0, False
        for trial in trials:
            hashes = {frame["sha256"] for frame in trial["frames"]}
            if trial["trialId"] in seen_ids or seen_captures.intersection(hashes):
                continue
            seen_ids.add(trial["trialId"])
            seen_captures.update(hashes)
            if trial["soak"] and trial["durationSeconds"] >= 3600:
                soak = True
            elif not trial["soak"]:
                repeats += 1
        if repeats >= 3 and soak:
            complete.append(dict(active=key[0], visible=key[1], mode=key[2], renderClients=key[3], repeatPasses=repeats))
    return complete


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    results = [evaluate(step, args.manifest.parent) for step in manifest["steps"]]
    passing = [r for r in results if r["passing"]]
    complete = complete_profiles(results)
    report = dict(eventKey=manifest["eventKey"], definitionVersion=manifest["definitionVersion"],
                  maps=manifest["maps"], revision=manifest.get("revision"),
                  highestPassingActive=max((r["active"] for r in complete), default=None),
                  highestPassingVisible=max((r["visible"] for r in complete), default=None),
                  headroom=.15, profilesApproved=False, completeProfiles=complete, trials=results)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"{len(passing)}/{len(results)} complete passing trials; approval remains an explicit measured profile review")


if __name__ == "__main__":
    main()
