# Trainer crash comparison, September 30

**Current result: the October1 baseline eventually failed after about fifteen minutes. The earlier short observation below is not stability acceptance. This failure has a different original signature from the earlier secured-data exceptions.**

## Installed comparison

At 10:54 PM PDT, installed the exact six-byte comparison built by `tools/client-diagnostics/prepare_trainer_baseline.py`. SHA256: `F8CF5AEB401D27EAA3B3054FD6A8DA3B02A6236F4E4E957CC41FC98C3EA185C8`.

- RVA CDE7: NOP the sole call to installed v5 trainer Start at E590. This removes its two fly hooks and trainer control pipe worker.
- RVA C0D2: JNS to JZ preserves the previously verified native nonzero exception handling repair.

The rest of the existing proxy, executable, config, assets and diagnostic sidecar are retained. This is a temporary diagnostic comparison; powers cannot be accepted while their adapter is disabled. GM owns the single launch and manual authentication handoff. Server FBECFD111C70BD236DE9774C2F894D96EC545303C37441CA801074470BA76CAA remains unchanged for comparison with the same attacking bots in Henesys.

Windows refused overwrite because the old image remained locked after exit. Same-directory rename succeeded. Rollback copy: `C:/Users/Lupert/Games/SoloMapling-v83/dinput8.v5-baseline-rollback.dll`, SHA256 `A4DF286AF3058C2AFBAF2B421F2C8982E392E19F474697086D2E1BB278D30348`. A separate workspace backup is in `tools/client-diagnostics/baseline-backup/dinput8.dll`. No duplicate client was launched by the installer.

## Evidence and limits

Latest failed PID45184 captured original native code5 while handling opcode BC, length22. Its stack reaches secured-double checksum verification at 539338 from 9B62A1. This differs from the earlier Avenger action checksum failure; the ranged route repair alone was insufficient. The user reports neither MouseFly nor Rapid Attack was enabled, and the attacking actors were bots.

Installed v5 fly hooks differ from current source. They preserve ECX/EDX but alter flags even when OFF. However, native continuations immediately TEST EDI or EBX, replacing those flags. Missing PUSHFD alone is therefore not a proven cause. This invocation fails before the X hook site. Earlier state corruption remains a hypothesis.

A successful comparison would implicate the removed adapter but still needs reproducibility. A failed comparison would exclude that adapter's hooks and worker as necessary causes; it would not exclude the remaining proxy rewrites or shared server bot attack path. No checksum bypass, suppression of bot combat, or additional per-skill patch was applied.

**Actual comparison gameplay is pending.** Manifest and installation receipt are under `tools/client-diagnostics/`. The sidecar's old-proxy identity check may reject its redundant runtime error repair; this comparison already contains that correction on disk.

## October 1 initial real comparison

User launched PID45956 at 01:08:13 PDT and authenticated manually. GM independently confirmed the human Admin in Henesys100000000 and unchanged server FBEC/PID27894. Native owner performed no launch or UI input in this run. Installed proxy F8CF was independently rehashed; trainer log still has no adapter-start messages after September30 22:44.

At 01:13:47 PDT the same process remained responsive, with criticalCount0, minidumps0 and null packets discarded0. Logged attack metadata included 42 BA melee, 27 BB ranged and 2 BC magic packets, in addition to movement packets. The faults file remained empty. This exceeds five minutes since startup and the earlier rapid failure windows. It supports investigating the removed adapter, but does not establish causal proof, full stability, or acceptance of disabled trainer controls. Preserve the same server and assets for any next comparison.

Snapshot: parent `tmp/trainer-baseline-20261001-0114/`, with file hashes. Counts reflect retained diagnostic metadata, not guaranteed complete packet totals. User may have started a GM wave during this interval; event start and responder counts require the server receipt, and have not been claimed here. A separate human GM SpecialMoveHandler packet-format error is tracked by GM and is not attributed to the native bot attack checksum failure.

GM subsequently correlated event start at 01:11:15 PDT and two actual victory incidents at 01:12:23 and 01:13:33. Server outbound pending/failed/oldest were zero for both receipts. STATUS at 01:15:19 returned no active NPC GM wave event. Further human commands at 01:14:01/12 may have stopped it; a third victory was not verified. Coverage is therefore natural Henesys bot attacks followed by a partial wave with two confirmed boss kills, not a completed three-boss event. Server receipts: parent `tmp/trainer-baseline-20261001-0108/server-0114.txt` and GM's wave status evidence.

Final native snapshot is preserved under parent `tmp/trainer-baseline-20261001-final/` with hashes. The client was still responsive at 01:15:36, with criticalCount0, minidumps0, null packets discarded0 and an empty faults file. No trainer reactivation or live memory change was performed during this comparison.

## Eventual failure at 01:23:43 PDT

PID45956 failed with the trainer-disabled F8CF proxy, unchanged FBEC server, and no server restart. GM preserved full current and rotated logs, two minidumps, faults and lifecycle records in parent `tmp/client-crash-20261001-0123-baseline/`.

First fault: 08:23:43.803075 UTC, thread45060, access violation C0000005 writing address0 at EIP0FD23E07. This instruction's memory is not captured and does not belong to a listed module, so the exact failing write cannot be decoded from this minidump. EAX was0. The captured caller chain is Canvas.dll+10E3F, Canvas.dll+103A4, PCOM resource routines, ResMan.dll+398A, native403AE7,414BBA,413440,41283C,407A03,454660,92EFC4,452142,971807,A0338E. This is graphics/action resource loading during remote actor update. A second null-write AV follows at08:23:45.320065, EIP0FD2ADF2; a stall breadcrumb follows afterward.

No original ZException5 was recorded in this run. Therefore the baseline failed to establish overall stability, but this different fault does not prove the earlier checksum error recurs with the adapter disabled. The removed adapter is not necessary for this graphics failure. Remaining shared client rewrites, resources and server actor traffic remain unresolved candidates.

Last healthy heartbeat08:23:41.942: private822013952 bytes (about784MiB), responsive1, zero critical faults and zero null-pool discards. That memory measurement alone does not establish out-of-memory. Lifecycle teardown occurs after the original AV, rather than initiating it. Compact derived registers/frames are saved as `native-stack.json`; decoded lifecycle is `decoded-lifecycle.json` alongside the preserved raw evidence. No additional client patch or launch was performed following this failure.
