# Quiet Grove additions to SoloMapling

Quiet Grove is a fork of SoloMapling v83, which is based on Cosmic. Upstream authors, history and license are preserved. This records the owner's additions as of September 30, 2026; source present does not imply full gameplay acceptance.

| Addition | What was added | Current boundary |
| --- | --- | --- |
| Bot parties and bosses | Six-seat companion parties, support/combat, recruiting and hosted boss waves | Earlier server build has partial live evidence; full catalog and cleanup acceptance remain. |
| GM events | OX, Ola, Fitness, Coconut, Snowball, Treasure, bot hosts, crowd instrumentation and bounded invasions | BotSM fix and 24-responder limit deployed. Rendered 80-responder run failed at 2/3 with outbound overload and a desktop exit. Headless 24-responder run passed 3/3 with measured damage, bot deaths and cleanup; client stability and six-game/crowd acceptance remain open. SocialBot activity change is not deployed. See [incident evidence](gm-henesys-live-incident-2026-09-30.md). |
| Trainer and social play | External trainer, Mob Vac, FMA, pickup/movement controls, reactions, finite stakes and venues | Installed v0.5 is partial; v16, rapid attack and durable venues remain candidates. |
| World restoration | Earlier quests/NPCs, iTCG/crafting, PC Café, medals/pets, Family, Market and Pyramid/Subway fixes; audited WZ assets | CONTENT-01 implementation: 12/13 rows complete (about 92% by row count), per content-owner source/audit signoff on October 1. Remaining code gap: Café return-scroll client compatibility (`CANNOTMIGRATE`). Combined deployment is tracked separately. Tour script fixes are deployed; in-game rewards/persistence testing remains open without reducing implementation status. See [content handoff](world-content-weekly-handoff-2026-09-30.md). |
| World statistics | Detailed World Ledger specification, bounded capture/journal/replay prototype and server collector | Core collector deployed and enabled; 30 tests passed, real gameplay acceptance and comprehensive PQ/JQ coverage remain open. Its 32 hook entries were preserved by the subsequent BotSM overlay. |
| Website | Quiet Grove frontend, accounts/profile, rankings, news/roadmap, downloads, support and HTTPS gateway integration | Kept in the separate private website repository; copied images and private runtime data remain outside Git. |
| Release workflow | Team development branches, `test` milestone builds and owner-approved `main` promotion | GitHub CI and immutable package identities support the separate test/prod deployment process. |

Full working requirements still include Carnival/KPQ bots, the full trainer catalog, NX card packs/Marvel Machine and hybrid interaction AI. Those plans are not claimed as completed additions.

WORLD-01 respawn policy/formula and `MonsterRespawnTest` are implemented, with probe/map fixtures available. Deployment remains pending: measured live interval is 4 seconds; reviewed target is 10 seconds. The release owner must apply that configuration change before claiming the runtime matches. Human gameplay testing is tracked separately from implementation.

Latest combined candidate is offline only: 3,958/3,958 tests passed; all 32 statistics hook classes were reinjected and JVM verification/hook smoke passed. Candidate `D3C64CE9…` is not deployed and includes staged SocialBot activity. The 18-case venue MariaDB integration suite has not run, and client crash diagnosis remains open. This checkpoint adds no EVENT acceptance or percentage increase.
