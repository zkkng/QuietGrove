# WORLD-01 finite respawn observation

This is a read-only fixture for the next combined client round. It samples
already loaded map objects in the owned SoloMapling JVM once per second. It
never loads a map, spawns or kills a monster, changes config, or reads character
names. `MonsterRespawnTest` already exercises the source formula; this tool is
for the separate live density and cadence check. A CSV alone is not a client
acceptance result.

## Build checkpoint

Build from the repository root with the bundled Java 21 JDK. The included
`gmevents.AttachProbe` launcher only attaches the probe to the exact PID the
GM release owner supplies; it does not change server code or the release JAR.

```powershell
$jdk = 'G:\Maplestory v83 server dev\tools\java21\jdk-21.0.12.1+1\bin'
& "$jdk\javac.exe" -d target/world-respawn-probe tools/world-respawn/WorldRespawnProbe.java tools/gm-events/AttachProbe.java
& "$jdk\jar.exe" --create --file target/world-respawn-probe.jar --manifest tools/world-respawn/agent.mf -C target/world-respawn-probe worldrespawn -C target/world-respawn-probe gmevents
Get-FileHash target/world-respawn-probe.jar -Algorithm SHA256
```

The GM release owner stages this exact hashed diagnostic JAR on the guest,
separate from `Server.jar`. Attach runs **on that same guest** with its Java 21
runtime; a Windows-local PID cannot attach to the Proxmox guest. Use a fresh
report basename and a short duration after GM identifies the active guest PID,
verifies deployed JAR/config hashes, and grants the probe slot. Guest example
from the staged directory:

```sh
java --add-modules jdk.attach -cp world-respawn-probe.jar gmevents.AttachProbe <exact-pid> world-respawn-probe.jar 'world01-a,120,0,1,100020000|102020000|100040000'
```

The pipe characters inside the last argument must be quoted.
The daemon writes `logs/world-respawn/world01-a.csv` in the server working
directory and refuses to overwrite an existing report. The comma fields are
basename, seconds (2–600), world, channel and one to eight map IDs separated
by `|`. A missing/`loaded=false` row means the client has not entered that
map; the probe does not load it for the test.

## Combined client scenarios

The three candidate WZ maps have ordinary monsters with `mobTime=0`:

| Map ID | Static points | One occupant target | Six occupant target |
| --- | ---: | ---: | ---: |
| `100020000` | 10 | 8 | 10 |
| `102020000` | 23 | 18 | 23 |
| `100040000` | 58 | 44 | 58 |

These targets use the current source formula and assume full respawn is off.
Reproduce the source fixture with
`python tools/world-respawn/audit_maps.py --output target/world-respawn-map-fixture.json`;
the current report SHA-256 is
`E66BD7C0B1AB795A0FF679741845CD4C97DF2C4613B866F9CAE4471BB449B6A3`.
It includes each map XML SHA-256 and monster IDs. This only inventories source
WZ; compare the deployed XML hashes before using its counts as live targets.
Choose maps that are free of a GM incident, event, trainer movement/attack
automation and special summons. Use a normal authenticated client to enter
the map and stay for at least three 10-second ticks. Record the deployed JAR,
config SHA-256, client build, map, UTC times and any other visible occupants.

1. At stable population, compare the CSV `spawnedCount` and `visibleAlive`
   with `expectedTarget = ceil((0.70 + 0.05 * min(6, occupants)) * staticPoints)`.
   The client must visibly show ordinary monsters. A `spawnedCount` above the
   target can reflect a summon or scripted actor; investigate rather than
   declaring the formula wrong. Denied/cooldown points can keep it below.
2. Defeat several ordinary mobs on screen and mark the UTC time. Observe the
   count drop and refill near a later 10-second `RESPAWN_INTERVAL` tick, after
   the spawnpoint cooldown/kill animation. Repeat in a second map. Count
   actual elapsed time from the CSV; the configured interval alone is not a
   measured respawn interval.
3. If the client can safely add another ordinary participant on the same
   map/channel, compare the changed `occupants` and target without restarting
   the map. Do not manufacture population by adding GM summons or event mobs.
4. Relog and revisit the map, then repeat once after GM's coordinated restart
   to verify the installed policy and ordinary map loading persist. Record any
   irregular map ID, active bots, blocked spawnpoint or capacity effect.

Source checkpoint: `src/test/java/server/maps/MonsterRespawnTest.java` checks
the formula, no-occupant behavior, cooldown/denied points, summon count and
the source config. The latest combined Java suite passed 3,864 tests including
that suite. The live WORLD-01 row and its percentage stay unchanged until the
above client/probe observations are captured and reviewed.

Old-JAR baseline at 2026-09-30 18:27–18:28 UTC: GM attached this finite
read-only probe for 60 samples per map on CT202 PID 19361. The copied raw CSV
is `docs/world-audit/world01-old-jar-baseline-20260930.csv`, SHA-256
`688D8D748DBC757DE17253DEE28F637ABA89DF66A383ABE4E18B50FBA904FAA2`.
All three maps were loaded and had 10/23/58 live monsters with four to seven
occupants; no static points were denied. **The runtime interval was 4000 ms**,
despite the local source config and WORLD-01 target of 10000 ms. The client
did not kill mobs or view this run, so the CSV does not measure refill timing.
GM owns a narrow backed-up live config correction in a future combined release;
the next client/probe run must record the corrected installed config hash and
actual refill timing. A target lower than current spawned count after an
occupant leaves is not automatically an overspawn defect: this loop does not
despawn healthy monsters merely to lower density.

Local fixture checkpoint at 2026-09-30 11:14 PDT: Java 21 `javac` compiled
`WorldRespawnProbe.java` and the existing attach launcher with exit 0; the
agent JAR manifest and class entries were inspected. The current local
`target/world-respawn-probe.jar` SHA-256 is
`C1903F60BC907A2C8349B3E1CA58C0277389B8CB258BC56975824CC3FB1F5EAB`.
It has **not** been attached to a live JVM. GM must rehash any staged copy and
schedule it with the next combined client round.
