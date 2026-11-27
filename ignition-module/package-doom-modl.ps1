# package-doom-modl.ps1
# Packages the DOOM Perspective Ignition module into a deployable .modl file.
#
# Run from PowerShell (ExecutionPolicy Bypass if needed):
#   powershell -ExecutionPolicy Bypass -File ignition-module\package-doom-modl.ps1
#
# Staging is done in %TEMP% to avoid file-lock conflicts with open editors.
# Output: ignition-module\build\doom-perspective-vX.Y.Z.modl
#         ignition-module\build\doom-perspective-vX.Y.Z-signed.modl  (if a signing keystore exists)
#
# Signing: if signing\doom-signing.p12 (or $env:DOOM_SIGNING_KEYSTORE) exists, the module is also
# signed via signing\sign-modl.ps1. Create a keystore once with signing\new-signing-cert.ps1.
# Pass -NoSign to skip signing.

param([switch]$NoSign)

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

# Verify all inputs exist before touching anything (a missing JAR must not yield a partial .modl)
$inputs = [ordered]@{
    'module.xml'            = Join-Path $scriptDir 'module.xml'
    'common.jar'            = Join-Path $scriptDir 'common\target\common.jar'
    'gateway.jar'           = Join-Path $scriptDir 'gateway\target\gateway.jar'
    'headless-renderer.jar' = Join-Path $src       "headless-renderer\target\headless-renderer-$hrVer.jar"
}
$missing = @($inputs.Values | Where-Object { -not (Test-Path $_) })
if ($missing) {
    Write-Host -ForegroundColor Red "ERROR: Missing build output(s) - build the module first (see README):"
    $missing | ForEach-Object { Write-Host -ForegroundColor Red "  $_" }
    exit 1
}

# Clean and create staging dir
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage | Out-Null

# Ensure output dir exists
if (-not (Test-Path $out)) { New-Item -ItemType Directory -Path $out | Out-Null }

# Copy module files into staging dir
foreach ($name in $inputs.Keys) { Copy-Item $inputs[$name] (Join-Path $stage $name) -ErrorAction Stop }

Write-Host "Staged files:"
Get-ChildItem $stage | Format-Table Name, Length

# Zip staged files and copy as .modl
$zip  = Join-Path $env:TEMP "doom-perspective-v$ver.zip"
$modl = Join-Path $out      "doom-perspective-v$ver.modl"

if (Test-Path $zip)  { Remove-Item $zip  -Force }
if (Test-Path $modl) { Remove-Item $modl -Force }
$signedModl = $modl -replace '\.modl$', '-signed.modl'   # never leave a stale signed build behind
if (Test-Path $signedModl) { Remove-Item $signedModl -Force }

Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip -Force
Copy-Item $zip $modl

Write-Host ""
Write-Host "Done! Modl file:"
Get-Item $modl | Format-Table Name, Length

# Sign (optional)
$keystore = if ($env:DOOM_SIGNING_KEYSTORE) { $env:DOOM_SIGNING_KEYSTORE } else { Join-Path $scriptDir 'signing\doom-signing.p12' }
if ($NoSign) {
    Write-Host "Skipping signing (-NoSign)."
} elseif (Test-Path $keystore) {
    & (Join-Path $scriptDir 'signing\sign-modl.ps1') -ModlIn $modl
    if ($LASTEXITCODE -ne 0) { Write-Host -ForegroundColor Red "Signing failed; the unsigned module above is still available."; exit 1 }
} else {
    Write-Host "Module is UNSIGNED (no keystore at $keystore)."
    Write-Host "To produce a signed module, run once:  signing\new-signing-cert.ps1"
}
