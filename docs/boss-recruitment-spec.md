# Boss recruitment and encounter objectives

2026-09-29. Gap audit and implementation specification. The goal is to ask bots to help with recognizable bosses, form a sensible party, travel together, fight with real skills/resources, react when the fight is too difficult and finish or abandon the actual encounter coherently. This extends [party companions](party-companions-spec.md) and shares combat/encounter contracts with [town incidents](gm-events-spec.md). No gameplay changes were made for this document.

## Current evidence

Gate 1 companion contracts exist, but their current `RecruitIntentParser` recognizes generic recruiting/training phrases rather than a boss registry/objective. The companion implementation handoff records 69 isolated tests and no runtime integration. Ordinary area-boss spawner scripts, WZ monsters/maps and multipart encounter helpers exist. None of those establishes boss-capable bot recruitment. Existing dialogue mentioning Jr. Balrog or CWKPQ is flavor, not an actionable objective.

The local data has multiple distinct monsters sharing names. For example String.wz maps Jr. Balrog to 8130100 and several Dojo/event/quest variants; Crimson Balrog includes 8150000, 9300210 and others. King Clang has both 5220000 and 5220001; the inspected `AreaBossKingClang.js` actually spawns 5220001. Never resolve a name by taking the first matching String.wz result or by guessing from a familiar online ID.

| Candidate registry entry | Local evidence verified | Support gate |
| --- | --- | --- |
| Mano | `AreaBossMano.js` spawns 2220000 in 104000400 | Curated ordinary field objective after combat gate; verify actual Mob WZ stats and route. |
| Stumpy | `AreaBossStumpy.js` uses 3220000 in 101030404 | Same; real arrival, spawn absence and contribution tests. |
| King Clang | Script uses 5220001 in 110040000; name also exists for 5220000 | Register selected variant explicitly, preserve source spawn lifecycle. |
| Mushmom | String 6130101 and map life in 100000005 | Field objective; validate reachable spawn area and respawn behavior. |
| Jr. Balrog | String 8130100 and map life in 105090900 | Ordinary dungeon boss objective; distinguish from boat/instance versions. |
| Manon and Griffey | 8180000 and 8180001; map life evidence in 240020401/402 and 240020101/102 respectively | High-level build/resource/route tests and correct map variant choice. |
| Dyle, Zombie Mushmom, Blue Mushmom | Name evidence for 6220000, 6300005 and more than one Blue Mushmom ID | Add only after complete spawn-map/variant/stat audit; a name match alone is insufficient. |
| Headless Horseman and Bigfoot | Name variants include 9400549/9400571 and Bigfoot 9400575; several Bigfoot map life entries exist | Curated area search/avoid/survival/navigation profile; do not merge event variants. |
| Anego or Female Boss | String name for 9400121 | Alias plus appropriate high-level encounter/build profile after map/skill audit. |
| Papulatus | Constants identify clock 8500001; String has Papulatus 8500002 and other variants | Phase/entry-item/quest/access adapter required; not generic field warp. |
| Zakum and Horntail | Existing constants/helpers identify arms/body phases and multipart parts | Expedition/instance prerequisite/phase adapter; coordinate with GM encounter registry. Not a promise a normal six-seat party bypasses expedition rules. |
| Crimson Balrog on the boat | Ordinary name candidate 8150000; transport-specific encounter not audited end-to-end | Separate future transport adapter with boarding schedule/route/spawn/controller/recovery tests. |
| Instanced Balrog | Separate encounter/access content exists in world policy; not the Jr. field boss | Separate future instance adapter, phase and skill restrictions; never substitute silently. |

This is the initial common-name audit/queue, not an exhaustive verified boss encyclopedia. The registry should support adding all agreed common bosses by data plus a validated encounter adapter. Names not yet supported return a precise status with an alternative, not generic recruiting followed by the wrong boss. The boss feature is incomplete until the required common catalog and explicitly queued transport/instance work have their own acceptance evidence.

## Intent and player experience

Examples to support: `R> Jr Balrog`, `anyone want to kill Mushmom`, `help me fight Stumpy`, `lets hunt Bigfoot`, `Mira come help with Manon`, `need a healer for Zakum`. Normalize case, punctuation, possessives and common abbreviations without matching fragments inside character names. Parse named recipient, objective, encounter alias, role request, gather location and cancellation separately. Preserve the shared maximum of five total bot recruitment replies per public request and the real party seat count.

Bare `Balrog` is ambiguous. Respond once with a short in-world clarification/menu choosing Jr. Balrog, boat Crimson Balrog or the instanced encounter. It must not default to a random variant. `Crimson Balrog here` with a visible summoned incident refers to that incident only after explicit context matching; it does not automatically board a boat. An unsupported future adapter explains that it is not ready. Negated/quoted discussion such as `don't recruit for Balrog` or `I remember fighting Balrog` must not start a task.

A valid request shows nearby suitable volunteers with brief role/confidence responses, forms/fills the canonical party, chooses the human leader, agrees a gather/travel objective and begins the common companion task. No free teleport, summoned boss or unlimited potions are part of asking for help. If the boss is absent, bots can wait briefly, search an approved area, report that it is gone or ask whether to switch objective. They cannot know its next spawn time from inaccessible internal scheduler state unless that is explicitly public knowledge.

The task tracks intent mode: `HELP_CURRENT_ENCOUNTER`, `HUNT_TEMPLATE_IN_AREA`, or `CONTINUING_PARTY`. One-shot help ends after the selected encounter resolves and restores previous activities; continuing party remains recruited until dismissed. Changing target/gather point comes from the human leader/owner through validated commands. A participant cannot redirect everyone to a different boss by incidental chat.

## Registry and encounter identity

Proposed `BossDefinition`: stable key/version, display/alias set, content-era/region evidence, allowed templates and linked phase IDs, encounter type, allowed maps/instances, entry/prequest/item/level rules, route profile, expected dangerous skills/statuses, suitability policy, spawn/absence policy, reward/contribution policy and completion predicate. Resolve aliases deterministically; collisions require context or clarification. Data load validates WZ existence, integer bounds, phase cycles, missing maps/portals and incomplete adapters.

`BossObjective`: task/generation, definition version, owner/human leader, party/actor leases, channel/map-instance identity, gather/route goal, observed target identity, phase descendants, contribution/evidence history, deadline, failure/withdrawal reason and prior activities. A runtime encounter identity is distinct from monster template ID. Killing another same-template monster in another channel is not success. If another party kills the target, report lost/completed-by-others according to actual contribution, not a fabricated win.

Maintain finite specific ownership for spawned incident descendants and multipart revives. Natural field bosses remain world entities; recruitment does not claim administrative ownership or despawn them on cancellation. Encounter cleanup removes only helper/task-owned state; it must not delete a naturally spawned boss or another party's damage credit. World event/GM spawned bosses use incident identity and a reviewed adapter.

## Suitable builds and credible combat

Suitability checks actual level/stats/equipment/learned skills, hit probability, survivable damage, required cleanses/mobility, MP/potion reserves, route/access and useful party role. Job label and cosmetic gear do not prove a build. Produce a versioned bot build profile with allocated stats, learned skill levels, supported attack rotation, equipment constraints, HP/MP, potion inventory and leveling behavior. No max-level skill on a bot merely because WZ provides that level.

Use roles such as frontline, ranged damage, magic damage and healing/buff support as preferences, not artificial requirements for easy field bosses. Priests/Bishops provide actual learned HS where appropriate; Clerics use offensive Heal only on legal undead and prioritize urgent allies. An easy Mano request need not recruit a high-level squad; a dangerous Bigfoot/Manon request should not select novices because they are nearby. Scarce suitable bots mean fewer volunteers or an honest refusal, not dynamically fabricated elites.

Initial suitability predicts risk, then actual evidence updates it: valid attacks/misses, damage dealt, incoming damage/statuses, potion burn and effective support. Cooldown/out-of-range failure is not an accuracy miss. Say `I can't hit it`, `I'm running out of pots`, `we need more damage`, or `I can't reach it` according to the actual cause. Use the companion observation window/cooldown to avoid constant whining, but react immediately to lethal resource exhaustion. Confident personalities can make bounded mistakes without becoming immortal.

Boss attacks must affect bots authoritatively. Existing Monster controller selection excludes bots in the inspected path, so zero-human/unobserved encounters need a verified server controller or equivalent. Human arrival/departure/control handoff cannot double-apply damage or freeze boss behavior. Validate projectile/area/status/dispel/summon/reflect mechanics and WZ timing. Do not call contact-animation-only damage a boss simulation.

The party navigates using actual map edges/portals/transport/access. Same map ID in another channel is not proximity. Generic GCTravel no-route fallback must not teleport into a boss arena. For strong bosses, define legal engagement positions, safe retreat points and support leash. Map geometry matters: vertical roofs, gaps and attack rectangles may make an apparently close target unreachable. Bounded replan/hold/report policy prevents infinite chase loops.

Death immediately cancels attack/support/contribution and follows the real permitted recovery route. No free battlefield resurrection unless a legitimate learned skill/item and encounter policy allow it. Retreat is a valid result; repeated suicide reinforcements are bounded by existing population, resources, recovery cooldown and travel. Difficulty never auto-nerfs the boss to produce a victory.

## Rewards and completion

Use ordinary authoritative damage/death/EXP/drop/quest attribution once, including actual healing/buff contribution where the shared policy permits. The companion party curve is applied only through its documented policy and modifier order, never an extra boss multiplier. Existing quest/access/expedition constraints remain meaningful. No bonus item/EXP appears merely because a boss task was marked complete.

Completion states: KILLED_WITH_CONTRIBUTION, RESOLVED_BY_OTHERS, TARGET_DESPAWNED, TARGET_ABSENT, PLAYER_CANCELLED, RETREATED, FAILED_ACCESS, TIMEOUT and FAILED_INFRASTRUCTURE. Multipart success requires the final required phase and valid descendant ownership. Administrative removal differs from legitimate death and grants no kill/reward. Emit one concise result; release task/route/observation state and restore prior activity once. The player's party state follows the chosen one-shot/continuing-party mode, not an arbitrary blanket disband.

Item looting uses real eligibility and finite inventories. Bots should not instantly vac every boss drop or reserve all rare loot forever. Define party loot preference before the fight; honor owner/party timers and user choice. Consumable replenishment is an actual shop/trade with funds or a declined next hunt. No hidden endless rare-drop farm when the human logs out.

## Implementation and acceptance

Implementation order: registry/intent fixtures -> common task objective and ordinary field route -> verified builds/combat/support -> specific encounter identity and completion -> higher-risk field catalog -> transport/instance/expedition adapters. Sol owns gameplay integration after current GM/shared companion gates. Reuse event encounter definitions rather than duplicate different Zakum/Horntail logic.

| Gate | Required evidence |
| --- | --- |
| B01 Names | Whole-name/alias/negation/named-recipient parsing; ambiguous Balrog asks once; unsupported variant never silently routes to Jr. or boat. |
| B02 Registry | Every enabled entry's exact template/map/phase/access/source validated; duplicate names and Dojo/quest variants cannot cross-match. |
| B03 Recruiting | At most five total replies, six-seat atomic party capacity, useful real builds, busy/owned bots excluded, scarce suitable population honestly reflected. |
| B04 Travel | Real routes through ordinary maps, correct channel/instance, no-route refusal, lawful transport/entry, no free boss spawning or gate bypass. |
| B05 Combat | Tested hit/avoid/defense/elemental/skill/resource fixtures, real boss HP and bot deaths, finite potions, useful Heal/HS, too-hard reports from actual evidence. |
| B06 Controller | Zero human, hidden GM and human control arrival/departure cases produce one consistent boss controller/effect stream. |
| B07 Identity | Multiple same-template bosses/channels, phase revives, target death by another party and administrative despawn lead to the right single outcome. |
| B08 Economy | EXP/HS applied once, actual contribution, correct quest/drop ownership, no idle-bot multiplication and no out-of-session farming. |
| B09 Lifecycle | Cancellation, leader change, disband, death, logout/relog, route failure and restart clean up once without deleting natural bosses. |
| B10 Performance | Measure ordinary five-companion party and several concurrent hunts with map-local indexes; no all-world scan every movement tick; real client effect load included. |
| B11 Catalog completion | Initial ordinary bosses pass individually; boat Crimson Balrog and instanced/expedition bosses stay visible queued items until their separate adapters pass. |

Source audit paths: current CompanionSystem parser and handoff; `scripts/event/AreaBoss*.js`; `wz/String.wz/Mob.img.xml`; inspected `wz/Map.wz/Map` life references; `constants/id/MobId.java`; companion and GM source audits. Full boss WZ skill/entry/controller verification remains an implementation gate. No live boss kill or gameplay test is claimed here.
