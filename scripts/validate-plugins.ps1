# SPDX-FileCopyrightText: 2026 ShenZhen wowblue culture and technology CO.,LTD.
# SPDX-License-Identifier: AGPL-3.0-or-later

<#
.SYNOPSIS
    Validate plugins.json against structural rules, SemVer, URL/checksum
    conventions, and cross-checks with the actual codebase.

.DESCRIPTION
    Read-only validation. Exit code 0 = pass, 1 = problems found.

    Checks:
      1. Structure: marketplace/version/updated/plugins present, plugins non-empty
      2. Unique ids + unique command namespace prefixes
      3. Required fields (id/name/version/type/author/description/status/commands)
         status in {builtin, remote, embedded, deprecated}; type in {native, script}
      4. SemVer X.Y.Z
      5. remote entries: downloadUrl HTTPS + contains releases/download/plugins-v*
         checksum (if present) must have sha256: prefix; if a matching AAR exists
         in releases/plugins/, compare against actual SHA256
      6. Cross-checks with code:
         - every plugins.json id has a matching module dir under plugins/
           (except embedded/deprecated)
         - the 13 bundled modules in mengpaw-shell have builtin entries
         - command prefixes follow PluginManager.namespaceFor derivation (warning)
      7. ports field: range 1-65535, no duplicates

.EXAMPLE
    .\scripts\validate-plugins.ps1
#>

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot
$JsonPath = Join-Path $RootDir "plugins.json"
$ShellBuildPath = Join-Path $RootDir "mengpaw-shell\build.gradle.kts"
$ReleaseDir = Join-Path $RootDir "releases\plugins"

$errors = @()
$warnings = @()

function Add-Error([string]$msg) { $script:errors += $msg }
function Add-Warning([string]$msg) { $script:warnings += $msg }

Write-Host "=== plugins.json validation ===" -ForegroundColor Cyan
Write-Host ""

# ── 1. Load & structure ───────────────────────────────────────────
if (-not (Test-Path $JsonPath)) { Write-Error "plugins.json not found: $JsonPath"; exit 1 }
$data = Get-Content $JsonPath -Raw -Encoding UTF8 | ConvertFrom-Json

if (-not $data.marketplace) { Add-Error "missing 'marketplace' field" }
if ($null -eq $data.version) { Add-Error "missing 'version' field" }
if (-not $data.updated) { Add-Error "missing 'updated' field" }
if (-not $data.plugins -or @($data.plugins).Count -eq 0) { Add-Error "'plugins' is empty" }

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Host "  [X] $_" -ForegroundColor Red }
    exit 1
}

$plugins = @($data.plugins)
Write-Host "Index: $($data.marketplace) v$($data.version) | $($plugins.Count) entries | updated: $($data.updated)"

# ── 2. Unique ids + unique namespaces ─────────────────────────────
$ids = @{}
$namespaces = @{}
foreach ($p in $plugins) {
    if ($ids.ContainsKey($p.id)) { Add-Error "duplicate id: $($p.id)" }
    else { $ids[$p.id] = $true }
    foreach ($cmd in @($p.commands)) {
        $ns = $cmd -replace '^([^.]+)\..*$', '$1'
        if ($namespaces.ContainsKey($ns) -and $namespaces[$ns] -ne $p.id) {
            Add-Warning "namespace '$ns' used by multiple plugins: $($namespaces[$ns]) and $($p.id)"
        } else { $namespaces[$ns] = $p.id }
    }
}

# ── 3. Required fields + enums ────────────────────────────────────
$validStatus = @("builtin", "remote", "embedded", "deprecated")
$validType = @("native", "script")
foreach ($p in $plugins) {
    foreach ($f in @("id", "name", "type", "author", "description", "status")) {
        if (-not $p.$f) { Add-Error "[$($p.id)] missing required field: $f" }
    }
    # version: required for non-builtin (builtin follows APK version, empty is valid)
    if (-not $p.version -and $p.status -ne "builtin") {
        Add-Error "[$($p.id)] missing required field: version"
    }
    if ($p.status -and $validStatus -notcontains $p.status) { Add-Error "[$($p.id)] invalid status: $($p.status)" }
    if ($p.type -and $validType -notcontains ($p.type -as [string]).ToLower()) { Add-Error "[$($p.id)] invalid type: $($p.type)" }
    if (-not $p.commands -or @($p.commands).Count -eq 0) { Add-Error "[$($p.id)] 'commands' is empty" }
}

# ── 4. SemVer ─────────────────────────────────────────────────────
foreach ($p in $plugins) {
    if ($p.version -and $p.version -notmatch '^\d+\.\d+\.\d+') {
        Add-Error "[$($p.id)] version is not SemVer: $($p.version)"
    }
}

# ── 5. remote URL / checksum / actual SHA256 ──────────────────────
$releaseFiles = @()
if (Test-Path $ReleaseDir) { $releaseFiles = Get-ChildItem $ReleaseDir -Filter "*.aar" }

foreach ($p in $plugins) {
    if ($p.status -eq "remote") {
        if (-not $p.downloadUrl) {
            Add-Error "[$($p.id)] remote entry missing downloadUrl"
        } elseif ($p.downloadUrl -notmatch '^https://') {
            Add-Error "[$($p.id)] downloadUrl is not HTTPS: $($p.downloadUrl)"
        } elseif ($p.downloadUrl -notmatch 'releases/download/plugins-v') {
            Add-Error "[$($p.id)] downloadUrl should point to releases/download/plugins-v* : $($p.downloadUrl)"
        }
        if ($p.checksum -and $p.checksum -notmatch '^sha256:[0-9a-f]{64}$') {
            Add-Error "[$($p.id)] bad checksum format (expected sha256:64hex): $($p.checksum)"
        }
    }
    # checksum vs actual AAR file (exact module->file name match)
    if ($p.checksum -and $p.version) {
        $moduleName = switch ($p.id) {
            "tribe-plugin"  { "plugin-hermes" }
            "tools-plugin"  { "plugin-agent-tools" }
            default         { "plugin-" + ($p.id -replace '-plugin$', '') }
        }
        $aarName = "$moduleName-$($p.version)-release.aar"
        $candidate = $releaseFiles | Where-Object { $_.Name -eq $aarName } | Select-Object -First 1
        if ($candidate) {
            $actual = (Get-FileHash $candidate.FullName -Algorithm SHA256).Hash.ToLower()
            if ($p.checksum -ne "sha256:$actual") {
                Add-Error "[$($p.id)] checksum does not match actual SHA256 of $aarName"
            }
        }
    }
}

# ── 6. Cross-checks with code ─────────────────────────────────────
# 6a. id -> module dir (skip embedded/deprecated)
foreach ($p in $plugins) {
    if ($p.status -in @("embedded", "deprecated")) { continue }
    $dirName = switch ($p.id) {
        "tribe-plugin"  { "plugin-hermes" }
        "tools-plugin"  { "plugin-agent-tools" }
        default         { "plugin-" + ($p.id -replace '-plugin$', '') }
    }
    if (-not (Test-Path (Join-Path $RootDir "plugins\$dirName"))) {
        Add-Warning "[$($p.id)] no module dir plugins\$dirName (pure remote plugin?)"
    }
}

# 6b. bundled modules in shell -> builtin entries in plugins.json
$shellText = Get-Content $ShellBuildPath -Raw
$bundled = [regex]::Matches($shellText, 'implementation\(project\(":plugin-([a-z0-9-]+)"\)\)') |
    ForEach-Object { "plugin-$($_.Groups[1].Value)" } | Sort-Object -Unique
foreach ($m in $bundled) {
    $entryId = switch ($m) {
        "plugin-hermes"      { "tribe-plugin" }
        "plugin-agent-tools" { "tools-plugin" }
        default              { ($m -replace '^plugin-', '') + "-plugin" }
    }
    $entry = $plugins | Where-Object { $_.id -eq $entryId }
    if (-not $entry) {
        Add-Error "bundled module $m has no plugins.json entry (expected id=$entryId)"
    } elseif ($entry.status -ne "builtin") {
        Add-Error "bundled module $m entry $entryId has status $($entry.status), expected builtin"
    }
}

# 6c. command prefixes follow namespaceFor derivation (warning level)
foreach ($p in $plugins) {
    if ($p.status -in @("embedded", "deprecated")) { continue }
    $base = $p.id -replace '-plugin$', '' -replace '-ext$', ''
    if ($base -like 'memory-*') { $base = $base -replace '^memory-', '' }
    foreach ($cmd in @($p.commands)) {
        $prefix = $cmd -replace '^([^.]+)\..*$', '$1'
        if ($prefix -ne $base) {
            Add-Warning "[$($p.id)] command '$cmd' prefix '$prefix' != namespaceFor '$base' (ignore if plugin uses custom namespace)"
        }
    }
}

# ── 7. ports field ────────────────────────────────────────────────
foreach ($p in $plugins) {
    $ports = @($p.ports | Where-Object { $_ })
    if ($null -eq $p.ports -or $ports.Count -eq 0) { continue }
    foreach ($port in $ports) {
        if ($port -lt 1 -or $port -gt 65535) { Add-Error "[$($p.id)] port out of range: $port" }
    }
    if (($ports | Select-Object -Unique).Count -ne $ports.Count) {
        Add-Error "[$($p.id)] duplicate port declarations"
    }
}

# ── Summary ───────────────────────────────────────────────────────
Write-Host ""
if ($errors.Count -eq 0 -and $warnings.Count -eq 0) {
    Write-Host "PASS - all $($plugins.Count) entries compliant" -ForegroundColor Green
    exit 0
}
if ($warnings.Count -gt 0) {
    Write-Host "WARNINGS ($($warnings.Count)):" -ForegroundColor DarkYellow
    $warnings | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkYellow }
}
if ($errors.Count -gt 0) {
    Write-Host "ERRORS ($($errors.Count)):" -ForegroundColor Red
    $errors | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    exit 1
}
Write-Host "PASS (warnings only)" -ForegroundColor Green
exit 0
