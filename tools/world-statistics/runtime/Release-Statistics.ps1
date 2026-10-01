param([ValidateSet('stage','apply','inspect')][string]$Mode='inspect')
$ErrorActionPreference='Stop'
$plink='C:\Program Files\PuTTY\plink.exe'
$pscp='C:\Program Files\PuTTY\pscp.exe'
$key='SHA256:VOJNMlHaLbrCNWbT37V2+dzI1iqXZw0T3yEL2Qw09g0'
$jar=Join-Path $PSScriptRoot 'Server-statistics.jar'
$installer=Join-Path $PSScriptRoot 'install_statistics.py'
$sha=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
$prefix=$sha.Substring(0,12)
$credentialText=Get-Content -Raw -LiteralPath 'C:\Users\Lupert\Documents\Codex\Homelab\secrets\credentials.md'
$section=[regex]::Match($credentialText,'(?ms)^## Proxmox 5700X3D RomM/game host\s+(.*?)(?=^## |\z)').Groups[1].Value
$password=[regex]::Match($section,'(?m)^- Password: \x60([^\x60]+)\x60$').Groups[1].Value
if(-not $password){throw 'Retained host credential unavailable'}
try{
 & $pscp -q -batch -scp -hostkey $key -pw $password $installer "root@192.168.1.95:/var/lib/vz/dump/ws-$prefix.py"
 if($LASTEXITCODE -ne 0){throw 'Installer upload failed'}
 if($Mode -eq 'stage'){
  & $pscp -q -batch -scp -hostkey $key -pw $password $jar "root@192.168.1.95:/var/lib/vz/dump/ws-$prefix.jar"
  if($LASTEXITCODE -ne 0){throw 'JAR upload failed'}
 }
 $remote="set -e; pct exec 202 -- mkdir -p /opt/solomapling/staging/ws-$prefix; pct push 202 /var/lib/vz/dump/ws-$prefix.py /opt/solomapling/staging/ws-$prefix/install.py"
 if($Mode -eq 'stage'){$remote+="; pct push 202 /var/lib/vz/dump/ws-$prefix.jar /opt/solomapling/staging/ws-$prefix/Server.jar; pct exec 202 -- chown -R solomapling:solomapling /opt/solomapling/staging/ws-$prefix"}
 $remote+="; pct exec 202 -- python3 /opt/solomapling/staging/ws-$prefix/install.py $Mode --jar /opt/solomapling/staging/ws-$prefix/Server.jar --sha $sha"
 & $plink -batch -ssh -hostkey $key -pw $password root@192.168.1.95 $remote
 if($LASTEXITCODE -ne 0){throw 'Statistics remote action failed'}
}finally{$password=$null;$credentialText=$null;$section=$null}
