[CmdletBinding()]
param(
    [string]$CredentialsPath = 'C:\Users\Lupert\Documents\Codex\Homelab\secrets\credentials.md',
    [Parameter(Mandatory=$true)][string]$ExpectedPid,
    [Parameter(Mandatory=$true)][string]$ExpectedJarSha256,
    [Parameter(Mandatory=$true)][string]$Agent,
    [ValidateSet('spawn','status','off')][string]$Action = 'status'
)

$ErrorActionPreference = 'Stop'
$plink = 'C:\Program Files\PuTTY\plink.exe'
$pscp = 'C:\Program Files\PuTTY\pscp.exe'
if ($ExpectedPid -cnotmatch '^[1-9][0-9]*$' -or $ExpectedJarSha256 -cnotmatch '^[a-fA-F0-9]{64}$') { throw 'Invalid PID/hash' }
$hostKey = 'SHA256:VOJNMlHaLbrCNWbT37V2+dzI1iqXZw0T3yEL2Qw09g0'
foreach ($path in @($plink,$pscp,$agent,$CredentialsPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing trial input: $path" }
}
$agentHash = (Get-FileHash -LiteralPath $agent -Algorithm SHA256).Hash.ToLowerInvariant()
$name = 'hybrid-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$hostAgent = "/var/lib/vz/dump/$name.jar"
$guestAgent = "/opt/solomapling/staging/$name.jar"
$guestReport = "/opt/solomapling/logs/event-capacity/$name.txt"
$credentialText = Get-Content -Raw -LiteralPath $CredentialsPath
$section = [regex]::Match($credentialText,'(?ms)^## Proxmox 5700X3D RomM/game host\s+(.*?)(?=^## |\z)').Groups[1].Value
$password = [regex]::Match($section,'(?m)^- Password: `([^`]+)`$').Groups[1].Value
if (-not $password) { throw 'Retained host credential unavailable.' }
try {
    & $pscp -batch -scp -hostkey $hostKey -pw $password $agent "root@192.168.1.95:$hostAgent"
    if ($LASTEXITCODE -ne 0) { throw 'Trial agent upload failed.' }
    $remote = @"
set -euo pipefail
test "`$(sha256sum '$hostAgent' | cut -d ' ' -f1)" = '$agentHash'
test "`$(pct exec 202 -- systemctl show solomapling.service -p MainPID --value)" = '$ExpectedPid'
test "`$(pct exec 202 -- sha256sum /opt/solomapling/Server.jar | cut -d ' ' -f1)" = '$ExpectedJarSha256'
pct exec 202 -- mkdir -p /opt/solomapling/staging
pct push 202 '$hostAgent' '$guestAgent'
test "`$(pct exec 202 -- sha256sum '$guestAgent' | cut -d ' ' -f1)" = '$agentHash'
pct exec 202 -- sh -lc "cd /opt/solomapling && java --add-modules jdk.attach -cp '$guestAgent' hybrid.build.AttachTrial '$ExpectedPid' '$guestAgent' '$($Action):$name'"
for attempt in {1..25}; do
  if pct exec 202 -- test -s '$guestReport'; then break; fi
  sleep 2
done
pct exec 202 -- test -s '$guestReport'
pct exec 202 -- sha256sum '$guestReport'
pct exec 202 -- cat '$guestReport'
pct exec 202 -- grep -q '^result=ok' '$guestReport'
"@
    & $plink -batch -ssh -hostkey $hostKey -pw $password root@192.168.1.95 $remote
    if ($LASTEXITCODE -ne 0) { throw "Trial attach/read failed: $LASTEXITCODE" }
} finally {
    $password = $null
    $credentialText = $null
    $section = $null
}
