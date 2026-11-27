# new-signing-cert.ps1
# One-time setup: creates a self-signed RSA code-signing certificate for signing the .modl.
#
#   powershell -ExecutionPolicy Bypass -File ignition-module\signing\new-signing-cert.ps1
#
# Produces (both gitignored — NEVER commit the .p12, it contains the private key):
#   ignition-module\signing\doom-signing.p12   PKCS#12 keystore (private key + certificate)
#   ignition-module\signing\doom-signing.p7b   PKCS#7 certificate chain (public; embedded in signed modules)
#
# The keystore password is read from $env:DOOM_SIGNING_PASSWORD, or prompted for.
# Requires: keytool (ships with the JDK used for the Maven build).

param(
    [string]$Subject,                       # X.500 DN; default derived from module.xml
    [int]   $ValidityDays = 3650,
    [string]$Keystore     = $(if ($env:DOOM_SIGNING_KEYSTORE) { $env:DOOM_SIGNING_KEYSTORE } else { Join-Path $PSScriptRoot 'doom-signing.p12' }),
    [string]$Chain        = $(if ($env:DOOM_SIGNING_CHAIN)    { $env:DOOM_SIGNING_CHAIN }    else { Join-Path $PSScriptRoot 'doom-signing.p7b' }),
    [string]$Alias        = $(if ($env:DOOM_SIGNING_ALIAS)    { $env:DOOM_SIGNING_ALIAS }    else { 'doom-signer' }),
    [switch]$Force                          # overwrite an existing keystore
)

$ErrorActionPreference = 'Stop'
trap { Write-Host -ForegroundColor Red "ERROR: $_"; exit 1 }

if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) {
    Write-Host -ForegroundColor Red "keytool not found on PATH. Install a JDK (the same one used for the Maven build)."
    exit 1
}

if (((Test-Path $Keystore) -or (Test-Path $Chain)) -and -not $Force) {
    Write-Host -ForegroundColor Red "Signing files already exist ($Keystore / $Chain). Re-run with -Force to replace them. Modules signed with the old certificate will need their new certificate re-accepted on each gateway."
    exit 1
}

if (-not $Subject) {
    # Build a DN from module.xml; escape DN special characters in the values.
    [xml]$xml = Get-Content (Join-Path (Split-Path $PSScriptRoot -Parent) 'module.xml')
    $esc = { param($s) ($s -replace '([,+"\\<>;=])', '\$1') }
    $Subject = "CN=$(& $esc ($xml.modules.module.name + ' Module Signing')), O=$(& $esc $xml.modules.module.vendor)"
}

# Password: env var or prompt. Passed to keytool via -storepass:env so it never appears on a command line.
$pw = $env:DOOM_SIGNING_PASSWORD
if (-not $pw) {
    $s1 = Read-Host 'New keystore password (min 6 chars)' -AsSecureString
    $s2 = Read-Host 'Confirm password' -AsSecureString
    $pw  = [Net.NetworkCredential]::new('', $s1).Password
    if ($pw -ne [Net.NetworkCredential]::new('', $s2).Password) { Write-Host -ForegroundColor Red 'Passwords do not match.'; exit 1 }
}
if ($pw.Length -lt 6) { Write-Host -ForegroundColor Red 'Password must be at least 6 characters (keytool requirement).'; exit 1 }

$prevEnv = $env:DOOM_SIGNING_PASSWORD
$env:DOOM_SIGNING_PASSWORD = $pw
$tmpCert = [IO.Path]::GetTempFileName()
try {
    foreach ($f in @($Keystore, $Chain)) { if (Test-Path $f) { Remove-Item $f -Force } }

    Write-Host "Generating RSA-3072 code-signing certificate"
    Write-Host "  Subject:  $Subject"
    Write-Host "  Validity: $ValidityDays days"
    & keytool -genkeypair -alias $Alias -keyalg RSA -keysize 3072 -sigalg SHA256withRSA `
        -validity $ValidityDays -dname $Subject `
        -ext KeyUsage=digitalSignature -ext ExtendedKeyUsage=codeSigning `
        -storetype PKCS12 -keystore $Keystore -storepass:env DOOM_SIGNING_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw "keytool -genkeypair failed ($LASTEXITCODE)" }

    & keytool -exportcert -alias $Alias -keystore $Keystore -storepass:env DOOM_SIGNING_PASSWORD -file $tmpCert
    if ($LASTEXITCODE -ne 0) { throw "keytool -exportcert failed ($LASTEXITCODE)" }

    # Wrap the certificate in a PKCS#7 (.p7b) chain, as module-signer requires.
    $coll = [Security.Cryptography.X509Certificates.X509Certificate2Collection]::new()
    $coll.Import($tmpCert)
    [IO.File]::WriteAllBytes($Chain, $coll.Export([Security.Cryptography.X509Certificates.X509ContentType]::Pkcs7))
    $fp = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($coll[0].RawData)) -replace '-', ':'
} finally {
    $env:DOOM_SIGNING_PASSWORD = $prevEnv
    Remove-Item $tmpCert -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "Created:"
Write-Host "  $Keystore   (PRIVATE - back it up, never commit or share it)"
Write-Host "  $Chain"
Write-Host ""
Write-Host "SHA-256 fingerprint (publish this so gateway admins can verify the cert they accept):"
Write-Host "  $fp"
Write-Host ""
Write-Host "Next: run package-doom-modl.ps1 (signs automatically) or signing\sign-modl.ps1."
