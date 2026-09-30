[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9][a-z0-9-]{0,40}$')][string]$TrialId,
    [ValidateRange(60,7200)][int]$Seconds = 600
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$captureTool = Join-Path $PSScriptRoot 'measurement-tools/PresentMon-2.6.0-x64.exe'
if (-not (Test-Path -LiteralPath $captureTool -PathType Leaf)) { throw 'Verified PresentMon tool is unavailable.' }
$destination = Join-Path $repo "docs/event-capacity-results/$TrialId-frames.csv"
if (Test-Path -LiteralPath $destination) { throw 'Trial capture already exists; use a fresh trial ID.' }
Write-Host "Capturing actual MapleStory.exe presents for $Seconds seconds to $destination"
Write-Host 'ETW access must already be available to this PowerShell session; this script does not request elevation.'
& $captureTool --process_name MapleStory.exe --session_name "SoloMapling-$TrialId" --output_file $destination --v1_metrics --timed $Seconds --terminate_after_timed --no_console_stats
if ($LASTEXITCODE -ne 0) { throw "PresentMon exited with code $LASTEXITCODE; the capture is not acceptance evidence." }
if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) { throw 'PresentMon did not create the capture.' }
$hash=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "Capture SHA256 $hash"
