# Creates the key that signs release builds (the APKs published on GitHub Releases),
# and writes keystore.properties so Gradle can find it. Run it once, in your own terminal:
#
#   powershell -ExecutionPolicy Bypass -File scripts\create-upload-key.ps1
#
# keytool asks for the password and for your name; neither is shown to anyone else.
# The key file is stored OUTSIDE the project so it can never be committed.

param(
    [string]$KeyDir = (Join-Path $env:USERPROFILE ".android-keys"),
    [string]$Alias = "upload"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# Find keytool: JAVA_HOME, then the JDK 17 used for builds, then Android Studio's bundled JDK.
$candidates = @()
if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\keytool.exe") }
$candidates += Get-ChildItem (Join-Path $env:USERPROFILE ".jdks") -Directory -ErrorAction SilentlyContinue |
    ForEach-Object { Join-Path $_.FullName "bin\keytool.exe" }
$candidates += "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
$keytool = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $keytool) { throw "keytool not found. Install a JDK or set JAVA_HOME." }

New-Item -ItemType Directory -Force $KeyDir | Out-Null
$keystore = Join-Path $KeyDir "uoft-print-upload.jks"
if (Test-Path $keystore) { throw "$keystore already exists. Delete it only if you are sure it was never used to publish." }

Write-Host "Creating $keystore"
Write-Host "keytool will ask for a password (at least 6 characters), then your name; other questions can be left blank."
& $keytool -genkeypair -v -keystore $keystore -storetype PKCS12 -alias $Alias -keyalg RSA -keysize 4096 -validity 10000
if ($LASTEXITCODE -ne 0) { throw "keytool failed" }

$secure = Read-Host "Type the same password again so Gradle can sign builds" -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
$props = @(
    "# Local release signing. Never commit this file (it is in .gitignore).",
    "storeFile=$($keystore -replace '\\', '/')",
    "storePassword=$plain",
    "keyAlias=$Alias",
    "keyPassword=$plain"
)
Set-Content -Path (Join-Path $root "keystore.properties") -Value $props -Encoding ascii
Write-Host ""
Write-Host "Done. Back up $keystore and its password somewhere safe (a password manager)."
Write-Host "Without them, people who installed a release cannot update to later releases without uninstalling."
