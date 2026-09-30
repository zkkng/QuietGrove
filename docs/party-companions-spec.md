# Party companions: capability audit and implementation specification

Date: 2026-09-29. Audit/specification only; no gameplay implementation or deployment.

Checkout: G:\Maplestory v83 server dev\SoloMapling-v83. No AGENTS.md was found in the checkout (including hidden directories), its development-folder parent, or G: root. Substantial unrelated changes already exist, including Character, Monster, World, configuration and chat handling. Preserve them. Findings are based on source inspection, not live tests.

## Required outcome

Available suitable bots accept direct party invites without mandatory dialogue. Public/named natural-language recruitment recognizes R>, LFM, party up, train/grind together and help me level. A public request emits at most FIVE total bot response messages, staggered; repeated requests fill the normal six-member party (human plus five bots). Exclude merchants/dealers/hosts, busy/event/PQ actors, unsupported combat profiles, and bots reserved by another player or party.

Companions follow the human leader through ordinary same-channel maps, fight using actual skills/resources/potions, heal and maintain useful party buffs, especially learned Holy Symbol. Recruitment suspends autonomous grinding destinations, breaks and shopping. Difficulty reports use actual misses and survival evidence. Configurable EXP rewards meaningful damage OR healing/buff support without parked-bot multiplication. Field bosses later use this same task model; boat Crimson Balrog and instanced Balrog remain deferred.

## Existing capability audit

| Area | Actual implementation | Missing requirement |
| --- | --- | --- |
| Invites | PartyOperationHandler queues bot invitations; BotPartyQueue wakes the bot. BotPartyCommands answers InviteCoordinator and calls Party.joinParty. | BotRecruitManager.pollInvites accepts only a previously dialogue-armed inviter; direct strangers are rejected. Its armed ID can match after expiry. OPQ's unconditional BotPartyLogic is a separate path. |
| Recruitment | Social/training dialogue rolls 70/80% acceptance, has decline cooldown and follower cap 30. Dispatcher supports named dialogue/menu keywords. | No general public recruitment coordinator, response budget or atomic seat/bot reservation. Name matching is case-sensitive substring matching. |
| Follow | FollowerBot resolves leader by ID, has 90-second loss grace, notices party loss and pauses for trade. GCFollow redirects cross-map travel. | FollowerBot has no combat/heal/buff loop. Release chooses Social/Training based on current map, not previous activity. GCFollow compares map IDs, not full channel/map identity. |
| Party training | TrainingBot DECIDE chooses first real party member's grindable map; GrindBrain/EngageBeat call BotAttackDriver. | Not continuous leader following. Can hold current grind map when leader goes to town; autonomous breaks/routines still exist. Converting to follower removes grinding. |
| Travel | GCTravel navigates portals/taxis/scripted warps with recovery. | No route causes a destination warp. Companion travel needs access/instance/normal-map gates before any fallback. |
| Combat | BotAttackEffects applies real Monster.damage and normal kill/EXP accounting. | BotDamageModel rolls tier/level bands, not real accuracy/defense outcomes. Driver lacks actual learned-skill/MP checks. AttackResult.miss includes cooldown and reach failure, not accuracy misses. |
| Survival | BotContactDamage broadcasts hurt/knockback/iframes. | Explicitly no HP loss. Flavor store visits do not prove potion consumption. Real incoming damage and finite resources are missing in audited paths. |
| Heal offense | Driver already chooses Cleric Heal against undead flag and damages undead targets. | Generic bot damage, no ally-heal priority, learned-skill/MP check or complete susceptibility/immunity policy. Targets are capped before undead filtering. |
| Buffs | BotBuffDriver calls BotBuffEffects.castBuff; nearby HUMAN party members receive real effects. Priest registry contains HS. Buff request handler grants real extended buffs. | Self/bot effects remain cosmetic. Max-level effects, fixed 700x350 range, no MP cost or weaker-overwrite arbitration. Training force-buffs every 90-120s; followers do not run it. Request handler only treats trading as busy. |
| EXP | Monster already shares bot damage and grants bots EXP. | Same-map membership determines bonus, not combat/support participation. Local level-range enforcement is off. |
| Concurrency | Party locks individual member operations. | joinParty checks size separately before World.updateParty(JOIN); atomic capacity across bot AND human joins must be verified/fixed. |

BotDecorate.setBotVariables sets job/level/equipment but does not allocate a real skill/stat build in inspected methods. Trace full construction/leveling before using stats; job label or WZ max level does not prove skill ownership. Some buff comments are stale: human party buffs ARE real already.

## Components and invariants

Use a shared CompanionTaskService, not constant conversion between independent training and follower brains. A dedicated companion bot type may save a prior-activity descriptor; an exclusive task mode may instead suspend the existing brain. Either design must have one movement owner and one combat/resource owner.

Small components: RecruitIntentParser; RecruitRequestCoordinator; CompanionTaskService; CompanionCombatController; PartySupportController; PartyParticipationLedger; a pure EXP policy helper. Resolve live characters by IDs, not long-lived Character references. Store world/channel, party, owner human, current human leader, bot, task ID/generation, state/objective, reservation expiry and prior activity.

Hard invariants:

1. One bot has at most one reservation/task; actual party capacity is six. Pending reservations consume free seats but are not membership.
2. Every delayed action revalidates task/request generation, party, world/channel/map instance, availability and expiry before speaking or mutating state.
3. Cancelled/expired tasks cannot join, attack, cast, travel, speak or resurrect old routines later.
4. Companion ownership suspends autonomous map selection, breaks and shopping.
5. A public request emits at most five BOT MESSAGES in total, including acceptance/join/follow acknowledgments, not five candidates each producing multiple lines. Full/unavailable requests may emit zero; one private server status can explain why.
6. Only authoritative applied damage/healing/buffs count as participation; animations never count.
7. Cleanup is idempotent and releases invitations, reservations, movement, scheduler exemptions, combat/buff timers, participation and references.

## Recruitment

Direct invitations use normal engine restrictions, then the same availability/reservation service as chat. Eligible available bots accept without conversation/RNG. Revalidate party at acceptance. Existing human party leader owns the task; a nonleader cannot redirect companions. Existing OPQ behavior remains its own event path.

Use deterministic token-aware parsing, not an online model in the chat path. Positive examples: R> party for grinding; LFM to train; anyone want to party up?; help me level; Mira come train with me. Bare train/help are insufficient. Handle case, punctuation, whole names and negations; do not match bot names containing keywords. Distinguish cancellation from recruitment. Ignore bot/system/NPC chat and command prefixes. Existing dialogue remains usable.

Public candidates default to visible same-map/channel bots. Create a human-led party if absent, then recruit up to actual free seats minus pending reservations. Rank by useful role, level suitability, distance and fair rotation. Each selected bot gets one staggered acceptance and canonical join; suppress redundant automatic join/follow speech under that request budget. Named recruitment only targets that bot.

Initial reply timing: 0.6-1.2s first reply, 0.5-1.0s gaps. Request expiry: 15s. Coalesce identical in-flight requests from the same human/party; later requests reevaluate remaining seats. Atomically reserve bot, party seat and global capacity; initial global cap 30, configurable. Revalidate at delayed execution and release on expiry, cancellation, busy-state change, disband/full party or failed invite answer. No three-minute successful-recruitment cooldown.

Initial allowlist: supported SocialBot, TrainingBot and TownWandererBot with verified combat profiles. Exclude merchant variants, FMBot, dealer/dice/gacha, tutorial/game hosts, JQ/PQ/OPQ/event actors, unregistered decorative spawns and already-owned followers. Check alive/current channel, trade/shop/game, another player's conversation, event instance, blocklist and pending reservation. The requester's own conversation may transition safely.

## State machine and lifecycle

AVAILABLE -> RESERVED -> JOINING -> FOLLOW <-> ENGAGE/SUPPORT -> RELEASING -> RESTORED.

Temporary states: LEADER_GRACE, RECOVER_ROUTE, DEAD/RECOVERING. All can release. Create task only after canonical join succeeds; failed join leaves original activity intact. Snapshot previous type, home/map intent and safe routine parameters, not old asynchronous closures or expired spot claims.

FOLLOW routes to the actual human leader through allowed same-channel maps. ENGAGE runs on the same allowed map instance within an initial 700x350 pixel leash. Return to FOLLOW when leader leaves the leash/map. Offset formation positions. Support/survival may preempt offense. Combat movement suspends follower movement; autonomous grinding never competes. Town visits remain FOLLOW.

- Kick/disband/cancel/owner leaves: immediately stop task actions, clean membership through canonical paths, restore prior activity once; prevent the same in-flight request from rerecruiting.
- Human leader change: revalidate and follow the new human leader. A bot leadership change does not grant it authority over companions; retain a valid human owner or release if none exists.
- Logout/relog: stop attacks and activity credit; retain task for 90s and re-resolve human ID, same-channel membership on relog. Expiry releases and restores. No autonomous farming while human is absent.
- Channel change: pause and notify once; same 90s return grace. Automatic cross-channel migration is deferred. Equal map IDs across channels are not co-location.
- Human death: stop autonomous farming and wait for legitimate revive/map transition. Bot death: immediately stop damage/support/EXP, apply explicit canonical town-recovery/revive policy; no free instant battlefield resurrection.
- Unsupported/event/boat map: hold safely and report once. Never use no-route teleport to bypass access restrictions.
- Stuck: initial 15s progress timeout, bounded replan, then validated allowed-edge recovery. After two failed recoveries or 45s without progress, hold/release and notify once; no endless warp loop.
- Despawn/shutdown/type change: invalidate generation and callbacks. Restart persistence is deferred; do not revive stale reservations.

## Real combat and resources

Start with a companion-only real-combat mode so decorative population behavior is not globally changed. Reuse visuals/navigation, but resolve learned skill level, hit/miss, defense, elemental immunity, MP, cooldown and target legality before applying effects. Successful damage reaches existing Monster accounting exactly once. Do not fake client packets to invoke player handlers.

Player attacks include client-computed damage, so existing server handlers are not automatically a complete reusable hit calculator. Implement/extract a documented deterministic resolver using local stats/WZ and tested fixtures; do not claim exact client equivalence without validation. Trace bot construction and allocate coherent STR/DEX/INT/LUK, HP/MP, equipment and learned skills. Leveling updates the build consistently.

Incoming contact damage must lower HP; audit monster projectile/skill/status damage and map hazards for a clientless character. Honor defenses/iframes and avoid duplicate application. Potions consume actual finite inventory and canonical item effects. Initial HP threshold 50%; MP threshold 30% or insufficient for next priority skill; potion cooldown initially 1s subject to normal restrictions. Exhaustion causes conservation/retreat and one report, not silent refills. No autonomous restock trip while recruited.

Difficulty uses actual valid attack attempts, accuracy misses, landed damage, HP loss and potion drain. Cooldown/no-target/out-of-range are not accuracy misses. Initial observation: 10 valid attempts over at least 8s; warn above 60% misses or when survival/resource trend predicts failure. Critical HP without a usable heal/potion triggers immediate survival action. Distinguish cannot hit, cannot survive and cannot reach. Aggregate warnings; initial cooldown 60s per bot/reason/map. Level difference helps suitability but never replaces evidence.

## Heal and proactive support

Urgent ally healing outranks offensive Heal. Require learned Heal, MP, legal cadence/range/target rules. Offensive Heal requires actual undead flag plus current elemental/immunity/skill legality; holy weakness alone does not make a living monster eligible. Filter legal undead before target cap. Verify exceptions against actual WZ/combat rules. Use Heal-specific damage calculation rather than generic tier damage.

A single cast may heal valid allies and damage valid undead; charge once. Restore only missing HP, never resurrect with Heal, and attribute only effective healing. Priority: immediate survival, critical ally Heal, missing high-value party buffs (especially HS), ordinary offense/maintenance.

Priests/Bishops proactively cast HS only when actually learned. Buffs on humans AND companion bots must be real. Respect actual skill-level duration/range, MP, cooldown, party and map identity. Do not inherit unconditional ten-minute/max-level public buff grants. Public requests involving companions should share the same cast policy.

Compare recipient source/magnitude/expiry/stacking group. Do not replace stronger effects; equal effects refresh near expiry (initially last 10% duration, at least 3s), or buff newly arrived unbuffed members. Coordinate best eligible caster per party/buff group, then revalidate delayed casts. Multi-stat effects need correct per-stat conflict handling. No beneficial recipient means no cast, MP spend or participation event. Suppress routine buff chat spam.

## EXP: actual formula, defaults and anti-idle policy

Local config: common share 0.8; MVP share 0.2; PARTY_BONUS_EXP_RATE 1.0; USE_FULL_HOLY_SYMBOL false; level-range enforcement false. For party-owned monster EXP P, member i's current pre-modifier base is B_i = P * (0.8 * level_i / totalEligibleLevel + (MVP_i ? 0.2 : 0)). Existing party bonus is B_i * 0.05 * eligibleMembers when more than one shares.

Monster.giveExpToCharacter separately multiplies base and bonus by status (including HS), character rate, PC cafe and family modifiers; bonus additionally uses PARTY_BONUS_EXP_RATE. They round separately. Flat EXP_INCREASE is added only to personal EXP. Preserve damage ownership between parties/solo attackers, level sharing and this modifier order. Never multiply final Character EXP a second time.

Recommend replacing the ordinary 5%-per-member coefficient in participating companion parties with the proposed curve, not stacking another bonus atop it. Keep the existing party-bonus rate as its one scale. Parties without companion tasks retain current behavior.

| Active members | Existing bonus | Proposed total bonus | Gain vs current party bonus | Equal-level MVP total/P | Equal-level other total/P |
| --- | --- | --- | --- | --- | --- |
| 1 | 0% | 0% | 0% | 1.000 | n/a |
| 2 | 10% | 20% | 9.1% | 0.720 | 0.480 |
| 3 | 15% | 35% | 17.4% | 0.630 | 0.360 |
| 4 | 20% | 45% | 20.8% | 0.580 | 0.290 |
| 5 | 25% | 55% | 24.0% | 0.558 | 0.248 |
| 6 | 30% | 65% | 26.9% | 0.550 | 0.220 |

Arithmetic example: six equal members, P=1000, rate=1, no other modifiers: MVP about 550, other members 220 each. With full 50% HS: about 825/330. Party totals 1650 before HS and 2475 after; separate rounding can vary individual results by one point. With the current HS flag, solo 50% HS grants 10%, while more than one eligible sharer gets full strength. A human non-MVP still receives less EXP per kill than solo; faster leveling depends on kill throughput, not just bonus size.

Participation policy:

- Snapshot actual living same-map-instance/channel members with normal level eligibility. Enhanced bonus needs at least one companion task, two active participants and an active human.
- Preserve ordinary human sharing. Inactive recruited bots must not increase the bonus, enable full HS, or dilute the active human's denominator as parked EXP receivers. Apply an explicit inactive-companion eligibility filter before final recipient/MVP calculations; test this intentional companion-only anti-leech change.
- Only active recipients get the enhanced coefficient. Inactive human recipients retain at most their ordinary entitlement and cannot raise enhanced active count; implement baseline/enhanced components explicitly.
- Initial activity window 30s. Meaningful damage: >=1% of recent party applied damage OR three successful damaging actions; a short-lived low-HP monster kill can qualify immediately. Tune for weak but useful members rather than arbitrary level gates.
- Meaningful healing: effective combat HP restored to an engaged ally, initially 5% max HP cumulatively within window. No overheal, self-inflicted/noncombat farming, dead/off-map targets.
- Meaningful buff: attributable real useful effect actually benefiting an active recipient's combat. HS benefit can credit its caster when an engaged recipient earns combat EXP. Cosmetic, weaker, duplicate/no-op recasts generate no credit.
- Prevent buff parking: caster alive, same party/map, in support range and active companion ENGAGE/SUPPORT duty (human sources need recent meaningful combat/heal/support interaction). Expired/off-map/public ten-minute effects and idle sources cannot perpetually qualify. FOLLOW/grace/paused/dead tasks are ineligible. Conservative human default: merely retaining an old long buff does not extend human activity beyond 30s.
- Snapshot before awarding EXP; HS-support credit arising from a kill affects the NEXT snapshot, avoiding circular count changes. Partition/clear records on map/party/channel/task generation changes; bounded ID-based storage.

Use existing HS status multiplication once, on base and bonus. hasPartySharers must reflect actual eligible sharing after inactive-companion filtering; all-human semantics stay unchanged. One active human plus five parked bots gets no enhanced bonus or idle-bot full-HS benefit.

The proposed curve is a reasonable starting setting, not validated EXP/hour balance. Measure solo and 2/3/6 members, unequal levels, human/bot MVP, support-only Priest, with/without HS and changed rates. Record human EXP/min, kills/min, deaths and potion costs before enabling globally.

## Incremental implementation milestones

1. Pure parser, eligibility, reservation/budget and EXP policy contracts/tests; task records and trace IDs. No combat changes.
2. Direct/public/named recruitment and atomic canonical party capacity; lifecycle, normal-map follow, prior-activity restore. Development feature disabled by default until combat works.
3. Verified builds/skills, real hit/miss/HP/MP/potions/death, combat/follow arbitration, leash and difficulty evidence. A recruited bot must fight and follow across a portal without manual mode changes.
4. Real Heal and proactive buff/HS support, stronger-effect/caster arbitration, costs and effect attribution.
5. Activity ledger, EXP filtering/curve/HS tests, economy measurement and chosen defaults.
6. Curated field-boss objectives using the same task lifecycle, after ordinary companions are reliable. Deferred Balrog variants remain unsupported.

Implementation can delegate parser/reservations independently against agreed interfaces, while one strong coding owner handles lifecycle/movement. Support/EXP depend on the authoritative combat event contract. Assign explicit ownership before parallel edits to already-dirty Monster/World/Character/chat files. Independent review should focus on concurrency, cancellation and EXP economics.

## Acceptance tests

- Direct invite succeeds with no prior dialogue for eligible bots; excludes busy/merchant/event/other-party targets, clears coordinator state and has no RNG requirement.
- Parser fixtures cover positive/negative/negation cases, punctuation/case/whole names, keyword-containing names, commands, repeated requests and dialogue coexistence.
- 100 eligible nearby bots still emit <=5 total messages per public request with fake-clock staggering. Cancellation stops queued speech/joins. After two joins, a later request fills remaining three slots.
- Race two humans for one bot; race requests for last seat; race canonical human join against reserved bot. Assert one owner and <=6 members. Inject expiry/busy/disband/full/failed invite between reservation and callback.
- Follow two adjacent maps, redirect mid-travel, town visit and vertical route, then combat. No competing autonomous destination/break/store task. Refuse event/boat/restricted maps and other-channel matching map IDs.
- Kick/disband/cancel/leader change/death/logout/relog at 89s/expiry at 91s/despawn/late cast/stuck recovery each leave no orphan tasks and restore once.
- Actual misses differ against known avoid fixtures; cooldown/reach failures do not count. Incoming damage lowers HP; finite potions decrement once; insufficient MP prevents cast; death stops effects/EXP.
- Heal damages eligible undead, never living/immune fixtures; mixed packs filter before cap. Wounded allies win priority; overheal does not count; combined healing/offense charges one cast.
- Learned-HS Priest casts proactively; unlearned does not. Stronger effects survive weaker attempts; equal healthy buffs do not spam-refresh; newly arriving ally gains buff; departed delayed target does not. Bot allies get real effects; resources/cooldowns apply.
- Exact EXP vectors n=1..6, unequal levels/MVP ties/mixed damage/dead/off-map/leech enforcement/rates/rounding/overflow/PC cafe/family/flat buffs/HS solo and full. No double HS or party coefficient.
- Five idle bots never multiply one human's EXP/HS. Effective healing-only/buff-only support qualifies. Cosmetic/no-op buffs, overheal, old public HS, off-map sources and kick/rejoin cycling do not farm activity.
- Measure five companions per human and global cap 30; avoid per-bot world scans every 50ms, unbounded queues and chat/log spam. Packet/navigation checks require local gameplay verification in addition to deterministic tests.

## Future field-boss objective

Task objective is TRAINING or FIELD_BOSS, not a separate recruitment implementation. Curated registry supplies aliases, verified template IDs, ordinary map IDs, suitability and completion rule. Verify IDs against actual repository data; never guess from names. Ambiguous Balrog requires explicit variant handling; boat Crimson Balrog and instanced Balrog return unsupported, never fallback travel.

Reuse response budget/reservations; suitability uses role/profile plus observed performance. Track selected spawned monster object/encounter and template ID. Completion requires its legitimate death with party contribution, not an unrelated same-template kill; despawn/reset/stolen kill is not silent success. Report once. Recommended one-shot boss task restores previous activity, while an explicit join-my-party objective remains companionship until cancelled.

## Remaining uncertainties and evidence

Resolve full bot stat/skill/leveling initialization; real clientless monster skill/projectile damage; Heal special exceptions; full World membership locking; multi-stat buff strength attribution; runtime config differences; performance/EXP-hour balance. This audit did not inspect or alter live server state.

Complete reads: BotPartySystem/BotPartyCommands.java, BotPartyLogic.java, BotPartyQueue.java, BotRecruitManager.java; BotTypes/FollowerBot.java; BotAttackSystem/BotBuffEffects.java, BotBuffDriver.java, BotDamageModel.java; BotMessagingSystem/Dispatcher.java; GCMoveSystem/GCFollow.java; net/server/channel/handlers/PartyOperationHandler.java.

Targeted reads/searches: BotTypes/TrainingBot.java, BotSM.java, BotGeneration.java, BotTypeManager.java, BotDecoratorSystem/BotDecorate.java; BotAttackSystem/BotAttackDriver.java, BotAttackEffects.java, BotAttackConfig.java, BotAttackProfile.java, BotBuffConfig.java; BotBuffRequestSystem/BotBuffRequestHandler.java; BotGrindSystem/GrindBrain.java, EngageBeat.java; GCMoveSystem/BotContactDamage.java, GCTravel.java, GCMovement.java, GCMovementDriver.java and navigation/movement sources; net/server/world/Party.java; server/life/Monster.java; server/StatEffect.java; net/server/channel/handlers/GeneralChatHandler.java, AbstractDealDamageHandler.java, MagicDamageHandler.java; config.yaml and config/ServerConfig.java. Unprefixed bot paths are under src/main/java/soloMapling/ArtificialPlayer.

Validation: static source inspection and arithmetic derivation only. No build, gameplay test, deployment or gameplay mutation was performed.
