# SPDX-FileCopyrightText: 2026 ShenZhen wowblue culture and technology CO.,LTD.
# SPDX-License-Identifier: AGPL-3.0-or-later
<#
.SYNOPSIS
    把主仓库 remote 插件 AAR 打包为 Android 宿主可加载的 dex JAR。

.DESCRIPTION
    plugins.json 中 status=remote 的插件经 DexClassLoader 下载加载, 只接受
    含 classes.dex 的 JAR。标准 AAR (classes.jar JVM 字节码) 无法在真机激活,
    此前安装仅注册占位元数据 (假安装)。本脚本:
      1. 构建 8 个 remote 插件模块的 AAR
      2. 用 Android SDK d8 把各插件 classes.jar 转 classes.dex
      3. 打包为 <module>-release.jar, 写 META-INF/plugin-class 主类清单
    宿主 APK 已内置 ktor/serialization/coroutines, 无需 fat 依赖。
    产物输出 releases/plugins-dex/, 供 plugins-v* tag 发布 (upload + plugins.json 指向 .jar)。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/package-plugins-dex.ps1
#>

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot
$OutDir = Join-Path $RootDir "releases\plugins-dex"

# ── 1. Android SDK 工具定位 ─────────────────────────────────────
$sdkDir = (Get-Content (Join-Path $RootDir "local.properties") | Select-String '^sdk\.dir=').ToString().Split('=')[1].Trim()
$d8 = Get-ChildItem (Join-Path $sdkDir "build-tools") -Directory |
    Sort-Object Name -Descending | Select-Object -First 1 |
    ForEach-Object { Join-Path $_.FullName "d8.bat" }
$androidJar = Get-ChildItem (Join-Path $sdkDir "platforms") -Directory |
    Sort-Object Name -Descending | Select-Object -First 1 |
    ForEach-Object { Join-Path $_.FullName "android.jar" }
if (-not (Test-Path $d8)) { Write-Error "找不到 d8.bat (Android SDK build-tools)" }
if (-not (Test-Path $androidJar)) { Write-Error "找不到 android.jar (Android SDK platforms)" }
Write-Host "d8: $d8" -ForegroundColor Cyan
Write-Host "android.jar: $androidJar" -ForegroundColor Cyan

# ── 2. 主类清单 (UTF-8 显式读取, 规避 PS 5.1 ANSI 吞行) ──────────
$manifest = @{}
$utf8 = New-Object System.Text.UTF8Encoding($false)
foreach ($line in [System.IO.File]::ReadAllLines((Join-Path $PSScriptRoot "plugin-class.txt"), $utf8)) {
    $t = $line.Trim()
    if (-not $t -or $t.StartsWith("#")) { continue }
    $parts = $t -split ":", 2
    if ($parts.Count -eq 2) { $manifest[$parts[0].Trim()] = $parts[1].Trim() }
}
if ($manifest.Count -eq 0) { Write-Error "主类清单为空 — 检查 scripts/plugin-class.txt" }

# ── 3. 构建 8 个 remote 插件 AAR ────────────────────────────────
$modules = $manifest.Keys | Sort-Object
Push-Location $RootDir
try {
    $tasks = @($modules | ForEach-Object { ":$($_):assembleRelease" })
    & .\gradlew.bat @tasks --console=plain 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "assembleRelease 失败 (exit $LASTEXITCODE)" }
} finally { Pop-Location }

# ── 4. 逐模块打包 ───────────────────────────────────────────────
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

foreach ($module in $modules) {
    $mainClass = $manifest[$module]
    $aar = Get-ChildItem (Join-Path $RootDir "plugins\$module\build\outputs\aar") -Filter "*-release.aar" -ErrorAction Stop | Select-Object -First 1
    $work = Join-Path $env:TEMP ("plugin-dex-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path (Join-Path $work "in") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $work "dex") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $work "stage\META-INF") | Out-Null
    try {
        Push-Location (Join-Path $work "in")
        & jar xf $aar.FullName classes.jar | Out-Null
        Pop-Location

        & $d8 --release --min-api 26 --lib $androidJar --output (Join-Path $work "dex") (Join-Path $work "in\classes.jar") 2>&1 | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "d8 失败 (exit $LASTEXITCODE)" }

        Copy-Item (Join-Path $work "dex\classes.dex") (Join-Path $work "stage\classes.dex")
        [System.IO.File]::WriteAllText((Join-Path $work "stage\META-INF\plugin-class"), $mainClass, $utf8)
        $outJar = Join-Path $OutDir "$module-release.jar"
        Push-Location (Join-Path $work "stage")
        & jar cf $outJar classes.dex META-INF/plugin-class | Out-Null
        Pop-Location

        $kb = [math]::Round((Get-Item $outJar).Length / 1KB, 1)
        Write-Host "  -> $outJar ($kb KB, 主类 $mainClass)" -ForegroundColor Green
    } catch {
        Write-Host "  -> FAILED: $_" -ForegroundColor Red
    } finally {
        Pop-Location
        Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "完成 — 产物目录: $OutDir" -ForegroundColor Cyan
