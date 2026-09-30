# PC Café return-scroll change — 2026-09-29

User request: return-to-town scrolls should work on every PC Café map and return to the lobby.

## Implemented locally

- PcCafe.isCafeMap recognizes the exact registered 15 hunting-map IDs and hub193000000; independent of reward configuration and event-instance membership.
- UseItemHandler routes actual203xxxx return-scroll items from recognized café fields to hub193000000 portal0.
- Existing inventory/item/quantity/alive validation remains in force.
- Remove exactlyone scroll only after the character actually reaches the hub.
- Missing destination/portal, rejectedwarp, alreadyhub, deadcharacter and forgeditem do not consume scrolls.
- Normalmaps keep original item-effect routing.
- Legacy ItemConstants.isTownScroll matches ALL itemIDs>=2030000; override deliberately constrained203category toavoid reclassifying unrelated highIDs.

## Verification

PcCafeReturnScrollTest:23passed,0failures/errors/skips. Includes everyregisteredroad, generic/namedtownscrolls, missinglobby, rejectedwarp, lobby/dead/forgeditem and normalmap.
Root Maven test -q exit0:3677tests,0failures/errors/skips after change. Scoped gitdiff--check clean except normalLF/CRLFnotice.
Offline ItemInformationProvider initialization follows existing GameplayTestData database-mock pattern; no liveDB needed ormodified.

## Remaining rollout gate — do not claim live complete

No serverdeployment/clientplaytest performed.
Every café huntingmap serverXML has fieldLimit270972 (includes CANNOTMIGRATE0x10); lobby8192. Existing clientWZ may prevent scrolling beforepacket reacheshandler. Need verifyactualnormalclient use on café map afterstaging; ifblocked, implement a reviewed CLIENT override limited to actual203scrolls andexactcaféIDs or an appropriatelyscopedasset solution. Simplyclearingmigrationflag also enableschannel/cashshop migration and is NOT a narrow equivalent; do not blindlyremoveitglobally.
ExistingEzorsia source declares dwTeleFieldLimit0x00957BB7 but no activeusefound; do notpatchunknownaddress withoutverifiedsource/behavior.
ServerreturnMap already193000000 onexamined190000000; thisroutingalsohandlesnamedtownscrolls whoseoriginaldestinationiselsewhere.

Files:
src/main/java/server/pccafe/PcCafe.java
src/main/java/net/server/channel/handlers/UseItemHandler.java
src/test/java/net/server/channel/handlers/PcCafeReturnScrollTest.java

