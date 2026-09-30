# World statistics snapshot bundle v1

Agreed between the SITE-06 server and website owners on 2026-09-30.

Root manifest.json atomically points to immutable shards/<sha256>.json files.
Archive manifests/<URL-safe snapshotId>.json for at least ten minutes; retain all referenced shards.
Descriptor: path, sha256, bytes, metricId (null for catalog/overview), projection, scope, period.
Maximum initial entity shard: 8 MiB and 100,000 rows. Validate byte length/checksum before parsing.
No parent traversal, absolute paths, symlinks escaping the snapshot root, or numeric coercion of counts.

Root fields: schemaVersion=1, snapshotId, epoch, fixture, generatedAt, dataThrough,
trackingStartedAt, definitionVersion, catalogVersion, coverage, scope, period, shards.
scope = {world,population,assistance}; period = {kind,from,to}. Initial period is since_start.
All shards repeat schemaVersion, snapshotId and fixture. Catalog contains metrics and entities;
overview contains cards; metric entities contain metric/unit/total/availability/coverage/scope/period/rows.
Entity rows: entityId, name, category, value (canonical decimal string).
Sort descending exact value, then numeric entity ID ascending. Stable cursor pins query and snapshotId.

Initial metric IDs: use.units_consumed, monster.defeats, quest.completions, player.deaths,
pq.runs_cleared, pq.participant_clears, jq.finishes. Core definition version is 1.
supportedFilters arrays explicitly advertise only the precomputed scope and period.
availability is available or not_connected for this slice. Coverage is per metric.
Unavailable value is null; measured zero is "0"; unavailable rows are empty with not_connected coverage.

This checkpoint creates ISOLATED synthetic fixtures only. fixture=true is mandatory.
The production website adapter must reject fixtures; never use this directory as public fallback data.
Fixtures deliberately include >2^53 counts, different PQ run/participant totals, unavailable deaths,
and measured-zero JQ finishes. Content catalog/source audit outputs are separate from test statistics.

Generate: python tools/world-statistics/build_fixture.py
Audit content/source candidates: python tools/world-statistics/audit_sources.py
Neither script changes gameplay, connects a producer, runs migrations or publishes the website.
