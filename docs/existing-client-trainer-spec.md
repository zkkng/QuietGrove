# SoloTrainer for the existing playable SoloMapling v83 client

2026-09-29: redesign/specification only. HeavenClient is explicitly paused; this plan does not resume it, change client binaries/config, or deploy server code. The full catalog, emotion/economy/lifecycle rules and acceptance requirements in `external-trainer-spec.md` remain in scope. This annex changes the integration target and makes unsupported client-local effects explicit; it does not silently cut or rename those effects.

## Verified exact target

Desktop `C:\Users\Lupert\Desktop\SoloMapling v83.lnk` targets `C:\Users\Lupert\Games\SoloMapling-v83\Launch SoloMapling.cmd`, working directory that game folder. The launcher uses RunAsInvoker and launches MapleStory.exe. User clarified v83, not protocol8.

- MapleStory.exe:4,286,088bytes, PE machine0x014C/x86, SHA256 ED5A699407B9705528B6A653CDEE7395E6E1B7317B9711DCFB34681092072848.
- dinput8.dll:2,841,088bytes, SHA256 F471D58946C1B1718ACB8A26227547A380B5A62DE33919850D7C7FB2C3534048. Exact hash matches `C:\Users\Lupert\Documents\ChatGPT\maplestory server\MapleEzorsia-v2\out\Release\dinput8.dll`.
- That local source repository origin is https://github.com/444Ro666/MapleEzorsia-v2.git, HEAD2769404755baad93655734e76735de141d0b6161. Installed plugin lineage is therefore evidenced by a matching artifact, not inferred from its filename. Build/source provenance beyond that checkout still needs reproducible build comparison before adding hooks.
- config.ini endpoint192.168.1.103. This address is a discovery hint, not proof that a trainer service exists or is authorized. Local game executable has no helpful product/version metadata. The known local server is v83; prove fresh protocol/session compatibility in M0.
- Existing plugin source config exposes Tubi, movement cap, damage cap and HD/window options. Their presence does not prove toggle state or correctness in the running client. Do not edit the installed config/DLL now.

## External presentation contract

Keep a genuinely separate SoloTrainer.exe, individual window/taskbar entry, cramped2007-2009 free-download aesthetic: black/charcoal panel, lime/cyan text, red LEDs, chunky bevels, bitmap-style title, compact checkboxes/sliders/text boxes, tiny scrolling log, tabs Main/Loot/Movement/Combat/Mobs/Bot/Visual/Profiles. Fake scene handle/credits/banner/ad boxes are original cosmetic art, not real advertising, tracking, installer offers or affiliation. Cosmetic Attach scan is optional, under1second and never misrepresents injection/authentication. No malware, credential capture, defender changes, elevated driver, downloader bundle or other-server support.

Top strip identifies the exact local instance/PID/build plus separate Game/Character/Own-Server-Permission/Optional-Adapter lights. Attach does not mean powers are active. Four-state feature UI Off/Pending/Active/Rejected-Suspended, precise effective limits, presets, editable hotkeys, visible All Off, panicCtrl+F12. Whole original catalog stays visible; unavailable controls explain the missing adapter/skill/map capability. DPI100/125/150/200%, keyboard navigation and sound/motion off remain requirements. The fake suspicious look must not make failures unreadable.

## Architecture: stock-compatible first, optional local adapter separately

`SoloTrainer.exe -> explicit authenticated own-server TrainerBridge -> TrainerSessionService/Policy -> existing game domain operations -> ordinary v83 packets + committed observation events`.

The unmodified game continues its normal login/packet flow. Do not send an invented custom opcode or expect a stock client to understand a new control message. PID/window discovery is read-only and useful for focus/status, not game authorization. TrainerBridge is a new proposed service, not currently delivered. Its implementation must be staged with server owners.

Pair with the already logged-in game session using a new account-allowlisted in-game command such as `@trainerpair` (syntax/registration audited before implementation). Server sends a short-lived one-use code privately via normal system-message UI. User enters it in trainer; HTTPS pairing binds the authenticated game connection, character/login generation/world/channel/map and selected writer lease. Trainer never stores or separately uses the game password. Pair code expires, attempts rate-limit, active powers begin off. Server allowlist/capabilities default disabled. Use operator-pinned server identity and a documented local/LAN-only bind/ACL; do not expose an unauthenticated admin HTTP endpoint. Off-host192.168.1.103 requires encrypted authenticated transport, not a same-machine loopback assumption.

Typed API follows original request/revision/idempotency/telemetry contract, max64KiB finite inputs, request budgets, writer lease, heartbeat and reset. Account/character authority comes from pairing, never supplied IDs. No arbitrary script execution, raw packet editor, memory-address API, admin shell or global anticheat disable. The normal authenticated action path keeps ordinary checks; only named trainer policy exceptions apply to this allowed session/map.

For genuine local physics/render/input effects, a separately reviewed OPTIONAL source adapter can extend the already evidenced MapleEzorsia plugin. Do not attach an arbitrary injector or overwrite the working DLL. First reproduce the source build, audit exact client ABI and existing modifications; stage in a separate recoverable test-client directory and obtain explicit opt-in before installing a changed plugin. Add allowlisted user-SID/local-only named-pipe DTOs, no generic memory editor. Same-user trainer writer nonce plus active signed/verified own-server capability binds effects to that game's logged-in session; no local powers on an arbitrary/public server. All hooking is fixed-purpose and source-reviewed for this exact owned client build, never general bypass. Preserve HD/login/window fixes and initial config behavior.

Optional-adapter requirements are real dependencies. Server position corrections are NOT a substitute for smooth mouse fly, midair input or hidden local visuals. If the adapter is not opted into, those controls remain visible unavailable and release completeness cannot be claimed. Trainer still works for proven server-compatible powers and drop-game reactions.

## Full-catalog compatibility matrix

S = achievable through scoped server domain policy and existing v83 presentation, subject to integration/playtest. A = exact local feel/rendering/input needs the optional existing-client adapter plus server approval where authoritative. B = both required for promised effect. These are design judgments, not tested delivery claims. All rows inherit original options/limits/historical-confidence/reset requirements.

| Original feature family | Mode | Required exact behavior and constraints |
| --- | --- | --- |
| Tubi; Super/Uber Tubi | B | Existing plugin pickup cadence plus attributed server policy; batching is bounded. Server auto-pickup alone is a separate helper, not Tubi input fidelity. |
| Item Vac; Meso Vac; filters; loot order/basket | S | Transfer real eligible drops to paired character using one atomic pickup path; ordinary v83 removal/inventory/meso updates, no fabricated items. Cursor anchor requires adapter world-coordinate input. |
| Pet Item/Meso Vac; feeder | S/B | Server verifies real summoned pet, requirements/ignore list/food; preserve pet attribution/animations. Adapter needed if stock local pet behavior conflicts with full promised feel. |
| Spawn-age and drop-game ownership overrides | S | Only explicit capability/tagged bot stakes. Protect unrelated human items/escrow and expiry. |
| Drop display filter | A | Local graphics suppression, never delete server drops or blind witnesses. |
| Inventory manager/autosell/restock | S/B | Real shops/currency/stock; approved whitelist and finite stop/retry rules. UI/input/routes require adapter where local intents are needed. |
| Infinite jump; infinite flash jump | B | Actual repeat midair press/learned skill, not teleports or a larger normal jump. Adapter physics/input plus server motion/MP policy. |
| Mouse Follow Fly; hover; gravity/glide | B | Continuous smooth local prediction and world-space cursor transform, held modifier, map bounds, safe landing and authoritative broadcast; no repeated SET_FIELD reload approximation. |
| Click teleport; coordinate bookmarks | S/B | Server can use ordinary position/map-transfer mechanisms only after proving local-player behavior. Teleport-to-same-map with fade is labeled discrete warp, not smooth click teleport. Accurate in-game click interception needs adapter. |
| Walk speed/jump height; ladder/regrab; teleport range | B | Existing client consumes bounded native stats where possible; above native cap and altered local mechanics need source adapter. No permanent fake equipment. |
| Air Rope/BYOR; airwalk/no-fall; floor-through | B | Explicit local pose/collision/controller support plus approved server position rules. Not silently removed or renamed. |
| Map rusher/ninja taxi; Kami/KamiLoot; routes/patrol | B | Real travel/attack/loot sequences, single motion owner, stuck detector and cancellation; no generic OS keyspam. |
| Full/miss/blink godmode; no knockback | S/B | Server damage/eligibility/invulnerability overlay plus correct normal feedback. Displacement/action prediction that cannot be suppressed stock requires adapter; do not only refund HP while claiming true miss. |
| HP/MP floor/no-cost; regen/refill | S | Real resources and normal update packets, with scoped overlay and correct off baseline. |
| Auto potion/heal; immunity/cleanse | S/B | Consume/validate real items/skills/statuses; server execution supported where consistent, local animation/prediction may require adapter. |
| No breath/fast recovery | B | Actual local action locks and server policy, not removal of unrelated damage or delay. |
| Unlimited stationary attack | B | Remove exact repeated-stationary local restriction with session policy; not merely periodic server damage. |
| FMA | S/B | Expand a REAL accepted player attack through ordinary damage/death/EXP/drop/quest hooks. Legacy target/hit count<=15 per legal broadcast; larger logical actions batched/deduplicated, caster animates once. Local skill gating/visual limitations require an adapter instead of pretending server damage equals full compatibility. |
| Fast attack/no delay; fast casting; cooldown controls | B | Honest supported local cast cadence and server shadow cooldown rules. Server-only periodic damage is not the requested attack. |
| Damage; accuracy/crit; unrandomizer; one-hit | S | Wide checked arithmetic, skill/boss guards, normal outcome feedback, no fake floating numbers/inventory results. |
| Hit count/fusion | S/B | Legal legacy presentation<=15 lines; any above-native local representation requires a validated adapter. Reject unsupported instead of count wrapping. |
| Unlimited ammo/stars | S/B | No consumption while keeping real compatible equipped projectile; prove local display/prediction sync. |
| Summon controls; buffs/auto buff | S/B | Scope real owned objects/self/approved party, natural timeline/provenance, native packet presentation. Local unsupported actions need adapter. |
| Skill sandbox; rapid reflection | S/B | Only curated client-supported skill animations; causal reflection loop guards. No permanent job/skillbook rewrite. |
| Mob vac/point/wall vac; aggro; freeze/disarm | S/B | Server position/controller/AI overlays and ordinary mob packets; test stock controller correction/jitter. Adapter only for cases that cannot predict/render coherently. |
| Monster speed/knockback | S/B | Native supported state/broadcast plus scoped expiry; unsupported prediction clearly gated. |
| Auto attack/buff/heal/loot; facing | S/B | Real allowed intents/action/cost/cadence; adapter routes local input when required. No ambient/companion control. |
| Auto channel/return; AP/SP | S/B | Normal validation/travel/spend rules, explicit preview and stop; input-side adapter where needed. |
| CPU mode/background/effect/damage/loot hiding | A | Local rendering rate only; no simulation/network slowdown. Server-only bridge cannot honestly implement it. |
| ESP/inspector; camera/pan/zoom; local cosmetic sit/fly/dark-sight/shadow | A | Stock client lacks a generic overlay/camera API. Optional adapter is required; server telemetry in trainer window is distinct, not an in-game ESP substitute. |
| Session statistics; roleplay detector log | S | Only confirmed results; permitted redacted telemetry, hidden bot thoughts omitted from immersive view. |

## Drop-game/bot scope remains intact

Keep existing10m/50m paid hosts/120second routine and loot pools; do not claim they are new. Implement real committed-payment receipts/refunds, single visible expiry, finite bot-owned instance ledger, negotiated social bait/stakes, atomic attributed theft/restitution and restart recovery as the original spec requires. Bots react to committed observable action/loss, not trainer checkbox state. Victim/witness anger/sadness/shock/suspicion/memory/rumor/restitution remain non-negotiable, authored and deterministic; no omniscience or real bans by default. Presentation uses ordinary stock-compatible bot characters/movement/chat/emotes, so current playable client remains the rendering baseline.

All drop/reaction identity includes world/channel/map instance/login/round generation. Same-map bot discovery uses shared exclusive task leases; respect GM-event hosts, companions, dealers and currently reserved actors. Thirty witnesses do not shout thirty lines; original speech budgets persist even when events have huge bot populations. Finite item conservation and workload limits remain hard gates.

## Implementation ownership and queue

Only this annex and pause handoff are written now. Original external-trainer-spec is preserved. Coordinate new server files/seams with party/GM owners before touching Character/MapleMap/ArtificialPlayer or deployment artifacts; no live restart/config change or migration is implied by design.

1. M0: record exact playable baseline, protocol/content; verify normal login/map transitions/movement/combat/NPC/loot/audio/pets and current plugin config. Reproduce plugin artifact build; audit existing source changes/rights/build requirements. Do not depend on paused HeavenClient.
2. M1: standalone retro EXE shell, honest discovery/status, typed transport/session pairing, account/map allowlist, writer lease/panic/profile persistence, mocks and tests; first bridge staged server build only. Package explicitly as non-gameplay-connected until server capability is available.
3. M2 server-first: attributed vac/meso/filter policy, stats/resource/survival effects, real-attack FMA adapters and basic bot observations with unchanged-client regression. UI keeps local-dependent controls unavailable.
4. Optional adapter gate: obtain explicit source-build/test-client opt-in; implement local named pipe/focus/coordinate/input/override lifecycle on verified existing MapleEzorsia foundation. Only then infinite jump/smooth mouse fly/clickteleport/no-delay/visual features are claimable. Same full catalog is retained, not silently deferred forever.
5. M3-M5: finish original finite bot economy/stake/theft scenes/reactions, advanced mechanics/routes/summons/visuals/accessibility. Every catalog row has source owner, negotiated capability, limit/default, off/reset behavior and test.
6. M6: coordinated staged server+trainer(+opt-in adapter) deployment after review, guided user tests in manageable batches, independent high-scrutiny review, then actual stock-client functional/load/restart/60minute soak evidence. Rollback restores the untouched baseline, not historical user files from guessed paths.

## New acceptance gates

Retain A01-A28 from the original spec with the existing client as baseline. Add: exact executable/plugin hash discovery; unauthorized server/account refusal; no generic process attach; same-account two-client/session collision test; pairing brute-force/expiry/certificate validation; stale map/generation and duplicate action/retry; panic under server loss; multiple writers; unaffected ordinary/other human clients; no unknown opcode delivered stock; native target/hit-count bounds with1/15/16/100mobs; visible bot names/crowds and theft evidence; local-dependent feature correctly unavailable without adapter; smooth flight/jump must pass real input feel test, not teleport approximation.

Optional adapter must be staged separately, audited against exact x86 binary, preserve existing HD/login/UI/audio/animation behavior, and restore genuine equipment/buff/physics state when off. Plugin/bridge lease loss stops new effects promptly and performs safe landing, while inventory/EXP/committed bot memories remain real. Source tests plus a screenshot are not whole-game acceptance. No active trainer EXE/bridge/plugin update is delivered by this annex.
