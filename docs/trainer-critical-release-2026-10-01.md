# Critical trainer release — October 1

**Latest installed fixes at02:00 PDT:** native D32E9A129D488BF93ED43D711D0CA55CA9BAF3C2EBFB2271B70C7AB06DD74144; critical EXE01689D861507F7AFBAC6BD2E235ECE797500FD3ED6D0FB07CE1275ED2CB76B39; sidecarACFD unchanged. The initial01:39 build below exposed another concrete hook bug and is superseded.

**03:00 checkpoint:** the sidecar is now `AB783D8BC585DD606A33A9F8CD9F69E78EF8896249CA1B3C201D99AED97EC393`, installed at02:58:11 with pinned backup/rollback. Native D32 and critical EXE016 remain unchanged. It adds bounded first-C++ exception evidence; it is not a new gameplay patch or proof that all crashes are fixed.

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

## Final coordinated server readiness

GM's final guarded release is ready: PID31131, JAR `9d3955f42a35f44a199b647db5865d9ed6620aef357b2e875bbc6db59041c048`, config `b03341b0e2737178386f6b52e141d695bdfb391f4b22cbe2e26ccc5da0be899c` unchanged, port7575 ready. Rollback backup: `/opt/solomapling/backups/hene-all-ambient-roles-20261001T093549Z`. Only `HenesysTownDefense.class` changed from the population-repair release. Six exact-JAR regressions passed; GM source commit `ebbeb028`.

GM separately verified 73 real Henesys actors and one genuine Snail BOT kill in both the database and public counters before test cleanup. This is server gameplay/statistics evidence, not native trainer stability acceptance. Future combined builds must integrate the GM worktree service/BotSM/exposure/social-availability/population-routing commits and the world-statistics33-hook source; the preserved archive is not the complete release source.

The native owner rechecked installed DLL D32E9A12 and critical EXE01689D86 against their full SHA256 pins after final readiness. The current disconnected client is preserved. No further GM restart is planned. The older Canvas gameplay fault remains a concrete unresolved item.

## Single launch at 02:18 PDT

One invocation of the existing launch script produced live PID40832. Native startup reports Fly, Unlimited Attack and Rapid Attack ready; the corrected rapid identity guard now matches. Trainer PID34892 was opened. The game window remains white before login/network traffic. At 02:23 PDT the diagnostic heartbeat is responsive with criticalCount=0, minidumps=0 and rx/tx=0. This is not gameplay acceptance.

A read-only dump and a brief x86 main-thread context capture identify graphics device initialization: win32u → d3d8 → nvd3dum → Gr2D_DX8 → proxy setup → native startup. Evidence is in parent `tmp/client-hang-20261001-022048/` and `tmp/client-wow64-20261001-022123/`. No login was automated, no powers were applied and no speculative GPU/client patch was made.

**02:27 PDT correction:** graphics initialization eventually returned in the same PID40832, with no diagnostic faults. The current dialog says the client was disconnected from the login server after the coordinated server restart. The prolonged white window is an observed startup delay, not a demonstrated permanent hang. GM repaired the separate bot-generation routing/null-map regression and deployed PID30717/JAR0a6a893458e9b8a53b37db1c09003b353f88c75ed483cd1b96656b5f5e49e9aa, config unchanged. The older Canvas gameplay fault remains unresolved. The client/error dialog is preserved; human authentication was not requested as an implementation gate.

## Local commit and upload

## New pre-login report

The owner reported a new failure before login. Fresh evidence identifies user-launched PID29728 at02:48:05 PDT, two E06D7363 C++ throw breadcrumbs at02:48:15 and native stage cleanup. Its faults/code files are empty, with no new AV or dump. The prior recorder retained only C++ event metadata except the exact native ZException5 type, so the actual new throw's HRESULT/type/context is unavailable. Parent relaunch processes18328/37400/21588 contain only DLL-detach exit records. Evidence was copied locally to parent `tmp/client-prelogin-20261001-0248/`. This is distinct from the earlier Canvas gameplay AV.

V6 captures the FIRST general C++ throw with distinct kind14, full native exception parameters/context and one best-effort local dump; existing bounded ring, two-dump limit and exception propagation remain. It never dereferences an unknown exception object. The real C++ fixture throws/catches80004005 unchanged; its actual first dump retained the exact throw-object DWORD80004005. Existing loopback I/O, lifecycle, player guard,24 handled-fault propagation and saturation/retention tests passed. Build/test source is in the owned trainer worktree. Installation receipt/rollback is under parent `tmp/prelogin-sidecar-20261001T095811Z/`.

One controlled launch after GM's stable PID31487/JAR87670cf9781751a73f825df2b94de92845c9d22fc966ea5d26eda32ddab7f2de release produced live PID41392 at02:58:16. The actual login screen rendered normally, with no C++/AV record; native Fly/Unlimited/Rapid all report ready. No authentication or gameplay was automated and no human testing gate was imposed. The earlier failure is not reproduced and its exact C++ cause is unconfirmed. Socket hooks were unavailable (`os_hooks=0`, update_thread error5), so recorded rx/tx zeros in these sessions do not establish absence of network traffic. Earlier handoff assertions based on those counters are superseded.

GM retrieved exact server timing in parent `tmp/dom-release-timing-20261001.log`: stop requested02:46:51 for oldPID31131; a user login connection reached that shutting-down process at02:48:04.431; oldservice stopped02:48:14; newPID31487 started02:48:15; channel7575 listened02:48:20.399 and login8484 listened02:48:20.597. The failed client's02:48:05–15 throw window therefore overlaps an actual connection to the old service and its shutdown. This establishes connection-loss timing, not the uncaptured native exception type/HRESULT. The clean02:58 startup occurred after server readiness, with the critical native fixes retained.

## Published source

Critical native/EXE changes and evidence were committed on `dev/trainer-social` as `d8da899e9cdd271d4ff301e859ec9687b5279439`, with subsequent factual receipt corrections. Automatic approval review initially rejected the GitHub push because source-upload authorization was unclear. Reading direct human messages in progress-chat turns `01a0f448` and `01a0f447` established the owner's GitHub/fork and dev-to-test milestone authorization. Normal review accepted the retry; the source was pushed through `03b522e3` and draft [PR3](https://github.com/zkkng/QuietGrove/pull/3) was created into `test` and attached. The earlier upload question no longer needs an answer. No bypass, global security change, production promotion or merge occurred.
