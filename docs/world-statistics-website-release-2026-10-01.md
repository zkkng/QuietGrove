# World statistics website connection release

The owner's October 1 request to connect the statistics collector to the website is **implemented and deployed**. The public [World Ledger](https://grove.caveboys.net/ledger) now reads real committed counters, with searchable and paginated entity breakdowns. Human testing is optional; it is not a completion gate.

## Implementation status

| Scope | Implementation | Deployment | Concrete remaining code |
| --- | --- | --- | --- |
| Current request: connect the existing seven core metrics to the website | **100% implemented** | Live, October 1 at 01:37 PDT | None within this connection milestone |
| SITE-06 full hyper detailed specification | **25% estimated**, in development | Core collector and this website milestone live | Additional metric families, comprehensive adapters, item subtype and continent taxonomies, historical/time/population filters and individual entity history |

The full-scope estimate describes implemented capabilities, with uncertainty of about ten percentage points. It is not a measured completion fraction. It does not withhold completion because a human has not played every route. The original specification covers about fifty metric families; the seven connected metrics and initial website slice do not implement that entire scope.

This is actual new functionality, not a percentage reclassification: a read-only publication job, scheduled snapshots, public API routes and all seven Ledger categories were added. Before this release the Ledger's gameplay request returned no connected statistics.

## Live behavior

Snapshot `ws-1790844077503-481529ed597e` was verified at **October 1, 01:41 PDT / 08:41 UTC**:

| Metric | Recorded total | Searchable entity records |
| --- | ---: | ---: |
| USE items consumed | 1,480 | 2,290 items |
| Monster defeats | 0 | 1,597 monsters |
| Quest completions | 0 | 2,818 quests |
| Player deaths | 316 | One record, cause unknown |
| PQ runs cleared | 0 | 22 supported definitions |
| PQ participant clears | 0 | 22 supported definitions |
| JQ finishes | 0 | Eight supported courses |

Example: searching item ID `2070000` returns Subi Throwing-Stars with **560 consumed**. The page includes ammunition in USE totals. It labels all-world, human-and-bot scope, tracking start, last recorded activity and partial coverage. Other metrics' zeros are the stored totals, not inserted sample data or claims that those gameplay scenarios were exercised today.

## Architecture and review

The publisher reads a consistent **read-only** transaction from existing `stats_*` tables every 30 seconds. It writes complete immutable, checksummed snapshot generations, then atomically switches the manifest. Entity totals must reconcile with overview totals before a generation can publish; a failed generation retains the prior complete snapshot. Archived snapshots remain for at least twenty minutes so pagination can stay on the same generation.

The website endpoint reads only snapshot files. Browser traffic does not query the game database. Counts are canonical decimal strings through the API and are formatted with `BigInt` in the browser. No game database writes, new database credentials or grants, firewall changes or gateway changes were made.

The systemd publication job has a 25-second timeout and 128 MiB memory cap. The verified run used **17,788,928 bytes peak memory and 130,361,000 ns CPU**; these describe that publication run, not a comprehensive game latency benchmark. It uses the existing local root socket authentication in a restricted service; its queries select only statistics tables. Published files are group-readable by the existing unprivileged website service. The snapshot data directory is outside the public static root.

Code review checked projection/period selection to avoid double counting, exact integer arithmetic, zero versus unavailable, fixture rejection, snapshot consistency, stable pagination, input bounds, escaped names/IDs, search by name or ID, stale publication and paused collection labels, and file permissions. No known unresolved defect in this website connection was found. Schema and adapter limits remain separate full-scope work.

## Verification

- **10/10** publication and Flask route tests passed in the existing deployed Python environment: exact counts above 2^53, PQ denominators, failed-publication preservation, unavailable/paused data, pinned pagination, ID search, input bounds, integrity/fixture rejection and unavailable-feed behavior.
- **13/13** existing snapshot contract tests passed locally; these are a distinct reader suite, not additional production gameplay scenarios.
- Existing collector evidence remains **30/30** focused Java checks and the production database transaction/rollback checks in the September 30 collection record.
- JavaScript syntax checks passed. Live API acceptance passed for every metric, matching totals, numeric item search, two nonoverlapping pages, invalid-query responses and the enabled periodic publisher.
- Browser verification on the actual HTTPS Ledger showed the live totals, items 21–40 on page two, and a successful search for Subi Throwing-Stars. Screenshot saved in the local operational evidence directory.

The website service alone restarted during this installation. The game JAR and PID were unchanged by the installation, verified before and immediately after. Another server release subsequently changed the game process; the publisher still read the collector's existing tables and reported running capture at the later verification. This website deployment did not issue a game restart.

## Operations and source

- API overview: `/api/gameplay-stats`.
- Breakdown: `/api/gameplay-stats?metric=use.units_consumed&snapshotId=<snapshot>&q=<name-or-id>&offset=0&limit=20`.
- Other allowed metrics: `monster.defeats`, `quest.completions`, `player.deaths`, `pq.runs_cleared`, `pq.participant_clears`, `jq.finishes`.
- Optional category filter; page size 1–100; stable archived snapshot IDs. Invalid filters fail explicitly. Expired snapshots return 409 and refresh the view.
- Service/timer: `quietgrove-world-statistics.service` / `quietgrove-world-statistics.timer`.
- Snapshot root: `/opt/quietgrove/world-statistics`; backend: `/opt/quietgrove/backend`; frontend: `/opt/quietgrove/site`.
- Deployment backup: `/opt/quietgrove/backups/world-statistics-web-1790843877`.
- Local operational evidence: `G:/Maplestory v83 server dev/SoloMapling-v83/tools/world-statistics/web/` contains checksummed package metadata, release and verification logs, and `live-ledger.jpg`. Private receipts and screenshots are not source commits.
- Website source modules: `publish_world_statistics.py`, `world_snapshots.py`, `world_statistics_api.py`, `test_world_statistics.py`, `ledger.js`, `ledger.css`, and minimal `app.py`/`portal.html` wiring. The existing authoritative local website source was synchronized, preserving its separate pending roadmap script version. Team source copies are preserved in the website worktree.

The USE category remains `unclassified_use`; all individual items are searchable. Continent labels are not inferred from quest area codes. Death causes are unknown. PQ support uses the central clear callback for 22 definitions. JQ support records eight reward exits, without proving full traversal. Most broader economy, loot, progression, exploration, combat/support, social/event and world-moment metrics remain unimplemented. Measured overload/latency acceptance and crash-proof zero-loss capture are not claimed.
