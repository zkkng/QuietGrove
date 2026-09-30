# External hack trainer and bot cheating specification

Date: 2026-09-29. Specification and source audit only. No trainer, gameplay changes, live configuration changes, downloads of historical trainers, or deployment are delivered by this document.

The product is a separate Windows trainer EXE with a convincingly sketchy late-2000s MapleStory appearance. It attaches to the new custom HeavenClient through an explicit local control interface. The player can enable extravagant movement, combat and looting powers in their own SoloMapling server, use them to cheat bots in drop games, and experience believable anger, sadness, suspicion and social consequences. The effects must actually work in gameplay, and bots must react to what they could observe. This is the complete intended feature scope; milestones sequence delivery without shrinking the catalog.

Server checkout: `G:\Maplestory v83 server dev\SoloMapling-v83`. Client checkout: `G:\Maplestory v83 server dev\custom-client\HeavenClient`. No AGENTS.md was found by the inspected root/parent checks and repository filename search. Both repositories contain ongoing work; this document does not authorize overwriting it. The party companion and GM event specifications in this directory remain separate requirements and provide shared task-lease/lifecycle conventions.

## Product contract

The external window is essential. A built-in client cheat menu alone does not satisfy the request. Use a standalone `SoloTrainer.exe` working title, independent process/window/taskbar entry, attach/detach button, tabs, hotkeys, presets, status lights and a panic key. A small in-client status indicator is optional and disabled in the immersive view. The custom client contains the control adapter and game hooks, not the trainer's primary interface.

Attachment means an authenticated connection to a deliberately exposed developer interface in the owned client. No arbitrary process memory editing, injected DLL, kernel driver, bypass or elevated privilege is necessary. A short fake scanner/injection animation may provide the nostalgic presentation, but the diagnostic status must truthfully report client discovery, connection, game login and server authorization separately. Cosmetic progress cannot turn an unavailable feature green or claim real injection. Do not reproduce malicious installer behavior or ask the player to disable security software.

Normal use is this owner's local/LAN server and selected account/character session. Changes must not accidentally give every character, companion or ambient bot godmode or altered physics. Server policy defines eligible accounts and maps. All toggles begin off. Automatic reattachment may discover the client again, but never rearms cheats after restart/login without the user choosing Apply. A deliberate roleplay preset can remember desired settings while keeping applied settings inactive.

The player's desired experience is: open old-looking trainer, select HeavenClient, press Attach, see real connection status, choose a preset, jump/fly/vac/FMA visibly, bait a bot with valuables, steal its exposed stake, hear the bot realize what happened, panic-disable, and continue living in a world where that bot may remember the incident. No live LLM or network service is required for any reaction.

## Historical evidence and naming

Historical names describe the fantasy and terminology, not a claim that every combination worked on every v55-v83 patch. Region, client build, private-server validation and community naming differed. Below, **P** means a period author source supports the specific label, **V** a v83-targeted source supports the label but does not establish contemporary release timing or completeness, **L** later author evidence only, and **R** a proposed recreation/extension whose exact period implementation is unverified. The implementation in this specification is original owned-client/server functionality.

| Evidence | What it supports | Limits |
| --- | --- | --- |
| [RaGEZONE author tutorial for v62 localhost](https://forum.ragezone.com/threads/tools-how-to-edit-and-add-hack-to-your-v62-localhost-ufj-tubi-dmg-cap.542780/) | P: Tubi, Miss GodMode, Item Vac and Infinite Flash Jump terminology, explicitly targeting v62. | Describes one author's patch-specific modifications. Not proof of working v83 behavior. No offsets or patch recipes are carried into this design. |
| [Timelapse v83 trainer author repository](https://github.com/obstriker/Timelapse-1) | V: tabbed trainer concept, filters, map rusher, click/mouse teleport, FMA, Kami and vac-family labels. | README contains many TODOs and reports FMA/Kami instability. A listed idea is not a delivered feature or proof of availability in 2008-2010. |
| [Team AMS author announcement dated August 16 2012](https://privateserverhacks.wordpress.com/) | L: FMA, no delay, pet item VAC, mob VAC, mouse fly, damage and godmode labels. | Later private-server announcement, not period v55-v83 proof. Its crash/dupe claims are outside this product. |

The broader requested catalog below deliberately includes R features so the game has all desired powers even where history is uncertain. `Tubi` means pickup cadence, `Item Vac` means pickup reach, `Mob Vac` means relocating monsters, `FMA` means extending the targets of an actual skill attack, and `Unlimited Attack` means removing the stationary repeated-attack restriction. These are distinct toggles. `Super Tubi`, `Uber Tubi`, `dEM/dEMi`, `Pvac`, `DupeX/MMC`, `Wall Vac`, `CSEAX`, `Vami` and `Kami` are aliases or family names, not interchangeable guarantees. dEM-family behavior is insufficiently established here; expose a tooltip describing the implemented effect instead of claiming fidelity. Alias requests can map onto explicit presets only when behavior is documented.

## Source audit and integration constraints

| Inspected area | Existing behavior | Required design consequence |
| --- | --- | --- |
| HeavenClient README and project tree | Custom C++ client; claims v83 server compatibility. README currently documents NX converted from v229.2 assets and v140/Windows 8.1 SDK requirements. | Protocol version and asset version are separate. Prove client build/login/map/attack/loot first. Current setup work owns toolchain/assets; spec can be completed before those blockers clear. See [upstream README](https://github.com/ryantpayton/MapleStory-Client). |
| `Character/Player.*`, `PlayerStates.*`, `Gameplay/Physics/*`, `IO/Window.*` | Explicit player state/physics and input entry points exist, including `prepare_attack`, `can_attack`, `can_use`, `damage`, jump force and climbing controls. | Add reversible session override layers and input intents at these boundaries. Do not permanently modify WZ/NX values or unrelated players. |
| `Gameplay/Stage.*`, `Gameplay/MapleMap/*`, `Net/Session.*`, `Net/PacketSwitch.*` | Stage lifecycle, map objects and packet handling are separated. | Stage transitions invalidate coordinates/targets; net adapter negotiates capabilities; UI updates remain off gameplay/network worker threads. |
| `ItemPickupHandler` | Rejects pickups more than 800 horizontal or 600 vertical units from character position. | Full-map vac needs an explicit authorized pickup policy/domain operation. Changing a client animation cannot override this. |
| `Character.pickupItem` | Applies 400 ms minimum drop age and `canBePickedBy`, item lock, picked-up guard, inventory checks, quest/special-map rules, meso handling and item scripts. | Centralize an attributed pickup transaction used by manual, pet, trainer and bot paths. Tubi does not silently remove all eligibility rules. |
| `PetLootHandler` | Checks summoned pet, magnet/pouch and ignore list, then calls character pickup. | Pet vac needs explicit capability and consistent validation. Do not infer pickup-distance correctness merely because one handler lacks the human check. |
| `MapItem.canBePickedBy` | Owner/party logic, ordinary 15-second ownership expiry, permanent-owner flag. | Keep general human ownership intact; scripted bot stake exceptions must carry session-specific eligibility. |
| `AbstractDealDamageHandler` | Attack byte uses 4 bits each for target and hit count. Ordinary skill target violations can call `MOB_COUNT.autoban`; additional damage/action checks exist. | Never squeeze hundreds of targets into the old packet. FMA uses a negotiated server operation/extension and bounded broadcasts, with a targeted policy path. Do not globally disable anticheat. |
| `DropGameBot` | 10m medium/50m elite paid trades; 120-second game; own party; Haste; recorded `dg_potshop_1` movement; drops every 4-6 seconds after 3-6-second initial delay. | Preserve the recognizable paid game, add real round/payment identity and cheating reactions. Add a separate social bait-and-stake mode. |
| `DropGameLootPool` and YAML | Weighted regular pools; elite special pool gets 33% selection when present. Medium has no special list. Scrolls, stars, chairs, consumables and elite equipment are configured. | Existing bots already drop valuables; avoid promising a new loot pool as if none existed. Validate item existence, content policy, actual stats and quantities. |
| `DropCommands.botDropItemWithExpiry` | Generates clean items rather than removing a held asset. Broadcast hides a drop at 1400ms medium/1150ms elite; actual removal occurs 3 seconds later. Scheduled callbacks access the bot's current map. | Current drops are not evidence of a finite bot inventory. Fix invisible-loot grace and capture exact map/session identity; movement/restart must not remove an unrelated drop. |
| `DropGameSpectatorSystem` | Reacts to an item being dropped with hype/envy/etc.; finds same-map available bots. Static counter/last-reactor state is shared; delayed callback lacks complete lifecycle revalidation. | This is not theft detection. Add per-session witness filtering, successful-pickup attribution and cancellation generations. |
| `ItemUtilities`, `ItemDatabase`, `UpgradeSimulator`, `FMEconomyManager` | Market valuation helpers distinguish clean/scrolled gear and versioned base prices; FM adjustments exist. | Snapshot valuation once per stake. Handle missing/null prices; do not use NPC sale value as market value or randomize perceived loss every tick. |

Potential existing defects requiring implementation validation: the paid trade flow advances after timed confirmation without an audited committed-payment receipt; its empty-loot error says refund without an explicit refund in that branch; `isPlayerOnMap` compares map IDs rather than complete channel/instance identity. Fix these when adapting the game. These are source findings, not reproduced live failures.

## External trainer interface

Proposed default size is about 560x430 logical pixels, resizable, with crisp pixel iconography, charcoal/black panel, neon lime/cyan text, optional red enabled flags, chunky beveled Windows-style buttons, compact 8-10pt equivalent labels and a tiny scrolling log. Use a bitmap-inspired title banner, fake scene-group handle/credits, dated version tag and short chiptune-style optional sound created/licensed for this project. Never claim affiliation with a historical trainer group. Maintain readable 100/125/150/200 percent DPI scaling, keyboard navigation, high-contrast alternative and sound/motion off.

| Area | Contents |
| --- | --- |
| Persistent top strip | Client instance selector with PID/build nickname; Attach/Detach; connection LEDs for Local Client, Character, Server Permission. Never attach solely by process name. |
| Main | Character/map/channel, HP/MP, coordinates, current preset, Apply, All Off, status of every rejected/unsupported feature. |
| Loot | Tubi/Super Tubi, item/meso/pet vac, radius, held/continuous modes, item ID/name filters, rarity/value estimates, drop-game scope. |
| Movement | Infinite jump, infinite flash jump, mouse fly, hover, gravity, click teleport, speed/jump/climb, coordinate bookmarks. |
| Combat | FMA, target mode, attack/cast cadence, unlimited attack, no breath, damage/accuracy, godmodes, MP/ammo/cooldown/buff controls. |
| Mob | Point/mouse/player vac, attraction, freeze/disarm, mob speed, knockback direction, controller status. |
| Bots | Auto attack/buff/heal/potion/loot, routes, Kami-style routines, activity timers and stop conditions. This tab controls the player's automation. |
| Visual | Hide backgrounds/effects/damage/loot animations, overlays, names/IDs, minimap/footholds, optional local cosmetic dark sight/shadow/sit effects. |
| Presets and Log | Save/load/export settings, per-character association, hotkeys, last accepted/rejected actions, panic diagnostics, optional observation inspector. |

An enabled checkbox has four states: Off, Pending, Active, Rejected/Suspended. Tooltip states what it does, client/server dependence, effective limit, likely visible tell, and historical confidence. Active means acknowledged applied state, not merely clicked. Unavailable features remain visible with a specific reason rather than silently disappearing.

Suggested editable hotkeys: Insert shows/hides the trainer; F6 toggles chosen loot preset; F7 toggles FMA; F8 toggles movement preset; Ctrl+F9 arms/pauses automation; Ctrl+F12 is panic. Detect collisions and permit rebinding. Global hotkeys target only the selected client and never type actions into another application. Chat, trade, NPC and text entry suspend non-panic action hotkeys by default. Mouse fly requires a held modifier and an in-game cursor; releasing it brakes to hover/landing policy. Click teleport requires a modifier so normal UI clicks remain normal. Panic must work regardless of tab/chat/focus and may not be rebound to nothing.

Preset examples: Vanilla, Loot Goblin (Tubi+filtered vac), Drop Game Menace (stake-scoped vac and fast pickup), Airwalker (jump+hover+mouse fly), Map Melter (FMA+bounded no delay), Lazy Grind (automation+loot+potions), Subtle Cheater (small reach/speed changes), Absolute Nonsense (maximum supported spectacle). The name does not alter limits or detection. Presets show a change preview, apply atomically, and report incompatibilities. No bundled preset includes permanent punishment or unlimited economy grants.

Assets deliverable during implementation: window mockup, normal/hover/pressed button states, attach LED states, 16/32/48/256 icons, tab icons, bitmap-style banner, optional short sound set, palette/font license inventory and accessible alternate theme. Use original graphics or permitted assets. Cosmetic scan runs after Attach for at most about 1 second and can be disabled; real handshakes and errors remain authoritative.

## Architecture and authority

`SoloTrainer.exe -> Windows named pipe -> HeavenClient developer adapter -> negotiated game-session command -> SoloMapling TrainerSessionService -> existing domain actions -> normal world broadcasts + observation events`.

Use a named pipe restricted to the current Windows user and local connections; prohibit remote pipe clients. Client publishes a user-scoped discovery record with instance nonce/build/protocol, not a global memory scan. Challenge/session token binds the selected adapter; authentication details never enter exported presets/logs. A second trainer may inspect state but only one active writer lease controls a given client. Commands are typed allowlisted operations, not arbitrary executable code, memory addresses, raw packets or JavaScript evaluation.

Server authority is mandatory for HP/MP, damage, target eligibility, attack cadence, cooldowns, items, monster placement/state and accepted player position. Client authority is sufficient for local rendering/filtering and transient input capture. Client prediction may begin an acknowledged permitted motion, but cannot invent a permanent inventory/stat result. The server derives account/character from the authenticated game connection; a supplied character ID is not authority. Capability is per account, character login generation, world/channel and policy scope.

Define proposed small components: TrainerWindow, TrainerProfileStore, HotkeyRouter, LocalControlHost, ClientOverrideState, ServerTrainerAdapter, TrainerSessionService, TrainerPolicy, TrainerActionService, AttributedPickupService, TrainerCombatService, TrainerMovementPolicy, ObservationService, BotCheatReactionService, BotAssetLedger and DropGameSessionService. Names are contracts to implement, not claims these classes already exist.

Client network support should use an explicitly negotiated extension with a reserved opcode selected after checking the actual opcode registry. Do not invent an occupied v83 opcode. The initial extension advertises version, capabilities, limits and session nonce; stock clients continue their normal protocol. If extension negotiation fails, server-dependent toggles reject as unavailable. Pure local visuals can remain available with their local-only label. The trainer never separately logs into the server as the player or stores their password.

All writes marshal onto the client game thread and the appropriate server actor/map executor. Input, physics, automation and teleport must have one owner at a time. A revisioned immutable effective profile feeds those systems. Apply changes by overlaying base gameplay state, not by setting base stats and hoping to restore old values later. Normal level-ups/equipment/buffs while cheats are active must survive turning them off.

## Control protocol

Pipe messages use UTF-8 JSON with a little-endian length prefix, max 64 KiB payload, strict schema and finite numeric values. Unknown major protocol versions reject; unknown feature IDs return unsupported. Initial limits proposed for testing: 20 configuration requests/second, 30 coalesced motion updates/second, one outstanding profile apply, 256 bounded pending intents. Latest mouse position replaces older pending mouse positions. Combat cadence is a server setting, not one pipe message per damage target. Telemetry is normally 5Hz, capped at 10Hz.

Every command envelope includes `protocol`, `requestId`, `clientInstanceId`, `loginGeneration`, `expectedRevision`, `operation` and `arguments`. The client adds the authoritative session binding when forwarding. Profile mutations compare expected revision; stale writes fail with the current revision. `requestId` deduplication makes retries return the prior result, including teleport/single-pickup actions. A new generation invalidates all prior commands.

```json
{"protocol":1,"requestId":"r-104","clientInstanceId":"client-A","loginGeneration":7,"expectedRevision":12,"operation":"profile.apply","arguments":{"features":{"loot.tubi":{"enabled":true,"intervalMs":80},"loot.itemVac":{"enabled":true,"scope":"dropGameSession","radius":1200},"move.mouseFly":{"enabled":false}}}}
```

Example values are requested values; server policy may reject or return explicit effective values. Never silently clamp a visible slider without reflecting the result.

| Operation | Inputs and response |
| --- | --- |
| `hello`, `capabilities.get`, `state.get` | Protocol/build/nonces; return lifecycle state, effective limits/features and redacted character identity. |
| `profile.validate`, `profile.apply` | Complete desired patch and revision; return errors/warnings or atomic applied revision and effective values. |
| `feature.set` | Feature ID plus typed config; same validation/revision rules as profile. |
| `motion.intent` | Mode, map generation, world coordinate, monotonic sequence and held state; reject stale map/sequence and unsupported geometry. |
| `action.execute` | Allowlisted single action such as teleport-to-point, pickup-once, cast-once or route-start; returns causal action ID. |
| `automation.pause`, `reset.all`, `detach` | Idempotent lifecycle actions; reset revokes ability lease and cancels pending work before acknowledgement. |
| `telemetry.subscribe` | Allowed fields/rate; no hidden bot private memory in immersive mode. |

Responses include request ID, accepted/rejected/pending, code, reason, effective revision, feature state and causal action ID where applicable. Stable errors: NO_CLIENT, NOT_IN_WORLD, SERVER_UNSUPPORTED, NOT_AUTHORIZED, STALE_SESSION, STALE_MAP, REVISION_CONFLICT, INVALID_VALUE, CONFLICTING_FEATURES, RATE_LIMITED, INVENTORY_FULL, TARGET_GONE, DROP_INELIGIBLE and FEATURE_UNAVAILABLE. Missing NX/toolchain are launcher/setup errors, not fake attach success.

Heartbeat defaults: once per second from trainer, client expires writer after 3 missed heartbeats, server ability lease expires within 5 seconds without renewal. Disconnect immediately initiates local reset; server lease prevents surviving powers if reset cannot be delivered. Apply defaults and thresholds are tunable implementation proposals, subject to measured latency. A panic action resets locally immediately and prioritizes server reset over queued work; show Server Reset Pending until acknowledged or lease expiry is observed.

## Feature catalog

Each row is a deliverable, not a claim of current implementation. C means client work, S server work, B both. M1-M6 refer to milestones below. All effects have enable/disable, effective-state feedback and reset coverage. Proposed ranges are starting policy envelopes, not assertions about stock v83 limits. Feature support is explicit per skill/map/class; unsupported combinations explain why. All rows default off unless they describe a non-mutating display.

### Loot and item controls

| Feature and history | Exact behavior and options | Authority and boundary | Milestone |
| --- | --- | --- | --- |
| Tubi [P] | Reduce repeat pickup delay at feet; configurable 50-1000ms initial envelope, held or toggle activation. | B. Does not expand reach or override ownership. Separate from server's 400ms spawn-age rule. | M2 |
| Super or Uber Tubi [R alias] | Burst pickup queue, default batch 10, up to 25 eligible drops per tick; server work budget wins. | B. Zero UI delay means queue as fast as budget allows, never an infinite loop. Super label does not claim exact historic algorithm. | M2 |
| Item Vac [P] | Collect eligible items from radius, viewport or current map; anchor player or cursor; continuous/held/one-shot. | B. Server resolves real drops and commits inventory. Remote items visibly travel/disappear with correct looter. | M2 |
| Meso Vac [R] | Separate currency toggle and minimum/maximum bag filters, same reach modes. | B. Use normal currency/party settlement with overflow protection; no money creation. | M2 |
| Pet Item Vac [V/L] | Selected summoned pet collects eligible items at expanded reach, pet pickup animation/attribution preserved. | B. Pet identity and requirements negotiated; optional explicit simulated pouch/magnet capability must be separate, never implied. | M3 |
| Pet Meso Vac and feeder [R] | Currency-specific pet sweep; feed when fullness threshold met. | B. Real food consumed by default; summon/missing food/full inventory reports stop condition. | M3 |
| Item filters [V] | Name/ID search, category, include/exclude lists, equipment/scroll/star/chair/use/ETC, minimum estimated value, source/drop-game filter. | C configuration, S final eligibility. Exclude wins; unknown value is unknown, not zero. Match actual instance stats for equipment. | M2 |
| Loot order and basket [R] | Nearest, highest estimated value, newest, owner-stake-first; per-sweep item/meso quota. | B. Deterministic tie break; repeat targets do not duplicate transfer. | M2 |
| Spawn-age override [R] | Explicit trainer-only minimum pickup age for designated scenario drops; 0-400ms requested range. | S. Negotiated exception scoped to actor/session. Tubi checkbox alone never bypasses it. | M3 |
| Drop-game ownership override [R] | Permit the invited cheater to take an exposed, scripted bot stake when its game definition allows theft. | S. Only tagged bot scenario drops. Other humans' protected drops, trade escrow and bank inventory remain ineligible. | M3 |
| Drop display filter [R] | Hide excluded loot graphics locally without deleting anything. | C. Distinct from loot filtering; observer bots still see real world objects. | M5 |
| Inventory manager [V/R] | Full warning, stop-loot, optional explicit autosell whitelist, potion restock and return route. | B. Real shops/mesos/quantities; no valuable auto-sale by inference. Favorites/locked items always excluded. | M5 |

### Movement controls

| Feature and history | Exact behavior and options | Authority and boundary | Milestone |
| --- | --- | --- | --- |
| Infinite Jump [R] | Jump again in midair on every press; optional held spam interval; normal directional control. | B. No landing needed; 80ms starting minimum repeat interval. Rising over map bounds clamps predictably. | M2 |
| Infinite Flash Jump [P] | Repeat learned flash-jump in air; separate MP consumption option. | B. Only supported class/skill unless explicit borrowed-skill sandbox capability is later enabled. | M3 |
| Mouse Follow Fly [L] | While modifier held, move smoothly toward world point under cursor; adjustable speed, dead zone, inertia and vertical freedom. | B. Converts screen to map coordinates after camera/zoom; input pauses over UI or outside window. Never teleports accidentally when camera moves. | M2 |
| Hover and levitate [R] | Lock altitude when engaged, horizontal steering allowed; release descends or lands safely. | B. Exclusive gravity owner with fly. Expected collision/foothold behavior visible. | M2 |
| Gravity and glide [R] | Gravity scale 0-2 initial range; glide limits falling speed; reverse gravity is explicit advanced mode. | B. Clamped map envelope; safe reset from ceiling/out-of-bounds cases. | M3 |
| Click Teleport [V] | Modifier+click moves to a selected valid map point; optional snap to nearest foothold and saved-point return. | B. Server position/broadcast acknowledge, preserving map-instance identity. Cannot click through UI or warp to another map by coordinate alone. | M2 |
| Walk Speed and Jump Height [V] | Overlay multiplier 0.25-5x walking and 0.25-4x jump initial range; display effective units. | B. Do not persist fake equipment/stat values. Bots can notice sustained impossible motion. | M2 |
| Rope and Ladder Speed [V/R] | Climb multiplier and no-regrab-delay option. | B. Existing ladder geometry retained unless Air Rope mode is explicitly chosen. | M3 |
| Air Rope or BYOR [V proposal] | Virtual vertical climbing at current x, with a visible custom pose. | B. Proposed recreation of a listed trainer idea; no claim existing code supports it. | M5 |
| Teleport Range [R] | Extend supported teleport skill distance; instant repeated skill use still governed by its separate cooldown/cost toggles. | B. Validate destination, class/skill and map restrictions; no map transition. | M3 |
| Airwalk and No Fall [R] | Maintain a temporary horizontal support plane; step off or disable returns to normal physics. | B. One support mode; no global map foothold edit. | M3 |
| Fall Through Floor [R] | Held input ignores selected ordinary platform collision and descends to a valid lower region. | B. Stop before map bounds; portals and solid map barriers use explicit policy. | M5 |
| Coordinate Bookmarks [V/R] | Capture current x/y/map/channel, name spots, warp within same map or route to other maps. | B. Verify map/portal existence and instance access; stale bookmarks show reason. | M2 |
| Map Rusher and Ninja Taxi [V/R] | Follow stored routes using valid portals/travel transitions; optional authorized direct-warp mode separately named. | B. No entering unowned event/PQ instances; route cancels on manual override. | M5 |
| Kami and Kami Loot [V] | Automatically move to selected mob/drop then attack/loot, with offset and return point. | B. One movement controller, bounded cadence. Meaning is explicitly defined here rather than copying old unstable implementation. | M5 |

### Survival and resources

| Feature and history | Exact behavior and options | Authority and boundary | Milestone |
| --- | --- | --- | --- |
| Full Godmode [L] | Negate allowed incoming HP damage; selectable contact/projectile/environment categories. | B. Server does not lose HP. Status immunity and knockback are separate. Scripts that force death require an explicit supported rule. | M2 |
| Miss Godmode [P] | Qualifying incoming hits resolve to misses with appropriate visible feedback. | B. Mutually exclusive with full/blink modes. Do not show fake misses while secretly damaging HP. | M3 |
| Blink or Post-hit God [R] | Extend configured invulnerability after a real hit; display remaining duration. | B. First hit may damage; specify this in tooltip. | M3 |
| No Knockback [R] | Suppress player displacement from qualifying hits while damage remains normal unless godmode enabled. | B. Boss scripted movement handled separately, not silently swallowed. | M2 |
| Unlimited HP or MP [R] | Minimum-resource floor or no-cost mode, explicitly chosen; HP godmode remains a distinct behavior. | S plus client display. Normal max values unchanged; disable recomputes from real state without healing beyond intended result. | M3 |
| HP and MP Regen [V proposal] | Bounded regeneration rate and interval; optional explicit instant-refill action. | S. Server resource journal and visuals agree; no tick-rate-dependent gain. | M3 |
| Auto Potion and Auto Heal [R] | Percentage thresholds, potion priority, emergency skill, cooldown, reserve stock and stop-on-empty. | B. Consumes real resources unless corresponding no-cost toggle explicitly active. | M2 |
| Status Immunity and Cleanse [R] | Pick poison/stun/seal/curse/slow/etc.; cleanse once or prevent new application. | B. Supported status list supplied by server; does not delete unrelated buffs. | M3 |
| Breath and Recovery [R] | No Breath removes applicable post-action transition lock; fast recovery removes supported local action locks. | B. Trade/warp/channel transitions remain serialized. No breath does not mean underwater invulnerability. | M3 |

### Combat and skill controls

| Feature and history | Exact behavior and options | Authority and boundary | Milestone |
| --- | --- | --- | --- |
| Unlimited Attack [R] | Continue attacking from one position beyond the normal stationary counter. | B. Cadence, MP and target count remain unchanged unless separately enabled. | M2 |
| Full Map Attack [V/L] | A real eligible basic/skill attack can reach chosen viewport/radius/map targets and apply skill damage. | B. Server expands valid target set, handles each once and preserves kills/EXP/drops; detailed contract below. | M2 |
| Fast Attack or No Delay [L] | Attack delay multiplier or interval per supported move; 80ms starting minimum, measured per-skill caps. | B. Real cast cadence and damage, not just animation speed. Packet/task budget limits apply. | M3 |
| Fast Casting [R] | Shorten supported cast/charge windup, independently of recovery and cooldown. | B. Hold/release/charge skills need explicit adapters; no zero-time busy loop. | M3 |
| Cooldown Control [R] | Scale selected skill cooldowns or set trainer cooldown zero; normal cooldown shadow timeline retained. | B. Off restores correct remaining natural cooldown rather than resetting it by toggle spam. | M3 |
| Damage Multiplier [L] | Server-authorized multiplier, initial range 0-100x, with visible cap reason. | S plus display. Clamp arithmetic using wide intermediates; skill immunity/reflection policy explicit. | M3 |
| Accuracy and Critical Control [R] | Hit guarantee or accuracy multiplier; optional forced critical chance within supported skill behavior. | S. Ordinary miss/crit behavior restored on disable; no false damage numbers. | M3 |
| Attack Unrandomizer [V proposal] | Pick minimum/average/maximum supported damage roll. | S. Changes combat rolls, not account RNG, scroll outcomes or loot rolls. | M5 |
| One Hit [R] | Authorized lethal damage against supported ordinary mobs after an attack action. | S. Boss/script phase locks protected by default; no arbitrary removal that skips death/EXP/drop hooks. | M3 |
| Hit Count and Fusion Style [R] | Adjustable bounded hit count or redirect a skill's total hit budget to one target. | B. Exact supported modes negotiated; stock count field cannot exceed 15. Custom extension required above legacy limits. | M5 |
| Unlimited Ammo and Stars [R] | No consumption of selected ranged ammunition, preserving required equipped compatible projectile. | S plus display. No inventory item generation or permanent stat changes. | M3 |
| Summon Controls [R] | Duration, attack interval, range and target count for supported owned summons. | B. Summon identity/ownership and lifecycle retained; no unbounded summon duplication. | M5 |
| Buff Duration and Auto Buff [R] | Extend allowed self buff duration; refresh selected learned buffs; optional no-cost mode explicit. | B. Party effects and cooldown/resource provenance tracked separately; remove only trainer overlays. | M3 |
| Skill Sandbox [R] | Curated borrowed-skill preview for the owned character, with per-skill compatibility status. | B. Later capability, no permanent job/skillbook rewrite. Unsupported animations/resources block activation. | M5 |
| Power Guard Style Rapid Reflection [R] | Optional authored reflection experiment with fixed damage cadence and limits. | S. Proposed recreation; real reflect loop prevented by causal-chain guard. | M5 |

### Monster and automation controls

| Feature and history | Exact behavior and options | Authority and boundary | Milestone |
| --- | --- | --- | --- |
| Mob Vac [V/L] | Move eligible monsters toward player, cursor or saved point; instant/pull mode; configurable radius/filter. | B. Server owns actual positions and broadcast; controller/foothold state updated coherently. | M3 |
| Point Vac and directional Wall Vac [V alias] | Hold mobs near a chosen valid point or map side, with spacing. | B. Preset over Mob Vac with explicit geometry; no claim of exact old Pvac/MMC algorithm. | M3 |
| Mob Aggro [R] | Attract/repel eligible mobs or clear aggression to actor. | S. Per-monster behavior overlay; other actors are not globally invisible. | M3 |
| Mob Freeze [R] | Stop movement while preserving or optionally stopping attack timers. | S. Movement freeze and disarm are separate. Expiry resumes from valid state. | M3 |
| Mob Disarm [R] | Disable supported contact/projectile/skill actions for selected mobs. | S. Map events/boss phases use explicit adapters. | M3 |
| Monster Speed and Knockback [R] | Movement multiplier, forced knockback direction/strength, no-reaction toggle. | B. Bounded map coordinates and lease-scoped per-monster ownership. | M5 |
| Auto Attack [V/R] | Use selected real attack/skill when target/condition satisfied; face target, stop on death/dialogue/map transition. | B. Executes game intents, not OS key spam. User movement cancels or pauses by setting. | M2 |
| Auto Buff or Heal [R] | Priority schedule for actual learned skills, thresholds, allowed party targets and resource checks. | B. No ambient bot control; companion heal contracts remain separate. | M3 |
| Auto Loot [V] | Repeated ordinary pickup, usable without Tubi/vac. | B. Same pickup transaction as manual input. | M2 |
| Route Grind and Patrol [V/R] | Record/edit waypoint route, zones, fallback point, potion break and completion count. | B. Bounded retries, stuck detector, status UI; single movement owner. | M5 |
| Turn and Facing [V proposal] | Periodic face-left/right or face target, optional patrol. | C intent with normal broadcast. Does not secretly reset unrelated stationary counters. | M5 |
| Auto Channel or Return [V] | Optional change channel after timer/empty map/crowding; stop or return by valid path. | B. Ordinary ownership/instance checks; bots may notice disappearance. No bypass of report or enforcement systems. | M5 |
| Auto AP/SP [V proposal] | Apply explicit saved stat/skill allocation rules after level up. | S through normal validation. Preview finite point spend and permit pause; not enabled by default. | M5 |

### Presentation and information controls

| Feature and history | Exact behavior and options | Authority and boundary | Milestone |
| --- | --- | --- | --- |
| CPU Mode and visual suppression [R] | Background/effect/loot-animation/damage-number hiding; reduced unfocused rendering rate. | C. Simulation/network continue at correct rate; simulation time must not depend on frame rate. | M5 |
| ESP and inspector [R] | Map positions, mob HP, drop ID/name/value, footholds/portals, distances and target boxes. | C plus explicitly permitted server telemetry. Hidden admin/bot private state omitted from immersive mode. | M5 |
| Session statistics [V proposal] | Real kills, EXP, items/mesos obtained, rejected pickups, runtime and effective action rates. | C displays server-confirmed results only, distinguishing party shares and estimates. | M2 |
| Cosmetic sit/fly/dark sight/shadow [R] | Optional local poses/effects, labeled Local Only unless separately broadcast by supported capability. | C. Never presented as true invisibility or a combat buff. | M5 |
| Camera and map view [R] | Pan/zoom and optional camera lock, with correct mouse-to-world transformation. | C. Does not change witness knowledge or make beyond-map targets eligible. | M5 |
| Roleplay detector log [R] | Optional debug view showing observable tells, witnesses and suspicion transitions. | S telemetry in diagnostic mode. Immersive default hides it; trainer toggles never directly notify bots. | M4 |

All requested feature families are in scope. Historical crash/disconnect-other-player, rollback duplication, credential/PIN bypass, spoofing, raw packet editor and real anti-cheat bypass are not requirements; catalog labels must not introduce these actions. Harmless visual homage to a period trainer is sufficient without implementing destructive historical claims.

## Detailed mechanics and feature interactions

FMA must expand a real attack, not apply periodic unrelated damage while the player stands still. On a valid attack intent the server snapshots the approved profile and map generation, validates the chosen skill and cost/cadence, selects eligible live targets, computes skill-consistent damage/status/knockback and invokes the ordinary monster damage/death path. The caster animates once; targets display actual applied damage. EXP, kill quests, drop creation, aggro and party participation receive normal causal events. Heal only damages susceptible monsters; friendly/noncombat NPCs are never target candidates. Summons and multi-phase bosses use adapters rather than assuming all skills are identical.

Legacy attack packets can express at most 15 targets and 15 lines from the audited count byte. FMA selects across the map but sends bounded render batches or a negotiated extension; it never wraps counts. Initial full-map action cap is 100 targets, processed in batches of at most 15 for legacy-compatible broadcasts. Targets beyond the configured cap are handled by a deterministic rotation on later attacks, and the UI displays the cap. If a specific new client extension supports a higher cap, raise it only after performance tests. One logical cast spends resources once, records each target once, and cannot double-hit because batches or retries repeat. AoE center, boss immunity, range override, accuracy and damage multiplier are independent policies. Legacy client observers must still render legal messages or the test map must explicitly require the custom client.

Mob Vac changes authoritative mob positions; Item Vac transfers world drops; FMA changes attack target eligibility. Any combination must preserve those distinctions. Mob displacement belongs to a leased overlay; on off, monsters resume normal movement from current valid positions, not a stale spawn point. Only restore a temporarily controlled property if its version still belongs to the trainer, so normal AI/events are not overwritten. Bosses, escort mobs and event-owned actors default unsupported pending adapters. Two enabled humans competing for the same mob get explicit scope conflict or deterministic first lease, never oscillating control.

Movement has priority Panic/Reset > map transition > explicit click teleport > held mouse fly > route/Kami > normal movement. A new manual motion suspends automation unless the player explicitly chose blending. Infinite jump can combine with speed/jump but not fight a hover altitude owner; the UI must explain whether jump releases hover or is suspended. Physics reset resolves current position against the real map bounds and nearest valid foothold, retaining ordinary velocity only when safe. Use a visible controlled landing or announced server correction, never a hidden warp to an unrelated map. Coordinate values use map units, not screen pixels.

Fast attack, FMA, summons and automation share one server action budget. Unlimited Attack removes the stationary lock only. MP/ammo toggles remove their named costs only. Auto potion still respects the selected real consumable cooldown. Cooldown overrides keep a natural cooldown shadow timeline so toggle-on/off cannot erase a cooldown unintentionally; explicit cooldown reset is a separate logged sandbox action if later added. Resource floors cannot permanently raise base HP/MP. Damage arithmetic uses checked/wide intermediates and clamps before protocol serialization; never allow negative overflow to heal mobs or award impossible currency.

Loot rules resolve in order: session/map identity; drop exists/unconsumed/unexpired; source and filter scope; ownership eligibility; configured minimum age; reach policy; pet prerequisites if applicable; inventory/quest/special-item eligibility; atomic transfer; committed pickup event. Blacklist beats whitelist; a filter cannot make an ineligible drop eligible. A failed pickup produces status, no inventory grant and no theft reaction. Runtime estimates of item value are labeled estimates with source/version. New drops must be eligible only after their spawn event is visible; instant pickup can still produce a short visible travel/removal beat while inventory settlement remains authoritative.

The existing display-expiry-plus-grace behavior needs one explicit choice: use a single authoritative expiry, or preserve a visible grace marker until eligibility ends. Default this design to a single expiry. Do not leave a valuable invisible but lootable for three seconds, and do not let reaction code accuse a character of visually impossible behavior caused by server-created invisibility. Lag compensation can use a bounded receive window internally without allowing new commands after expiry; eventual settlement and visual state must agree.

## Drop games and baiting bots

Support two related modes. The existing paid host game retains medium/elite prices, familiar moving host, short-lived prizes and chase-to-loot feel, subject to the transaction/expiry fixes. A new social stake game lets the player convince eligible ordinary bots to expose valuables, then cheat with vac, Tubi, flying or teleport. The latter is the requested bait-and-steal story and must not be replaced by merely increasing host prize frequency.

Social discovery uses named or nearby public deterministic intent matching: `drop game?`, `lets play drop game`, `show yours and i'll show mine`, and contextual stake/match replies. Normalize case/punctuation, require a game context for ambiguous show/drop phrases, and recognize refusals/negation. An explicit interaction menu is a fallback when phrasing is not recognized. Bots choose based on personality, trust, available assets, risk budget and memory. A bot can decline, demand the player go first, propose a smaller stake, boast about a possession, invite spectators or walk to a safer spot. Eligible social/training bots may participate; traders, dealer hosts, tutorial/PQ actors, recruited companions and reserved/event bots cannot be stolen from their current task. Reserve one exclusive bot task lease and record previous activity.

Default public invitation produces at most five total bot messages, staggered across the scene, not five per bot. The player chooses one consenting bot; others return to their routines or spectator role. Repeated prompts within a cooldown do not create duplicate games. Private/named interactions affect that bot only. Rude/teasing responses are personality flavor, not an implicit agreement to risk any item.

Proposed state flow: OFFERED -> NEGOTIATING -> RESERVED -> READY -> PLAYER_SHOW -> BOT_SHOW -> EXPOSED -> RESOLVING -> WON/LOST/ABORTED -> REACTION -> COOLDOWN -> CLOSED. Each round has a UUID/generation, host/participant IDs, channel/map instance, participant positions, agreement/rules, asset reservations, visible drops, deadlines, observation IDs, result and cleanup status. A paid game has PAYMENT_PENDING -> PAYMENT_COMMITTED before READY. Game generation must accompany every delayed drop, emote, line, pickup, refund and movement callback.

The bot reserves an actual eligible item instance before showing it. Player bait can be shown in a trade display, equipped/inventory preview when explicitly offered, or a real world drop. If physically dropped, the player's item follows real ownership and risk rules defined for that round. The bot's confidence in the bait comes from what it saw and recognized, not hidden access to the player's inventory or trainer config. The bot's stake may be equal/lower/higher perceived value depending on personality, but cannot exceed its available finite risk budget. Fake claims in player chat can convince a gullible bot only as a scripted decision with uncertainty; they do not create items.

At BOT_SHOW the bot briefly announces the item and puts the reserved instance into the world at a real coordinate. At EXPOSED the item becomes stealable under that round's explicit rules. The player may act immediately, enable vac after seeing it, wait for a rare item, or play fairly. A cheat-assisted successful transfer commits the loss and initiates observation-based reactions. Some low-value thefts provoke grumbling; losing a treasured scrolled item can make the bot stop, plead, swear mildly, cry, accuse, try to reclaim it, quit or remember the player. Unseen losses can produce confusion first. Do not reveal private item details a witness could not identify.

Example playable scene: player shows a valuable scroll; a boastful bot agrees to put down an owned upgraded glove. The bot drops it beside itself while the player is visibly across the platform. Item Vac consumes the drop to the player's inventory, with a pickup effect/attribution. The bot pauses, shocked emote, says `wait... where did my glove go`, turns toward the player if it saw the trail, then `you were all the way over there!!`. A nearby witness may say `i saw that lol`. The victim cancels further stakes, asks for it back, and records a personal grudge. If the player was close enough and used only a modest Tubi rate, the bot may complain about losing without confidently accusing cheating.

The game may let the bot grab back an exposed item or race to the player's bait, but only through the same actual inventory transaction. Existing bot floor-cleanup helpers often remove drops for simulation; those are not acceptable proof of ownership acquisition for a stake game. The bot needs a real transfer into its asset ledger/inventory. A bot cannot reclaim an item after the player already owns it without a real return/trade action.

Paid game implementation must use a settled trade receipt, not a timer as evidence of payment. The receipt links account/character, bot, round, exact mesos and commit identity. Reserve prize stock before collecting entry; reject unavailable tiers before payment. Server/host failure before a playable round produces exactly one refund; player withdrawal after clearly disclosed start rules follows the game definition. A failed party invite caused by infrastructure is a failure/refund, not automatic forfeiture. Reject or explicitly handle extra offered items; never silently consume them. After closure, settle remaining visible prizes by configured return/expire policy and release only game-owned state.

## Bot inventory and economy

The current pool generates clean rewards. Replace unlimited generation in new stake games with `BotAssetLedger` backed by explicit finite assets. Use existing bot inventory if it provides correct instance ownership/persistence; otherwise introduce a dedicated persisted ledger with adapters, not a cosmetic count. Auditing all existing bot trade/merchant inventory sources remains an implementation gate. A shop listing, equipment appearance, trade wishlist or generated loot template is not automatically an owned stake.

Asset record fields: unique asset ID; bot/account owner; item ID; quantity; complete equip stats/upgrades/flags; acquisition provenance; acquisition/value snapshot; sentimental weight; tradability/content-version status; reservation/round ID; current location; version. Location is HELD, RESERVED, WORLD, PLAYER, RETURNED or CONSUMED. One asset/quantity cannot exist in both bot stock and world/player stock. Stack splitting gets child identities with quantity conservation. Meso balances use checked integer bounds and journaled deltas.

Initial bot wealth bands and rare-item budgets are tunable design values, not historical facts. Configure poor/casual/merchant-rich/collector personalities separately; only suitable bots join the social game. Seed a finite stock once per bot or campaign, using a deterministic recorded grant ID. Replenish through explicit earned income or a capped periodic grant ledger, never on reconnect, repeated invitation, state reset or process restart. A rich bot may risk a rare item occasionally; a poor bot should not conjure a White Scroll because the player asked twice.

Use the existing `ItemUtilities.getItemMarketValue` and versioned `ItemDatabase` where valid, with `UpgradeSimulator` for equipment instance stats and coherent FM market context. Record nullable/unknown values explicitly, validate price bounds, and use a curated fallback estimate only when tagged as such. Calculate perceived loss from quantity, real estimated market value, sentimental weight and the bot's remaining wealth. The same 10m loss can be minor to a collector and devastating to a novice. Do not equate NPC sale price, item ID rarity or UI color with fair value.

Keep the existing YAML pool as an eligible supply/prize definition, not an unlimited giveaway generator. Medium already contains scrolls, stars, chairs and consumables; elite also contains high-value scroll IDs including 2340000 and 2049100, stars and equipment. Resolve display names from actual WZ/item data before presenting them as validated content. A code comment describing a tier or scroll percentage is not enough. Validate every configured item against the active v83 content policy and available custom-client assets; reject nonexistent/unsupported entries at load time. Actual upgraded valuables require persistent instance stats rather than regenerating a clean copy at drop time.

Default proposal for repeatable solo play: finite per-bot personal stake stock; separate capped daily paid-game prize stock; configurable campaign replenishment; per-human/per-bot invitation cooldown; rare stakes budgeted per campaign/day. The exact prices and budgets need measured economy balancing, but no test fixture should silently become production unlimited stock. Provide a clearly labeled local scenario reset that resets a dedicated test fixture with a new scenario ID. It cannot duplicate already exported items into the normal economy; either require return/destruction of fixture-owned assets through an explicit reset or keep scenario economy isolated from the start.

## Drop and pickup event contracts

Emit domain events after authoritative commit, not after a request or visual disappearance. Essential facts: event/action ID, server monotonic timestamp, world/channel/map-instance identity, round/generation, actor and affected IDs, drop/asset identity, item/quantity/mesos, source/destination coordinates, creation/expiry times, previous owner, successful pickup owner/pet, transfer outcome, and applied policy capabilities. The private event can carry trainer provenance for diagnostics; the bot observer receives only a sanitized observation.

`DropCreated` establishes the visible asset and source. `PickupCommitted` follows successful inventory/meso transfer and removal. `PickupRejected` is useful for diagnostics but never means the bot lost something. `DropExpired` and `DropReturned` distinguish natural loss of visibility from theft. `AttackApplied`, `DamageApplied`, `ActorMotionAccepted`, `MobRelocated`, `EffectApplied`, `TradeCommitted` and `ActorLeftMap` support shared reactions across all hacks. Each has causal action identity and source policy; all derive from committed gameplay, not trainer checkbox changes.

Atomic transfer owns the drop/item lock and verifies state/version before crediting the destination. Post-commit event publication uses a reliable outbox or equivalent recoverable journal if persistence crosses restart boundaries. Duplicate publication is permitted only when subscribers deduplicate by event ID. Competing human/pet/bot/vac claims produce exactly one winner. World object IDs are insufficient unique identities across map reload/restart; combine map generation with a stable drop/asset UUID. Never grant first and then retry a whole grant on packet send failure.

Expiry and pickup serialize against the same state. At the boundary, one outcome commits; expired assets cannot later transfer. If inventory is full, the drop remains eligible until real expiry and the game emits no loss accusation. If partial stack acceptance is supported, split/journal it explicitly; initial implementation can require entire-stack space. Preserve existing quest requirements, unique-item restrictions, consume-on-pickup/script semantics and party meso policy through typed adapters. A death/revive, map change, disconnect or server restart cancels stale tasks and reconciles assets exactly once.

## Observation and exposure

Bots do not read trainer state, server privilege flags, offscreen hidden positions or every map event indiscriminately. `ObservationService` derives evidence from what a bot plausibly perceived: its location/facing, actor visibility, viewport-like range, visible pickup trail, prior drop awareness, attack effects, travel trajectory, elapsed time and local conversations. Full channel/map-instance identity is mandatory. Default observation envelope proposal: about 900x600 map units for sight, with per-map/camera-profile tuning; short-range speech uses the existing map chat presentation. Use foothold/platform occlusion heuristics only where meaningful in this 2D world, not an invented universal 3D line-of-sight rule.

At pickup time, preserve both observed player/drop positions and the relevant position history. Compare to legitimate pickup reach and plausible movement/pet reach with a latency tolerance. Current handler's broad 800x600 rejection limit is an anti-abuse envelope, not the distance every bot should treat as normal hand pickup. A pet next to the drop can explain a distant owner. A visible teleport skill or valid portal can explain a position jump. A skill with legitimate broad range cannot be called FMA from range alone. The observer checks known visible class/skill animation and target effects before judging.

Evidence examples: a recognized stake jumps across a platform into a faraway player; several items converge in an implausible short interval; a stationary attacker hits dispersed mobs beyond that skill's plausible reach; repeated midair jumps with no landing; sustained mouse-like flight; monsters snap into a pile without a known skill; repeated obvious hits leave an apparently vulnerable character unharmed. One miss or one strong attack is weak evidence. A bot can be wrong at low confidence, but dialogue must reflect uncertainty rather than state omniscient facts.

The victim may know its own stake vanished and was not normally returned even when it cannot identify the looter. Then it says `where did it go?`, searches, checks nearby actors and only names someone after a visible trail, direct admission or trusted witness report. If no one saw the player, no victim-specific grudge attaches to that player's identity solely because the server knows who picked it up. Server logs retain full attribution for debugging without leaking it into roleplay. Bot recognition uses character identity, not a guessed hidden alt account; an optional omniscient scenario mode must be visibly labeled and is off by default.

Visibility suppression is local presentation only. Hiding drops/backgrounds/effects in the trainer does not blind bot witnesses. Panic disables future effects but does not erase prior observations. Walking out of sight before acting can reduce witnesses; victims may still notice their missing property without knowing the culprit. Teleporting away after theft can add evidence if seen; it is not automatic global detection.

## Bot reaction state and dialogue

Per observer/suspect/incident state: UNAWARE -> NOTICED -> SUSPICIOUS -> CONVINCED -> REACTING -> AVOIDING/REPORTING/RECOVERING -> REMEMBERED. Evidence scores are bounded and decay over time, with different weights for direct witnessing, personal loss and hearsay. Use hysteresis so one contradictory clue does not alternate angry/normal every tick. A new high-value loss can interrupt an idle line but not an atomic trade, combat/death transition or existing higher-priority scene.

Initial behavior knobs: suspicion thresholds 25/60/85 for wonder/suspect/confident; evidence decay 5 points per minute outside an active incident; direct impossible-distance pickup worth 35-60 depending on visibility; ambiguous fast loot 5-15; repeated independent observations required for strong non-victim accusations. These numbers are proposed starting values, to tune with deterministic scene tests. Deduplicate repeated render packets of one action; do not count one FMA cast as 100 independent proof events.

Emotional state is separate from confidence. A bot can be devastated by a loss without knowing who cheated, or amused by obvious flying without losing anything. Track anger, sadness, embarrassment, greed, fear, trust, loss ratio and personality. Example profiles: gullible novice feels shocked/sad and asks for return; proud collector becomes furious and embarrassed; chill grinder laughs then refuses another game; paranoid player suspects earlier and demands safer rules; showoff escalates stakes but reacts strongly to humiliation. Behavior selection uses authored weighted rules and seedable randomness, not a required LLM.

| Trigger and evidence | Immediate physical reaction | Dialogue possibilities | Follow-through |
| --- | --- | --- | --- |
| Missing low-value stake, unknown looter | Stop, look at former drop, confused emote | `huh? where did it go` | Search nearby, ask witnesses, do not name an unseen culprit. |
| Clearly seen remote theft | Turn toward player, shocked/angry emote, short pause | `you were nowhere near it`, `vac hacker??`, `give that back` | Stop new drops, cancel round safely, record direct evidence. |
| Treasured item lost | Cry/sad emote, sit, stop boasting | `that was my best one...`, `i actually saved for that` | Plead, leave, reduced trust and longer game cooldown. |
| Angry confident victim | Face/chase a short reachable distance, angry emote | `are you serious`, `i'm done playing with you` | Refuse more stakes, tell nearby witnesses, optional fictional report. |
| Ambiguous Tubi win | Frown or surprised emote | `how did you grab it that fast`, `lag??` | One warning or smaller stakes; no immediate conclusive accusation. |
| Witnessed flying/FMA/mob vac | Stop/point/turn or retreat | `how are you staying up there`, `that hit the whole map`, `all the mobs just moved` | Watch briefly, become wary, inform nearby friends if sufficiently confident. |
| Witness saw theft but victim did not | Look between actors, speak after victim reaction | `i saw it go to {name}`, `they were over there` | Adds hearsay evidence with source identity; cannot become stronger than direct sight automatically. |
| Item returned through real transfer | Relief, reluctant thanks, reduced anger | `okay... thanks`, `still not doing that again` | Reconcile loss ledger; partial trust recovery, memory retained. |

Lines live in YAML categories with personality/severity variants and safe placeholders (`{actor}`, `{item}`, `{loss}`, `{witness}`). Use existing `BotSpeak`, `BotEmote`, facing/movement and dialogue scheduling facilities with cancellation tokens. Extend victim/spectator dialogue packs or add `CheatReactionDialogue.yaml`; do not replace unrelated current chatter. Missing category/name falls back to a simple generic line without crashing. Store actual emote IDs only after verifying existing resources. Delays use the scheduler, never blocking sleeps in gameplay hot paths.

Scene pacing proposal: 250-900ms initial pause, first line/emote by roughly 1.5 seconds, follow-up after 1-3 seconds. At most three victim lines and two witness lines in a normal first reaction burst; one map-wide burst budget prevents 30 bots shouting at once. Severity can choose a longer optional scene after the initial budget cools. Per-bot speech cooldown, per-incident deduplication and availability recheck apply at emission time. Interrupted lines do not reappear after the bot leaves the map or accepts another task.

Memory record: bot ID, recognized character ID, incident ID/type, evidence/confidence, asset loss/restitution, relationship delta, first/last time, expiry and rumor sources. Proposed personal grudges last 30-120 gameplay minutes with severe collector losses persisting across sessions; configurable capped retention (for example 7 days real time) keeps storage bounded. Rumors spread only through actual nearby interactions, lose confidence with retelling and have hop/TTL limits. A reconnect or trainer detach does not wipe a committed grudge. A character rename resolves to the same character identity, while other characters are not assumed guilty by default.

Consequences are local social/gameplay events: refusal, smaller stakes, asking the player to leave, quitting the game, walking away, cooldown, warning friends or an authored GM investigation scene. `report` creates a roleplay incident, never a real ban request by default. An NPC GM may arrive, question witnesses, warn the player or stage a temporary pretend jail in a dedicated scenario. Permanent account ban, IP ban, irreversible confiscation and database deletion are not default outcomes. If a later punishment simulation mode is explicitly enabled, it must have a clear exit/reset and remain distinct from the server's real account enforcement. Normal checks still apply to unrelated malformed/unauthorized actions; trainer authorization must not become blanket immunity.

The GM event and companion specs should consume the same task leases and observations where relevant. A companion can react to cheating it directly sees but must not abandon a critical combat action in a way that corrupts its party task. A drop-game participant suspends its prior ambient routine until cleanup, then resumes it once. Investigation actors receive their own leases and cannot commandeer busy dealers or event hosts.

## Lifecycle and reset

Trainer lifecycle is DISCONNECTED -> DISCOVERED -> CONNECTING -> ATTACHED_IDLE -> AUTHORIZING -> READY -> APPLYING -> ACTIVE, with SUSPENDED/ERROR transitions and DISCONNECTING back to DISCONNECTED. Attached idle can exist at the login screen but cannot change a nonexistent character. Capability/authorization readiness is separately visible. Server profile state is keyed by login generation, not just account ID; character select, channel change, reconnect and server transfer invalidate it.

Panic order: stop producing automation intents; clear local held keys and pending actions; revoke active server profile/leases; restore local visual/input/physics overrides; request safe landing/correction where required; receive effective off state; leave the trainer attached and readable. Panic does not revert legitimate inventory gains, rewind combat/EXP, refund a completed wager or erase bot memories. Those are separate scenario administration actions with transaction rules. It also does not kill the client. An inaccessible server produces a pending reset with lease countdown, never a false All Off acknowledgement.

Death stops motion/combat/loot loops and releases monster control. Respawn begins off or paused with explicit rearm. Map transition invalidates target/drop/coordinate caches and stops map-scoped actions before loading. The profile may retain desired preferences but effective movement/vac/combat rearms only after new-map capability validation; default to manual rearm. Cash shop/trade/NPC/character select pause action automation. Logout, client crash, trainer crash, pipe loss and server restart use the same idempotent cleanup and lease expiry.

Store preferences in a versioned user-local profile file with schema migration and atomic replacement. Store hotkeys/theme separately from per-character desired feature settings. Never persist active leases/tokens, fake base stats or bot reaction working references in that file. The server persists bot assets, committed results/payment/refunds and selected memory; transient cheat capabilities always start inactive after restart. Logs redact credentials/session secrets and rotate by size/age.

Cleanup checklist per session: release input/movement owner; cancel timers/tasks with generation guard; revoke resource/combat overrides; release controlled monsters; clear scoped drop subscriptions and observation buffers; stop audio/scan animation; clear pending IPC responses; resolve/refund pre-start rounds as defined; return unconsumed reserved assets once; preserve committed ownership/results; release bot task/party lease; reconcile temporary game buff provenance; resume prior valid bot activity. Never delete all map drops/mobs or restore an old global config snapshot.

## Proposed configuration and persistence

Add versioned definitions under a proposed `server-config/trainer/` area, with server-owned account allowlist and capability limits separated from player preferences. Integrate with current config infrastructure after checking its conventions; paths below are proposed, not existing files. `capabilities.yaml` contains feature limits and skill/map support; `drop-games.yaml` contains definitions, stock budgets and timing; `bot-cheat-reactions.yaml` contains evidence thresholds/personality weights/memory TTL. Dialogue remains with established bot dialogue packs.

Definition validation fails closed per invalid feature/round, with actionable startup diagnostics. Validate duplicate IDs, negative weights, missing items/skills/maps, impossible timers, undefined dialogue categories, negative budgets, overflow, contradictory mode combinations and absent fallback points. Schema version is required. A reload applies to new sessions or explicitly versioned migrations; never change a stake's value/expiry mid-round without an announced session action.

Illustrative configuration, not a live change:

```yaml
schemaVersion: 1
trainer:
  enabled: false
  allowedAccountIds: []
  requireCustomClientCapability: true
  leaseSeconds: 5
  defaultsActive: false
  limits:
    configRequestsPerSecond: 20
    motionUpdatesPerSecond: 30
    attackIntervalFloorMs: 80
    fmaTargetsPerAction: 100
    pickupsPerTick: 25
  roleplay:
    reactionsEnabled: true
    omniscientBots: false
    realAccountPunishments: false
```

Recommended persistence entities: bot_assets, bot_asset_transactions, drop_game_sessions, drop_game_asset_reservations, drop_game_payment_receipts, drop_game_results, bot_social_incidents and bot_relationship_memory. Reuse existing equivalents if they provide the same atomicity and recovery semantics. Define unique constraints for asset version/transaction ID, one active reservation per asset, payment/refund identity and one result per round. Content IDs and trainer session IDs must not collide with existing party/event IDs. Migrations are additive with a documented rollback that preserves committed economy records.

## Implementation packages and build requirements

The independent trainer can use a modern installed Windows desktop stack; proposed default is C# WinForms for period-style controls and straightforward named-pipe IPC. Pin the actual available supported SDK during implementation rather than assuming a particular runtime is already installed. Produce an x64 standalone package and decide self-contained versus installed-runtime distribution from the target PC requirements. The trainer process bitness need not match the custom client because IPC is typed data, not pointer sharing.

HeavenClient remains a native C++ solution. Add a small local IPC host and typed adapter, using libraries compatible with the selected client toolchain. Keep protocol DTOs/code generation independent of C++ runtime choices. UI thread and game loop may never block waiting for server acknowledgements. Actual client solution/toolset modernization, NX conversion and setup-agent changes are prerequisites owned by that workstream; coordinate later against its verified final build rather than editing around it now.

Server implementation packages should extend domain services, not duplicate packet handlers for each trainer button. First isolate attributed pickup and trainer combat policy; adapt human, pet and bot entry points to those services carefully. Hook the existing death/EXP/drop/quest logic once. Route normal non-trainer actions through unchanged ordinary policy behavior with regression tests. Trainer policy grants named exceptions only when a valid lease and context match; account status checks and malformed input handling remain real.

Proposed client modification map: local IPC host starts/stops with application; `Net/Session` and packet switch negotiate extension; `Player`/player states consume effective motion/combat policy; `Physics` applies actor-scoped gravity/foothold behavior; `Stage` invalidates map generation; map mob/drop renderers handle extended outcomes; `IO/Window` performs coordinate conversion/focus gating. Exact symbol-level changes require a fresh audit after client setup. No work in this spec assumes the current checkout already implements those hooks.

Proposed server modification map: new TrainerSessionService/Policy/Action services; audited pickup transaction behind Character/handlers; attack parser/validator and damage path adapter; movement and monster controller policy; DropGameBot session migration; finite asset adapter for DropCommands; observer/reaction service using existing BotTiming and dialogue infrastructure. Avoid large global edits to already dirty Character/Monster/MapleMap files; add narrow seams and review overlapping changes before implementation.

Build outputs: trainer executable/resources/config schema; custom-client build with declared protocol version; server build/migrations/config examples; operator README with setup and reset; sample finite scenario fixtures; compatibility manifest containing build hashes, capability schema, active asset/content version and supported feature/skill matrix. No binary should be called compatible solely because it compiles. Packaging must not bundle an unverified historical trainer or an external injection tool.

## Delivery milestones

| Milestone | Complete work | Exit evidence |
| --- | --- | --- |
| M0 Foundation audit | Verify custom-client build, required NX assets, login, map transitions, ordinary movement/attack/loot; record versions and ownership of setup changes. Trace server packet and bot inventory paths. | Real client smoke test and compatibility manifest; unresolved setup blockers named. Spec itself does not wait on this. |
| M1 External shell and contracts | Standalone retro UI, discovery/attach/detach, status states, capability negotiation, typed commands, profile persistence, writer lease, heartbeat, panic and diagnostics. | Two client instances distinguished; unsupported server rejected; process crash expires active lease; no game feature required to fake status. |
| M2 Core powers | Tubi, filtered item/meso vac, real FMA, infinite jump, mouse fly/hover, click teleport, speed/jump, god/no-knockback, unlimited attack, basic automation and counters. | Visible playable demonstration plus ordinary gameplay regression and packet-limit tests. All effects actually apply and reset. |
| M3 Full mechanics and economy | Pet vac, resource/status/cooldown/skill controls, mob controls, finite bot stock, paid-game receipts/refunds, social bait/stake session and atomic pickup attribution. | Player can win/steal a specific real bot asset; loss persists, no duplication/refund exploit, clear scope isolation. |
| M4 Reactive world | Witness filter, evidence/emotion state, victim/witness scenes, memory, rumor limits, restitution and optional fictional investigation. | Fair, subtle, blatant, unseen and false-positive scenarios produce appropriately different reactions. |
| M5 Extended catalog and polish | Routes/Kami, advanced combat/summons, remaining movement, visual/info features, complete skins/assets/hotkeys and accessibility. | Every catalog row implemented or explicitly rejected with a documented technical reason and a concrete substitute decision; no silent omissions. |
| M6 Release verification | Full compatibility matrix, concurrency/load/soak/restart tests, documentation and recoverable packaging. | All required acceptance tests pass on the actual owned server/custom client, with artifacts and measured results. |

M2 is a useful intermediate build, not the complete request. The complete product includes M3-M6 and all requested catalog families. Optional advanced representations may be refined based on client compatibility, but FMA, item vac, jump spam, mouse fly, external trainer presentation, baited valuable drops and emotional bot reactions are non-negotiable acceptance behaviors.

## Acceptance tests

| ID | Scenario and required result |
| --- | --- |
| A01 | Launch trainer before client, while client is at login, during loading and after world entry. Each status/error is correct; no fake success from cosmetic scanner. |
| A02 | Run two clients and two trainers. Selecting one changes only its character; second writer is rejected or read-only. Cross-account commands and stale session IDs cannot affect another character. |
| A03 | Unauthorized server/account and unknown protocol/features reject clearly. Stock client still logs in and plays under ordinary rules. |
| A04 | Apply a multi-feature preset with one invalid parameter. Atomic failure leaves prior effective state intact; accepted/clamped values are exactly reflected by UI. |
| A05 | Panic from chat, trainer hidden, game unfocused and automation under load. New actions stop promptly, held input clears, server acknowledgement or pending expiry is truthful. |
| A06 | Kill trainer/client or break connection with all major toggles on. Lease expires within configured limit, no bot/mob remains frozen, no repeated actions survive reconnect. |
| A07 | Tubi at feet speeds repeat pickup without widening reach. Vac widens reach without inventing drops. Combining them yields real inventory and correct statistics. |
| A08 | Item/pet/meso filters, unknown prices, exclusions, quest/unique items, full bag and party mesos behave consistently. Rejected pickup never triggers victim loss. |
| A09 | Drop age/expiry boundary, duplicate command, simultaneous manual/pet/vac/bot pickup and map object ID reuse yield exactly one terminal ownership outcome. |
| A10 | FMA with 1, 15, 16 and 100+ mobs applies real skill damage and normal deaths/EXP/quests/drops, never count wrap or duplicate damage. Legal large-area skills do not produce false accusations. |
| A11 | Test physical, ranged, magic, charge, multi-hit, healing-on-undead, summon and boss-adapter skill cases. Unsupported cases say so; no arbitrary autoban from approved trainer actions. |
| A12 | Speed/fast attack/damage/accuracy/cooldown/MP/ammo toggles alter named behavior only. Off preserves real level-up/equipment/buffs and natural cooldown state. |
| A13 | Repeated midair jump, modifier mouse fly, camera pan/zoom, hover, click teleport and ladder transitions work on tall/wide maps without coordinate drift or out-of-bounds lock. |
| A14 | Jump/fly/route/Kami/teleport conflicts have deterministic priority. Manual input pauses automation; text input never starts movement; panic always wins. |
| A15 | Mob vac/freeze/disarm/reset affects only leased eligible mobs. Another actor's normal mobs and boss/event scripts are unaffected. Two writers cannot fight over control. |
| A16 | Existing paid host still supports 10m/50m tiers and recognizable duration/routine. Payment failure, empty pool, party failure and restart settle/refund exactly once with actual balance evidence. |
| A17 | Social bot declines if busy/poor/suspicious, negotiates from actual assets, exposes a real valuable instance, and loses that same instance when stolen. No repeated prompt creates stock. |
| A18 | Distant visible vac theft triggers shock then justified accusation/anger/sadness and refusal/cooldown. Dialogue timing and poses are visible to the player. |
| A19 | Normal nearby pickup and subtle Tubi produce loss/uncertainty rather than certain hacking accusations. A pet/legitimate teleport/lag explanation is considered. |
| A20 | Theft out of every bot's sight yields missing-item confusion without naming an unseen culprit. A distant bot in another channel/map instance never reacts. |
| A21 | Flying/FMA/mob vac witnesses react only to visible effects; merely enabling a toggle while idle produces no bot response. Local visual hiding does not blind them. |
| A22 | Thirty potential witnesses produce bounded speech, not a chorus; one incident is not scored repeatedly from target batches. Busy bots ignore or defer appropriately. |
| A23 | Disconnect/restart/map change mid-reaction cancels stale lines/tasks. Completed losses/memories survive as defined; no stale callback drops a new prize in the wrong map. |
| A24 | Player returns all/part of a stolen asset through a real transfer. Loss/restitution and dialogue reflect exact quantity; no fake apology command creates ownership or wipes evidence. |
| A25 | Roleplay report/investigation never writes a real account/IP ban by default. Pretend consequences have an exit and do not corrupt unrelated party/event state. |
| A26 | Finite rare stock and daily/campaign budgets survive reconnect/reset; server restart during transfer/refund cannot multiply assets. Fixture reset cannot export duplicate wealth. |
| A27 | UI remains usable at supported DPI, keyboard only, sound/motion off; hotkey collision and unknown preset version are handled clearly. |
| A28 | Every catalog row has an implementation test or manually evidenced feature check, authority owner, default/range, conflict behavior, reset result and version confidence tooltip. |

Verification levels: pure tests for capability/schema/profile/evidence/economy rules; integration tests for pickup/payment/combat/lifecycle; replayable scripted scenes for witness behavior; actual custom-client interactive verification for rendering/input/feel. Use deterministic seeds and controllable clocks for timing/concurrency tests. Assertions check real inventory/HP/EXP/map state, not only emitted packets or checkboxes.

## Performance and reliability targets

Initial test profiles: one player with 100 mobs and 500 drops; two trainer users sharing a map with 30 bots; larger stress map with 200 mobs, 1000 drops and 50 bots; a 60-minute all-feature soak with repeated transitions and a 1000-cycle attach/apply/panic loop. These are proposed test sizes, not measured support claims. Respect the production machine's measured baseline and report hardware/build/assets alongside results.

Budget expensive work by active map spatial indices and event subscribers, not a full-world/all-bot scan every frame. Vac scans use map indexes and bounded candidate batches; FMA target selection is bounded; observers consume relevant spatial events and aggregate one cast/incident; dialogue callbacks are cancellable. No per-target timer explosion, unbounded event buffer or new thread per pickup. Backpressure drops stale telemetry/mouse updates, never committed economy events.

Proposed pass targets: trainer idle CPU under 1 percent on the target machine; UI remains interactive and panic input acknowledged locally within 100ms; ordinary attach/profile response typically under 250ms on local/LAN after readiness; server reset within 500ms when connected or configured lease deadline on failure; added map action work below a measured 5ms p95 budget at the core profile; no more than 10 percent regression in baseline map tick/frame p95 under comparable rendering load. If target hardware cannot meet a threshold, reduce declared capability limits and document the measured result rather than invent success.

Observe action rate, queue depth, dropped/coalesced intents, target/pickup batch size, transaction conflicts, tick duration, memory, IPC latency, active leases, stale task rejections, observation counts and speech budgets. Separate simulation time from render FPS so CPU mode does not create speed hacks or delayed expiry. The soak must show no growing reserved stock, locked drops, leaked bot tasks, frozen mobs or monotonically growing queues.

## Known blockers and completion evidence

Current spec is complete enough to implement; runtime feasibility remains to be demonstrated. Client setup may still lack required NX assets or the documented legacy toolchain. Upstream v83 compatibility does not prove every SoloMapling custom opcode/content path works. The client setup's final build and asset alignment are M0 gates. The exact reserved extension opcode and per-skill animation support require a fresh registry/code audit. Bot asset ownership and restart-safe payment/transfer persistence need end-to-end tracing before economy work. Witness geometry/lag tolerances and rare-value budgets require playable tuning.

The historical audit establishes some labels from period/v83/later primary sources; it does not establish an exhaustive verified list of every hack available in every v55-v83 patch. Each catalog feature has a defined recreation regardless of naming confidence. Further archival research can strengthen individual labels without blocking the owned-client mechanics.

Release completion requires: standalone EXE and retro assets; real attach/status/panic behavior; all core and extended catalog outcomes or explicit approved substitutions; no unintended global cheats; actual valuable drop theft with finite stock; varied visible bot emotion/evidence/memory; default non-permanent roleplay consequences; passing functional/concurrency/recovery/performance tests; operator/setup/reset instructions; compatibility manifest; and a recorded demonstration on the real custom client. Saving this specification does not claim any of those implementation gates have passed.
