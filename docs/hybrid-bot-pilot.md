# Limited hybrid bot pilot: Henesys party combat

Three temporary level-70 Crusaders: **HybridOak**, **HybridAsh**, **HybridElm**. Each keeps one Character and one HybridPilotBot root while switching between dormant, social, nearby combat and party activity. Existing bots are not converted. Default population is zero; restart never respawns the trial. Cap: three per process. Lifetime: 30 minutes after creation.

The owner narrowed the event trial to **killing monsters, partying and Mushmom**. Quiz events, jump quests, trading, selling and merchant advertisements are outside this slice. The broader migration research remains in `docs/bot-brain/` on the research branch.

Implementation, deployment and a human playtest are separate statuses. Passing automated tests does not prove a crash-free client or a latency bound.

## Interaction and test sequence

1. Meet the named bots in **Henesys, channel 1**, map **100000000**. Say `HybridOak hello` or `HybridOak status`. Names require a word boundary and replies have a three-second cooldown.
2. Invite each by the normal party interface while nearby. Recruitment uses the existing companion eligibility, six-member party limit and global companion cap. Leave about a second for the scheduled response. Companion recruitment must already be enabled in server configuration.
3. Go to **Henesys Hunting Ground I (104040000)** and use `!hybrid here` to bring these three actors to your position. While partied they use existing companion navigation, skills, finite potions and normal damage/EXP paths. The root identity and inventory stay intact.
4. For **Mushmom**, go to **100000005 (Pig Park)** and use `!hybrid here`. With Mushmom visible, say `help fight Mushmom here`. Existing party members must pass the real boss build/supplies checks. Alternatively `hunt Mushmom and keep partying` uses the existing hunt objective. Recruitment does not spawn or instantly kill the boss.
5. Watch attacks, HP/MP and party membership. Talk to a named bot while fighting. Dismiss with `HybridOak dismiss companions`, then confirm the same bot resumes its ambient mode. Ambient mode is stationary basic melee against ordinary monsters; boss combat requires the party/hunt path.
6. Leave the exact map with every human client. Check `!hybrid status` from elsewhere: DORMANT; no pilot attacks, conversation, autonomous travel or movement simulation. Return or relocate the cohort and verify there is no catch-up burst. The cheap heartbeat still runs.
7. Run `!hybrid off` during combat. Confirm `!hybrid status` reports 0/3, party leases and movement stop, and legacy bots remain. Repeat spawn/off and check for duplicate sprites, stale party seats, server exceptions or client disconnects.

**Map transitions in this trial:** the strict empty-map rule means bots cannot finish a route after the last human leaves their old map. Use `!hybrid here` after moving to another map. This explicitly preserves the requested sleep behavior; seamless following across empty maps is a later design decision. Following within an observed map uses the normal movement driver.

Keep a visible human on the combat map to supply ordinary monster control. Unit tests and server probes cannot substitute for checking rendered movement/attacks in the MapleStory client.

## Commands and operator entry point

GM level 4:

| Command | Result |
| --- | --- |
| `!hybrid spawn [1-3]` | Add named pilots beside the GM, up to the process cap |
| `!hybrid here` | Bring only this cohort to the GM in the same channel; preserve HP, inventory and root |
| `!hybrid status` | Names, IDs, mode, map, HP, root ticks and ambient counters |
| `!hybrid off` | Stop and remove only this cohort; retry if body cleanup fails |
| `!hybrid help` | In-game quick guide |

`HybridPilotService.get().spawnHenesys(0, 1, 3)` is the explicit operator/probe entry point for the requested placement. It uses the same cap, names, factory and cleanup as the GM command. No startup switch or background population job invokes it.

Names are reserved while their actor is tracked, including failed cleanup. Removed slots reuse their recognizable names. A spawn batch failure cleans up only the new batch. Administration commands admit one operation at a time instead of queuing unlimited requests.

## Root ownership and combat

- The shared BotTickService owns one root registration, base period 500 ms. Party activation constructs a CompanionBot decision adapter but **does not register its scheduler or replace CharacterStorage**. Its movement executor is separate existing physics, not another decision brain.
- A real CompanionTaskService lease and canonical Party join precede party mode. Generation-matched release stops the delegate, removes the lease/party membership and returns to the same hybrid root. Legacy type conversion and direct registry replacement remain fenced.
- All root decisions, chat, relocation and shutdown serialize on the actor. Empty-map observation uses the exact MapleMap and real Client, not numeric map IDs or legacy character-ID thresholds. The movement driver independently suppresses pilot physics/contact on empty maps, with a one-second idle wakeup.
- The root mailbox is one expiring reply, not a growing queue. Unrelated chat does not wait on each pilot monitor. Companion/boss commands parse before pilot small talk so recruitment and dismissal are not swallowed. No LLM is involved.
- The build uses level-budgeted AP/SP, real equipped items and one finite starter inventory. Full HP/MP is granted only at creation, not when switching modes. CompanionBuild's existing initialization is idempotent for an unchanged level.
- Ambient combat uses one basic melee line, real accuracy/defense and map.damageMonster. Ambient contact can kill; it does not revive. Boss/fake/friendly/incident-owned targets and unknown hitboxes are excluded from this basic adapter.
- Party mode inherits existing companion skill costs, supplies, incoming damage, movement, encounter authority and normal rewards. If a companion dies, the existing return-map recovery restores 30% HP and releases the party duty; it does not resurrect in the battle map.
- Ambient attack/contact counters do **not** include delegated party actions. Use character HP/MP, party/hunt state and existing boss telemetry for the party trial. Root tick duration includes synchronous delegated work; movement has its own scheduling.
- Legacy trade, merchant menus, ambient buff solicitation and GM event recruitment exclude pilots. Normal companion recruitment explicitly admits verified hybrid builds. The HYBRID_PILOT enum value is a restoration descriptor; its factory refuses creation outside the capped service.
- Unexpected root/delegate errors stop that actor and log the failure. Faulted actors remain for diagnosis until `off`. Healthy actors expire after 30 minutes, including on empty maps. No database/schema migration or durable pilot inventory is introduced.

## Tests and release gates

Automated coverage includes mode transitions, same-instance observation, ID-independent human detection, damage-before-attack death ordering, cooldowns, chat floods, stale messages, failure-stop, lifetime expiry, stop/tick races, conversion/trade/event isolation, same-channel relocation, global cap concurrency and partial cleanup. Party additions cover exact root/body preservation through the actual CompanionRuntime acceptance/release path, no delegate scheduling, stale generations, party failure cleanup, bounded chat and empty-map suppression.

Run from this worktree with Java 21:

```powershell
mvn -o '-Dmaven.repo.local=G:/Maplestory v83 server dev/tmp/bot-brain-maven-repository' verify
```

The local Maven executable is `G:/Maplestory v83 server dev/maven/apache-maven-3.9.8/bin/mvn.cmd`. Test results and a deployment receipt must record the actual tested source and artifact hashes. Do not present old v1 tests as verification of the party extension.

Before release: commit the reviewed source; compare every changed live class to the exact source baseline; preserve unrelated live fixes and instrumentation; validate the candidate JAR; perform guarded deployment preflight against exact JAR/config/PID. Deployment must not silently disconnect an attached player. After startup: verify readiness, configuration hash, three names and IDs, exact Henesys/channel placement, root types, HP/build suitability, no extra pilot actors and empty-map dormancy. Human party/Mushmom acceptance remains outstanding until actually exercised.

## Rollback

1. `!hybrid off` is the immediate cohort rollback. It removes only the three pilot bodies and their active duties without restarting.
2. For binary rollback, restore the exact pretrial `Server.jar` from the guarded release backup and restart using the release workflow. Keep configuration, scripts and database unchanged unless an observed problem specifically requires their restoration. The installer also snapshots the database; do not overwrite subsequent human progress merely to remove these ephemeral bots.
3. A restart with the pilot-capable binary leaves zero pilots until explicitly spawned. No population flag or automatic conversion needs reversal.

Stop expansion on duplicate controllers, stale party ownership, offscreen pilot actions, unbounded work, asset duplication, failed cleanup, rising exceptions or a client hang. Compare zero/one/three-bot CPU/heap and tick behavior during a matched observed-map trial before any wider rollout.
