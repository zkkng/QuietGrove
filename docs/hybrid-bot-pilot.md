# Limited hybrid bot pilot

This is the first executable slice of the shared-character/brain design. It creates **new, temporary actors**, capped at **three per server process**, without converting the existing population. Default population is zero; startup does not spawn or enable anything. No schema or configuration migration is needed.

Implementation, packaged build, deployment, and native-client acceptance are separate milestones. This document describes implemented behavior and the owner test procedure, not a claim that the game trial already passed. Start with one bot on an isolated test server/database.

## What this slice can test

- One Character and one HybridPilotBot controller persist across social/combat/dormant/dead states. The old social, training, and merchant controllers are never started for that character.
- A level-20 warrior stands where spawned. A hostile ordinary monster entering basic melee reach triggers a real, character-stat attack. Clearing the nearby targets returns it to social mode.
- Contact with a monster changes real HP and can kill the bot. Contact damage is checked before the social/combat decision; death prevents the next attack. There is no automatic revival or refill on wake/relocation.
- Addressed chat works while fighting. `Hybrid1 hello`, `Hybrid1 status`, and `Hybrid1 sell` produce a greeting, a status reply, and an honest trade refusal, respectively. Use the actual name reported at spawn; serial numbers increase after each attempt.
- Gameplay actions require a real client on that exact MapleMap object. A player in another channel, world or instance with the same numeric map ID does not count. BotClient characters do not count as observers.
- Empty-map checks continue at the low, bounded pilot cadence, but do not attack, take pilot contact damage, speak, travel, earn abstract EXP or replay missed actions. Integrity cleanup still runs. The legacy world's independent timers are not globally rewritten by this experiment.

## Deliberate limits

This is **not full normal-player combat parity**. The first slice uses a single basic melee target and one damage line, with character attack power, defense and accuracy calculations adapted from the existing companion path. No skill costs are bypassed because this slice does not cast skills. The normal `MapleMap.damageMonster` path handles deaths and rewards; there is no second synthetic drop roll. It does not certify bot-only loot eligibility.

The pilot does not walk, pursue targets, follow players, cast spells, use potions, pick up loot, trade, buy from shops, advertise stock, persist across restart, or join parties/events. Incoming monster projectile, magic, status and boss mechanics are not part of this first test. Bosses, fake/friendly monsters, encounter markers and incident-owned monsters are not attack/contact targets. Unknown monster hitboxes are skipped rather than guessed. Tests therefore need ordinary low-level monsters near the bot's feet.

`!hybrid here` is an explicit GM relocation tool for testing modes in different environments. It preserves the Character, controller, HP and possessions, and only moves this process's pilot actors within the GM's current channel. It is not autonomous travel or an ability offered to ordinary characters.

The existing template/decoration path still supplies the appearance/build. The equipment cache must have finished normal server startup. These actors use existing ephemeral bot IDs; durable identity and inventory migration remain future work.

## GM commands

Commands require GM level 4, consistent with existing bot administration.

| Command | Effect |
| --- | --- |
| `!hybrid spawn` | Spawn one new pilot beside the GM |
| `!hybrid spawn 2` | Add two, provided the server-wide total remains at most three |
| `!hybrid status` | Show each pilot's name, ID, mode, map, HP, ticks, attacks, contact hits, replies, transitions and maximum tick duration |
| `!hybrid here` | Relocate active pilot bodies in this channel to the GM's ordinary map and position |
| `!hybrid off` | Stop and remove only this pilot cohort across the process |
| `!hybrid help` | Show the short command and scope guide |

The command runner allows one administration operation at a time and rejects additional requests with a retry message; it does not queue unlimited spawns. Spawn admission also atomically checks the process cap. If part of a batch fails, it attempts to remove the new batch and leaves previously created pilots alone. Failed body cleanup stays tracked for another `off` attempt.

## Owner test checklist

Keep the client visible for combat; hidden GMs do not supply the ordinary monster-control stream. A normal visible player can observe alongside the GM. Stand on solid ground before spawning/relocating these stationary bots. Map names/IDs below are verified against the repository's String.wz.

1. On the test build, run `!hybrid status`. Expect `0/3`, with existing bots behaving as before.
2. Run `!warp 100000000` (Henesys). Then `!hybrid spawn 1` and note the name/ID. Say `<name> hello` and `<name> status`. Expect one reply, SOCIAL mode, and no additional brain/controller.
3. Run `!warp 104040000` (Henesys Hunting Ground I). Stand near ordinary low-level monsters and run `!hybrid here`. Expect the same name/ID/HP. Within melee reach, attacks and contact counts should rise; HP may fall. Move it near another mob with `here` if none reach it naturally.
4. Address it while it fights. Expect bounded conversation without a legacy menu, a trade dialog, a type change, or a second controller. Attempt a normal trade and a party invite; each must be refused without reserving inventory or leaving an invitation hanging.
5. Return to town with `here`. Expect SOCIAL again and unchanged identity. Repeat town/field relocation ten times. Check for exceptions, duplicate sprites, reset HP, and unexpected tasks.
6. With the pilot in the field, record status, then have **every real player leave that map instance**. From another map use `!hybrid status` twice, ten seconds apart. Expect DORMANT and unchanged attack/contact/reply counts. The cheap tick counter can increase. A second client in the same numeric map on another channel must not wake it.
7. Re-enter the original map. Expect activity to resume within approximately one governor-adjusted tick, with no burst of catch-up attacks or stale conversation. The 500ms base cadence can be stretched by the existing wheel governor under load.
8. Let one pilot die. Expect DEAD, no attack or conversation afterward, and no automatic HP restoration when moved. Remove and spawn a new one for subsequent tests.
9. Run `!hybrid spawn 3` when one is already present. Expect refusal and still one actor. Fill to three with `spawn 2`, then repeat a spawn request; it must remain at three.
10. During combat run `!hybrid off`, then `!hybrid status`. Expect zero pilot actors, no further pilot attacks/replies, and ordinary bots still present. Repeat spawn/off three times.
11. In a separate soak, leave one healthy pilot for 30 minutes. Its actor/body should be removed when the lifetime expires, including on an empty map. A faulted pilot is already unscheduled and remains visible for diagnosis until `off`; faulted cleanup does not rely on the lifetime timer.

Capture server exceptions, client crashes, any unexpected health/asset change, status before/after, process CPU/heap, and tick delay during a matched zero/one/three-pilot run. **Unit tests do not prove client stability or a latency bound.** Stop promotion on any duplicate controller, growing backlog, offscreen pilot gameplay, asset transfer, death/cleanup failure or client hang.

## Ownership and isolation

- A synchronized actor serializes its tick, addressed chat, relocation and stop. No independent movement/attack/chatter timer is started. The existing shared BotTickService owns its sole registration.
- Stopping closes admission, clears the one-slot chat mailbox and unregisters the matching actor. It waits for an already executing action to leave the actor monitor. Late queued ticks and legacy restart requests cannot reactivate it.
- One expiring chat slot and a three-second speech cooldown bound conversation. Ordinary unrelated chat does not acquire pilot actor monitors. Chat does not invoke an LLM.
- Each attack/contact path checks the current map/observer before committing. As with ordinary server actions, a concurrently departing observer can overlap an already accepted synchronous action; after that action drains there is no offscreen simulation or catch-up.
- Legacy type factories refuse a pilot **before constructing** a replacement, and CharacterStorage rejects replacement of a registered pilot. Existing types retain their normal factory behavior.
- Legacy trade start/invite/visit, ordinary party invitations and ambient buff-request selection exclude pilots. Existing automatic companion/event eligibility requires known legacy activity types and therefore does not recruit them. Legacy named-chat dispatch is also fenced away from its service menus.
- The pilot's first unexpected exception stops its scheduler and records/logs the failure. An `off` cleanup failure does not stop cleanup of the other pilots; retry remains possible.
- The lifetime removes abandoned healthy actors after 30 minutes. No autonomous population generator, database record, global enable flag or type conversion is introduced.

These are cohort boundaries, not a universal redesign of all existing bot APIs. Do not combine the pilot trial with legacy force-attack, movement, summon/retype commands or runtime instrumentation that deliberately bypasses its controller.

## Rollback

1. Run `!hybrid off`; verify `0/3` and no subsequent pilot actions. This is the normal runtime rollback and does not restart the server.
2. If the server/client is unresponsive, stop the **test** server and restore the exact previously recorded test package/configuration using its release receipt. Restart starts with zero pilots. Save logs first when possible.
3. No database migration is added. Do **not** restore a whole shared/live database to remove this feature. Removing bots does not undo earlier monster kills, shared EXP or drops received by other characters. This is why the first economy-impact check belongs on an isolated test database.
4. Keep the old package until the native-client trial and soak are accepted. Promote neither the global bot population nor the production branch as a side effect of this test.

Production installation still follows [the repository release workflow](branch-and-release-workflow.md): approval of the exact tested source/package and a verified rollback location. A development JAR is not evidence of a live installation.

## Automated validation and reproduction

**Local verification, 2026-10-01:** Maven `verify` completed successfully with **3,999 tests, zero failures/errors/skips**, including **37 new pilot tests**, and assembled `target/Cosmic.jar`. The branch incorporates shared test baseline `47d3212e`. Native-client gameplay, database-backed acceptance, live load/soak and deployment rollback have not been performed.

Earlier runs on the old research baseline reproduced its eight known script-context failures. The refreshed shared test branch contains their fixes. The first run after rebasing also exposed missing sparse-checkout fixtures; adding the committed fixture directories resolved those initialization errors. The successful result above is from the corrected checkout and final pilot source.

The new HybridPilotBotTest, HybridPilotEffectsTest and HybridPilotServiceTest cover mode changes, same-instance observation, real-client identity, no empty-map mutations/catch-up, HP/death ordering, independent cooldowns, chat flooding/expiration, trade/type/event isolation, relocation, concurrent cap admission, partial spawn cleanup, retryable removal, lifetime expiration and stop-versus-in-flight action synchronization. Combat tests exercise the production effect adapter with mocked map/monster services and the actual melee packet builder. They are not native-client or database acceptance tests.

Use JDK 21 and `mvn verify`; for a focused run use `mvn -Dtest=HybridPilot*Test test`. The complete run requires the committed Mob.wz, Reactor.wz, UI.wz and server-config fixtures. In a sparse worktree, include those directories before running. Database `*IT` suites are separate and are not claimed by the ordinary Surefire run.

Local offline command used in this workspace:

```powershell
$env:JAVA_HOME = 'G:/Maplestory v83 server dev/tools/java21/jdk-21.0.12.1+1'
$env:TEMP = (Resolve-Path target/test-tmp).Path
$env:TMP = $env:TEMP
& 'G:/Maplestory v83 server dev/maven/apache-maven-3.9.8/bin/mvn.cmd' -o `
  '-Dmaven.repo.local=G:/Maplestory v83 server dev/tmp/bot-brain-maven-repository' verify
```

After the final source is committed and verification passes, use `python tools/release/package_release.py --output release/hybrid-pilot`, then `verify_release.py` with that package's commit and SHA256. The generated manifest and hash file identify the concrete test candidate; generated binaries/manifests stay outside source commits.

## Next expansion gate

After the one/three-bot native-client checks pass, add one capability at a time under the same cap: synchronous movement ownership, wider supported combat/resource rules, then finite inventory/persistence and trade. Keep the existing population unchanged until those separate gates pass. Enabling the old synthetic merchant paths is not an acceptable shortcut for the next pilot.
