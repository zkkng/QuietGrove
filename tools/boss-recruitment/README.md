# Boss recruitment live acceptance

`BossAcceptanceProbe` observes existing boss companion tasks for 1–600 seconds. It
does not recruit, move, spawn, heal, grant items, reset telemetry or change configuration.
Build it against the exact reviewed server JAR and attach as the existing server user
using the existing `gmevents.AttachProbe` launcher. Argument: `report-basename,seconds`.
Reports are created exclusively in `logs/boss-acceptance/<basename>.jsonl`.

The report records task generations, party/world/channel identity, actual HP/MP,
EXP/mesos and finite USE/ETC inventories; active map instances and observed boss roots,
phases, HP and controller identities; existing hunt outcome/traffic reports; and server
timing measurements. Collection visits current tasks and their maps once per second.

## Required runs

- One ordinary party with a human and five admitted companions, followed by several
  concurrent hunts. Record actual party size and map identities; do not call a sparse
  refusal a five-helper performance test.
- Each of the 19 registry entries individually, including lawful boat boarding,
  Papulatus entry/trigger, expedition prerequisites and both Balrog instances. Record
  refusal/absence honestly; those do not prove a successful playable encounter.
- Zero visible humans, hidden GM, then human arrival/departure controller transitions.
- Actual damage, finite resource consumption, learned healing/HS, legitimate completion
  and reward ownership. Compare canonical reward/death logs and actual inventory changes.
- Cancel, retarget, continuing-party dismissal, leader/disband/logout/channel/death
  and restart cleanup. Verify natural bosses remain and prior bot activity resumes once.

## Interpretation

This is a sampled observer, not a death or reward ledger. A monster disappearing between
samples does not prove a legitimate kill; a bot can die and leave the map between samples.
Use canonical outcomes/death/reward evidence to resolve those cases. Inventory deltas can
include normal drops/shops; recording a delta alone does not establish correct attribution.
Controller null alone does not prove the server driver executed an effect.

Timing counts and packet/byte counters are cumulative; compare start/end snapshots.
Timing percentiles cover the bounded most recent 1,024 samples, while maximum covers the
measurement lifetime. Exact-map traffic can include other visible activity. Run the client
PresentMon capture at the same time and record its process/map interval. This observer
cannot verify client rendering, FPS, end-to-end latency or a playable capacity limit.

## Hosted trial diagnostics

`HostedFailureProbe` records the first failed trial's retained canonical events and
current original host state. Historical samples do not establish its shutdown cause.
`HostedTelemetryProbe` observes the actual Henesys show, incident telemetry and
committed actors' HP/MP build context once per second for 180 seconds. Neither starts
a show, mutates actors, changes configuration or supplies capacity approval. Sources
use separate agent manifests and fresh output basenames in `logs/boss-acceptance`.

`HostedLifecycleProbe` observes for 420 seconds. It tracks only committed leases in
world 0/channel 1, records real release/replacement generations, map ownership,
instrumentation retirement, the actual FSM/running/scheduled state and temporary
host name/gear restoration. It also reports the loaded companion flag. Compile it
against the exact reviewed server JAR, package with `lifecycle-agent.mf`, and attach
through `gmevents.AttachProbe` with a fresh basename. It performs no mutations and
cannot establish genuine death/reward or client rendering; compare canonical logs.
