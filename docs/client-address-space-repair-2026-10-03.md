# October 3 client address-space crash repair

## Captured cause

Client PID40692 crashed at 2026-10-03 09:48:50.831764 UTC. The first exception is C0000005 writing NULL at ZLZ.dll+0x3E07, during Canvas resource allocation. The bounded instruction capture shows `GetProcessHeap` followed by `RtlAllocateHeap` for 0x8004 bytes, then `mov [eax], edi` with EAX=0. The imports were resolved using their exact module base/RVA and matching system exports. A second deliberate NULL write during incomplete resource cleanup is secondary.

The dump's complete MemoryInfo stream shows the client reached the 2 GB address limit: 1,962,901,504 bytes committed, 169,615,360 reserved, 14,901,248 free, and largest free region only 65,536 bytes. These sum to exactly 2 GB. This establishes virtual address exhaustion/fragmentation rather than inferring it from private working memory alone. It is the same fault and Canvas call path as the prior trainer-disabled baseline failure.

Raw dumps and telemetry remain local. Neither private working memory nor physical RAM capacity alone explains the failed allocation. Cache growth and fragmentation beyond this limit repair are not claimed resolved.

## Prepared fix

The existing playable EXE is PE32/x86, SHA256 `ED5A699407B9705528B6A653CDEE7395E6E1B7317B9711DCFB34681092072848`, without IMAGE_FILE_LARGE_ADDRESS_AWARE. On 64-bit Windows, setting that flag permits a 4 GB user address range.

`prepare_large_address_client.py` pins the original identity and validates the PE headers, exact one-byte difference and exact rollback. The sole change is file offset318: 0F -> 2F, characteristics 010F -> 012F. Candidate SHA256 `766A9485532AA20D054278F3430AB8246D8337CF36A035906C9BC503EA357E18`.

`install_large_address_client.ps1` requires a closed game and 64-bit Windows, verifies both identities, backs up the original EXE, checks every changed byte, installs the candidate and preserves DLLs/config/launcher. It records a local receipt and rolls back on installation failure. No native code, trainer hooks, game assets or server changes are included.

## Installed and verified

Installed at 2026-10-03 09:58:16 UTC. The first overwrite attempt encountered a Windows retained image lock without changing the original. The committed installer now uses a same-directory rename, then creates the replacement under the original playable filename. Original image rollback: `C:/Users/Lupert/Games/SoloMapling-v83/MapleStory.pre-large-address-20261003T095816Z.exe`. A second verified backup and local receipt are in `tools/client-diagnostics/build/large-address/installation-20261003T095816Z/`.

Existing launcher, config.ini, dinput8.dll (D32E9A12...) and diagnostics DLL (AB783D8B...) hashes were preserved. The trainer EXE remains unchanged; the separately prepared Mouse Fly EXE is still blocked by Windows Application Control and is not installed.

One controlled launch through `Launch SoloMapling.cmd` reached the actual login screen, PID2856, start09:58:45 UTC. Native Fly/Unlimited/Rapid hooks reported ready, and diagnostics fault/code files remained empty. No authentication or gameplay was automated.

`capture_address_space.ps1` captured a read-only MiniDumpWithFullMemoryInfo snapshot, then `analyze_address_space.py` proved the process address range now spans exactly4GB. It had 1,633,198,080 free bytes and a largest contiguous free region of 1,144,410,112 bytes. This proves the flag is active in the running client rather than merely present in the file. The initial generic stack capture used ThreadInfo without FullMemoryInfo and could not establish this; the dedicated capture explicitly uses both flags.

The client is left at login. Sustained gameplay stability and any continued resource growth remain unverified. No claim is made that every possible client crash is fixed.
