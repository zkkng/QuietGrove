param([Parameter(Mandatory=$true)][int]$GameProcessId)
$ErrorActionPreference='Stop'
$gameProcess=Get-Process -Id $GameProcessId
if($gameProcess.HasExited -or $gameProcess.Path -ne 'C:\Users\Lupert\Games\SoloMapling-v83\MapleStory.exe'){throw 'Unexpected game process'}
$capturedHash=(Get-FileHash -LiteralPath $gameProcess.Path).Hash
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class ClientAddressCapture {
    [DllImport("kernel32.dll", SetLastError=true)] public static extern IntPtr OpenProcess(uint access, bool inherit, uint id);
    [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr handle);
    [DllImport("dbghelp.dll", SetLastError=true)] public static extern bool MiniDumpWriteDump(IntPtr process, uint id, IntPtr file, uint type, IntPtr exception, IntPtr userStream, IntPtr callback);
}
'@
$directory=Join-Path $PSScriptRoot ('build\address-capture-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))
New-Item -ItemType Directory -Path $directory | Out-Null
$handle=[ClientAddressCapture]::OpenProcess(0x410,$false,$GameProcessId)
if($handle -eq [IntPtr]::Zero){throw 'Cannot open client for read-only capture'}
$stream=[IO.File]::Create((Join-Path $directory 'address-space.dmp'))
try {
    # MiniDumpWithFullMemoryInfo=0x800, not MiniDumpWithThreadInfo=0x1000.
    if(-not [ClientAddressCapture]::MiniDumpWriteDump($handle,$GameProcessId,$stream.SafeFileHandle.DangerousGetHandle(),0x1800,[IntPtr]::Zero,[IntPtr]::Zero,[IntPtr]::Zero)){
        throw ('Memory info capture failed: '+[Runtime.InteropServices.Marshal]::GetLastWin32Error())
    }
} finally {$stream.Dispose();[void][ClientAddressCapture]::CloseHandle($handle)}
[ordered]@{pid=$GameProcessId;startUtc=$gameProcess.StartTime.ToUniversalTime().ToString('o');exeSha256=$capturedHash;dumpType='MiniDumpWithFullMemoryInfo | MiniDumpWithThreadInfo';readOnly=$true} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $directory 'receipt.json')
Write-Output $directory
