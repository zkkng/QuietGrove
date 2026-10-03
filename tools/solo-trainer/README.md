# Current critical release (2026-10-01)

The owner narrowed the project to the original small trainer and critical crash fixes. Use bin/SoloTrainer.exe; EXE -> INJECT HAX -> powers remains. Fly branch repair, rapid byte guard correction, exited-process filtering and HRESULT/error handling repairs are installed with rollback copies. See docs/trainer-critical-release-2026-10-01.md for installed identities, evidence and the separate unresolved graphics resource crash. The historical 70-control catalog and social scope are not claimed complete.

# Weekly checkpoint (2026-09-30, historical)

See `docs/trainer-weekly-checkpoint-2026-09-30.md` and `docs/trainer-depth-test-matrix-2026-09-30.md` from the repository root. Candidate v16 is frozen, compiled and uninstalled; combined Java3940/3940 passed, server package/dry-run only. The installed v0.5 client is currently hung; diagnostic evidence and unverified native mitigation are preserved. Windows signing and real MariaDB execution remain gates. The older release notes below are historical and do not establish current acceptance.

# SoloTrainer

## Current closeout candidate — September 30

The live baseline below is historical: it was superseded by the combined JAR `4836EFAB22DE7D198C2683DD6EC49A1F5403FDB554B56DFE924F8B796680A140`. GM owns subsequent combined releases. The installed v0.5 EXE and client DLL remain unchanged. The staged v0.6 candidate adds a fixed-byte native rapid hook, game-loop patch application and watchdog reset, local skill-effect hiding, and configurable server rapid cadence. New venue source adds finite stock, durable inventory/prize settlement, entry/refund receipts, startup reconciliation and shutdown cleanup.

Focused source checks: 35/35 passed at 10:33:02 PDT. Twelve opt-in MariaDB integration cases compile but have not run; the isolated test-account approval remains pending. Native build and EXE self-test/render checks passed. Neither these candidates nor venue/social transactions have live acceptance. Full catalog and durable ordinary social stakes remain open. Current artifact hashes and evidence are recorded in `docs/trainer-social-closeout-ledger-2026-09-30.md`. Do not deploy from the historical hashes or scripts below.


## September 30 v0.5 deployment

The v6 server JAR is deployed to CT202 at `/opt/solomapling/Server.jar`. The v5 client plugin remains installed at `C:/Users/Lupert/Games/SoloMapling-v83/dinput8.dll`; this update needs no DLL replacement. The service starts, game ports 8484/7575 and bridge port 8488 listen, and the bridge HTTP/form response smoke check passes. The previous server JAR is retained at `/opt/solomapling/Server.trainer-v5-20260930.jar`; the previous client plugin is retained beside the installed DLL as `dinput8.pre-trainer-v5-20260930.dll`.

The current trainer is v0.5 at `bin/SoloTrainer.exe` (PID 43456 at deployment), with the identical release at `candidate-v6/SoloTrainer.exe`. The older EXE is backed up as `bin/SoloTrainer.pre-v6-20260930.exe`. Earlier v0.4 builds were blocked by Windows Application Control policy `{0283ac0f-fff1-49ae-ada1-8a933130cad6}` when launched by tools. The latest build successfully ran its self-test, rendered the Movement tab, and launched from bin. The two old trainer processes were closed while the game was closed.

Deployment scripts are `../../../ops/deploy-solotrainer-v6.ps1` and `../../../ops/update-solotrainer-v6-guest.sh`. The HTTP smoke check uses Bash TCP under a five-second timeout because the guest lacks curl.

### Chatter and movement correction

- One randomly selected nearby witness per incident, with a 45-second map budget, 90-second actor budget, and two-minute bot budget. Repeated FMA/vac discussion waits three minutes for that actor; flight discussion waits ten minutes across maps and witnesses. Some reactions use an emote without speech. These budgets affect bot reactions only.
- 72 contextual dialogue variants, avoiding the last six spoken variants on a map, with varied response timing.
- Ground support is queried above the character's feet instead of below them. A fresh, unexplained absolute movement sequence must travel at least 360 pixels for four seconds with a vertical range at most 28 pixels before a flight question can be considered. Landings, jump/teleport/special movement, rope proximity, swimming, packet gaps, missing terrain, and map changes break or exclude the observation. Unknown terrain is not evidence of flight.
- Movement packets are restored to their original read position before broadcasting, even when the optional observer cannot parse a movement fragment.
- Nine regression tests cover upper platforms/slopes, repeated jumping, ropes/swimming/special movement, sustained flight, landings, packet gaps, map changes, shared conversation budgets, recent-line avoidance, and simultaneous incidents. All pass, along with the 14 existing trainer lease tests and invitation parser smoke test. These fixes still need the player's live movement/chatter playtest.

The v0.3 patch fixes FMA's empty-swing failure: an accepted close-range attack with zero local targets can now expand to eligible monsters elsewhere in the map. Supported learned close-range skills use the parsed attack's damage ceiling and attack line count. The server logs the first three FMA casts and every twentieth cast, and status includes accepted swings, FMA casts, last extra target count, skill ID, and reason. The EXE logs each observed FMA cast to `SoloTrainer.log`.

Current controls:

- FMA damage multiplier (1 to 100 times, with a 199,999 damage-per-line display cap) and FMA one-hit for ordinary eligible mobs. Bosses and protected monsters remain outside this implementation. Both are OFF/resettable through **ALL OFF**.
- Mob Vac anchor: in front of the character, left map wall, or right map wall. A valid foothold is used before moving a mob.
- The timed synthetic attack-key loop is removed. **INJECT HAX** still enables the working vac and FMA controls in one click without taking over the player's attack key. True rapid/no-delay remains an unimplemented client-local power; it is not represented as active.
- Mouse Fly is a separate switch on the Movement tab. **F6 toggles the same switch while the game or trainer is focused**, with optional F7/F8/F9/F10 choices saved in `SoloTrainer.fly-key.txt`. Holding the key causes one toggle, and presses in other apps do not toggle flight. The installed v83 plugin checks original client instruction bytes after unpacking, and the trainer uses a per-process local named pipe. Disconnect, panic, or EXE exit clears flight. The player reports Mouse Fly functioning, and v0.4 logs show acknowledged ON/OFF commands; the new shortcut still needs an in-game press test. Shortcut press/hold/release/focus logic and the rendered tab passed verification.
- Unlimited Attack is a separate Combat switch. It checks and changes the v83 client's stationary attack-lock byte and restores the original byte on OFF or disconnect. It does not accelerate attacks. It has not been verified in game.
- Nearby available social bots can react to a committed map-wide FMA or mass Mob Vac with anger and occasional defaming. Sustained server-accepted midair movement can prompt a nearby witness to question flying. A bot that loses an item to a completed manual, pet, or trainer-vac pickup can show anger or sadness based on its estimated value, item type, and whether the remote pickup was visible. A legitimate pet pickup does not count as proof of remote vac. Delayed reactions check the map again and have speech/fame cooldowns. Paid drop-game hosts do not treat their prizes as theft. This first reaction slice has no persistent memory yet. A reaction failure does not turn off unrelated trainer powers.
- Paid drop-game prizes now leave the world and become ineligible in one map operation; the former three-second invisible-but-lootable grace is removed.
- The paid host checks its prize pool and rejects extra offered items before confirming a trade. It records the trade-success callback before starting the round and refunds an online player if setup fails before gameplay. Offline refunds and crash recovery still need a durable receipt journal; this code is not a full paid-game economy release.
- A staged social stake scene recognizes explicit public `drop game?`, `let's play drop game`, and `show yours and I'll show mine` invitations near available social bots. It observes the player's real dropped item, compares estimated value/tier with actual items in candidate bots' inventories, and exposes one matching item as a real world drop. A bot without a match quits and the next nearby bot gets a turn. A bot's item is removed from its inventory when exposed, returned if unclaimed after 20 seconds, and can be stolen through ordinary or trainer pickup. If the bot has no inventory room for a return, the item stays visible and lootable through six return attempts and remains in the world rather than being silently erased. The round and bot reservation have generation IDs and diagnostic logs. This is a first playable slice: bot assets and grudges are not persisted across bot respawn/server restart, and full trade/observer integration remains incomplete.

Current loot and survival powers remain available: item/meso vac, radius and filters, key or automatic sweep, HP god, HP/MP regeneration, and visible **ALL OFF**. Pickup still uses `Character.pickupItem` and its ordinary inventory, age, and ownership behavior.

## Build and verification

The x86 WinForms EXE compiles with the .NET Framework C# compiler. The v0.5 build passes its codec and shortcut self-test and rendered Movement-tab check, and is running from bin. The named-pipe connection uses bounded asynchronous reads/writes. The installed Win32 Release DLL was built with MSBuild. The v6 server JAR contains only the updated movement handler, reaction classes, and two new observation/conversation helpers layered onto the exact deployed v5 baseline. All 23 trainer/reaction regression tests and the social invitation parser smoke test pass. The new keyboard shortcut and revised bot chatter/movement observation still need live game verification; advanced drop-game and visual damage behavior remain incompletely verified.

Current SHA256: deployed server JAR `81465B16087C44B3F446D159CA6F15AD42351B0C76A1D720509C7271F3ABAD82`; installed client DLL `A4DF286AF3058C2AFBAF2B421F2C8982E392E19F474697086D2E1BB278D30348`; running v0.5 EXE `D9418A5387CFEB1EC7CA9493985444367F9E962163E904D762150359D8855586`. The `/v2` bridge route remains for backward compatibility.

## Remaining catalog

The full feature catalog is in `docs/external-trainer-spec.md`. Rapid/no-delay, infinite jump, click teleport, speed/jump, local hit/knockback suppression, visual filters, camera, ESP, general damage/accuracy/cooldown/MP/ammo controls, advanced mob states, and automation/routes remain unimplemented. The candidate Fly and Unlimited Attack hooks are unverified. Bot reactions currently cover FMA, mass Mob Vac, sustained airborne movement, and committed bot-owned item loss; there is no persistent memory or full observer model. The social stake scene replaces quitting contenders on the same map but has no persistent finite asset ledger across bot lifetimes. FMA's multiplier and one-hit controls apply specifically to extra targets.
