param([string]$GameDirectory='C:\Users\Lupert\Games\SoloMapling-v83')
$ErrorActionPreference='Stop'
if(-not [Environment]::Is64BitOperatingSystem){throw 'This fix requires 64-bit Windows'}
$clientPath=Join-Path $GameDirectory 'MapleStory.exe'
$candidate=Join-Path $PSScriptRoot 'build\large-address\MapleStory.exe'
$originalHash='ED5A699407B9705528B6A653CDEE7395E6E1B7317B9711DCFB34681092072848'
$patchedHash='766A9485532AA20D054278F3430AB8246D8337CF36A035906C9BC503EA357E18'
foreach($gameProcess in @(Get-Process MapleStory -ErrorAction SilentlyContinue)) {
    if(-not $gameProcess.HasExited){throw 'Close the game before changing the client header'}
}
if((Get-FileHash -LiteralPath $clientPath -Algorithm SHA256).Hash -ne $originalHash){throw 'Playable client identity changed'}
if((Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash -ne $patchedHash){throw 'Candidate identity changed'}
$before=[IO.File]::ReadAllBytes($clientPath)
$after=[IO.File]::ReadAllBytes($candidate)
if($before.Length -ne $after.Length){throw 'Candidate size differs'}
$differences=0
for($i=0;$i -lt $before.Length;$i++){
    if($before[$i] -ne $after[$i]) {
        if($i -ne 318 -or $before[$i] -ne 15 -or $after[$i] -ne 47){throw 'Unexpected byte difference'}
        $differences++
    }
}
if($differences -ne 1){throw 'Expected exactly one PE header byte change'}
$stamp=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$receiptDirectory=Join-Path $PSScriptRoot ('build\large-address\installation-'+$stamp)
New-Item -ItemType Directory -Path $receiptDirectory | Out-Null
$backup=Join-Path $receiptDirectory 'MapleStory.original.exe'
Copy-Item -LiteralPath $clientPath -Destination $backup
if((Get-FileHash -LiteralPath $backup).Hash -ne $originalHash){throw 'Backup verification failed'}
$preserved=@{}
foreach($name in @('dinput8.dll','SoloClientDiagnostics.dll','config.ini','Launch SoloMapling.cmd')){
    $preserved[$name]=(Get-FileHash -LiteralPath (Join-Path $GameDirectory $name)).Hash
}
try {
    Copy-Item -LiteralPath $candidate -Destination $clientPath -Force
    if((Get-FileHash -LiteralPath $clientPath).Hash -ne $patchedHash){throw 'Installed hash verification failed'}
    foreach($name in $preserved.Keys){
        if((Get-FileHash -LiteralPath (Join-Path $GameDirectory $name)).Hash -ne $preserved[$name]){throw ('Preserved file changed: '+$name)}
    }
} catch {
    Copy-Item -LiteralPath $backup -Destination $clientPath -Force
    throw
}
[ordered]@{installedUtc=[DateTime]::UtcNow.ToString('o');client=$clientPath;originalSha256=$originalHash;installedSha256=$patchedHash;backup=$backup;changedBytes=1;preserved=$preserved;startupVerified=$false;gameplayVerified=$false} |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $receiptDirectory 'receipt.json')
Write-Output $receiptDirectory
