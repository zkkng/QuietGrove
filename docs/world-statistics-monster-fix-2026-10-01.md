# Monster capture ordering repair

The original killBy hook required HP zero, but canonical MapleMap removal disposes the monster first, changing HP to -1. Normal monster deaths therefore produced no facts. Previously missed deaths cannot be backfilled from this collector.

Capture now uses the synchronized Monster.disposeMapObject boundary within the canonical five-argument MapleMap.killMonster scope. It captures zero HP before disposal and emits only after HP becomes -1, with matching monster identity, character killer and the actual map ID. The negative HP tombstone prevents repeats. Null-killer cleanup, positive-HP forced removals, encounter markers and admin scopes are excluded. Nested scopes restore their predecessor and are cleared on exceptions. The legacy monster method is a compatibility no-op.

No database or file I/O runs on this hook. It offers a primitive fact to the existing bounded recorder. One small context object is allocated per canonical removal, including cleanup.

Regression executes real HP damage, real map removal and disposal, and the advised canonical map-kill method. It checks human/bot classification, once-only capture, map attribution, cleanup, forced removal, admin exclusion, nested scopes and scope restoration. Reward/event services are stubbed in the offline regression; live combat acceptance is separate.

The narrow deployed patch preserves every baseline JAR entry except Monster, MapleMap and the legacy WorldStatistics.monster method; it adds MonsterDeathTracking and its Scope record. The full overlay builder now transforms 33 classes including MapleMap and runs the monster lifecycle regression from OverlaySmoke. Combined release builders must compile all runtime Java sources and use this worktree's statistics sources and resources.

Live baseline: 7821b2eb2e468daa7fd6ba7cd9488a8a03545e68ee1632217d03249cbdaed92e.
Candidate: 74ba63758b539472ea8bf5da8d3ebd8e65bb841133c4095a952774e66cc778df.

Activation and live observed counters are recorded in the deployment receipt, outside source control. A stats_coverage gap records the uncaptured interval; previous counters are preserved.
