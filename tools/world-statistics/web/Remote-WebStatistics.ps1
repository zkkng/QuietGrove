param([ValidateSet('probe','deploy','verify')][string]$Mode='probe')
$ErrorActionPreference='Stop'
$plink='C:\Program Files\PuTTY\plink.exe'
$pscp='C:\Program Files\PuTTY\pscp.exe'
$key='SHA256:VOJNMlHaLbrCNWbT37V2+dzI1iqXZw0T3yEL2Qw09g0'
$credentialText=Get-Content -Raw -LiteralPath 'C:\Users\Lupert\Documents\Codex\Homelab\secrets\credentials.md'
$section=[regex]::Match($credentialText,'(?ms)^## Proxmox 5700X3D RomM/game host\s+(.*?)(?=^## |\z)').Groups[1].Value
$password=[regex]::Match($section,'(?m)^- Password: \x60([^\x60]+)\x60$').Groups[1].Value
if(-not $password){throw 'Retained host credential unavailable'}
try{
 $helper=Join-Path $PSScriptRoot $(if($Mode -eq 'probe'){'probe_live.py'}elseif($Mode -eq 'deploy'){'install_web.py'}else{'verify_live.py'})
 & $pscp -q -batch -scp -hostkey $key -pw $password $helper 'root@192.168.1.95:/var/lib/vz/dump/ws-web-helper.py'
 if($LASTEXITCODE -ne 0){throw 'Helper upload failed'}
 if($Mode -eq 'deploy'){
  & $pscp -q -batch -scp -hostkey $key -pw $password (Join-Path $PSScriptRoot 'release.tar') 'root@192.168.1.95:/var/lib/vz/dump/ws-web-release.tar'
  if($LASTEXITCODE -ne 0){throw 'Release upload failed'}
 }
 $command='set -e; pct push 202 /var/lib/vz/dump/ws-web-helper.py /tmp/ws-web-helper.py'
 if($Mode -eq 'deploy'){$command+='; pct push 202 /var/lib/vz/dump/ws-web-release.tar /tmp/ws-web-release.tar'}
 $command+='; pct exec 202 -- python3 /tmp/ws-web-helper.py'
 if($Mode -eq 'probe'){$command+='; pct pull 202 /tmp/world-stats-web-base.tar /var/lib/vz/dump/world-stats-web-base.tar'}
 & $plink -batch -ssh -hostkey $key -pw $password root@192.168.1.95 $command
 if($LASTEXITCODE -ne 0){throw 'Remote statistics website action failed'}
 if($Mode -eq 'probe'){
  & $pscp -q -batch -scp -hostkey $key -pw $password 'root@192.168.1.95:/var/lib/vz/dump/world-stats-web-base.tar' (Join-Path $PSScriptRoot 'live-base.tar')
  if($LASTEXITCODE -ne 0){throw 'Live source download failed'}
 }
}finally{$password=$null;$credentialText=$null;$section=$null}
