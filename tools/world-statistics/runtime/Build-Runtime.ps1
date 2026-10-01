$ErrorActionPreference='Stop'
$repoRoot=[System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$jdk=Join-Path (Split-Path $repoRoot -Parent) 'tools\java21\jdk-21.0.12.1+1\bin'
$base=Join-Path $PSScriptRoot 'base\Server.jar'
$classes=Join-Path $PSScriptRoot 'classes'
$resources=Join-Path $PSScriptRoot 'resources'
$output=Join-Path $PSScriptRoot 'Server-statistics.jar'
[System.IO.Directory]::CreateDirectory($classes)|Out-Null
$buddy=Join-Path $env:USERPROFILE '.m2\repository\net\bytebuddy\byte-buddy\1.17.5\byte-buddy-1.17.5.jar'
$sources=@(Get-ChildItem (Join-Path $repoRoot 'src\main\java\server\statistics') -File -Filter '*.java')
$sources+=@(Get-ChildItem $PSScriptRoot -File -Filter '*.java')
$mockito=Join-Path $env:USERPROFILE '.m2\repository\org\mockito\mockito-core\5.18.0\mockito-core-5.18.0.jar'
$classPath=$base+[System.IO.Path]::PathSeparator+$buddy+[System.IO.Path]::PathSeparator+$mockito
$arguments=@('--release','21','-encoding','UTF-8','-cp',('"'+$classPath.Replace('\','/')+'"'),'-d',('"'+$classes.Replace('\','/')+'"'))
$arguments+=@($sources|ForEach-Object{'"'+$_.FullName.Replace('\','/')+'"'})
$argumentFile=Join-Path $PSScriptRoot 'javac.args'
[System.IO.File]::WriteAllLines($argumentFile,$arguments,[System.Text.UTF8Encoding]::new($false))
& (Join-Path $jdk 'javac.exe') ('@'+$argumentFile)
if($LASTEXITCODE -ne 0){throw 'Statistics overlay compilation failed'}
& (Join-Path $jdk 'java.exe') '-cp' ($classes+[System.IO.Path]::PathSeparator+$classPath) 'statistics.build.BuildOverlay' $base $classes $output $resources
if($LASTEXITCODE -ne 0){throw 'Statistics overlay generation failed'}
Write-Output ('Statistics JAR SHA256 '+(Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash)
