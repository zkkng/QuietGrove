# Quiet Grove world statistics and World Ledger specification

Status: **Specified; 0% / Planned**. Feature ID: **SITE-06**. Specification version: **1.0**, 2026-09-30. This is a requirements and implementation contract; no statistics producer, database migration, API, gameplay change, or live deployment is delivered by this document.

## 1. Product outcome and decisions

Quiet Grove's website must show what has actually happened across the whole game world. A visitor opens the existing World Ledger, sees totals, selects a category, and drills down to an individual item, monster, quest, activity, location, or cause. Every number has a definition, unit, tracking start, and freshness/coverage state.

The owner's "potions" requirement explicitly means **any item in the USE inventory consumed through gameplay**, including ammunition, upgrade scrolls, books, summon bags, coupons, boxes, and food where their actual inventory classification is USE. The main card is named **USE items consumed**; healing potions are a filter within it. The broad catalog and performance requirements below are approved planning scope.

Decisions for version 1:

- Scope is public world aggregates across all channels. Default world selector is all configured worlds; each world remains selectable. Real human and artificial-player activity both contribute by default. Population filters make their contributions visible.
- Public activity has no character/account attribution or personal leaderboard in this release. A future personal-stat feature can use the contracts, but must budget its own much larger storage requirements.
- Ordinary gameplay and owner-supported trainer-assisted gameplay count when an actual effect/resource mutation occurs. Tag assistance where known. Do not infer it from damage or expose private trainer state. Administrative grants, forced cleanup, fixtures, and simulated bot flavor are excluded.
- Preserve lifetime totals and daily history from each metric's activation. Display Last 24 hours, Today, 7 days, 30 days, custom dates, and Since tracking began. A missing feed, unimplemented source, or history before activation is never displayed as zero.
- Use the website's existing /ledger route and visual language. Statistics are read-only and available without sign-in.
- No database, disk, network, JSON, catalog lookup, or website work on gameplay execution paths. Runtime overhead and failure behavior are release gates.
- Collection is asynchronous with an explicitly disclosed bounded-memory loss window. Exactly-once ingestion of durable batches does not mean zero loss across arbitrary crashes. Gameplay availability takes precedence if collection is saturated.
- Existing medals, quest progression, rewards, and rankings remain authoritative in their own systems. Statistics observe their outcomes and never grant rewards.

This explicit owner request adds SITE-06 despite the earlier closeout policy against adding unsolicited expansion ideas. It does not assign implementation to the three existing closeout owners or raise their percentages.

## 2. Website journey

The World Ledger has a compact overview with primary cards: USE items consumed, monsters defeated, quests completed, deaths, PQ runs cleared, JQ finishes, boss encounters cleared, mesos created, mesos removed, items looted, levels gained, and time adventuring. Secondary category navigation exposes all delivered metric families.

Example: USE items consumed -> Recovery -> HP potions -> Red Potion -> consumption over time, actual quantity, share of the selected total, population split, consumption purpose/method, and region distribution. The complete item list is available from the top category, so category navigation never hides a valid USE item.

Equivalent examples:

- Monsters -> region or boss/ordinary filter -> species -> kills over time and location distribution.
- Quests -> continent -> region -> quest -> completions, first-time completions, repeat completions, and abandonments.
- PQs -> named PQ/variant -> started, cleared, failed, abandoned, unresolved, participant completions, and duration distribution.
- JQs -> named course/variant -> attempts, finishes, retries, checkpoint progress, duration, and failure/reset counts.
- Deaths -> cause category -> monster/skill/hazard -> regions and activities.
- Economy -> creation/removal/transfer -> source or sink -> daily quantities. Transfer volume is never added to currency creation.

Each explorer needs name/ID search, sorting, category breadcrumbs, pagination, selected-period totals, a time chart, a definitions tooltip, and a visible coverage notice. Changing a filter resets pagination. Links preserve selected metric, entity, period, world, population, and supported breakdowns. Browser back restores the previous view.

Top lists show 25 rows by default, maximum 100; do not silently truncate the catalog at 100. Offer alphabetical browsing including zero-observation catalog entries with "0 since tracking began" only when source coverage is complete. Retired or renamed entities remain discoverable under their stable IDs. Unknown IDs show a readable fallback such as "Unknown USE item #<id>" and still count.

Use existing local game assets for icons after an offline export. Missing art receives a generic icon; it must not trigger game-server WZ reads. Render names as text, use keyboard-operable filters, readable empty/error states, responsive tables, and reduced-motion charts. Do not animate invented intermediate totals.

Keep online population and saved-character rankings visually separate from accumulated gameplay counts. Existing database login-state readings keep their current caveat. A website decorative monster click cannot increment game-world kills.

## 3. Common measurement model

Every metric registry entry declares: stable metric ID, definition/version, unit, entity kind, allowed filters and projections, reducer, inclusion/exclusion policy, source adapters, activation timestamp, retention tier, coverage, and acceptance test IDs.

Three distinctions are mandatory:

1. **World occurrence:** one mob death, one party-instance clear, one transfer.
2. **Participation:** several characters may receive credit for that occurrence.
3. **Units:** one action may spend 20 ammunition charges, consume a scroll plus a White Scroll, or award multiple items.

Do not sum those measures together. Values are nonnegative committed observations except explicit signed adjustment records. Daily counts use occurrence time in UTC. Durations use a monotonic clock and are stored as integer milliseconds. UI displays dates/times in the chosen timezone, but v1 bucket boundaries are visibly UTC.

Population is an exclusive attribution dimension: human, bot, mixed, system, unknown. Actor-owned actions use human/bot. Shared encounters use human-only, bot-only, or mixed qualifying participants. System represents real autonomous world outcomes; unknown represents missing attribution. UI labels change appropriately for shared encounters. "Human activity" includes human-owned actions and human-only shared outcomes; mixed outcomes have their own selectable lane and are included in All. This preserves additivity. Never duplicate one mixed boss kill into two population lanes.

GM/staff actor actions and explicit test/admin operations go into private diagnostic lanes, outside public totals. A legitimately played hosted event can count normal participants even when a GM started it. Its spawned mobs carry event provenance; "GM spawned" alone is not a reason to discard a real hosted fight.

Attribution is captured when the outcome occurs, not reconstructed from the character's current job, map, party, or bot ownership later. Optional level-band/job breakdowns describe the actor at the time. Administrative or unknown classification must be explicit; character-name heuristics are insufficient.

## 4. USE inventory: full consumption contract

Canonical headline metric: `use.units_consumed`. Count actual positive inventory quantity/charges irreversibly spent by an accepted gameplay action. Count all USE IDs; subtype support is not an allowlist restricting the total.

Primary categories are disjoint so their sum equals the headline: recovery HP, recovery MP, recovery hybrid, cure, temporary buff/food, return/travel, equipment upgrade, skill/mastery book, summon/capture, container/reward/coupon, ammunition arrows, ammunition stars, ammunition bullets, other ammunition, other USE, and unclassified USE. Assign by versioned item metadata plus curated overrides. Dual-purpose HP/MP items use hybrid. Secondary tags may overlap but must not be summed as primary categories.

Record three separate measures: consumed units per item; item-use lines per item; accepted actions in the overall family. One scroll attempt with a White Scroll is two units/two item-use lines/one action. One accepted attack spending five bullets is five units/one item-use line/one action. Item detail labels this distinction.

Consumption purpose is activation, attack ammunition, skill reagent, upgrade, crafting ingredient, quest surrender, event entry/payment, or other gameplay spend. All count when the inventory is USE. Buying, selling, transferring, dropping, storing, expiration, administrative deletion, and damage-induced loss are separate inventory-flow metrics, not consumption. A quest that requires possession but removes nothing consumes nothing.

| Path/example | Required result |
| --- | --- |
| Manual potion, food, buff or cure | Increment actual units removed even if restoration is zero at full HP/MP or a valid cure removes no status; rejected input contributes zero. |
| Pet auto potion | Increment actual units across every stack used, not one per packet; preserve the item ID of each stack. |
| Bot and trainer auto potion | Same rule, including direct Inventory.removeItem paths. A simulated sip or free heal consumes zero items. |
| Party-wide consumable | Count what the owner spends once; record recipients/effects separately. |
| Return scroll | Count when its actual spend commits; a blocked use with no removal counts zero. |
| Upgrade and White Scroll | Count each spent USE item irrespective of upgrade success/failure/curse. Record the upgrade result separately; burnt equipment is not a consumed USE item. |
| Mastery/skill book | Count a removed book even on a legitimate failed roll. Rejected eligibility or unavailable inventory counts zero. |
| Summon/capture item, coupon, bag, box | Count once upon accepted spend; opening rewards, summoned mobs, captures, and coupon awards are separate outcomes. |
| Arrows/stars/bullets | Count actual charges removed, not projectiles shown or hit targets. Soul Arrow, Shadow Stars or a no-cost effect spending zero charges adds zero consumed units. |
| Recharge | Record charges replenished and meso cost separately. Recharge is not negative consumption, and a rechargeable stack is not newly minted each time. |
| Scripted USE ingredient/payment | Count positive committed units removed for the documented purpose, once even if generic inventory code also sees the mutation. |
| Instant pickup effect without USE inventory spend | Track world consumable pickup/effect separately. It does not enter the USE-inventory headline. |
| Refund or compensation | Record the original spend and a separate refund/grant. Do not erase genuine past use; an in-action rollback before commit emits no spend. |

Actual HP/MP restoration is the clamped state increase caused by this effect. Track potential restoration after applicable modifiers and wasted restoration = max(0, potential - actual) when both are known. Multi-recipient effects sum recipient effects but never multiply the spent units. If an effect fails after an irreversible removal, retain the spend with outcome=effect_error and mark its effect values unknown. Never make the statistics refactor silently alter existing consumption behavior.

There must be one typed consumption context/receipt shared by manual, pet, bot, skill, script, and trainer paths. Capture real mutation results inside the existing authoritative critical section, publish through the bounded recorder without waiting, and retain no live Character/Item/Map references. A generic inventory removal is not enough to infer purpose; legacy adapters without an established reason must report diagnostic unmapped-removal coverage, not fabricate a consumption count.

## 5. Metric catalog and required entity drilldowns

All rows are planned scope. Delivery phases below sequence them; a partial phase does not complete SITE-06. "By" columns define mandatory meaningful entity breakdowns; not every Cartesian combination is supported.

| ID / metric family | Measures and semantics | Required drilldown |
| --- | --- | --- |
| U01 use consumption | units, item-use lines, actions; Section 4 | item ID/category, purpose, method, region, population |
| U02 recovery | actual/potential/wasted HP and MP, effect errors/unknowns | item ID or healing skill, target type |
| U03 ammunition recharge | charges restored, transactions, mesos paid | ammo ID, shop; no item minting |
| K01 monster defeats | one legitimate death per runtime spawn | mob ID, map/region, ordinary/boss/part/summon, spawn provenance |
| K02 kill participation | credited character participations; distinct from K01 | mob ID, population, solo/party |
| K03 boss encounters | attempts, completed encounters, failures, abandonments, duration | encounter definition/variant, completion cohort, region |
| K04 encounter mechanics | boss parts/phases defeated and summons killed | parent encounter, phase/part template; do not multiply clears |
| Q01 quests | starts, completion events, forfeits, expirations | quest ID, questline, assigned region/continent, category |
| Q02 quest firsts | first completion per character/quest/epoch and repeats | quest ID; distinct quest IDs completed world-wide is a separate measure |
| D01 deaths | alive-to-dead transitions; deaths since last revival | cause category, mob/skill/hazard ID, map/region, activity |
| D02 death cost/recovery | actual EXP lost, charms spent, resurrections, revive method | activity, charm/skill/item ID, location |
| P01 PQ runs | runs started, cleared, failed, abandoned, unresolved | PQ definition and variant, party-size band, cohort |
| P02 PQ participation | character entries, completions, exits, stage participations | PQ/stage, population |
| P03 PQ timings/rewards | successful run duration, stage duration, reward units | PQ/variant/stage, reward item |
| J01 JQ runs | attempts, finishes, abandonments, explicit resets, unresolved | course and variant, cohort/actor |
| J02 JQ progress/timing | checkpoint passages, verified falls/resets, finish duration | course/stage/checkpoint; no inferred fall from arbitrary movement |
| E01 EXP | actual positive EXP awarded by source, actual deductions by reason | monster/PQ/quest/event/other source, job/level band |
| E02 advancement | levels gained and job advancements | destination level/job, population |
| E03 skill progression | successful skill unlocks/level increments, SP/AP allocated | skill/job and stat category; admin changes excluded |
| I01 item generation | units created through actual drops/rewards/crafting | item ID, source family and source entity |
| I02 item pickup | units and pickup actions, actual recipient | item ID, natural drop/re-drop/reward/event origin, human/pet/bot/trainer method |
| I03 item disappearance | expired ground-drop units, item expiration, destroyed equipment | item ID, reason; not every removal is consumption |
| I04 storage/transfers | committed item units transferred, stored, withdrawn | item ID, trade/shop/storage/delivery channel |
| M01 mesos sources | actual minted mesos, by authoritative origin | mob drop creation, quest, NPC buyback, event, other grant |
| M02 mesos sinks | actual destroyed mesos, by reason | NPC purchase, tax, travel, recharge, crafting, entry fee, other sink |
| M03 mesos transfer | committed transfer count/volume; sum once per transaction | trade, player shop, delivery, other transfer type |
| M04 mesos pickup | world currency moved into inventory | natural/re-dropped origin; not new minting at pickup |
| M05 sampled balances | periodic aggregate wallet/storage/ground totals if complete scan available | world/population; timestamped snapshot, never mixed with flow counters |
| C01 crafting | attempts, success/failure, output and ingredient units, fees | recipe ID/system, output item |
| C02 upgrades | accepted attempts, success/fail/curse, slots used/restored | scroll ID, target equipment type, protection used |
| C03 book/capture rolls | attempts, successful/failed accepted rolls | book or capture item/target |
| L01 collections | actual monster cards gained, new entries, set completions | card/mob/set ID; repeated cards separate from first discoveries |
| L02 medals/achievements | medals earned and verified achievements | definition ID; duplicates/reissues separate |
| L03 random rewards | real gachapon/container/coupon reward outcomes | machine/container/coupon and item ID; show observed sample size, not asserted odds |
| X01 travel | successful map entries/region crossings, completed transport trips, return-scroll travel | destination map/region, transport route/method |
| X02 exploration | first map visits per character/epoch, world catalog coverage | map/region; a login entry is tagged separately |
| T01 presence | connected character time, active time, idle time, party time | world/region/job band/population; character-hours, not unique-account-hours |
| T02 concurrency | sampled concurrent connected and active characters | world/population; min/max/average over covered sample seconds |
| S01 party/social | parties formed, successful joins, party activity completions | party-size band/activity, population |
| S02 fame/guild/family | committed fame changes, guild creation/joins, Family benefit uses | action/benefit type; no private social graph |
| S03 minigames | matches started/completed, wins/losses/draws | Omok, Match Cards, other supported definition; match once, player outcomes separately |
| G01 GM games/invasions | runs, entrants, results, objectives, rewards, casualties | game/incident definition, objective, cohort |
| G02 Carnival | matches, team wins/draws, CP earned/spent/lost, guardian/summon/debuff purchases | Carnival variant/action, mob/guardian/skill ID |
| G03 Dojo/survival/cafe | attempts, stages/waves, clears, score and reward outcomes | content definition/stage; map to activities without calling every mode a PQ |
| B01 combat totals | actual HP damage dealt/taken, absorbed damage where known | skill or source kind, mob/target category, population |
| B02 combat actions | accepted skill/basic attacks, actual hits/misses/crits, summons | skill ID; unavailable human-client-derived details stay unavailable |
| B03 support/status | actual healing, resurrections, cures, applied statuses/buffs | skill/item/status ID; application and recipient counts distinct |
| F01 small world moments | chair occupancy time, mount time, pet feed/level gains, fishing catches, hair/face changes | chair/mount/pet-food/catch/style ID where canonical action exists |
| F02 milestones | first tracked species defeat, first tracked clear, aggregate millionth threshold, fastest eligible clear | metric/entity and coverage epoch; no invented historical first |
| W01 future website economy | committed NX grants/spends and reward deliveries only after feature exists | wallet/purpose/receipt product; no NX policy assumed by this spec |

Unmeasurable metrics remain registered but marked unavailable with a named adapter gate. Raw jumps, footsteps, exact distance traveled, per-packet positions, chat contents, and every animation frame are excluded from v1: their collection cost and interpretation do not justify world stats. If added later, use explicit bounded counters and label estimates.

## 6. Domain rules and edge cases

### 6.1 Monsters and encounters

Identify a spawn using producer boot ID, world, channel, map-instance generation, and spawn generation/object identity. A reused object ID must not collide after death or restart. Count its transition to legitimate death once. Despawn, timeout cleanup, administrative removal, fake boss markers, scenery, and scripted noncombat removals are excluded or separately classified.

The kill event owns its world count; EXP recipients and quest-credit recipients own participation counts only. Final blow, highest damage, and credited participants are different fields. Damage-over-time, pet/summon damage, reflected damage, bots, and trainer extra targets require explicit source attribution. Unknown participation does not erase a proved death.

A Zakum arm or Horntail part can contribute to monster/part deaths but never independently finish a boss encounter. Encounter victory follows the authoritative final-phase/completion definition. Bind descendants to one encounter. Track attempted encounters only after canonical admission/activation plus an accepted engagement, not merely a boss spawn. Closed terminal state is exactly one of cleared, failed, abandoned, administratively cancelled, or unresolved; open attempts stay in progress.

### 6.2 Quests and geography

Record each genuine completion episode, not every COMPLETED status write. Give an episode identity at accepted start; one-step/script quests receive an explicit completion-operation identity. Repeated callback delivery for the same episode counts once; a legally restarted repeatable quest gets a new episode.

The method name forceComplete is insufficient to classify a completion as administrative because ordinary scripts also use it. Pass context from the actual caller. GM commands and state restoration are excluded; ordinary quest scripts count. Info/storage quests and fake progress markers are cataloged separately from playable quests.

Continent -> region -> town/map uses a curated versioned lookup built offline from local WZ and content definitions. Do not infer continents solely from map-ID arithmetic. A quest has one primary assigned region (including Global, Multi-region, Event, or Unknown), plus optional secondary tags. Primary buckets partition totals; overlapping tags do not. Also retain completion-location region as a separately named projection. Same-day repeats, medal quests, tutorials, and job quests are separately filterable.

First completion requires durable uniqueness per character/quest/epoch. Imported already-complete quests establish a historical baseline if selected; they do not emit a new completion. "Unique quests completed" means distinct quest templates observed; "first-time character completions" means distinct character/quest pairs.

### 6.3 Deaths

Emit on the actual alive-to-dead edge before mode-specific early returns, including Carnival and Dojo. Revival increments the life generation; repeated HP=0 updates cannot increment death again. A forced HP reset or test action is tagged administrative.

Maintain bounded recent cause state only where existing damage resolution can supply it. Primary cause is the known lethal event: monster contact, monster skill, damage over time, reflected damage, map hazard, script mechanic, PvP if supported, or unknown. An old attacker is not automatically the death cause. Store direct and originating source where reliably known; otherwise keep unknown. EXP loss and charm use are actual applied amounts and can legitimately be zero.

### 6.4 PQs, JQs and timed activities

Create an activity definition registry covering every installed PQ/JQ/event/Dojo/survival/Carnival implementation. Each adapter declares admission, start, stage completion, terminal finish, failure, abort, retry, reward commit, and clock rules. Catalog all adapters as supported/partial/unavailable with reason. No script-name wildcard automatically establishes a clear.

A four-player PQ clear contributes one run cleared and four eligible participant clears. Joining/rejoining one run does not create a new run or duplicate unique entry. Reward claim is separate from completion: inventory-full rewards or delayed claims cannot change clear counts. Use authoritative eligibility for disconnected/left participants; absent an existing policy, qualify only registered participants at finish and record that definition.

Run start is the actual activity start/gate activation; member join time is not the world run clock. Monotonic timing excludes wall-clock jumps. Record successful, failed, and abandoned duration distributions separately. Closed-run clear rate = cleared / (cleared + failed + abandoned); show ongoing, admin-cancelled, and unresolved separately. Entrant-day cohort reports must not mix attempts started yesterday with clears completed today without labeling them.

JQ adapters cover Shumi, Sabitrama, John, Zakum jump course, relevant Puppeteer courses, Ola/Fitness and any installed custom courses after verification. Completion is the canonical final checkpoint/exit/award entitlement once per attempt, not an arbitrary warp into a finish map. Teleport/GM bypass can contribute only to an explicitly assisted/admin category. Record best ordinary and known-assisted times separately; do not alter trainer gameplay.

Each event type declares its primary reporting family; shared totals cannot count Ola as both one JQ and one GM event and then sum them as two unique activities. Family pages may display related views with overlap explained. Persist rare run lifecycle facts asynchronously to recover an unresolved run after restart without guessing a failure.

### 6.5 Economy, items and distinct counts

Currency creation/removal/transfer is attributed at the authoritative economic operation. If 1,000 mesos appear as a natural drop, mint 1,000 at drop creation; pickup transfers the same value into a wallet, not another mint. If the drop expires, remove it from the world economy under the expiry sink. Re-dropping existing mesos is a transfer. Player-shop principal is transfer; tax is a sink. NPC buyback mints paid mesos and removes sold items, separate from consuming them.

This is gameplay analytics, not a replacement financial audit ledger. Game rollback can invalidate an observed spend; record a verified compensating adjustment or mark the affected interval uncertain. Never change balances to reconcile statistics. Game wallet/storage caches make database-only balance scans incomplete; label their source or omit M05 until an authoritative bounded snapshot exists.

Exact distinct counts are worker-side durable membership insertions with unique keys, never unbounded game-thread sets. The membership insert and its first-time aggregate increment commit in the same receipt transaction; only a newly inserted member increments the aggregate. A duplicate member may still produce a genuine repeat-completion event in its separate episode. They are not additive across days/populations/worlds. Offer lifetime and precomputed supported-window distincts only; do not sum daily unique visitors into a weekly unique count. Arbitrary distinct queries are unsupported until a dedicated precomputation exists. Approximate algorithms, if later adopted, require explicit labeling.

## 7. Time, history and coverage

Create a collection epoch for each deployment environment/world reset. Reboots continue the same epoch; a deliberate reset creates another. Deleted characters do not cascade-delete world history. No automatic midnight reset of lifetime totals.

Historical import is optional and separate from live collection. Inventory contents cannot reconstruct consumed items, and existing quest/PQ counters cannot establish historical dates, populations, causes, or world run counts. In particular, summing per-character PQ wins produces participant completions, not unique PQ clears. Import only proved measures under an import ID, source checksum, as-of date and baseline=true provenance. Re-running an import ID is idempotent; baseline numbers appear in a separately labeled historical section and never get placed into today's activity bucket. Stage/reconcile the import before publication, preserve its source snapshot, and support reversal through a verified adjustment. Empty new live counters show "Tracking begins <date>", never "Since server launch."
Store UTC occurrence timestamps and producer monotonic durations. The durable pipeline uses an event-time watermark; late arrivals update their original bucket and invalidate caches. Batches may contain several day/hour buckets and must not force everything into flush time. Rate/definition/catalog changes have effective times and versions.

Retention defaults:

| Data | Retention |
| --- | --- |
| Lifetime aggregate, daily aggregate, metric definitions/catalog versions, epoch and coverage/adjustment records | Indefinite; annual size review and backed-up compaction |
| Hourly aggregates for approved core projections | 400 days; supports Last 24h and daily trends; sub-hour portion explicitly provisional |
| Five-minute aggregates for core totals | 14 days; bounded freshness charts only |
| Ordinary individual hits, movement packets, potion-use event history | No permanent raw-event table; aggregate in the worker |
| Durable unacknowledged spool | At most 2 GiB and 7 days, whichever limit is reached first; never silently overwrite |
| Acknowledged spool for short recovery/debugging | Up to 24 hours within a separate 256 MiB cap |
| Run lifecycle/milestone/adjustment facts | 90 days detailed run facts; keep derived aggregates and record summaries indefinitely |
| Exact first-completion/exploration membership | Epoch lifetime, only declared distinct families; separate storage budget and indexes |

Today and date ranges use UTC boundaries. Last 24 hours uses retained fine/hour buckets plus the latest partial bucket, with resolution disclosed. A complete five-minute boundary result can be offered when an exact sliding second is unsupported. Custom date ranges before the first available bucket are rejected or clipped with explicit coverage metadata, never silently filled with zeros.

Per metric/dimension coverage state is not_connected, warming_up, complete, partial, stale, paused, or unavailable. "Complete" means complete for its disclosed instrumented source set and covered interval, not before activation. Partial includes known drops, missing adapters, crash uncertainty, or rollback uncertainty. Feed age and coverage are separate: a stale complete historical interval stays complete; today's interval may be incomplete.

Every public response includes trackingStartedAt, dataThrough, generatedAt, sourceCoverage, gaps, definitionVersion, and catalogVersion. Zero requires an implemented metric with covered time. Lifetime totals become "Recorded total" with a visible gap notice if any included interval is incomplete.

## 8. Capture architecture and resource bounds

Proposed modules: server.statistics (registry, typed facts, recorder, accumulator, journal, exporter, health), offline catalog builder, additive stats schema, and website stats reader/cache. Reuse existing environment configuration and migration conventions.

Pipeline:

1. Existing domain action resolves and mutates authoritative state.
2. A small typed fact containing primitive IDs/amounts is offered to a bounded process-local recorder. No database access or waiting for capacity.
3. A dedicated aggregation worker drains facts, validates versions/IDs, updates sparse approved projections, and freezes delta batches.
4. A persistence worker appends immutable delta/rare-fact batches to a local journal, durably syncs, and applies them to statistics tables in bounded transactions.
5. An isolated publication worker reads aggregates with a read-only database principal and creates the immutable snapshot shards defined in Section 11. Website workers read those published snapshots, cache results, and serve clients independently.

Normal gameplay hooks perform one bounded publication per domain outcome (a bounded compound publication for actions with multiple item lines). Bulk item actions use declared maximum line counts; partition only off the gameplay thread while preserving the action count once. No one-event-per-HP-point/projectile/affected-party-member expansion when a fact can carry a bounded count. Explicit participant facts are permitted for small bounded parties.

Preallocate a primitive-record ring with 65,536 slots; target slot footprint at most 192 bytes. Variable lists and string serialization are forbidden in ring slots. A bulk descriptor has a bounded pooled payload; exhausted payload capacity is handled like ring saturation. No arbitrary retry/spin loop on offer, no allocation of a fresh object per hit. Verify actual Java heap/allocation behavior rather than assuming preallocation proves zero allocation.

A process has one capture/aggregation worker and one persistence worker initially. No new timer/task per event. Hook overhead is constant bounded work; catalogs are prepared outside gameplay threads. Worker aggregation may create bounded sparse rows; it cannot hold game/inventory/map locks or query live entity graphs. Export workers never run through gameplay timer pools.

Initial hard limits and operating defaults (tunable after benchmark):

| Control | Default / contract |
| --- | --- |
| Total statistics memory, including ring, pools, accumulators and pending batches | 256 MiB per game process hard budget |
| Ring capacity | 65,536 fixed slots; occupancy gauges at 50/75/90% |
| Accumulator active keys | 100,000 maximum across projections per process; upper bound still subject to measured 256 MiB budget |
| Delta freeze cadence | 1 second or size threshold; no unbounded queue of frozen deltas |
| Journal durable-sync cadence | 1 second target, with measured durable lag and explicit failure state |
| DB batch size | At most 1,000 aggregate rows or 1 MiB encoded payload per transaction, whichever first |
| Statistics DB connections | Dedicated pool of 2 maximum; no borrowing gameplay connections |
| Database apply cadence | Up to every 5 seconds while healthy; exponential bounded retry on failure |
| Snapshot/API cache freshness | Publish every 15 seconds; typical displayed data lag target <=30 seconds |
| Cache TTL / stale grace | 15 seconds fresh, up to 24 hours historical fallback with stale label |
| Shutdown drain | Up to 5 seconds on a dedicated shutdown step; report any unpersisted interval |
| Overload shedding | Optional combat/detail facts first, then core only when unavoidable; no gameplay backpressure |

A deadline cannot guarantee durability if the OS/disk/worker stalls. Expose current ring age, frozen-batch age, journal synced-through time and published-through time. The effective crash uncertainty window is measured from the last durable watermark, not asserted to always be one second.

## 9. Projection and cardinality contract

Never materialize every combination of item x map x skill x job x player x time. The registry declares finite projection keys, their capacity and supported filters. Unknown template IDs can use an explicit unknown-entity bucket until registered; bound unknown-ID diagnostic samples. Reject arbitrary strings and high-cardinality session/character IDs from aggregate keys.

Core projection templates:

| Projection | Key dimensions | Intended views |
| --- | --- | --- |
| P0 totals | metric, world, population, assistance mode, bucket | overview, trend, population filter |
| P1 entity | P0 + entity ID | all individual item/mob/quest/activity lists and detail totals |
| P2 geography | P0 + primary region ID | continent/region overview |
| P3 entity geography | P1 + primary region ID | item/species/quest details by region; core families only |
| P4 purpose/outcome | P1 + purpose OR outcome enum | consumption reason, activity/upgrade result; separate projections |
| P5 source | P0 + bounded source family/entity | economy/EXP source tables |
| P6 location | P0 + map ID, and optionally a registered mob-map pair | deaths, visits, mob locations; approved families only |
| P7 class band | P0 + job family OR level band | progression/support totals; no personal identifier |

Category/continent rollups are joins/sums over a catalog partition, not extra game-thread increments. Do not sum projections together. A query combining dimensions not represented in a stored projection receives unsupported_filters with supported alternatives; never approximate it using unrelated marginals.

Hot facts support at most eight worker projection updates in v1. Extra registered projections require storage/CPU measurements. High-volume B01/B02 default to totals and entity only; mob x skill x map drilldown is outside v1. P3 retains daily data and core hourly data only within the storage budget. Overflow downgrades optional projections with per-projection coverage gaps while preserving core totals/P1 where possible. If those also cannot fit, shed with an explicit gap, never silently merge known item IDs into "Other."

Pre-launch capacity report computes observed distinct keys per day x serialized row/index bytes x retention for every projection, including replica/backups. Initial analytics database budget is 10 GiB with alert at 70% and operator action at 85%; this is a capacity target, not grounds to delete promised lifetime/daily totals. Tune optional projection retention or storage before expansion. Show projected 30/365-day growth.

## 10. Durability, deduplication and failure semantics

Domain adapters prevent duplicate facts first: consumption operation ID, spawn/life generation, quest episode, activity run ID and transfer receipt. State transitions occur under their existing authority/locks. A replayed callback must refer to the same operation; generating a fresh UUID on every callback is not deduplication. No global, ever-growing in-memory set of all kills is allowed.

Each producer has a persistent producer ID, a new boot ID, and a monotonic batch sequence. Journal records contain schema version, epoch, batch identity, occurrence coverage interval, delta rows/rare facts, payload length, and checksum. Aggregate amounts use checked arithmetic; reject malformed overflow with coverage error rather than wrapping.

The database ingestion transaction first inserts a unique receipt for (producer, boot, sequence) and checksum, then applies all rows and publishes its watermark atomically. Duplicate receipt plus identical checksum is a no-op; checksum mismatch is quarantined. The receipt and all deltas commit together or neither does. A large freeze is split into individually identified bounded batches before journaling, not partially applied under one receipt. Late retry after an uncertain commit cannot add the batch twice.

After commit acknowledgement, advance a durable producer checkpoint. Receipt pruning is allowed only behind a durable contiguous acknowledged sequence and a retained anti-replay lower bound for that boot. Replaying older discarded batches is rejected, not treated as new data. Restores must restore compatible aggregates, receipts/checkpoints, and epoch metadata together. Never reuse producer/boot IDs after copying an installation.

Crash before a fact/batch is durably journaled can lose observations. Crash after journal sync but before DB commit replays them. Crash after DB commit but before acknowledgement also replays safely through the receipt. On unclean startup, mark the interval from the last proved durable coverage checkpoint to restart as potentially incomplete; do not invent a number of lost actions.

Gameplay saves and analytics durability are independent. A gameplay rollback might leave a journaled observation describing an action that was later undone. Administrative recovery must either emit proven compensating adjustments or label that interval uncertain. World counters are records of observed runtime activity, not evidence that all corresponding inventory changes survived the same crash.

| Failure | Mandatory behavior |
| --- | --- |
| Database unavailable | Continue bounded local journal, retry in worker; website serves last cached data with stale notice. |
| Disk slow/full, spool full/expired, aggregation exhausted | Stop accepting affected optional facts, then core if unavoidable; keep gameplay moving; record gap in a reserved health channel when possible. Never delete unacknowledged data silently. |
| Recorder saturated | Nonblocking reject; increment reserved drop counters by family and open a coverage gap. Whole compound action must be accepted or rejected coherently. |
| Worker exception | Isolate stats worker, rate-limit diagnostics, restart safely if possible; gap/watermark must expose stalled collection. |
| Website traffic surge/outage | Cache/rate-limit on the website. No calls to gameplay execution threads, no feedback that slows actions. |
| Definition mismatch/corrupt batch | Quarantine and alert; do not apply guessed interpretation. |
| Graceful shutdown | Freeze/drain/sync within 5-second budget, record checkpoint; timeout is a gap, not permission to delay shutdown indefinitely. |

Use reserved bounded health counters outside the main event ring, persisted by the worker. If even health persistence fails, the last durable heartbeat plus unclean boot flag provides a conservative gap. Drop estimates, durable/ingested counts, and UI freshness cannot be conflated.

## 11. Persistence model

Use additive statistics tables in a separate schema where practical. Verify the deployed MySQL-compatible engine/version and existing migration loader during implementation. Do not add these tables to the per-character ContentState blob. Do not cascade world totals from accounts/characters.

Logical schema (implementation DDL must specify all types/index lengths against the real engine):

| Table | Key and contents |
| --- | --- |
| stats_epoch | epoch ID, environment/world scope, start/end, reason, release/config provenance |
| stats_metric | metric ID/version, unit, reducer, allowed projections, activation, source coverage |
| stats_entity | kind + stable ID + catalog version, name/icon/category/region/parent/retired flag |
| stats_batch_receipt | producer/boot/sequence unique, checksum, committed timestamp |
| stats_producer_checkpoint | producer/boot contiguous acknowledged floor, durable and published watermarks |
| stats_aggregate_lifetime | epoch/metric/version/projection/world/population/assistance/dimension IDs unique, additive amounts and reducer state |
| stats_aggregate_daily | same key + UTC day, indexed by metric/period and entity |
| stats_aggregate_hourly | same key + UTC hour, restricted projection set |
| stats_aggregate_5m | same key + UTC bucket, P0 core totals only |
| stats_run | run ID/definition/version, start/end/status, cohort, duration, qualification flags; private diagnostic detail |
| stats_distinct_member | epoch/metric/entity/member token unique, first observed time; private exact firsts only |
| stats_coverage | epoch/metric/projection/source, start/end, state/reason, known dropped facts or null |
| stats_adjustment | idempotent correction ID, operator reason, affected metric/bucket/delta, provenance |
| stats_milestone | metric/entity/threshold/version unique, reached interval, coverage eligibility, best-run summary |
| stats_publication | published revision/watermarks/catalog version, immutable shard-manifest checksum, state and completion flag; publisher-owned metadata |

Use BIGINT for ordinary integer counts with checked addition and a nonnegative contract; DECIMAL(38,0) for cumulative damage/EXP/currency where needed. Encode all public integer values as decimal strings to avoid JavaScript precision loss. Decimal rates/percentages are derived, rounded, and carry a denominator.

Reducers are sum, minimum/maximum with provenance, fixed-bin histogram, current gauge, or exact distinct membership. Successful run duration stores count/sum/min/max and histogram; averages combine sums/counts, never average averages. Percentiles are explicitly approximate within their histogram bucket. Suggested logarithmic duration bins cover 1 second through 24 hours with under/overflow buckets; registry freezes exact boundaries. Ratios with denominator zero return null.

Public snapshot publication reads a consistent database snapshot and only committed revisions. Overview/category/entity lists for the same snapshotId must agree; a partial multi-world refresh remains unpublished. Use immutable, checksummed snapshot shards by metric/projection/world/population/period, with a small manifest that atomically switches after every changed shard is written. Unchanged historical shards are reused, never recopied on every 15-second publish. The backend queries these published shards through a bounded indexed read cache; it does not combine a cached overview with newer mutable aggregate rows. Retain prior manifests/shards for at least 10 minutes for stable pagination, then return snapshot_expired (409) and refresh the whole view when needed. Backend custom-range work sums only the approved published daily/hourly shards and obeys the query budget; bound its cache at 128 MiB separately from the game-process budget. Benchmark publication/index/shard growth as part of WS-A28. Avoid recomputing lifetime sums from all historical rows per request. Corrections to min/max/distincts require rebuilding from retained facts or marking unavailable if proof was pruned; subtraction alone is insufficient.

## 12. Website API contract

Extend the existing separate Quiet Grove backend. Game producers write statistics; the publication worker gets SELECT on approved aggregate/catalog/coverage views and narrowly scoped rights to its publication metadata, not raw private facts or gameplay writes. The website reads completed snapshot manifests/shards and needs no additional game-table permissions. Website requests never access gameplay Character objects or scan inventory tables. Use prepared publication queries and allowlisted dimensions/order fields. Public requests never wait for an on-demand publication rebuild.

Versioned base path: /api/world-stats/v1.

| GET route | Response |
| --- | --- |
| /catalog | metrics, definitions, entities/categories/regions/activity definitions, supported filters, availability |
| /overview | primary cards and common coverage for selected epoch/world/period/population |
| /metrics/{metricId} | metric total, unit, reducers and supported drilldowns |
| /metrics/{metricId}/entities | paginated entity counts, category/name/ID filtering, totals and other-row coverage |
| /metrics/{metricId}/entities/{entityId} | one entity summary, supported purpose/outcome/region projections |
| /metrics/{metricId}/series | time buckets at allowed daily/hourly/5-minute resolution |
| /activities/{definitionId} | starts/outcomes/participants/durations/rewards with their distinct denominators |
| /milestones | verified recorded milestones and aggregate records without private character identities |
| /coverage | public source availability, tracking start, freshness and gaps |

Parameters: epoch, world=all|ID, population=all|human|bot|mixed|system|unknown, assistance=all|ordinary|assisted|unknown, from inclusive/to exclusive ISO UTC, bucket, category, region, entity, sort, q, cursor, limit, snapshotId. Only advertise combinations backed by an approved projection. Search is at most 80 characters, page limit 100, date span at most 366 days for series; larger ranges need coarser explicit rollups or return a supported-range error. Since-start overview remains available without returning every daily point.

Use stable sort ties on entity ID and opaque cursor tied to query and snapshot. Cache keys include every filter, definition/catalog version, and publication revision. Clients discard late responses after a filter change.

Illustrative response, with deliberately fictional values for schema only:

~~~json
{
  "schemaVersion": 1,
  "snapshotId": "epoch-1:revision-123",
  "metric": "use.units_consumed",
  "definitionVersion": 1,
  "catalogVersion": "v83-local-1",
  "unit": "units",
  "scope": {"world": "all", "population": "all", "assistance": "all"},
  "value": "12345678901234567",
  "trackingStartedAt": "2026-10-01T00:00:00Z",
  "dataThrough": "2026-10-01T12:00:00Z",
  "generatedAt": "2026-10-01T12:00:15Z",
  "coverage": {"state": "complete", "sourceCoverage": "all_registered_use_paths", "gaps": []},
  "rows": [
    {"entityId": "2000000", "name": "Red Potion", "value": "1200", "unit": "units"}
  ],
  "nextCursor": null
}
~~~

A response must distinguish null/unavailable from "0". Fictional examples never ship as fallback data. Unsupported combinations return 400 with machine-readable supported filters; unknown metric/entity 404; retired entities remain 200 when cataloged; cold unavailable publication 503 with retry hints; a valid stale snapshot returns 200 with stale metadata. Use ETags and conditional GETs. No raw stack traces, machine paths, private IDs, or secrets in errors.

Initial web limits: 60 requests/minute per client/IP with reasonable shared-IP handling, five concurrent requests per client, 500 ms indexed-snapshot read budget, bounded service query concurrency, and single-flight cache rebuild. The background publisher uses its own pool of at most two read connections and a measured statement timeout; it backs off rather than competing for game resources. Cache misses must not multiply one expensive refresh across visitors.

The existing /api/gameplay-stats expectation is a legacy presentation contract, not an implemented API found during this audit. Update ledger.js to consume the versioned API. If a compatibility route is retained, it must return schemaVersion, map only the true recovery-potion subset to "potions", and not silently relabel all USE as potions. Remove Number(value)/Number.isSafeInteger assumptions for totals; use decimal-string/BigInt-aware formatting. Charts can use explicitly scaled approximations for geometry while displaying exact integer labels.

## 13. Runtime controls and operations

Configuration defaults in implementation: enabled=false until migrations/catalog/adapters and gates pass; core enabled as one rollout group; optional combat and world-moment families separately switchable. Fields include epoch ID, ring/memory/spool limits, flush/sync/publish periods, DB pool/timeouts, projection set, family toggles, source-catalog version, and health thresholds.

Enable/disable affects collection and opens/closes coverage intervals; it never clears history. A disabled hot hook does a cheap branch and returns. A metric cannot be marked complete while any required registered source is disabled or unmapped. Runtime reloads may tighten optional projections safely but cannot reinterpret previously persisted definitions silently.

Expose operator health: enabled families/adapters, source coverage, ring size/age, memory/key counts, offered/accepted/rejected facts, optional/core drop counts, worker CPU/allocation, flush duration, journal depth/age/bytes, disk errors, DB retries/latency, ingested watermarks, last publication, catalog mismatches and per-projection growth. Use rate-limited logs and existing private operator channels; statistics health is separate from actual game uptime.

Rollout controls are a recorder kill switch and optional-family switches. Turning stats off must keep gameplay normal and preserve the readable historical website. Document restart/replay, corrupted-tail truncation at last checksum-verified boundary, restore compatibility, backfill, and manual verified correction procedures.

## 14. Performance acceptance

These are proposed release targets, not measurements or a capacity claim:

- Hot recorder p99 <=10 microseconds per offer under representative mixed load; no synchronous I/O, blocking monitor, or unbounded retry in new hook code.
- With stats on versus off, identical warmed-up workload has <=2% relative added game-process CPU and no more than max(1 ms, 2%) increase in p95 and p99 game action/tick latency. Report both raw values and relative changes; no hidden averaging away tail latency.
- No new sustained allocation/GC pressure per combat hit; report allocation profiles and full/young GC pauses. Total stats memory stays <=256 MiB and plateaus in a 24-hour soak.
- Zero rejected core observations and zero unexplained gaps at the selected supported production load and a 2x measured-peak burst lasting 60 seconds. Beyond tested capacity, shedding is bounded, visible, and does not degrade gameplay latency.
- Healthy publication lag p95 <=30 seconds and p99 <=60 seconds. Cached API p95 <=200 ms; cold supported queries <=1 second at 100 concurrent website readers. Serve stale cached data when the query budget cannot be met.
- Repeat game-load measurement while website reads peak and while DB is offline, disk is throttled, ring is saturated, and spool reaches its cap. Game behavior, tick latency and memory bounds must still pass.
- Publication query plans use intended indexes and incremental watermarks; no public request causes full scans of gameplay history, live characters, inventories, or all-time raw facts. Snapshot indexing runs outside the game process and must meet the same co-located resource benchmark.

Benchmark captures hardware, JVM flags, database co-location, game build/config, human/bot populations, active maps, attacks/kills/consumptions per second, projection cardinality, enabled families, baseline and enabled warmup/duration, and p50/p95/p99. Include ordinary training, large trainer attacks, hosted boss combat, bot-only scenes, multi-channel play, and website traffic together. Use replay/synthetic producer tests off live gameplay for saturation; measure actual integrated hooks too. Do not claim "should be fine" as acceptance.

## 15. Source audit and implementation map

Source inspected 2026-09-30 in the current dirty checkout. These paths establish integration candidates, not complete coverage or deployed support.

| Source | Observed behavior / implementation responsibility |
| --- | --- |
| src/main/java/net/server/channel/handlers/UseItemHandler.java | Manual use, cures and return scrolls; some paths remove before effect. Capture actual removal and context. |
| src/main/java/client/processor/action/PetAutopotProcessor.java | Auto use can consume several units across stacks. Count actual quantity/item identity. |
| src/main/java/soloMapling/ArtificialPlayer/CompanionSystem/CompanionCombat.java | Potions and cures directly call Inventory.removeItem. Generic InventoryManipulator hooks alone miss them. |
| src/main/java/net/server/channel/handlers/ScrollHandler.java | Normal and White Scroll consumption, success/fail/curse. One action with multiple item lines. |
| src/main/java/net/server/channel/handlers/RangedAttackHandler.java and src/main/java/server/StatEffect.java | Ammo/reagent removal paths; count real spend independent of hit count. |
| src/main/java/net/server/channel/handlers/SkillBookHandler.java, UseSummonBagHandler.java, UseCatchItemHandler.java, UseMountFoodHandler.java, UseSolomonHandler.java, ItemRewardHandler.java | Additional USE paths requiring canonical accepted-outcome adapters. |
| src/main/java/client/inventory/manipulator/InventoryManipulator.java and client/inventory/Inventory.java | Shared mutation primitives also serve transfers/sales; require reason/receipt, not blanket decrement counting. |
| src/main/java/server/maps/MapleMap.java and server/life/Monster.java | Null-killer removal differs from kill path; rewards and participation have several recipients. Bind stats to legitimate spawn/encounter outcome. |
| src/main/java/client/Character.java | playerDead has a Carnival early return; updateQuestStatus increments completed counts; neither alone establishes all dedup/context rules. |
| src/main/java/server/quest/Quest.java and scripting/AbstractPlayerInteraction.java | forceComplete is used by ordinary scripts as well as commands. Explicit provenance required. |
| src/main/java/server/content/PqRanks.java | Per-character attempt/clear/best records only for six named rank categories; not a complete world PQ ledger. |
| src/main/java/scripting/event/EventInstanceManager.java | setEventCleared guards one event and awards per-character PqRanks; one world clear must be outside that participant loop. |
| scripts/npc/1043000.js, 1052008.js, 1063000.js and related JQ variants; scripts/portal; server/events/gm | Course-specific interactions and completion require adapter audit; map presence does not prove finish. |
| src/main/java/server/content/ContentState.java and ContentStore.java | Persistent character key/value blob; preserve existing earned progress, do not extend it into the high-volume world store. |
| src/main/java/server/Shop.java, Trade.java, maps/PlayerShop.java; pickup/crafting/quest/reward paths | Economy and inventory-flow audit targets. Follow canonical committed mutations and origin, including bot-specific stores. |
| src/main/resources/db/extensions and db/changelog-root.xml | Existing SQL extension and Liquibase conventions; verify actual loader and migration version before implementing new schema. |
| src/main/java/tools/DatabaseConnection.java | Shared game pool exists; statistics must not consume it from hot paths. |
| Website outputs/Ellinia/ledger.js | Existing four-category World Ledger presentation, optional gameplay-stats fetch, safe-integer-only values and a 100-row cap. Needs broader categories, pagination, precision and coverage support. |
| Website outputs/QuietGroveBackend/app.py | Existing account/ranking/status backend; no gameplay-stats endpoint found in this inspected copy. Add isolated aggregate reads. |
| Website outputs/Ellinia/data/roadmap.json and outputs/QuietGroveBackend/sync_roadmap.py | Reviewed static roadmap input; add SITE-06 deliberately without changing unrelated features. |

Website workspace: C:/Users/Lupert/Documents/ChatGPT/Maplestory Website. This is a saved source copy; its README says the existing preview still serves an earlier workspace. Updating this JSON is not proof that a live page was published. No website/game service deployment is performed by the spec task.

Required audit deliverable before collection activation: a source-coverage manifest mapping every installed USE removal path and every PQ/JQ/activity definition to its adapter, metric, acceptance test, and supported/partial/unavailable status. Search direct inventory mutations and script helpers, not only handlers named UseItem. Catalog unknowns prevent a false "all USE" claim.

## 16. Implementation phases and deliverables

| Phase | Required deliverables | Exit evidence |
| --- | --- | --- |
| WS0 definitions/audit | metric registry, item/category/region/activity catalogs, mutation and completion source manifest, baseline benchmark and sizing report | Owner-readable definitions; every installed source classified; repeatable load profile |
| WS1 bounded foundation | recorder, worker aggregation, journal/replay/receipts, migrations, coverage/health/config switches | Saturation, crash/replay, checksum, bounds and numeric tests; gameplay overhead gate |
| WS2 original requirements | all USE consumption, mobs, quest completion/firsts/region, all death modes, complete installed PQ/JQ adapters | Integrated source-path tests; exact hand-counted scenarios; no hidden partial core source |
| WS3 website delivery | /ledger integration, versioned API, item/entity search and pagination, filters, charts, precision, stale/gap handling | API contracts, concurrent-load test, desktop/mobile/browser validation using real collected fixtures |
| WS4 expanded world ledger | economy/items/crafting/upgrades, progression/exploration/presence, social/minigames/events/collections | Per-family semantics and reconciliation scenarios; bounded distincts and queries |
| WS5 optional detail and release | combat/support/world moments, records, full retention/ops docs, migration/restore/recovery and 24h soak | Complete catalog coverage or explicitly documented unavailable mechanics; measured production profile; end-to-end live acceptance |

Future NX metric W01 activates only after a real NX/economy feature is implemented; its source remains unavailable meanwhile. All other rows require an implemented adapter or a reviewed factual reason the gameplay mechanic does not exist; "later" is not completion. Existing unsupported human combat detail must be labeled unavailable rather than made up.

Coordinate Character, Inventory, Monster, MapleMap, event and script edits with their existing owners. Give one integration owner responsibility for each canonical fact. Website owner implements publication/read/API/UI after registry and response contract are stable. No new model, message bus, external analytics service or additional game-client packet extension is required.

## 17. Acceptance matrix

| Test | Scenario and expected evidence |
| --- | --- |
| WS-A01 | Manual HP/MP/hybrid/cure/buff/return item; verify exact consumed ID/quantity and correct primary category, including zero-effect valid uses and rejected inputs. |
| WS-A02 | Pet auto use spans two stacks and consumes several units; human/manual/bot totals agree with actual mutations. |
| WS-A03 | Companion direct potion/cure and trainer-assisted auto use count; free heal and no-ammo actions consume zero. |
| WS-A04 | Upgrade success/fail/curse, White Scroll, book failed roll, coupon/box/bag/capture; one action and exact multi-item consumption lines. |
| WS-A05 | USE crafting/quest/event payments count; sale/trade/drop/storage/expiration/admin deletion do not enter consumption. |
| WS-A06 | Ammo charges spent/recharged with all no-cost effects; no multiplication by attack targets/projectile visuals. |
| WS-A07 | Duplicate packet/callback, rejected mutation and retry after rollback; no phantom or duplicate counts. Simultaneous actions on one stack reconcile. |
| WS-A08 | One mob with several party recipients => one death plus defined participations; bot/human/mixed, summon/DOT/reflection and unknown attribution checked. |
| WS-A09 | Despawn/GM cleanup/fake marker excluded; object ID reuse after restart counts a new legitimate spawn; multipart encounter yields one final clear. |
| WS-A10 | Normal/scripted/repeatable/one-step quests; same episode retry counts once; first/repeat distinction durable; info/admin restore excluded. |
| WS-A11 | Quest region differs from turn-in location; primary region sums equal total; Multi-region/Unknown retained. |
| WS-A12 | Ordinary/Carnival/Dojo/hazard deaths, repeated HP=0, revive and second death; actual EXP/charm totals, unknown lethal cause retained. |
| WS-A13 | Four-person PQ => one clear/four eligible completions; rejoin, leader transfer, disconnect, timeout, early leave, duplicate finish, delayed/full-inventory reward. |
| WS-A14 | Every registered JQ: actual course start/checkpoints/finish, retry, unsupported warp/bypass, assisted timing and repeated NPC reward conversation. |
| WS-A15 | One shared event shown in related pages does not duplicate unique activity count; failed/ongoing/unresolved runs and rate denominators correct. |
| WS-A16 | Meso drop mint/pickup/re-drop/expiry, NPC purchase/buyback, player-shop tax/trade/delivery; creation/removal/transfer reconcile independently. |
| WS-A17 | Item drop/pickup/transfer/craft/destroy/expire; generated-versus-reused origin and partial-stack quantities preserved. |
| WS-A18 | Progression, cards, medals, exploration, social and minigame outcomes; no admin/flavor achievements or fake ambient PQ clears. |
| WS-A19 | Presence survives map/channel changes without overlapping sessions; reconnect/idle/party timers and missing samples; distinct daily sets not incorrectly added. |
| WS-A20 | Flush at UTC midnight, time rollback, delayed batch, multiple worlds/channels, definition change; correct original buckets, consistent publication. |
| WS-A21 | Kill process before journal, after sync, during transaction, after commit before acknowledgement; documented gaps and exactly-once durable replay. |
| WS-A22 | Duplicate/checksum-conflicting/partial-corrupt batches, out-of-order retry, checkpoint pruning and restore; no double count or silent repair. |
| WS-A23 | DB offline, disk slow/full, spool full, ring/key capacity, worker failure; gameplay latency/memory bounds and explicit gap states. |
| WS-A24 | Counts exceed 2^53; API decimal strings and exact UI formatting; checked BIGINT/DECIMAL limits and zero-denominator ratios. |
| WS-A25 | Category/entity/geography sums match the same snapshot under supported projections; unknown item names/category included; no marginal cross-filter guesses. |
| WS-A26 | Search IDs/names, >100 entities, deterministic pagination, date filters, cursor reuse on wrong query, escaping and unsupported filters. |
| WS-A27 | Unconnected vs zero, stale vs partial, pre-tracking dates, no available history; website states remain truthful. |
| WS-A28 | 100 concurrent readers plus measured game peak, 2x burst, 24-hour soak; report Section 14 targets and projected storage. |
| WS-A29 | Character deletion, game rollback, world reset, baseline import and compensation; history retained and coverage adjusted without changing gameplay. |
| WS-A30 | Whole pipeline: recorded fixture and real client actions -> durable batches -> aggregates -> API -> website item/activity details, restart and restore repeated. |

Required release evidence includes source manifest and checksums, game/publisher/backend/frontend build IDs, schema/catalog/definition versions, enabled families, automated commands/results, baseline/enabled benchmarks, storage forecast, restart/restore results, browser checks, and observed real-client scenarios. No invented test results or runtime percentage from specification length.

## 18. Roadmap handoff

Date: 2026-09-30. Owner request: fully specify server-wide world stats, including all USE items, per-entity website drilldowns, expanded useful categories, and bounded server overhead.

Feature: SITE-06, **World statistics and item-level World Ledger**. Previous state: no dedicated roadmap row; four-category website presentation exists without the inspected gameplay feed. New state: **0% / Planned; specification complete**.

Next checkpoint: Audit every USE consumption and PQ/JQ completion path, then prove bounded capture, durable batch replay and website drilldowns under measured game load.

Deliverables of this spec task: this specification; project scope-ledger entry; website status-ledger entry; website future-plan cross-reference; and the saved website roadmap JSON entry/change note. Unrelated feature percentages and current closeout assignments are preserved.

Implemented gameplay/backend collection: none. Runtime tests/benchmarks: not run because this is specification authoring. Deployed: no. Document verification on 2026-09-30 passed: all 18 numbered sections present; 50 unique metric families; WS-A01 through WS-A30 present once; example JSON and website JSON parse; 26 unique feature IDs agree between the website ledger and saved snapshot; SITE-06 title/status/percentage/checkpoint agree; specification links resolve; all pre-existing website feature records were preserved by the update. The saved specification was compared with the prepared text before this verification note and historical-import clarification were added. These checks validate documents and roadmap data only, not the designed runtime.
