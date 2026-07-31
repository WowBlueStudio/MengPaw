# SPDX-FileCopyrightText: 2026 ShenZhen wowblue culture and technology CO.,LTD.
# SPDX-License-Identifier: AGPL-3.0-or-later

<#
.SYNOPSIS
    Build all MengPaw plugin AARs, collect them into releases/plugins/, and
    write back checksum/size/changelog into plugins.json.

.DESCRIPTION
    Module list is derived dynamically from settings.gradle.kts
    (include(":plugin-...")), no hardcoded list.
    Artifact naming: releases/plugins/plugin-<name>-<version>-release.aar
    JSON write-back is done by scripts/update-plugins-json.py (avoids the
    PowerShell 5.1 ConvertTo-Json Chinese \uXXXX escaping issue).

.EXAMPLE
    .\scripts\build-plugins.ps1                 # full build + write-back
    .\scripts\build-plugins.ps1 -DryRun         # print derived module list only
#>

param(
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot

# ── 1. Derive module list from settings.gradle.kts ───────────────
$settingsPath = Join-Path $RootDir "settings.gradle.kts"
$settings = Get-Content $settingsPath -Raw
$modules = [regex]::Matches($settings, 'include\(":plugin-([a-z0-9-]+)"\)') |
    ForEach-Object { "plugin-$($_.Groups[1].Value)" } | Sort-Object -Unique

if ($modules.Count -eq 0) {
    Write-Error "No plugin modules derived from settings.gradle.kts"
    exit 1
}

$missingDirs = $modules | Where-Object { -not (Test-Path (Join-Path $RootDir "plugins\$_")) }
if ($missingDirs) {
    Write-Warning "Missing module dirs (will fail at build): $($missingDirs -join ', ')"
}

# ── 2. Version (single source of truth: gradle.properties) ───────
$verMatch = [regex]::Match((Get-Content (Join-Path $RootDir "gradle.properties") -Raw), 'mengpaw\.version=(\S+)')
$Version = if ($verMatch.Success) { $verMatch.Groups[1].Value.Trim() } else { "0.0.0" }

Write-Host "=== MengPaw Plugin AAR Builder ===" -ForegroundColor Cyan
Write-Host "Version: $Version | Modules: $($modules.Count) | DryRun: $DryRun"
Write-Host ""

if ($DryRun) {
    Write-Host "Derived modules:" -ForegroundColor Yellow
    $modules | ForEach-Object { Write-Host "  $_" }
    Write-Host ""
    Write-Host "Dry-run finished (nothing built, nothing written)." -ForegroundColor Green
    exit 0
}

# ── 3. Recreate output dir (no stale leftovers) ──────────────────
$ReleaseDir = Join-Path $RootDir "releases\plugins"
if (Test-Path $ReleaseDir) { Remove-Item $ReleaseDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null

# ── 4. Build each module ─────────────────────────────────────────
$Built = @()
$Failed = @()

foreach ($module in $modules) {
    Write-Host "[$module] Building..." -ForegroundColor Yellow
    try {
        Push-Location $RootDir
        $result = & .\gradlew.bat ":$module`:assembleRelease" 2>&1
        $gradleExit = $LASTEXITCODE
        Pop-Location

        if ($gradleExit -ne 0) { throw "Gradle build failed (exit $gradleExit)" }

        $aarDir = Join-Path $RootDir "plugins\$module\build\outputs\aar"
        $aarFile = Get-ChildItem -Path $aarDir -Filter "*-release.aar" -ErrorAction Stop | Select-Object -First 1
        if (-not $aarFile) { throw "AAR file not found in $aarDir" }

        $destName = "$module-$Version-release.aar"
        $destPath = Join-Path $ReleaseDir $destName
        Copy-Item $aarFile.FullName $destPath -Force

        $hash = (Get-FileHash $destPath -Algorithm SHA256).Hash.ToLower()
        $sizeBytes = $aarFile.Length
        $sizeKB = [math]::Round($sizeBytes / 1024, 1)

        Write-Host "  -> $destName ($sizeKB KB)" -ForegroundColor Green
        $Built += [PSCustomObject]@{
            Id        = (($module -replace '^plugin-', '') -replace '-$', '') + "-plugin"
            Module    = $module
            File      = $destName
            Sha256    = $hash
            SizeBytes = $sizeBytes
        }
    } catch {
        Write-Host "  -> FAILED: $_" -ForegroundColor Red
        $Failed += $module
    } finally {
        Pop-Location
    }
    Write-Host ""
}

# ── 5. Summary ───────────────────────────────────────────────────
Write-Host "=== Build Complete ===" -ForegroundColor Cyan
Write-Host "Built: $($Built.Count) | Failed: $($Failed.Count)" -ForegroundColor $(if ($Failed.Count -gt 0) { "Red" } else { "Green" })
if ($Failed.Count -gt 0) {
    Write-Host "Failed: $($Failed -join ', ')" -ForegroundColor Red
    Write-Host "Note: individual module failure does not block the rest; re-run is idempotent."
}

# ── 6. plugins.json write-back (Python, avoids PS 5.1 JSON issues) ─
if ($Built.Count -gt 0) {
    $artifactsPath = Join-Path $ReleaseDir "_artifacts.json"
    $Built | ConvertTo-Json -Depth 4 | Set-Content $artifactsPath -Encoding UTF8

    $py = Join-Path $PSScriptRoot "update-plugins-json.py"
    $pyResult = & python $py $RootDir $artifactsPath $Version 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "plugins.json write-back failed:" -ForegroundColor Red
        $pyResult | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
        exit 1
    }
    $pyResult | ForEach-Object { Write-Host $_ }
}

Write-Host ""
Write-Host "AARs saved to: $ReleaseDir" -ForegroundColor Cyan
Write-Host "Next steps:" -ForegroundColor White
Write-Host "  1. .\scripts\validate-plugins.ps1        # validate plugins.json" -ForegroundColor Gray
Write-Host "  2. git add plugins.json && git commit" -ForegroundColor Gray
Write-Host "  3. gh release create plugins-v$Version $ReleaseDir\*.aar --title 'Plugin AARs v$Version'" -ForegroundColor Gray
Write-Host "  4. push both remotes (see .claude/skills/plugin-dev.md)" -ForegroundColor Gray
