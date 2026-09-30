# Persistent high-stakes drop-game venues

Added 2026-09-29 at the user's explicit request: 24/7 high-stakes drop games in Sleepywood sauna and Henesys potion shop. This is an implementation requirement, not an optional idea. Status: audited/specification complete; persistent venue runtime not yet implemented or deployed. Party runtime remains the current owner's priority. Trainer cheating/reactions inherit [external trainer specification](external-trainer-spec.md).

## Verified local v83 destinations and access

| Venue | Local map identity | Evidence/access policy |
|---|---|---|
| Henesys potion shop | `100000102`, WZ name **Henesys Department Store** | `wz/String.wz/Map.img.xml`; map life contains shop NPC `1011100`. Preserve the ordinary shop and its merchant; do not convert its role to a game host. |
| Sleepywood regular sauna | `105040401`, **Regular Sauna** | Hotel lobby `105040400`; receptionist `scripts/npc/1061100.js` charges 499 mesos before entry. Primary always-on sauna venue. |
| Sleepywood VIP sauna | `105040402`, **VIP Sauna** | Same receptionist charges 999 mesos. Optional overflow/elite table, never required for access to the core feature. |

The inspected local receptionist has **no gender or adult-age check**: regular versus VIP is the actual branch, not a male/female split. Do not invent a restriction from another MapleStory version or silently bypass existing entry fees/scripts. Preserve any additional access restrictions discovered in the actual bundled client/server playtest. This is in-game mesos/items, not real-money wagering, account payment, age verification or cash-out. Bots already assigned to the venue may spawn there; humans enter through normal routes.

## Persistent venue behavior

- One owned host per enabled venue/channel, a finite rotating roster of actual bot participants and modest spectators, active all day **while the server runs**. No claim of availability while the server process is stopped.
- Explicit venue lifecycle: STARTING -> WAITING -> ESCROW -> ROUND -> SETTLE -> INTERMISSION -> WAITING; FAULT/SHUTDOWN stops new wagers and settles/refunds existing escrow idempotently. No autonomous global bot takeover.
- Host/participant/spectator ownership must use the same exclusive lease registry as companion and GM events. Do not steal party members, GM attendees, merchants, tutorial/PQ actors or busy/trading bots. Venue leases do not consume the 30 companion cap; they have a separate configured population cap.
- Visible host pitches, bot-to-bot rounds and spectators continue without a human. When a human arrives, a round and its real finite valuable prize/wager stock must be visible rather than a fake text-only scene. Humans may join the next safe admission window or watch without paying.
- Default paid tiers remain 10,000,000 / 50,000,000 mesos, matching existing `DropGameBot` constants. Validate loss/win distribution against the actual local economy and stock valuations; configurable caps can lower exposure without fabricating wealth. Never silently debit a spectator or charge merely for entering either map.
- Every entry debit, pot contribution, inventory removal, spawned drop, pickup, return and payout has a durable round ID and unique operation ID. Round accounting must conserve mesos and item quantities. A bot's high-value item is genuinely removed from its finite stock before becoming a world drop. No fallback item generator, unlimited refills, repeat claim, disconnect refund-plus-loot or rollback duplication.
- Restocking is a scheduled, explicitly budgeted economy operation, not a reaction to the player emptying a host. Exhausted tables announce the break and rotate solvent participants. Rare equipment/scroll/star pools have auditable acquisition/value rules and per-day scarcity limits.
- Persist escrow and terminal settlement so restart/retry cannot charge or pay twice. At startup, reconcile interrupted rounds and world-drop ownership before re-opening a table; conflicting recovery enters operator-visible FAULT, not silent prize duplication.

## Existing drop timing defect to fix before reuse

`DropGameBot` currently advertises two-minute paid rounds, dropping every 4-6 seconds. Its medium/elite visual expiry is 1,400 / 1,150 ms. `DropCommands.botDropItemWithExpiry` retains server-side objects for an additional `PICKUP_GRACE_PERIOD_MS = 3000` after the visual removal. This permits a mismatch between what is visible and what remains pickup-eligible. The new venue adapter must use one authoritative expiry instant checked inside the same drop lock as inventory transfer. If network grace is needed, display and eligibility must remain consistent through grace; never leave invisible stealable valuables.

Normal pickup behavior must enforce existing owner/party protection, distance, inventory capacity, expiration and pickup delay. Any trainer override is an explicit own-server capability with a recorded causal pickup event, not a hidden bypass in the ordinary game. Protect unrelated player drops and merchants' inventory.

## Cheating and reactions

The player may bait a bot into dropping a valuable item and steal it through the authorized trainer once that subsystem exists. This is a separate consented roleplay game mode from ordinary paid-round rules. Witnesses react to observable actual outcomes: missing named item, pickup attributed to the player, impossible distance/timing, visible flying/FMA/vacuum. They may panic, accuse, cry, get angry, quit, report in roleplay, blacklist a later round or remember the incident. Non-witnesses do not obtain global cheat knowledge. No unintended permanent account ban, real external report or automatic confiscation outside the configured pretend-investigation mode.

Each round has exactly one settlement rule for a stolen prize: loss transfers to the recorded recipient and the losing bot's stock decreases; any insurance/restitution is a separate limited treasury entry. Anger text must never issue a second prize or refund already claimed loot. Spectators, victim and host can have different scripts and evidence; no LLM is required.

## Implementation queue and release gates

1. Add finite inventory/meso escrow + idempotent journal and recovery tests; repair visual/server expiry mismatch.
2. Add persistent venue definitions for the verified maps, independent caps, shared leases, bot population/recovery and ordinary human admission UI.
3. Connect actual owned items/drops/pickup attribution and normal rules. Preserve existing one-off drop game until migration tests pass.
4. Add observer-scoped reaction scripts/memory; connect trainer capabilities only after its bridge is tested.
5. Combined-bundle deployment with party/events/client/trainer after integrated review; no independent live deployment from this spec task.

Acceptance: both venues available after startup; NPC shops and sauna fee behavior preserved; 24-hour accelerated soak and real soak measured; no-human rounds produce real finite inventory transitions; players can watch/join/leave; both paid tiers debit once; 100-way pickup race gives one winner; pickup-at-expiry boundary is consistent; full inventory and protected ownership fail without loss; restart during each lifecycle phase conserves assets; no net meso/item generation outside explicitly logged budgets; bot exhaustion pauses; shared-lease races cannot steal companion/event/merchant roles; visible cheating produces attributed local reactions and no omniscience; no permanent bans; independent map/channel populations and CPU/packet budgets measured. None of these gates are yet claimed passed for the new persistent feature.
