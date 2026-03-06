# package-doom-modl.ps1
# Packages the DOOM Perspective Ignition module into a deployable .modl file.
#
# Run from PowerShell (ExecutionPolicy Bypass if needed):
#   powershell -ExecutionPolicy Bypass -File ignition-module\package-doom-modl.ps1
#
# Staging is done in %TEMP% to avoid file-lock conflicts with open editors.
# Output: ignition-module\build\doom-perspective-vX.Y.Z.modl

$scriptDir = $PSScriptRoot                              # <repo>/ignition-module/
$src       = Split-Path $scriptDir -Parent              # <repo>/
$out       = Join-Path $scriptDir 'build'               # <repo>/ignition-module/build/
$stage     = Join-Path $env:TEMP 'doom-modl-stage'      # %TEMP%/doom-modl-stage/

# Read version from module.xml (used for .modl filename and display)
[xml]$xml = Get-Content (Join-Path $scriptDir 'module.xml')
$ver = $xml.modules.module.version

# Read headless-renderer JAR version from its own pom.xml (may differ from module version)
[xml]$hrPom = Get-Content (Join-Path $src 'headless-renderer\pom.xml')
$hrVer = $hrPom.project.version

Write-Host ""
Write-Host "============================================================"
Write-Host "  DOOM Perspective Module Packager  v$ver"
Write-Host "============================================================"
Write-Host "  Source:  $src"
Write-Host "  Stage:   $stage"
Write-Host "  Output:  $out"
Write-Host "============================================================"
Write-Host ""

# Clean and create staging dir
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage | Out-Null

# Ensure output dir exists
if (-not (Test-Path $out)) { New-Item -ItemType Directory -Path $out | Out-Null }

# Copy module files into staging dir
Copy-Item (Join-Path $scriptDir 'module.xml')                                               (Join-Path $stage 'module.xml')
Copy-Item (Join-Path $scriptDir 'common\target\common.jar')                                 (Join-Path $stage 'common.jar')
Copy-Item (Join-Path $scriptDir 'gateway\target\gateway.jar')                               (Join-Path $stage 'gateway.jar')
Copy-Item (Join-Path $src        "headless-renderer\target\headless-renderer-$hrVer.jar")   (Join-Path $stage 'headless-renderer.jar')

Write-Host "Staged files:"
Get-ChildItem $stage | Format-Table Name, Length

# Zip staged files and copy as .modl
$zip  = Join-Path $env:TEMP "doom-perspective-v$ver.zip"
$modl = Join-Path $out      "doom-perspective-v$ver.modl"

if (Test-Path $zip)  { Remove-Item $zip  -Force }
if (Test-Path $modl) { Remove-Item $modl -Force }

Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip -Force
Copy-Item $zip $modl

Write-Host ""
Write-Host "Done! Modl file:"
Get-Item $modl | Format-Table Name, Length
