# Client diagnostics

## Current v4: native error propagation, installed September30 at22:27:59PDT

Real v3 trace identified cleanup on the main thread, then update of the disposed pools. Both recent dumps contain pending native error5; the installed v5 loop incorrectly ignores all positive ZException codes. V4 verifies original proxy SHA A4DF286A... and exact instruction bytes, then applies one byte at loaded RVA C0D2 (JNS toJZ) with scoped thread suspension/protection restoration/cache flush. Disk proxy stays unchanged. It also captures the exact native ZException5 first throw before cleanup. This can prevent the secondary access violation while the original error/disconnect cause remains unresolved. Require startup `native_error_fix ACTIVE`; gameplay acceptance remains pending.

Installed sidecar SHA0841A14EEC84EEC1A5EF8810AD3113002AE62A49CA4792EDFA302D926EF2C652; `native-error-installation-receipt.json`. Details and evidence in `docs/client-player-pool-fix-2026-09-30.md`. Positive/negative/zero branch regression and existing harness passed.

## V3 lifecycle observation

V2 activated in real session36960 but did not resolve stability: after3789 null-player-pool discards, NPC lookup faulted reading0x28 at006D92DA. Both pools were absent while the field continued updating. V3 observes verified stage/disposal/clear entries to find the initiating call; no additional behavioral fix is claimed. Installed SHA256B24F4F087EC3298D0A68C1F6C911D7BB6DA4420498A541069A4E6CACBC13B656; backup/receipt: `lifecycle-installation-receipt.json`.

`.lifecycle` records contain address metadata, time, thread and up to16 stack frames. The previous64 lifecycle records freeze on the first null pool; a reserved first-null slot uses an independent handle; later64 records rotate separately (maximum14964 bytes). Decode using `python read_lifecycle.py FILE.lifecycle`. Five x86 hook forwarding/stack tests and pre/post saturation retention passed. Actual-game activation still requires a fresh session logging `lifecycle_trace ACTIVE`.

## Previous v2 packet guard

The captured real fault shows a null global player pool dispatched for MOVE_PLAYER (0xB9). The lookup adds12 to null and faults reading0x10 at0x973744; the crash reporter then faults at0x7974DC. V2 checks the exact packet-entry and field-call bytes, skips only calls with a null pool, and forwards valid calls unchanged. It adds decoded **player-pool opcode/length metadata only**, plus specific hook-install failure codes. The generic v16 lookup guard is not installed.

The x86 harness passed10,000 null calls and10,000 valid calls with balanced stack and unchanged forwarded arguments, plus the existing socket/fault/retention/dump checks. Prior installed SHA256 `A22D2A647CAEB3E965A4E74D8F766919D5E42D089428A9D20E4CBF7329260949`; backup and unchanged EXE/proxy/config hashes are in `player-pool-installation-receipt.json`. Actual v2 activation was verified; gameplay stability failed.

V1's real-client loader, VEH and dumps are now verified by session20261001T045254Z-pid36484. Its OS socket hook transaction failed (`os_hooks=0`); zero rx/tx counters in that session do not mean zero traffic. V2 logs the failure phase/code to diagnose unsupported OS hooks independently of its narrowly guarded player-pool entry.

The existing v0.5 EXE and dinput8.dll are preserved. `SoloClientDiagnostics.dll` loads automatically through the existing optional DLL setting. No pairing, extra buttons or native gameplay patches. Exact install/rollback hashes are in `installation-receipt.json`.

## Owner check

Launch/login normally. Let GM confirm a fresh `diagnostics/session-*.log` contains `player_pool_guard ACTIVE`, `veh=1` and `critical_file=1`, then repeat ordinary entry/map changes before the coordinated event. OS hook availability is separately reported. If the game closes, note the time; leave any error window untouched.

## Recorded evidence

- Absolute per-launch logs, UTC times, PID/TID, sequence/ticks, build and EXE/DLL hashes, module names/bases.
- Bounded socket metadata ring: operation, socket ID, bytes/result/error, when OS hooks succeed. **No packet payload, credentials or chat text.** Encrypted transport has no decoded opcodes; v2's verified player-pool entry separately records its opcode and length. Overlapped completion byte counts and direct-provider bypasses are not covered; totals are observations of these API calls, not guaranteed unique wire totals.
- Two-second flushed health records: process CPU/memory, visible-window response, ring loss/overwrite and fault/dump counts. Three missed pings record a suspected stall; this alone is not proof of a crash.
- Pre-opened, flushed binary breadcrumbs: latest 16 selected first-chance faults with exception/context and recent metadata; separately reserved exit and detach records. Handler always continues exception dispatch. Handled exceptions are not labeled fatal. `python read_faults.py <file.faults>` decodes them.
- Up to two best-effort local minidumps using Microsoft-signed ProcDump outside the fault thread. The exact fault context is in the breadcrumb; the dump may show the thread waiting in capture. Dump capture may briefly pause the game. Direct system calls, external termination, process corruption or helper failure can bypass capture. Minidumps can contain process memory and stay local.

Log rotation: 4MiB current + previous, eight sessions retained on startup; eight bounded fault/lifecycle files; up to four retained minidumps across startups. Concurrent clients can temporarily exceed retention counts. Faults and exit paths can wait up to1.2seconds for capture. V3 performs one flushed write at the first missing-pool packet and low-volume lifecycle boundaries; subsequent ordinary packet callbacks do no disk I/O.

`test-result.json` records the isolated x86 harness: actual loopback payload roundtrip/error behavior, ring overflow, 24 handled faults, latest-16 retention, reserved exit/detach slots, payload absence from logs/breadcrumbs and structurally valid fresh MDMP streams. This is not gameplay acceptance or proof of the optional loader in the actual client. Build with `python build.py`, then run `python test.py` from this directory (signed ProcDump must be in build/diagnostics-tools).

The staged trainer source also clamps its legacy snprintf log write length; that candidate change is not in the preserved installed v0.5 proxy. No v16 powers or lookup guard are installed by this release.
