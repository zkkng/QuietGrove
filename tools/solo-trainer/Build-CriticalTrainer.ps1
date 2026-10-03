param([string]$OutputDirectory = (Join-Path $PSScriptRoot 'critical-release'))
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$trainerExe=Join-Path $OutputDirectory 'SoloTrainer.exe'
& 'C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe' /nologo /target:winexe /platform:x86 /optimize+ /warn:4 /define:CRITICAL_RELEASE /out:$trainerExe /reference:System.dll /reference:System.Core.dll /reference:System.Drawing.dll /reference:System.Windows.Forms.dll (Join-Path $PSScriptRoot 'SoloTrainer.cs')
if($LASTEXITCODE -ne 0){ throw 'Critical trainer compilation failed' }
Get-FileHash -Algorithm SHA256 -LiteralPath $trainerExe
