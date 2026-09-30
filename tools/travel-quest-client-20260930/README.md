# Traveling Around Maple: client data candidate

Prepared from the installed playable v83 client's original `Quest.wz` using the existing MapleLib library. Corrected artifact installed September 30, 2026 at 13:14 PDT. No trainer EXE/DLL changes are included.

Edits are restricted to `Check.img`:

- Quests8053–8059: remove `start` and `end` date requirements.
- Quest8053 only: also remove `lvmax` and `pop` requirements.
- Sixteen properties removed; fourteen script markers added: `0/startscript=q8053s` through `q8059s`, and `1/endscript=q8053e` through `q8059e`. NPC references, preceding-quest `state=2` requirements and every other quest node are left as supplied to the WZ writer. This is not a claim of a completed binary round-trip comparison.

Installed/candidate `Quest.wz` SHA256: `8EBCFC476C7370AC6EC61A069E6951CDDF17C9C57A4CFCEE808B678C2125B00F`. The earlier `0348C406` artifact lacked script markers and was never installed.

`manifest.json` records build provenance and all 30 edits; its `installed=false` describes the build stage. `installation-receipt.json` records the subsequent successful installation. Builder: parent workspace `ops/build-travel-quest-client-patch.ps1`; installer: `ops/install-travel-quest-client-patch.ps1`.

**Untested at the user's explicit request:** no automated suite, output reparse comparison or gameplay check. Closed only the previously diagnosed hung PID18576 after checking its executable path and start time. Installed at `C:/Users/Lupert/Games/SoloMapling-v83/Quest.wz`; original backup is `backup-20260930T201404Z/Quest.wz`, SHA256 `41E1C0CBFEEA1ACB735DF91794C802143A4BFD25DB17BFE774039211AAA176E8`. Installed hash matches the corrected artifact. The game was not relaunched. GM owns the matching server release. The earlier trainer/native weekly checkpoint remains frozen separately.
