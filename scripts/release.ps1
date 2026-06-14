<#
.SYNOPSIS
    EmuHelper release automation helper.

.DESCRIPTION
    Runs a clean debug build + unit tests, copies the APK, computes SHA-256,
    and generates a release-notes template from recent git commits.

    This script is SAFE: it never commits, pushes, or publishes anything.
    The final gh release command is printed for you to review and run manually.

.PARAMETER Version
    The version string to stamp into the APK filename and release notes,
    e.g.  0.2.2

.PARAMETER Bump
    If set, also updates versionCode (+1) and versionName in app/build.gradle.kts
    before building. Without this flag the current values are used as-is.

.EXAMPLE
    .\scripts\release.ps1 -Version 0.2.2
    .\scripts\release.ps1 -Version 0.2.2 -Bump

.NOTES
    Run from the repository root on Windows with PowerShell 5+.
    Requires: git, Java (for Gradle), a connected device is NOT required (debug APK).
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [switch]$Bump
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# --- Helpers ------------------------------------------------------------------

function Write-Step([string]$msg) {
    Write-Host "`n==> $msg" -ForegroundColor Cyan
}

function Write-Ok([string]$msg) {
    Write-Host "    OK  $msg" -ForegroundColor Green
}

function Write-Warn([string]$msg) {
    Write-Host "    WARN  $msg" -ForegroundColor Yellow
}

function Write-Fail([string]$msg) {
    Write-Host "`n    FAIL  $msg" -ForegroundColor Red
    exit 1
}

function Resolve-RepoRoot {
    $root = git rev-parse --show-toplevel 2>$null
    if (-not $root) { Write-Fail "Not inside a git repository." }
    return $root.Trim().Replace("/", "\")
}

function Get-CurrentBranch {
    return (git rev-parse --abbrev-ref HEAD 2>$null).Trim()
}

function Test-CleanTree {
    $status = git status --porcelain 2>$null
    return [string]::IsNullOrWhiteSpace($status)
}

function Get-LastTag {
    $tag = git describe --tags --abbrev=0 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return $tag.Trim()
}

function Compute-Sha256([string]$path) {
    $hash = Get-FileHash -Path $path -Algorithm SHA256
    return $hash.Hash.ToLower()
}

function Read-CurrentVersion([string]$buildGradle) {
    $content = Get-Content $buildGradle -Raw
    $codeMatch   = [regex]::Match($content, 'versionCode\s*=\s*(\d+)')
    $nameMatch   = [regex]::Match($content, 'versionName\s*=\s*"([^"]+)"')
    return @{
        Code = if ($codeMatch.Success) { [int]$codeMatch.Groups[1].Value } else { 0 }
        Name = if ($nameMatch.Success) { $nameMatch.Groups[1].Value }       else { "0.0.0" }
    }
}

function Bump-Version([string]$buildGradle, [string]$newName) {
    $content    = Get-Content $buildGradle -Raw
    $ver        = Read-CurrentVersion $buildGradle
    $newCode    = $ver.Code + 1

    $content = [regex]::Replace($content, 'versionCode\s*=\s*\d+',    "versionCode = $newCode")
    $content = [regex]::Replace($content, 'versionName\s*=\s*"[^"]+"', "versionName = `"$newName`"")
    Set-Content -Path $buildGradle -Value $content -Encoding UTF8
    return $newCode
}

# --- Main ---------------------------------------------------------------------

Write-Host ""
Write-Host "+------------------------------------------+" -ForegroundColor Magenta
Write-Host "|   EmuHelper Release Script  v$Version   |" -ForegroundColor Magenta
Write-Host "+------------------------------------------+" -ForegroundColor Magenta

# 1. Validate repo root
Write-Step "Validating environment"
$repoRoot = Resolve-RepoRoot
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$expectedRoot = Split-Path -Parent $scriptDir

if ($repoRoot -ne $expectedRoot) {
    Write-Warn "Detected repo root:  $repoRoot"
    Write-Warn "Script parent dir:   $expectedRoot"
    Write-Warn "Running from unexpected location -- continuing anyway."
} else {
    Write-Ok "Repo root: $repoRoot"
}

# 2. Branch check
$branch = Get-CurrentBranch
if ($branch -ne "main") {
    Write-Warn "Current branch is '$branch', not 'main'. Releases are normally cut from main."
} else {
    Write-Ok "Branch: main"
}

# 3. Clean-tree check
if (-not (Test-CleanTree)) {
    Write-Warn "Working tree has uncommitted changes. Consider committing first."
} else {
    Write-Ok "Working tree is clean"
}

# 4. Read / optionally bump version in build.gradle.kts
$buildGradle = Join-Path $repoRoot "app\build.gradle.kts"
if (-not (Test-Path $buildGradle)) {
    Write-Fail "app/build.gradle.kts not found at: $buildGradle"
}

$currentVer = Read-CurrentVersion $buildGradle
Write-Step "Version info"
Write-Host "    Current versionName : $($currentVer.Name)"
Write-Host "    Current versionCode : $($currentVer.Code)"
Write-Host "    Release label       : $Version"

if ($Bump) {
    $newCode = Bump-Version $buildGradle $Version
    Write-Ok "Bumped to versionName=$Version, versionCode=$newCode"
} else {
    Write-Warn "-Bump not set: building with existing versionName=$($currentVer.Name) / versionCode=$($currentVer.Code)"
    Write-Warn "Pass -Bump to also update app/build.gradle.kts automatically."
}

# 5. Clean + build debug APK
Write-Step "Running: gradlew clean :app:assembleDebug"
Push-Location $repoRoot
try {
    & ".\gradlew.bat" clean :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { Write-Fail "gradlew clean :app:assembleDebug FAILED (exit $LASTEXITCODE)" }
    Write-Ok "Build successful"
} finally {
    Pop-Location
}

# 6. Run unit tests
Write-Step "Running: gradlew :app:testDebugUnitTest"
Push-Location $repoRoot
try {
    & ".\gradlew.bat" :app:testDebugUnitTest
    if ($LASTEXITCODE -ne 0) { Write-Fail "Unit tests FAILED (exit $LASTEXITCODE)" }
    Write-Ok "All unit tests passed"
} finally {
    Pop-Location
}

# 7. Copy APK
Write-Step "Copying APK"
$apkSrc  = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
$apkDest = Join-Path $repoRoot "EmuHelper-v$Version.apk"

if (-not (Test-Path $apkSrc)) {
    Write-Fail "Expected APK not found: $apkSrc"
}

Copy-Item -Path $apkSrc -Destination $apkDest -Force
Write-Ok "APK copied to: $apkDest"

# 8. Compute SHA-256
Write-Step "Computing SHA-256"
$sha256 = Compute-Sha256 $apkDest
Write-Ok "SHA-256: $sha256"

# 9. Gather recent commits since last tag
Write-Step "Collecting commits since last tag"
$lastTag = Get-LastTag
$commitLog = ""
if ($lastTag) {
    Write-Host "    Last tag: $lastTag"
    $commits = git log "$lastTag..HEAD" --pretty="- %s" 2>$null
    $commitLog = if ($commits) { $commits -join "`n" } else { "- (no new commits since $lastTag)" }
} else {
    Write-Warn "No previous tag found; collecting last 20 commits"
    $commits = git log --pretty="- %s" -n 20 2>$null
    $commitLog = if ($commits) { $commits -join "`n" } else { "- (no commits found)" }
}

# 10. Generate release notes template
Write-Step "Generating release notes"
$rnFile = Join-Path $repoRoot "_relnotes_v$Version.md"

$rnContent = @"
<p align="center"><img src="https://raw.githubusercontent.com/mayusi/EmuHelper/main/docs/logo.png" alt="EmuHelper logo" width="110" height="110"></p>

## EmuHelper v$Version

> **Status:** Early alpha -- expect rough edges.

---

## What changed

<!-- Edit this section before publishing -- remove items that don't apply. -->

$commitLog

---

## Install

1. Download **EmuHelper-v$Version.apk** from the assets below.
2. On your device, allow installs from unknown sources for your browser / file manager when prompted.
3. Open the APK to install. Requires **Android 10 (API 29)** or newer.

> Debug builds install as a separate ```.debug``` package and will not conflict with any future release build.

---

## APK integrity

**APK integrity** - SHA-256:
``````
$sha256
``````

The in-app updater checks this hash automatically before applying the update.

---

*Built by mayusi -- EmuHelper is open-source under the [MIT License](../../LICENSE).*
"@

# Write release notes as UTF-8 without BOM so the in-app updater and GitHub read it correctly
[System.IO.File]::WriteAllText($rnFile, $rnContent, [System.Text.UTF8Encoding]::new($false))
Write-Ok "Release notes written to: $rnFile"

# 11. Print summary and gh command
$apkSize = (Get-Item $apkDest).Length
$apkSizeMb = [math]::Round($apkSize / 1MB, 1)

Write-Host ""
Write-Host "================================================================" -ForegroundColor Magenta
Write-Host "  Release artefacts ready" -ForegroundColor Green
Write-Host ""
Write-Host "  APK  : EmuHelper-v$Version.apk  ($apkSizeMb MB)"
Write-Host "  SHA  : $sha256"
Write-Host "  Notes: _relnotes_v$Version.md"
Write-Host ""
Write-Host "  To publish (review + edit the notes file first, then run):" -ForegroundColor Yellow
Write-Host ""
Write-Host "  gh release create `"v$Version`" ``" -ForegroundColor White
Write-Host "    --title `"v$Version`" ``" -ForegroundColor White
Write-Host "    --notes-file `"_relnotes_v$Version.md`" ``" -ForegroundColor White
Write-Host "    `"EmuHelper-v$Version.apk`"" -ForegroundColor White
Write-Host ""
Write-Host "  This script does NOT publish -- run the command above when ready." -ForegroundColor Yellow
Write-Host "================================================================" -ForegroundColor Magenta
Write-Host ""