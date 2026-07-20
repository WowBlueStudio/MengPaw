# SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
# SPDX-License-Identifier: AGPL-3.0-or-later

<#
.SYNOPSIS
    Build all MengPaw plugin AARs and collect them into releases/plugins/.

.DESCRIPTION
    Iterates over all plugin modules, runs assembleRelease, and copies the
    resulting AAR files to releases/plugins/. After running this script, use
    gh release upload to publish the AARs, then update plugins.json with
    accurate download URLs, SHA256 checksums, and set status to "remote".

.EXAMPLE
    .\scripts\build-plugins.ps1
#>

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot

# All plugin module names (without the plugin- prefix in settings.gradle.kts)
$Plugins = @(
    "fs", "net", "memory", "skill", "self",
    "clipboard", "notification", "pad", "tavily",
    "hermes", "workflow", "incubator", "render",
    "comfy", "translate", "dev", "error-report"
)

$ReleaseDir = Join-Path $RootDir "releases\plugins"
New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null

Write-Host "=== MengPaw Plugin AAR Builder ===" -ForegroundColor Cyan
Write-Host ""

$Built = @()
$Failed = @()

foreach ($plugin in $Plugins) {
    $moduleName = "plugin-$plugin"
    Write-Host "[$moduleName] Building..." -ForegroundColor Yellow

    try {
        Push-Location $RootDir
        $result = & .\gradlew.bat ":$moduleName`:assembleRelease" 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed"
        }
        Pop-Location

        $aarDir = Join-Path $RootDir "plugins\$moduleName\build\outputs\aar"
        $aarFile = Get-ChildItem -Path $aarDir -Filter "*-release.aar" | Select-Object -First 1

        if ($aarFile) {
            $destName = "$moduleName-release.aar"
            $destPath = Join-Path $ReleaseDir $destName
            Copy-Item $aarFile.FullName $destPath -Force

            $hash = (Get-FileHash $destPath -Algorithm SHA256).Hash.ToLower()
            $sizeKB = [math]::Round($aarFile.Length / 1024, 1)

            Write-Host "  -> $destName ($sizeKB KB, SHA256: $hash)" -ForegroundColor Green
            $Built += @{ Module = $moduleName; File = $destName; Hash = $hash; SizeKB = $sizeKB }
        } else {
            throw "AAR file not found in $aarDir"
        }
    } catch {
        Write-Host "  -> FAILED: $_" -ForegroundColor Red
        $Failed += $moduleName
    } finally {
        Pop-Location
    }

    Write-Host ""
}

# Summary
Write-Host "=== Build Complete ===" -ForegroundColor Cyan
Write-Host "Built: $($Built.Count) plugins" -ForegroundColor Green
Write-Host "Failed: $($Failed.Count) plugins" -ForegroundColor $(if ($Failed.Count -gt 0) { "Red" } else { "Green" })

if ($Failed.Count -gt 0) {
    Write-Host "Failed plugins: $($Failed -join ', ')" -ForegroundColor Red
}

if ($Built.Count -gt 0) {
    Write-Host ""
    Write-Host "AARs saved to: $ReleaseDir" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Next steps:" -ForegroundColor White
    Write-Host "1. Upload to GitHub Release:" -ForegroundColor Gray
    Write-Host "   gh release create v0.3.0 $ReleaseDir\*.aar --title 'Plugin AARs v0.3.0'" -ForegroundColor Gray
    Write-Host ""
    Write-Host "2. Update plugins.json with download URLs and checksums" -ForegroundColor Gray
    Write-Host ""
    Write-Host "Generated checksums:" -ForegroundColor White
    foreach ($b in $Built) {
        Write-Host "  $($b.Module): sha256:$($b.Hash)" -ForegroundColor Gray
    }
}
