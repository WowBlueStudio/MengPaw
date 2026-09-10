#!/usr/bin/env pwsh
# SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial
#
# DeepSeek API 连通性 + 上游协议体检 (v0.46.3 起随仓库提供)
#
# 用途: 排查「模型未返回任何内容（空响应）」类故障时, 先用本脚本确认"服务端/密钥/请求体"是否正常,
#       避免在服务端假设上盲改客户端 (2026-09-10 事故教训: 真因是上游分片新增 "usage": null,
#       客户端 JsonNull 取值抛异常被静默吞掉 — 见 docs/lessons.md §46)。
#
# 密钥来源 (按优先级, 脚本不打印/不落盘任何密钥):
#   1. 环境变量 DEEPSEEK_API_KEY (Process → User → Machine 作用域)
#   2. DSH 凭据文件 %DSH_HOME%\.credentials.yaml 或 %USERPROFILE%\.dsh\.credentials.yaml
#
# 用法:
#   pwsh -File scripts/check-deepseek.ps1                     # 连通性 + 非流式 + 流式体检
#   pwsh -File scripts/check-deepseek.ps1 -Model deepseek-flash
#   pwsh -File scripts/check-deepseek.ps1 -SkipChat           # 只查密钥/模型列表/余额

param(
    [string]$Model = "deepseek-flash",
    [string]$Endpoint = "https://api.deepseek.com",
    [switch]$SkipChat
)

$ErrorActionPreference = 'Continue'
$fail = 0

function Resolve-DeepSeekKey {
    foreach ($scope in @('Process', 'User', 'Machine')) {
        $v = [System.Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', $scope)
        if ($v) { return @{ key = $v; source = "环境变量($scope)" } }
    }
    $files = @()
    if ($env:DSH_HOME) { $files += (Join-Path $env:DSH_HOME '.credentials.yaml') }
    $files += (Join-Path $env:USERPROFILE '.dsh\.credentials.yaml')
    foreach ($f in $files) {
        if (Test-Path $f) {
            $m = [regex]::Match((Get-Content $f -Raw), '(?m)^\s*DEEPSEEK_API_KEY\s*:\s*(\S+)')
            if ($m.Success) { return @{ key = $m.Groups[1].Value; source = $f } }
        }
    }
    return $null
}

$resolved = Resolve-DeepSeekKey
if (-not $resolved) {
    Write-Host "[×] 未找到 DeepSeek API Key — 请设置环境变量 DEEPSEEK_API_KEY, 或确认 DSH 凭据文件存在" -ForegroundColor Red
    exit 2
}
$key = $resolved.key
Write-Host "密钥来源: $($resolved.source) (长度 $($key.Length), 前缀 $($key.Substring(0,[Math]::Min(5,$key.Length)))...)" -ForegroundColor DarkGray
Write-Host "端点: $Endpoint   模型: $Model" -ForegroundColor DarkGray
Write-Host ""

# ── 1. 模型列表 ────────────────────────────────────────────────────────────
Write-Host "[1/4] GET /models — 认证与连通性" -ForegroundColor Cyan
try {
    $r = Invoke-WebRequest -Uri "$Endpoint/models" -Headers @{ Authorization = "Bearer $key" } -TimeoutSec 30 -UseBasicParsing
    $ids = ([regex]::Matches($r.Content, '"id"\s*:\s*"([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
    Write-Host "  [√] HTTP $($r.StatusCode) — 可用模型: $($ids -join ', ')" -ForegroundColor Green
} catch {
    $code = 0; try { $code = [int]$_.Exception.Response.StatusCode } catch {}
    Write-Host "  [×] HTTP $code — 认证或网络失败: $($_.Exception.Message)" -ForegroundColor Red
    $fail++
}

# ── 2. 余额 ────────────────────────────────────────────────────────────────
Write-Host "[2/4] GET /user/balance — 账户可用性" -ForegroundColor Cyan
try {
    $r = Invoke-WebRequest -Uri "$Endpoint/user/balance" -Headers @{ Authorization = "Bearer $key" } -TimeoutSec 30 -UseBasicParsing
    $avail = ([regex]::Match($r.Content, '"is_available"\s*:\s*(\w+)')).Groups[1].Value
    $bal = ([regex]::Match($r.Content, '"total_balance"\s*:\s*"?([0-9\.]+)"?')).Groups[1].Value
    $color = if ($avail -eq 'true') { 'Green' } else { 'Red' }
    if ($avail -ne 'true') { $fail++ }
    Write-Host "  [√] HTTP $($r.StatusCode) — is_available=$avail total_balance=$bal" -ForegroundColor $color
} catch {
    $code = 0; try { $code = [int]$_.Exception.Response.StatusCode } catch {}
    Write-Host "  [!] HTTP $code — 余额接口不可用 (402 余额不足会表现为 402, 非空响应)" -ForegroundColor Yellow
}

if ($SkipChat) { Write-Host "`n已跳过对话测试 (-SkipChat)"; exit $fail }

$sysPrompt = '你是 MengPaw Agent, 严格按 Thought:/Action: 格式输出, 不寒暄。'
$body = @{
    model             = $Model
    max_tokens        = 16384
    temperature       = 0.7
    stream            = $true
    stream_options    = @{ include_usage = $true }
    thinking          = @{ type = 'enabled' }
    reasoning_effort  = 'high'
    messages          = @(
        @{ role = 'system'; content = $sysPrompt },
        @{ role = 'user'; content = '说一句你好' }
    )
}
$json = $body | ConvertTo-Json -Depth 12 -Compress
$headers = @{ Authorization = "Bearer $key"; 'Content-Type' = 'application/json' }

# ── 3. 流式对话 (MengPaw 真实请求体) ───────────────────────────────────────
Write-Host "[3/4] POST /chat/completions (stream, thinking=high) — 与 MengPaw 请求体一致" -ForegroundColor Cyan
$sw = [System.Diagnostics.Stopwatch]::StartNew()
try {
    $r = Invoke-WebRequest -Uri "$Endpoint/chat/completions" -Method POST -Headers $headers `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 300 -UseBasicParsing
    $sw.Stop()
    $text = $r.Content
} catch {
    $code = 0; try { $code = [int]$_.Exception.Response.StatusCode } catch {}
    Write-Host "  [×] HTTP $code — 请求失败 (HTTP 400 多为参数问题; 402 为余额; 429 为限流)" -ForegroundColor Red
    exit ($fail + 1)
}

$cText = -join ([regex]::Matches($text, '"content"\s*:\s*"([^"]*)"') | ForEach-Object { $_.Groups[1].Value })
$rText = -join ([regex]::Matches($text, '"reasoning_content"\s*:\s*"([^"]*)"') | ForEach-Object { $_.Groups[1].Value })
$dataLines = ([regex]::Matches($text, '(?m)^data:')).Count
$finish = (([regex]::Matches($text, '"finish_reason"\s*:\s*"([^"]*)"') | ForEach-Object { $_.Groups[1].Value }) -join '|')
$usage = ([regex]::Match($text, '"usage"\s*:\s*\{[^}]*\}')).Value
$nullUsageChunks = ([regex]::Matches($text, '"usage"\s*:\s*null')).Count
$crlf = ([regex]::Matches($text, "`r`n")).Count
$crOnly = ([regex]::Matches($text, "`r(?!`n)")).Count

Write-Host "  [√] HTTP $($r.StatusCode) 耗时 $([math]::Round($sw.Elapsed.TotalSeconds,1))s — data 行=$dataLines 正文=$($cText.Length) 字符 思维链=$($rText.Length) 字符 finish=[$finish]" `
    -ForegroundColor $(if ($cText.Length -gt 0) { 'Green' } else { 'Red' })
Write-Host "      正文开头: $($cText.Substring(0, [Math]::Min(80, $cText.Length)).Replace("`n", ' '))"
Write-Host "      usage: $usage"
if ($cText.Length -eq 0) { $fail++ }

# ── 4. 上游协议体检 (客户端解析假设) ──────────────────────────────────────
Write-Host "[4/4] 上游协议体检 — 客户端解析假设是否仍成立" -ForegroundColor Cyan
Write-Host "      行尾: CRLF=$crlf 单独CR=$crOnly (SSE 应为 LF 分帧, 单独 CR 会让逐行解析退化为整包)"
if ($nullUsageChunks -gt 0) {
    Write-Host "      [!] 分片中出现 $nullUsageChunks 处 `"usage`": null — 客户端必须 JsonNull 安全解析" -ForegroundColor Yellow
    Write-Host "          (v0.46.3 已修: objOrNull/arrOrNull; 若使用旧版本会全线『空响应』)" -ForegroundColor Yellow
} else {
    Write-Host "      [√] 分片未携带 usage:null" -ForegroundColor Green
}
if ($dataLines -eq 0) {
    Write-Host "      [×] 响应中没有 data: 行 — 上游未按 SSE 返回 (中转站常见)" -ForegroundColor Red
    $fail++
}

Write-Host ""
if ($fail -eq 0) { Write-Host "体检结论: 服务端/密钥/请求体均正常 — 若 App 仍报空响应, 问题在客户端链路" -ForegroundColor Green }
else { Write-Host "体检结论: 有 $fail 项异常, 见上方标记" -ForegroundColor Red }
exit $fail
