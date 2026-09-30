# GM event measurement tools

No measured crowd ceiling has been published. Unit fixture counts are not capacity results.

## Combined release staging for the three closeouts

`build_combined_payload.py` validates the pinned content manifest and every local asset hash, then creates an immutable tarball with the combined Server.jar, the exact reviewed content paths, the GM runtime scripts, and the 2,052 upstream WZ XML assets under a missing-only policy. Add trainer-owned runtime assets through a reviewed `replacePaths` extra manifest after trainer's final source freeze. The builder has `--verify-only` for hash checks without creating a payload. It never includes live `config.yaml`.

`G:/Maplestory v83 server dev/ops/gm-combined-release.ps1` uploads the exact tarball and guest installer with pinned host key and hashes. Its default is a guest comparison with no live app changes; `-Apply` performs a serialized, no-player, PID/JAR/config-guarded release. The guest installer snapshots the stopped database and exact replaced files, preserves live rates/config/service definitions, installs missing-only XML only when absent, verifies startup, file hashes and required Liquibase IDs, and restores file changes on failed startup. The database snapshot is retained for recovery; additive migration rollback requires a reviewed database action and is not automatic. Run the dry comparison and inspect its counts before applying. Do not run a historical deployment script pinned to an older JAR/PID.

For the reviewed WORLD-01 4,000 ms to 10,000 ms respawn correction, pass `-SetRespawn10000` in both dry-run and apply. The guest installer requires exactly one old 4,000 ms line, changes only that line, backs up the whole live config, verifies the target hash and restores it on release failure. The source `config.yaml` is never copied wholesale, preserving live rates and other settings.

## Trigger the finite hosted Henesys waves

From PowerShell in the project repository, run
`& .\tools\gm-events\Start-Henesys-Waves.ps1 -Action start`.
Use `-Action status` to inspect it and `-Action stop` to clean up its owned monsters
and host tasks. This uses the existing authorized private-server operator connection;
the player can observe it on world0/channel1 in Henesys without becoming a GM.
The host displays exactly one `[GM]` prefix and the actual Wizet uniform. The default
plan uses three Mushmoms, then two Jr. Balrogs, then one Crimson Balrog, with a20-second
introduction, at least15 seconds between spawns and30 seconds between cleared waves.
Later waves require real defeat. No more than3 bosses or30 ordinary descendants can
be alive. This is explicitly an UNMEASURED finite trial, not a measured crowd profile.

For an existing GM rank3 operator in a public map, the same runtime is available as
`!event waves bosses`, `!event waves mobs`, and `!event stop-waves`.

`EventProbe.java` is a finite, read-only Java attach probe for the owned SoloMapling JVM. It writes an actual live bot census by world/channel/map/level/job and a 1 Hz CPU/heap/platform-thread/GC-counter baseline to `logs/event-capacity`. It does not create bots, execute commands, change actor state, collect player names, or treat network actors as render clients. Its potential-availability count precedes exclusive-task and dialogue checks. GC counters alone do not establish GC pause p99.

Compile outside Maven's output directory with the existing Java 21 JDK, package `Agent-Class: gmevents.EventProbe`, and run `gmevents.AttachProbe <pid> <agent-jar> <basename>,<seconds>`. Sampling is bounded to 1–3600 seconds. Use a fresh basename; existing captures are preserved. The deployed guest already supports `jdk.attach`; it does not currently provide `jcmd`.

`report_capacity.py` accepts a manifest of actual observed trials and measured frame CSV files. It validates frame captures, computes median/p95/p99, slowest-one-percent FPS and >250 ms stalls, hashes the input captures, and lists every failed/missing gate. Headless trials never approve a profile. Raw server and gameplay reports must exist. The report retains `profilesApproved: false`; review the complete measurements before installing a runtime profile.

`ops/gm-event-server-capture.ps1` collects pinned-PID raw Linux process/container/network/TCP CSV. `ops/gm-event-jfr-capture.ps1` runs a finite JFR profile through `JfrCaptureAgent.java` because this guest JRE has `jdk.jfr` and `jdk.attach` but no `jcmd`; the wrapper pins PID/JAR/config, rejects overlapping recordings, downloads a hash-checked `.jfr`, and validates it with the local JDK. It is diagnostic tooling, separate from the server release JAR. Pair both captures with the actual legacy-client frames and gameplay notes for each trial; the old-server smoke captures are not event capacity evidence.

During an allocated real-client slot, `Capture-EventFrames.ps1 -TrialId <unique-id> -Seconds <600..7200>` records a finite, uniquely named PresentMon capture for the 10-minute full-round trials or 60-minute soak. It uses only the existing verified portable tool and never requests elevation; the PowerShell session must already have ETW access. Record the synchronized event ID and server metrics separately, then feed the raw CSV to the reporter. A capture of an idle screen is not event capacity evidence.

Run the progression in `docs/gm-events-spec.md`: 25, 50, 100, 200, 400, 800, 1200, 1600, then +25% while gates pass; refine the first failure. These are planned steps. Record actual active/visible counts, unique avatars, human map entry, join bursts, scoring/peak effects, results and cleanup. Compare all-active and participant/spectator mixes with 1/3/10 real viewers where machines permit. Final candidate needs three passes plus a 60-minute soak under normal world load.

Explicit developer trials use a human GM's `!event create <key> <operator-limit>` followed by `!event trial-bots <count> [spectators]`, or `!event bot-host-trial <key> <operator-limit> [bot-participants]` to exercise an actual bot host with optional queued competitors and a reserved human seat. These are labeled UNMEASURED and cannot approve normal bot hosting or player requests. A finite supported existing population supplies actors; the commands do not generate elite builds or increase population.

An unmeasured bot-host trial waits up to five minutes for its first human entrant so a single real client can switch from the GM operator to a normal character. Normal requested bot-host events retain the 90-second empty-lobby timeout. The host starts its ten-second countdown once a human has joined and the first 30 seconds have elapsed.
