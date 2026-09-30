[CmdletBinding()]
param([ValidateSet('start','stop','status')][string]$Action='start')
$ErrorActionPreference='Stop'
$gmRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$gmAgent=Join-Path $PSScriptRoot 'build\henesys-managed-20260930.jar'
$gmAgentHash='9CEF19BCDBC69158C590D73015412B7AD7BBEEB3C3ECC70594DDB4ADEF999C81'
$gmKey='SHA256:VOJNMlHaLbrCNWbT37V2+dzI1iqXZw0T3yEL2Qw09g0'
if((Get-FileHash -LiteralPath $gmAgent -Algorithm SHA256).Hash -ne $gmAgentHash){throw 'Managed control artifact changed'}
$gmSecretText=Get-Content -Raw -LiteralPath 'C:\Users\Lupert\Documents\Codex\Homelab\secrets\credentials.md'
$gmSection=[regex]::Match($gmSecretText,'(?ms)^## Proxmox 5700X3D RomM/game host\s+(.*?)(?=^## |\z)').Groups[1].Value
$gmPassword=[regex]::Match($gmSection,'(?m)^- Password: `([^`]+)`$').Groups[1].Value
if(!$gmPassword){throw 'Retained own-server credential unavailable'}
try {
    & 'C:\Program Files\PuTTY\pscp.exe' -batch -scp -hostkey $gmKey -pw $gmPassword $gmAgent 'root@192.168.1.95:/var/lib/vz/dump/henesys-managed-20260930.jar'
    if($LASTEXITCODE){throw 'Managed control upload failed'}
    $gmRemote=('set -eu; pct push 202 /var/lib/vz/dump/henesys-managed-20260930.jar /opt/solomapling/henesys-managed-20260930.jar; pct exec 202 -- chown solomapling:solomapling /opt/solomapling/henesys-managed-20260930.jar; gm_pid=$(pct exec 202 -- systemctl show solomapling -p MainPID --value); test "$gm_pid" -gt 0; pct exec 202 -- runuser -u solomapling -- java --add-modules jdk.attach -cp /opt/solomapling/henesys-managed-20260930.jar gmevents.AttachProbe "$gm_pid" /opt/solomapling/henesys-managed-20260930.jar {0}; pct exec 202 -- bash -lc ''ls -t /opt/solomapling/logs/event-capacity/henesys-managed-*.txt | head -n1 | xargs -r cat''' -f $Action)
    & 'C:\Program Files\PuTTY\plink.exe' -batch -ssh -hostkey $gmKey -pw $gmPassword 'root@192.168.1.95' $gmRemote
    if($LASTEXITCODE){throw 'Managed event action failed; see server diagnostics'}
} finally {$gmPassword=$null;$gmSecretText=$null;$gmSection=$null}
