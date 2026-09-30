# Monster Carnival bot gameplay specification

2026-09-29. Source audit and implementation contract. No gameplay edits or live match was performed for this document. Scope includes actual bot teammates/opponents in Monster Carnival 1 and 2, not actors standing in the lobby or repeating CPQ dialogue. See [master roadmap](project-roadmap.md) and [companion combat contract](party-companions-spec.md).

## Existing implementation and defects to validate

The checkout has substantial human Carnival mechanics: `server/partyquest/MonsterCarnival.java`, `MonsterCarnivalParty.java`, `CarnivalFactory.java`, `net/server/channel/handlers/MonsterCarnivalHandler.java`, Character CP/death handling, MapleMap spawn/guardian/kill hooks, channel lobby management and Spiegelmann NPC scripts. A targeted ArtificialPlayer search found no dedicated Carnival bot controller. Existing human code is a substrate, not verified bot support.

| Audited path | Current fact | Required action |
| --- | --- | --- |
| NPC 2042000/2042002 | CPQ1 lobby 980000000, level 30-50, team size 2-6; `USE_CPQ` gate | Preserve the current local rule until an explicitly chosen historical profile differs. Do not claim 30 versus 31 is historically settled by this source. |
| NPC 2042005 | CPQ2 level 51-70, team size 2-6 | Separate mode definition and room/reward assets; do not route it through CPQ1 assumptions. |
| MonsterCarnival constructor | Two parties become enemies, members get team/session, disposable map, clocks and respawn tasks | Introduce complete preflight and atomic roster/room reservation before mutation; constructor catches/prints failures and can leave partial setup. |
| Match clock | `startTime` hardcodes ten minutes/three-minute extension while timers use map `timeDefault/timeExpand` | Use one authoritative definition/deadline; validate positive durations and result lead-in. |
| `complete` and `timeUp` | `complete()` sets `medalResultRecorded` once and snapshots totals; tie returns. `timeUp()` can extend; future `complete()` immediately returns | Likely permanent-tie/stale-score extension defect in current dirty source. Separate one-time final award from per-period score snapshot and prove overtime behavior with tests. Do not overwrite another owner's ranking-medal changes. |
| `dispose`/disconnect | Mutable leader party rosters drive cleanup; assumes map/leaders remain valid; disposes map and timers | Snapshot admitted participants, idempotent close state and exact map generation; repeated callbacks/leader departure cannot clean another match or throw. |
| Action handler | Reads signed byte tab/number, has upper-bound checks but no explicit lower bound, casts summons/debuffs/guardians and later spends CP | Typed domain action validates lifecycle, membership, map/team, lower/upper indexes and positive costs; debit and mutation atomic. |
| Summon action | Can assign `neededCP` even when Carnival is null; cap checks/increments separate | Rejected/no-session actions cost zero and broadcast no successful summon; capacity reservation is atomic. |
| Debuff action | Null disease can be dereferenced before the null/dispel branch; single-target selection uses `size - 1` | Validate supported effect type; choose from a nonempty eligible opposing roster without excluding the last member. No off-map/wrong-session targets. |
| CarnivalFactory | WZ skills/guardians and random placeholders; random choice draws from buckets including the recorded IDs | Resolve random actions to concrete eligible non-placeholder effects, preserve displayed action cost semantics and handle empty buckets. |
| CP state | Character has int `cp/totCP` and separate legacy short `availableCP/totalCP`; MonsterCarnivalParty also has shorts | Choose one authoritative ledger; retain protocol adapters only. Trace actual callers before retiring redundant fields. Use checked arithmetic, never wrapping counters. |
| Kill/death paths | MapleMap grants monster CP on kill; Character's CPQ death branch subtracts up to available CP and returns early | Prove attribution, CPQ1/CPQ2 map predicates, no EXP death penalty and actual revive lifecycle for clientless bots. Do not interpret early return as complete bot death handling. |
| NPC explanation | Two teams compete via monsters, CP spending, debuffs/guardians; carried recovery potions unavailable and pickup recovery items activate | Enforce the same verified rules for bots and humans; audit actual item/field-limit behavior instead of trusting dialogue alone. |

These are source findings requiring targeted fixtures and actual matches; this document does not claim observed production failures. Existing scripts/data and the active content policy define the first supported local profile. Exact historical region/patch reward details remain a provenance task; later Carnival reworks are not automatically valid v83 rules.

## Player flow and matchmaking

The player can ask suitable bots to join a Carnival party using contextual `CPQ`, `MC`, `Monster Carnival`, mode/level and named invitations, or an explicit NPC menu. Bare `MC` outside recruitment context is ambiguous and must not trigger a match. A normal party can include up to six, but room/team constraints may be lower; room definition is authoritative. One human may assemble a bot team and challenge a bot-led opposing team, or fill gaps around another human party. Bots remain actual actors with the same match membership and effects.

Use the common bot/seat task lease. Exclude busy traders/dealers/hosts, event/PQ actors, dead actors, companions already owned by another human and invalid builds. Recruited companions may explicitly transition together into Carnival through an approved adapter; generic follower no-route warp is prohibited. Reserve both teams and room atomically. Invitations time out and restore prior activity; no open reservation leaves a bot or room permanently unavailable.

Matchmaking balances effective build/gear, learned skills, hit/survival ability and team roles within the mode's actual level rules. Level alone is insufficient. Offer useful healing/support where available without materializing a high-level Priest in level-30 Carnival. Role diversity is preferred rather than an absolute class gate. Display team rosters/rules before start. Do not replace bots secretly with stronger ones midmatch, grant hidden catch-up damage or force a human win. Difficulty presets alter allowed bot skill/decision quality and visible builds before the match.

Lifecycle: REQUESTED -> RESERVED -> LOBBY_READY -> COUNTDOWN -> ACTIVE -> PERIOD_SETTLEMENT -> optional OVERTIME -> FINAL_SETTLEMENT -> RESULTS -> CLOSED. CANCELLED/FAILED converge on idempotent cleanup. Session includes UUID/generation, mode/version, immutable admitted roster and teams, party IDs, human owner(s), leased map/room, return locations, deadlines, CP ledger, owned summons/guardians/drops, reward journal and prior bot tasks. Handle human-only, mixed and test bot-only sessions through the same service; no background farm rewards to bots merely because a test runs with no human.

Existing leader-challenge/lobby NPCs should call the new service. The packet handler and bot planner call the same typed `CarnivalActionService`, never synthetic client packets. Party/disconnect changes use documented match policy: initial default cancels an unrecoverable incomplete roster with no win farming; a short reconnect grace may pause admission/result eligibility if adopted, but must not let departed players keep spending CP.

## Combat and tactical bot decisions

Use real companion combat/build/resource contracts with a Carnival policy overlay: allowed moves, same skill range/cadence, actual HP/MP, damage/accuracy/defense, real buffs/heals/statuses, and legal pickup recovery. No infinite carried potions for bots when humans cannot use them. Looting an instant-effect item invokes its canonical effect once and consumes that world drop. Bots must navigate to it at legal pickup reach; no trainer vac or hidden potion refill in ordinary Carnival.

Decision priorities: resolve death/revive and critical survival; useful allied heal/cleanse; accessible recovery pickup; defend an imminent threat; attack legal assigned-team targets; spend CP tactically; reposition/support. Select mobs the team is permitted to fight according to the actual map/team model, which must be verified for blue/purple layouts. Guard against accidentally farming one's own protected summons because template IDs match. Different jobs use their actual supported skills, range and positioning.

Bots can summon monsters, use debuffs and deploy guardians through the same menu choices/costs available to humans. A team coordinator may recommend a plan, but individual CP authority remains exactly what the selected local rules permit. Current handler checks personal CP; do not convert it into a freely shared bank merely because team totals exist. Keep available personal CP, cumulative earned CP and displayed team aggregates distinct. Spending lowers available CP, not cumulative score, unless an explicitly documented local rule says otherwise.

Tactics use observable state: score/time, visible opposition/classes, known guardian effects, nearby recovery items and own team needs. A novice spends imperfectly; a stronger bot saves for useful upgrades, coordinates expensive choices and avoids repeating an already active guardian. No access to hidden opposing cooldowns or future RNG. Think roughly every 0.5-1.5 seconds with jitter; execute combat/movement at normal engine cadence. This is a proposed planner rate, to tune under load. Human directions such as `save CP`, `summon more`, `focus guardian` choose a bounded strategy preference; they do not invent CP or bypass legality.

Guardians must have real world objects, ownership, effects and destruction rules. Bots navigate to and strike eligible enemy guardians using permitted attacks and cooldowns; a destroyed guardian removes only its own sourced effect. Summon caps apply per team/room and count live spawn definitions as the rules require; defeat/respawn/cancel must not free or consume capacity inconsistently. Defeated summoned mobs produce normal allowed CP/EXP/drop outcomes, with causal match ownership.

Deaths stop actions immediately, apply the mode's CP penalty once and revive via the canonical Carnival location/delay. Restore HP/MP as that verified rule specifies, not normal-world potion logic. An actor cannot be alive for damage while still dead for scoring or vice versa. No world EXP loss should appear if the Carnival rule promises none; test both variants and every exit path.

## Score, time and rewards

Each earn/spend/death transaction has action ID, session generation, actor/team, reason, previous/new personal balances, team aggregates and score. Serialize on the session/map executor or narrow ledger lock, then publish packets outside the lock. Negative balances, duplicate debit and reward on rejected actions are invalid. UI totals are derived, never a separate truth. All actions reject once settlement begins.

Use one common end-of-period timestamp and a defined settlement instant. Current code produces effects ten seconds before end; decide whether those ten seconds are a frozen result display or still playable, and make every score/action path agree. Initial proposal: ACTIVE ends at the authoritative deadline, then a ten-second result presentation with frozen score. A tie gets one configurable three-minute overtime under its own generation/deadline, then a draw policy if still equal. This bounded draw rule is a proposed reconstruction requiring visible rules; do not leave infinite overtime or manufacture a winning team.

Victory grade, EXP, festival points, coins and redemption use the selected mode's validated existing tables with an explicit result receipt. Distinguish ordinary monster EXP from completion EXP and coin drops. Companion enhanced-party bonus does not apply to competitive Carnival by default; its support ledger may inform eligibility but must not multiply match payouts. Inventory-full claims can retry once per receipt without duplicate reward. Death/leave/cancel/early disconnect cannot be used to generate a win reward or medal repeatedly.

Bots receive coherent progression/assets only under configured economy policy; no always-on bot-vs-bot infinite coin factory. Rewards consume/generate through a documented match economy budget. Reconnect/repeated results-NPC interactions do not regrant coins, EXP or ranking medals. End-of-match cleanup clears enemy-party links, session/team/CP state, leased summons/guardians/timers and bots' temporary activity, while preserving unrelated world state.

## Implementation queue

1. Trace NPC/lobby/channel/Character/MapleMap/Reactor paths and establish a passing human-only match for each variant; resolve the audited tie/debit/cleanup hazards with focused tests.
2. Extract definition/session/CP/action/result contracts and canonical entry/exit with frozen rosters and idempotent generation-safe cleanup.
3. Integrate shared bot leases and real build/combat support; implement lobby travel/readiness and a simple legal fight/recovery bot.
4. Add CP spending, guardian/debuff tactics, team balance and player strategy requests; keep personality/difficulty visible and fair.
5. Prove score/reward/death/exit/reconnect and multiple sequential matches, then measure concurrent rooms and background-world load.

Implementation owner is queued Sol after current GM/shared combat dependencies. This doc owns no gameplay files. Changes overlapping current dirty MonsterCarnival/Character/MapleMap or ranking-medal work require integration with that owner, not overwriting it.

## Acceptance gates

| Gate | Required result |
| --- | --- |
| C01 Admission | Exact local level/team/room rules; two teams and one bot lease atomically reserved; race human joins, expired invites and another event for the same actor. |
| C02 Modes | Separate CPQ1/CPQ2 maps, clocks, spawn/guardian menus, exits and reward tables load and validate; unknown variants reject. |
| C03 Core play | Bot visually moves, attacks with real skill damage, heals/buffs legitimately, collects actual recovery drops and can lose/die. |
| C04 Actions | Every tab/index including negative/out-of-range values; no-session/wrong-map/dead/nonmember actions reject without debit or success broadcast. |
| C05 CP concurrency | Simultaneous kill/spend/death/duplicate action maintains exact balances and totals with no negative/short overflow or cap race. |
| C06 Random/debuffs | Null/dispel/random skills work or reject clearly; every eligible opponent can be selected; no off-instance effects. |
| C07 Guardians/summons | Caps, duplicate effects, destruction, respawn and cleanup agree for both map layouts; wrong-team targets invalid. |
| C08 Tie and deadline | Tie at normal end, lead changes in overtime, repeated tie/draw, last-tick kill, ten-second presentation and repeated complete() all settle once. |
| C09 Death | Penalty once, no unintended EXP loss, real bounded revive and no actions while dead; carried potion restrictions match humans. |
| C10 Fairness | Same build/seed and comparable tactics produce comparable damage/CP; higher difficulty improves legal decisions rather than hidden power. No guaranteed human victory. |
| C11 Economy | Monster/result EXP separately audited, no unintended companion bonus, inventory-full retry safe, coin/medal receipt cannot repeat after disconnect/NPC spam. |
| C12 Lifecycle | Leader leave, party change, bot despawn, shutdown, map reload, exception during preparation and repeated dispose release only this match and restore actors once. |
| C13 Performance | Measure 1/3/6 actors per side where the room permits, then multiple rooms up to the measured limit; include real human viewers, effects and normal background population. No full-world scan per bot tick. |
| C14 Compatibility | Both normal v83 and ready custom client render UI/CP/guardians/results; packet legality remains identical for humans and bots. |

No capacity result or completed-match claim exists from this audit. Source-level tests, headless deterministic runs and actual rendered play are all needed for release.
