# Critical trainer release — October 1

**Latest installed fixes at02:00 PDT:** native D32E9A129D488BF93ED43D711D0CA55CA9BAF3C2EBFB2271B70C7AB06DD74144; critical EXE01689D861507F7AFBAC6BD2E235ECE797500FD3ED6D0FB07CE1275ED2CB76B39; sidecarACFD unchanged. The initial01:39 build below exposed another concrete hook bug and is superseded.

## Owner scope

The owner narrowed this project on October1 to the original small trainer, client crash repairs and critical features/fixes, then closeout as soon as possible. The original 70-control/social venue closeout is not claimed complete. Its source and backlog are preserved outside this release. No expansion ideas were implemented.

## Installed candidate at 01:39 PDT

Installed with backups and exact hashes by parent `ops/install-critical-trainer-20261001.ps1`. Receipt: `tools/client-diagnostics/critical-installation-receipt.json`.

| Artifact | SHA256 |
| --- | --- |
| Existing-client dinput8.dll | CE8582DEA198A73ED3D9CE534A4EDFE654C70E60D380EC9B1182A51DDBE44924 |
| Diagnostic sidecar | ACFD229C787D641B4C408B694917EB01A2D9025364AEF403B7022AE0FC59D8ED |
| bin/SoloTrainer.exe | EE758F1E428E7D1D11FE0C8B6F0B45DF6B05738D636E7391360098E4598495A8 |

The existing MapleStory.exe, config, game resources, server connection and launch script are preserved. The installer refuses live clients/trainers, validates old/candidate hashes, retains rollback files and rolls back applied copies on errors.

## Critical changes

- Four shared COM call sites now use their returned HRESULT: graphics updates, filesystem initialization and namespace resource lookup. The old implementation compared a function pointer with zero and even returned the function address from filesystem initialization. Resource failure now follows error handling and releases its temporary variant/path. This is a concrete defect; its role in recorded crashes is not yet proven.
- Native nonzero ZException handling is corrected; original errors stop normal updates after cleanup. Earlier captured error5 had been ignored.
- Trainer powers restored from the temporary trainer-disabled comparison. Existing staged controls use the game thread for mutation, a bounded local pipe, reset/watchdog, focus/hold flight and native rapid recovery removal. Rapid is separate from the stationary Unlimited Attack switch.
- The critical EXE exposes the original core tabs and rapid attack. Unfinished catalog panels are kept in source and omitted from this build. INJECT HAX and ALL OFF remain the entry/reset controls.
- Fault diagnostics now retain up to16 bounded instruction windows from executable pages, 224bytes each, in a separate `.code` file. The last Canvas crash's dynamic instruction was missing from ordinary minidumps; this closes that specific evidence gap without altering exception propagation.

## Verification

Native build succeeded. Existing x86 diagnostics harness passed actual loopback I/O/error preservation, native lifecycle hook receiver/argument/stack behavior, null/valid dispatcher calls, handled-fault propagation and bounded fault/lifecycle retention. Its actual executable fault-code records were verified:16 slots,4096bytes total, valid executable protection and EIP inside each captured window. EXE codec, shortcut, preset self-test and critical Combat render passed. No Windows policy change was made.

Real client acceptance is pending. GM is deploying its separately requested boss wave changes with no trainer protocol change; its new server condition must be recorded before the next client run. One final gameplay batch must cover INJECT, actual vac/pickup/FMA/resource effects, genuine rapid accepted attack cadence, flight/OFF, ordinary map transitions, and sustained crowd/action resource use. No blanket claim that all possible crashes are fixed or that the old catalog is100% is supported.

## Proven fly hook defect and repair

User-authenticated PID44480 failed at 01:47:15 PDT. The first AV wrote to 0x751A62DD at EIP 009B62EF while handling a 22-byte magic attack; actual registers show EBX=0. Raw evidence: parent `tmp/client-crash-20261001-critical-44480/`, including the new `.code` instruction window and native-stack.json. Native JE 009B6295 targets 009B62EF, the second instruction of the original five-byte MOV pair. The installed hook replaces the pair at 009B62ED with a five-byte JMP; the incoming branch therefore lands inside its displacement bytes. The Y path has the same defect: JE 009B62F4 targets 009B6354 inside the JMP installed at 009B6352. These hooks apply to the shared vector routine, so OFF and nonlocal bot actors still traverse them.

The repair validates both original MOV pairs and both incoming JE instructions. It redirects each JE two bytes earlier to the guarded hook entry. A null optional output skips its write and replays the original tail MOV; valid outputs retain the original value or the scoped local-flight result. Flags and saved ECX/EDX are restored. The four instruction edits share one startup protection window and restore originals on failure.

Production naked-hook testing passed10000 X and10000 Y calls with null/valid outputs, native frame slots, values, registers, flags and stack behavior. An expanded harness for exact incoming branch addresses compiled but Windows Application Control rejected its enlarged fixed-address executable with WinError4551. It was not bypassed; no security settings changed. That optional expanded execution remains unrun. The incoming branch fix is verified statically against captured live instructions and by the successful direct production hook tests. Neither result is represented as a long-duration gameplay pass.

Two further critical bugs are fixed: rapid's expected prologue contained1C where the observed native call displacement is1D; attachment counted the stale exited PID19348 as a second game. The updated source pins the actual ten-byte prologue and excludes exited processes before requiring exactly one live client. Updated EXE self-test passed. The actual native function ends RET16, matching the rapid replacement's stack contract; real accepted cadence remains a gameplay observation.

GM content release was a new condition: PID29752, JAR7821b2eb2e468daa7fd6ba7cd9488a8a03545e68ee1632217d03249cbdaed92e, configb03341b0e2737178386f6b52e141d695bdfb391f4b22cbe2e26ccc5da0be899c. This included GM's requested boss waves plus content/venue migrations. It was superseded by the statistics fix at PID30151/JAR74ba6375, then the Henesys town-defense overlay at PID30369/JARbb68ad2e4bad26d3b255d7be31268ce0a6f5c9d260dfc5fa5df0ed8cc502acb9, with config unchanged. Native client changes were independent and the client was kept closed for those coordinated restarts. Human playthrough is not a source implementation gate; known defects and missing scope remain separate from optional live QA.

## Source freeze

## Single launch at 02:18 PDT

One invocation of the existing launch script produced live PID40832. Native startup reports Fly, Unlimited Attack and Rapid Attack ready; the corrected rapid identity guard now matches. Trainer PID34892 was opened. The game window remains white before login/network traffic. At 02:23 PDT the diagnostic heartbeat is responsive with criticalCount=0, minidumps=0 and rx/tx=0. This is not gameplay acceptance.

A read-only dump and a brief x86 main-thread context capture identify graphics device initialization: win32u → d3d8 → nvd3dum → Gr2D_DX8 → proxy setup → native startup. Evidence is in parent `tmp/client-hang-20261001-022048/` and `tmp/client-wow64-20261001-022123/`. No login was automated, no powers were applied and no speculative GPU/client patch was made. The current startup issue and older Canvas fault remain unresolved. GM separately found and is repairing a bot-generation null-map regression in its combined server release; the current client is preserved for that coordinated restart.

## Local commit and upload

Critical native/EXE changes and evidence were committed locally on `dev/trainer-social` as `d8da899e9cdd271d4ff301e859ec9687b5279439`. Automatic approval review rejected the GitHub push because direct owner authorization for source upload to `zkkng/QuietGrove` was not established. A precise upload question is pending with the owner; no indirect push or policy workaround was attempted. Installed local fixes and client validation are unaffected.
