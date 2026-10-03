# October 3 client address-space crash repair

## Captured cause

Client PID40692 crashed at 2026-10-03 09:48:50.831764 UTC. The first exception is C0000005 writing NULL at ZLZ.dll+0x3E07, during Canvas resource allocation. The bounded instruction capture shows `GetProcessHeap` followed by `RtlAllocateHeap` for 0x8004 bytes, then `mov [eax], edi` with EAX=0. The imports were resolved using their exact module base/RVA and matching system exports. A second deliberate NULL write during incomplete resource cleanup is secondary.

The dump's complete MemoryInfo stream shows the client reached the 2 GB address limit: 1,962,901,504 bytes committed, 169,615,360 reserved, 14,901,248 free, and largest free region only 65,536 bytes. These sum to exactly 2 GB. This establishes virtual address exhaustion/fragmentation rather than inferring it from private working memory alone. It is the same fault and Canvas call path as the prior trainer-disabled baseline failure.

Raw dumps and telemetry remain local. Neither private working memory nor physical RAM capacity alone explains the failed allocation. Cache growth and fragmentation beyond this limit repair are not claimed resolved.

## Prepared fix

The existing playable EXE is PE32/x86, SHA256 `ED5A699407B9705528B6A653CDEE7395E6E1B7317B9711DCFB34681092072848`, without IMAGE_FILE_LARGE_ADDRESS_AWARE. On 64-bit Windows, setting that flag permits a 4 GB user address range.

`prepare_large_address_client.py` pins the original identity and validates the PE headers, exact one-byte difference and exact rollback. The sole change is file offset318: 0F -> 2F, characteristics 010F -> 012F. Candidate SHA256 `766A9485532AA20D054278F3430AB8246D8337CF36A035906C9BC503EA357E18`.

`install_large_address_client.ps1` requires a closed game and 64-bit Windows, verifies both identities, backs up the original EXE, checks every changed byte, installs the candidate and preserves DLLs/config/launcher. It records a local receipt and rolls back on installation failure. No native code, trainer hooks, game assets or server changes are included.

Preparation and static validation have passed. Installation, startup and additional address-range verification are separate steps. Sustained gameplay stability remains unverified until played.
