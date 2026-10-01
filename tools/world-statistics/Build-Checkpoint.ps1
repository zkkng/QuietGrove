$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$buildRoot = Join-Path $PSScriptRoot 'build'
$classes = Join-Path $buildRoot 'classes'
[System.IO.Directory]::CreateDirectory($classes) | Out-Null
$jdk = Join-Path (Split-Path $repoRoot -Parent) 'tools\java21\jdk-21.0.12.1+1'
$repository = Join-Path $env:USERPROFILE '.m2\repository'
$jarNames = @(
'org\junit\jupiter\junit-jupiter-api\5.13.1\junit-jupiter-api-5.13.1.jar',
'org\junit\jupiter\junit-jupiter-engine\5.13.1\junit-jupiter-engine-5.13.1.jar',
'org\junit\platform\junit-platform-commons\1.13.1\junit-platform-commons-1.13.1.jar',
'org\junit\platform\junit-platform-engine\1.13.1\junit-platform-engine-1.13.1.jar',
'org\junit\platform\junit-platform-launcher\1.13.1\junit-platform-launcher-1.13.1.jar',
'org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar',
'org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar'
)
$jars = @($jarNames | ForEach-Object {
    $path = Join-Path $repository $_
    if (-not (Test-Path -LiteralPath $path)) { throw ('Local test dependency missing: '+$path) }
    $path
})
$classPath = ($jars -join [System.IO.Path]::PathSeparator) + [System.IO.Path]::PathSeparator + (Join-Path $PSScriptRoot 'runtime\base\Server.jar')
$sources = @(
    Get-ChildItem (Join-Path $repoRoot 'src\main\java\server\statistics') -Filter '*.java'
    Get-ChildItem (Join-Path $repoRoot 'src\test\java\server\statistics') -Filter '*.java'
    Get-Item (Join-Path $PSScriptRoot 'StatisticsCheckpointRunner.java')
)
$argumentFile = Join-Path $buildRoot 'javac.args'
$arguments = @('--release','21','-encoding','UTF-8','-cp',('"'+$classPath.Replace('\','/')+'"'),
               '-d',('"'+$classes.Replace('\','/')+'"'))
$arguments += @($sources | ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' })
[System.IO.File]::WriteAllLines($argumentFile,$arguments,[System.Text.UTF8Encoding]::new($false))
& (Join-Path $jdk 'bin\javac.exe') ('@'+$argumentFile)
if ($LASTEXITCODE -ne 0) { throw 'Isolated statistics compilation failed.' }
$summaryPath = Join-Path $buildRoot 'test-summary.json'
& (Join-Path $jdk 'bin\java.exe') '-cp' ($classes+[System.IO.Path]::PathSeparator+$classPath) 'server.statistics.checkpoint.StatisticsCheckpointRunner' $summaryPath
if ($LASTEXITCODE -ne 0) { throw 'Focused statistics tests failed.' }
$artifact = Join-Path $buildRoot 'statistics-checkpoint-tests.jar'
& (Join-Path $jdk 'bin\jar.exe') '--create' '--file' $artifact '-C' $classes '.'
if ($LASTEXITCODE -ne 0) { throw 'Checkpoint test artifact failed.' }
$owned = @($sources)
$owned += @(Get-ChildItem $PSScriptRoot -File | Where-Object { $_.Extension -in '.py','.ps1','.md' })
$owned += @(Get-ChildItem (Join-Path $PSScriptRoot 'staged') -File)
$manifest = [ordered]@{
    goalIds=@('SITE-06','CONTENT-01')
    scope='isolated-source-and-contract-tests; no game hooks, migrations, root Maven, deployment or performance acceptance'
    javaRelease=21
    artifact=(Split-Path $artifact -Leaf)
    artifactSha256=(Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    tests=(Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json)
    files=@($owned | Sort-Object FullName | ForEach-Object {
        [ordered]@{path=[System.IO.Path]::GetRelativePath($repoRoot,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
}
[System.IO.File]::WriteAllText((Join-Path $buildRoot 'checkpoint-manifest.json'),($manifest | ConvertTo-Json -Depth 10)+[Environment]::NewLine,[System.Text.UTF8Encoding]::new($false))
Write-Output ('Checkpoint SHA256 '+$manifest.artifactSha256)
