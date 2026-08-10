# SPDX-FileCopyrightText: 2026 ShenZhen wowblue culture and technology CO.,LTD.
# SPDX-License-Identifier: AGPL-3.0-or-later
<#
.SYNOPSIS
    创建/更新 Gitee release 并上传附件 (幂等)。

.DESCRIPTION
    从环境变量 GITEE_TOKEN 读取令牌 (红线 4: 严禁硬编码进仓库)。
    tag 已存在 release 时复用其 id (幂等), 不存在则创建; 然后逐个上传附件。

.EXAMPLE
    # 上传 v0.35.6 的 shell APK
    powershell -ExecutionPolicy Bypass -File scripts/gitee-release.ps1 `
        -Owner WowBlueStudio -Repo MengPaw -Tag v0.35.6 -Name "MengPaw v0.35.6" `
        -Assets D:\MengPaw\mengpaw-shell\build\outputs\apk\release\mengpaw-shell-v0.35.6-release.apk

.EXAMPLE
    # 上传插件 dex JAR 到 mengpaw-connectors 的 plugins-v0.3.0
    powershell -ExecutionPolicy Bypass -File scripts/gitee-release.ps1 `
        -Owner wowbluestudio -Repo mengpaw-connectors -Tag plugins-v0.3.0 `
        -Name "Plugins v0.3.0" -Assets (Get-ChildItem D:\MengPaw\releases\plugins-dex\*.jar).FullName
#>

param(
    [string]$Owner = "WowBlueStudio",
    [Parameter(Mandatory = $true)][string]$Repo,
    [Parameter(Mandatory = $true)][string]$Tag,
    [string]$Name = "",
    [string]$Notes = "",
    [string]$TargetBranch = "master",
    [Parameter(Mandatory = $true)][string[]]$Assets
)

$ErrorActionPreference = "Stop"

# ── 令牌 (环境变量, 禁止硬编码) ─────────────────────────────────
$token = $env:GITEE_TOKEN
if (-not $token) {
    Write-Error "未设置 GITEE_TOKEN 环境变量。设置方法: [Environment]::SetEnvironmentVariable('GITEE_TOKEN','<你的 Gitee 私人令牌>','User')"
    exit 1
}

$releaseName = if ($Name) { $Name } else { "Release $Tag" }
$releaseNotes = if ($Notes) { $Notes } else { "MengPaw release $Tag" }  # Gitee 要求描述非空
$apiBase = "https://gitee.com/api/v5/repos/$Owner/$Repo"

# ── 幂等: tag 已有 release 则复用, 否则创建 ────────────────────
# Gitee API 对不存在的 release 返回 HTTP 200 + body "null" — 必须显式判空
$existingRaw = & curl.exe -s "$apiBase/releases/tags/$Tag"
$existing = if ($existingRaw -and $existingRaw -ne "null") { $existingRaw | ConvertFrom-Json } else { $null }
if ($existing -and $existing.id) {
    $releaseId = $existing.id
    Write-Host "已存在 release, 复用 id=$releaseId" -ForegroundColor Yellow
} else {
    Write-Host "创建 release: $releaseName ..." -ForegroundColor Cyan
    $body = @{
        access_token = $token
        tag_name = $Tag
        name = $releaseName
        body = $releaseNotes
        target_commitish = $TargetBranch
    } | ConvertTo-Json -Compress
    $created = Invoke-RestMethod -Uri "$apiBase/releases" -Method Post -Body $body -ContentType "application/json" -TimeoutSec 60
    $releaseId = $created.id
    Write-Host "创建成功, id=$releaseId" -ForegroundColor Green
}

# ── 上传附件 ────────────────────────────────────────────────────
$uploaded = @()
foreach ($asset in $Assets) {
    $file = Get-Item $asset -ErrorAction Stop
    Write-Host "上传: $($file.Name) ($([math]::Round($file.Length/1KB,1)) KB) ..." -ForegroundColor Cyan
    # PS 5.1 无 -Form, 用系统自带 curl.exe 传 multipart (Win10 1803+ 内置)
    $raw = & curl.exe -s -X POST "$apiBase/releases/$releaseId/attach_files" -F "access_token=$token" -F "file=@$($file.FullName)"
    $result = $raw | ConvertFrom-Json
    if (-not $result.browser_download_url) { throw "Gitee 上传失败: $($raw -join ' ')" }
    $uploaded += $result.browser_download_url
    Write-Host "  -> $($result.browser_download_url)" -ForegroundColor Green
}

Write-Host "完成: https://gitee.com/$Owner/$Repo/releases/tag/$Tag (上传 $($uploaded.Count) 个附件)" -ForegroundColor Cyan
