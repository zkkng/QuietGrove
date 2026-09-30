param([string]$OutputDirectory = (Join-Path $PSScriptRoot 'bin'), [switch]$CompileOnly)
$ErrorActionPreference='Stop'
$trainerSource=Join-Path $PSScriptRoot 'SoloTrainer.cs'
$trainerOut=$OutputDirectory
if(!(Test-Path -LiteralPath $trainerOut)){ New-Item -ItemType Directory -Path $trainerOut | Out-Null }
$trainerExe=Join-Path $trainerOut 'SoloTrainer.exe'
$trainerCompiler='C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe'
& $trainerCompiler /nologo /target:winexe /platform:x86 /optimize+ /warn:4 /out:$trainerExe /reference:System.dll /reference:System.Core.dll /reference:System.Drawing.dll /reference:System.Windows.Forms.dll $trainerSource
if($LASTEXITCODE -ne 0){ throw 'Trainer compilation failed' }
if($CompileOnly){ Get-FileHash -Algorithm SHA256 -LiteralPath $trainerExe; return }
& $trainerExe --self-test
if($LASTEXITCODE -ne 0){ throw 'Trainer wire self-test failed' }
& $trainerExe --render-check (Join-Path $trainerOut 'ui-check.png')
if($LASTEXITCODE -ne 0){ throw 'Trainer render check failed' }
& $trainerExe --render-check (Join-Path $trainerOut 'loot-check.png') 4
if($LASTEXITCODE -ne 0){ throw 'Trainer loot render check failed' }
& $trainerExe --render-check (Join-Path $trainerOut 'combat-check.png') 1
if($LASTEXITCODE -ne 0){ throw 'Trainer combat render check failed' }
& $trainerExe --render-check (Join-Path $trainerOut 'visual-check.png') 7
if($LASTEXITCODE -ne 0){ throw 'Trainer visual render check failed' }
& $trainerExe --render-check (Join-Path $trainerOut 'powers-check.png') 10
if($LASTEXITCODE -ne 0){ throw 'Trainer powers render check failed' }
& $trainerExe --render-check (Join-Path $trainerOut 'pet-loot-check.png') 11
if($LASTEXITCODE -ne 0){ throw 'Trainer pet loot render check failed' }
Get-FileHash -Algorithm SHA256 -LiteralPath $trainerExe
