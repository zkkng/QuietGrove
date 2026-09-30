# v83 gameplay and access policy

The user's target is a playable, close-to-vanilla v83 world with unavailable real-world barriers replaced by sensible in-game access. Normal NPC roles, quests, level requirements, prerequisites, crafting ingredients and earned progression remain meaningful. A missing implementation is a defect to restore, not a reason to grant its rewards automatically.

## Implemented access

- The content era and generated equipment pool are **83**, replacing the source's version-55 setting. This setting is separate from the already-v83 client protocol. Later v83 transport NPCs, Nautilus, Ereve, Rien, Temple of Time, Mushroom Castle, Kerning Square and the other era-gated destinations can now be reached under their normal scripts.
- Removed nine artificial version-99 NPC bans and three world-destination bans (MV Lair, Altair Forest and Balrog's approach). Unlocking a destination is not proof that its complete quest/event has been played through.
- Retained the twelve mini-dungeon plain-portal/bot protections. Players enter these instances through the existing scripted `MD_*` portals; they do not use this version gate. Do not send bots into private instances or replace their instance-entry scripts with direct warps.
- **PC Café:** Mong from Kong in Kerning City offers entry for the existing 5,000 mesos. No cybercafé affiliation or external service is required. The restored Computer, Billy and Vending Machine provide the original setting, fifteen Premium Road hunting maps and the documented custom progression. The existing Café PQ remains available.
- **iTCG:** Henesys Spindle replaces unavailable physical codes with the approved **64 original code-exclusive reward IDs**, including five pets. Prices and crafting ingredients remain in `scripts/npc/data/tcg-catalog.tsv`. Crafted outputs are not sold directly.
- Hosting address, database location and related machine settings remain deployment choices. No host is hardcoded by this policy change. Existing server rates and bot/FM customizations were not reset.

## TCG NPC roles

The shared crafting code now supplies an NPC-specific menu and enforces that same catalog on the server during exchange. Only Henesys Spindle sells the 64 code rewards. All 281 crafting/redemption choices remain reachable.

| NPC | Role |
| --- | --- |
| John Barricade | Ten forging manuals and six Bosshunter crafts |
| Professor Foxwit | Four forging manuals |
| Spindle | Fourteen equipment recipes and eleven Materia weapons |
| Glimmer Man | Materia Orb, matching the local WZ quest 4924/4925 reward role |
| Corine | Twelve Taru upgrades |
| T-1337 | Twelve potion/bonus-potion choices |
| Stirgeman | Twenty-four Stirgeman upgrades |
| Adonis | Four Zakum upgrades and eight Stone Denari exchanges |
| Henesys Spindle, additionally | Code reward shop and 175 Ridley redemption choices |

NPC 9201103 remains the source's custom Crimsonwood Keep PQ guide. Its Ridley redemption role is deliberately provided at Henesys Spindle instead. The 175 entries are twenty-five rewards with seven alternative boss ingredients, not 175 unique reward items.

Role references: the [historical forging guide](https://global.hidden-street.net/item-synthesis/itcg), [archived forging walkthrough](https://maplesecrets.blogspot.com/2011/08/abridged-guide-to-itcg-items-forging.html), and the local v83 Quest WZ. These references establish crafting roles; custom prices and the friendly exchange mechanics remain server balance choices.

## Confirmed NPC repairs in this pass

- **Irene and Shalon:** cancellation stops the transaction; invalid menu choices and repeated replies cannot buy/board; an unavailable airplane event reports a closed departure without throwing. Ticket price, inventory capacity, existing-ticket checks and boarding schedule remain.
- **Geras and the Ariant return usher:** ticket possession and boarding status are checked again on confirmation. Removing a ticket or closing the departure after the prompt cannot produce a free trip. Null event managers and repeated replies are handled.
- **Eric, Singapore VIP hair/color:** confirmation indices 1 and 2 now apply the chosen cosmetic instead of reopening a service menu. Invalid indices cannot consume coupons.
- **Jimmy, Singapore REG hair/color:** repaired the broken gender/style loop and undefined index. Random selection only uses available styles. Both salons retain their original coupons and reject empty lists, missing coupons, cancellation and repeated confirmations.

## Previously listed gameplay gaps now implemented

The subsequent completion pass implements medals, smart-pet recall/automatic speech training, all four Maple 7th Day Market layouts and egg care, all Family EXP/drop/bonding benefits, and Nett's Pyramid/Dusty Platform. It also restores the missing monster/reactor XML metadata, survival entrance routes, correct weighted chest rewards and persistent progress. [Implementation, balance choices, provenance and deployment](gameplay-completion-2026-09-26.md).

**3,422 combined regression tests pass**, with zero failures/errors/skips. The iTCG shop remains exactly 64 original code-exclusive rewards; all 281 crafting choices and the existing PC Café restoration remain intact. Host/database addresses remain deployment variables.

## Remaining acceptance boundary

Static coverage includes all **757 town NPC templates** across **1,353 town maps** and **1,319 Victoria-associated quests**. There are zero NPC version gates and zero remaining medal-placeholder routes. The inventory still has **58 dialogue-only service-review candidates** and **31 unexpired quests with static dependency findings**. These counts overlap and include flavor actors and scripted encounters, not just confirmed failures.

- Old seasonal quests retain 652 historical WZ end dates. Historical Mesoranger/anniversary medal registrations still require their old event rewards; this pass does not fabricate those acquisition paths.
- Quest 1028 remains the source's GM-clothing-gated legacy/debug route; Shanks's ordinary travel is already available.
- Dynamic encounters need live gameplay validation. Do not add duplicate static spawns simply to silence the audit.
- Market stock/prices, tie handling and some survival scoring/spawn details are documented reconstruction choices. Original progression checks remain, while weekday, external-service and obsolete access barriers are removed.
- Abdula's mastery-book guidance, NPC 9201103's CWKPQ guide, the custom Café PQ and the other documented source customizations are preserved.

See [town NPC inventory](world-audit/town-npcs.md), [Victoria quests](world-audit/victoria-quests.md) and [remaining findings](world-audit/remaining-findings.md). These are static records, not a claim that every interaction was played. Deploy the Java build, scripts, XML and required Liquibase extensions together. No live database migration, server launch or client patch was performed; live acceptance remains pending server setup.
