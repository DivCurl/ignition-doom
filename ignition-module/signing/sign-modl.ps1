# sign-modl.ps1
# Signs a .modl with Inductive Automation's module-signer, using the keystore created by
# new-signing-cert.ps1 (or any RSA code-signing keystore + .p7b chain, e.g. a CA-issued cert).
#
#   powershell -ExecutionPolicy Bypass -File ignition-module\signing\sign-modl.ps1 [-ModlIn <file>] [-ModlOut <file>]
#
# Defaults: signs ignition-module\build\doom-perspective-v<version>.modl (version from module.xml)
#           and writes doom-perspective-v<version>-signed.modl next to it.
#
# Keystore password: $env:DOOM_SIGNING_PASSWORD, or prompted for.
# Overrides: -Keystore/-Chain/-Alias, or $env:DOOM_SIGNING_KEYSTORE / DOOM_SIGNING_CHAIN / DOOM_SIGNING_ALIAS.
#   module-signer picks PKCS#12 for .p12/.pfx keystores and JKS for anything else.
#
# module-signer is downloaded once from IA's public Nexus into signing\tools\ (gitignored) and its
# SHA-1 is checked against the value pinned below. Requires: java on PATH.

param(
    [string]$ModlIn,
    [string]$ModlOut,
    [string]$Keystore = $(if ($env:DOOM_SIGNING_KEYSTORE) { $env:DOOM_SIGNING_KEYSTORE } else { Join-Path $PSScriptRoot 'doom-signing.p12' }),
    [string]$Chain    = $(if ($env:DOOM_SIGNING_CHAIN)    { $env:DOOM_SIGNING_CHAIN }    else { Join-Path $PSScriptRoot 'doom-signing.p7b' }),
    [string]$Alias    = $(if ($env:DOOM_SIGNING_ALIAS)    { $env:DOOM_SIGNING_ALIAS }    else { 'doom-signer' })
)

$ErrorActionPreference = 'Stop'
trap { Write-Host -ForegroundColor Red "ERROR: $_"; exit 1 }

# --- Pinned signer (IA public Nexus; matches the .sha1 published alongside it) ---
$SignerVersion = '0.0.1'
$SignerSha1    = 'ab947ffd2d4d8976f273d27a5897aa2287f89828'
$SignerUrl     = "https://nexus.inductiveautomation.com/repository/inductiveautomation-releases/com/inductiveautomation/ignitionsdk/module-signer/$SignerVersion/module-signer-$SignerVersion-jar-with-dependencies.jar"
$SignerJar     = Join-Path $PSScriptRoot "tools\module-signer-$SignerVersion.jar"

$moduleDir = Split-Path $PSScriptRoot -Parent
if (-not $ModlIn) {
    [xml]$xml = Get-Content (Join-Path $moduleDir 'module.xml')
    $ModlIn = Join-Path $moduleDir "build\doom-perspective-v$($xml.modules.module.version).modl"
}
if (-not $ModlOut) { $ModlOut = $ModlIn -replace '\.modl$', '-signed.modl' }

foreach ($f in @($ModlIn, $Keystore, $Chain)) {
    if (-not (Test-Path $f)) {
        Write-Host -ForegroundColor Red "Not found: $f`n(Run new-signing-cert.ps1 first if the keystore/chain is missing.)"
        exit 1
    }
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Write-Host -ForegroundColor Red 'java not found on PATH.'; exit 1 }

# --- Fetch + verify module-signer ---
function Test-SignerJar { (Test-Path $SignerJar) -and ((Get-FileHash $SignerJar -Algorithm SHA1).Hash -eq $SignerSha1) }
if (-not (Test-SignerJar)) {
    Write-Host "Downloading module-signer $SignerVersion from Inductive Automation Nexus..."
    New-Item -ItemType Directory -Force (Split-Path $SignerJar) | Out-Null
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $tmp = "$SignerJar.download"
    Invoke-WebRequest -Uri $SignerUrl -OutFile $tmp -UseBasicParsing
    $got = (Get-FileHash $tmp -Algorithm SHA1).Hash
    if ($got -ne $SignerSha1) {
        Remove-Item $tmp -Force
        Write-Host -ForegroundColor Red "module-signer checksum mismatch (expected $SignerSha1, got $got). Not using it."
        exit 1
    }
    Move-Item $tmp $SignerJar -Force
}

# --- Password ---
$pw = $env:DOOM_SIGNING_PASSWORD
if (-not $pw) { $pw = [Net.NetworkCredential]::new('', (Read-Host "Password for $(Split-Path $Keystore -Leaf)" -AsSecureString)).Password }

# --- Sign ---
# Note: module-signer only accepts passwords as command-line arguments, so the password is briefly
# visible to other processes run by the same user (e.g. in Task Manager command lines).
Write-Host "Signing $(Split-Path $ModlIn -Leaf) ..."
if (Test-Path $ModlOut) { Remove-Item $ModlOut -Force }
& java -jar $SignerJar "-keystore=$Keystore" "-keystore-pwd=$pw" "-alias=$Alias" "-alias-pwd=$pw" `
    "-chain=$Chain" "-module-in=$ModlIn" "-module-out=$ModlOut"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $ModlOut)) {
    Write-Host -ForegroundColor Red "module-signer failed (exit $LASTEXITCODE). Check the keystore password and alias '$Alias'."
    exit 1
}

# --- Sanity check: every original entry has a signature, chain is embedded ---
Add-Type -AssemblyName System.IO.Compression.FileSystem
$inZip  = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $ModlIn))
$outZip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $ModlOut))
try {
    $expected = $inZip.Entries | Where-Object { -not $_.FullName.EndsWith('/') } | ForEach-Object { '/' + $_.FullName }
    $sigEntry = $outZip.GetEntry('signatures.properties')
    if (-not $sigEntry -or -not $outZip.GetEntry('certificates.p7b')) { throw 'signed module is missing signatures.properties or certificates.p7b' }
    $reader = [IO.StreamReader]::new($sigEntry.Open())
    $signed = $reader.ReadToEnd() -split "`r?`n" | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object { ($_ -split '=', 2)[0] }
    $reader.Dispose()
    $missing = $expected | Where-Object { $signed -notcontains $_ }
    if ($missing) { throw "entries without a signature: $($missing -join ', ')" }
} finally { $inZip.Dispose(); $outZip.Dispose() }

$coll = [Security.Cryptography.X509Certificates.X509Certificate2Collection]::new()
$coll.Import((Resolve-Path $Chain).Path)
$cert = $coll[0]
$fp = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($cert.RawData)) -replace '-', ':'

Write-Host ""
Write-Host "Signed module: $ModlOut"
Write-Host "  Entries signed: $($expected.Count)"
Write-Host "  Certificate:    $($cert.Subject)  (expires $($cert.NotAfter.ToString('yyyy-MM-dd')))"
Write-Host "  SHA-256:        $fp"
