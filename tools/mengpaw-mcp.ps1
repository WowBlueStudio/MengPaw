# MengPaw MCP Client — communicate with MengPaw Agent via ACP
# Usage: .\tools\mengpaw-mcp.ps1 [-Query <cmd>] [-Task <desc>] [-List] [-Review]
param(
    [switch]$List,
    [string]$Query,
    [string]$Task,
    [string]$Command,
    [switch]$Review,
    [int]$Port = 9876
)

$ErrorActionPreference = "Stop"
$uri = "http://localhost:$Port/acp"

function Send-AcpMessage($type, $payload) {
    $body = @{
        from = "claude-code"
        to = "*"
        type = $type
        payload = $payload
        ttl = 1
    } | ConvertTo-Json -Compress

    try {
        $response = Invoke-RestMethod -Uri $uri -Method POST -ContentType "application/json" -Body $body -TimeoutSec 30
        return $response
    } catch {
        Write-Host "ACP Error: $_" -ForegroundColor Red
        Write-Host "Ensure: adb forward tcp:${Port} tcp:${Port}" -ForegroundColor Yellow
        return $null
    }
}

# ── MCP Tools ────────────────────────────────────────────────

if ($List) {
    Write-Host "Fetching MengPaw Agent tools..." -ForegroundColor Cyan
    $mcpRequest = '{"jsonrpc":"2.0","method":"tools/list","id":1}'
    $result = Send-AcpMessage -type "MCP_REQUEST" -payload $mcpRequest
    if ($result) {
        $data = $result | ConvertFrom-Json
        if ($data.success -eq "true") {
            Write-Host $data.data -ForegroundColor Green
        } else {
            # Parse MCP response from data field
            try {
                $mcpData = $data.data | ConvertFrom-Json
                $tools = $mcpData.result.tools
                Write-Host "`nMengPaw Agent Tools ($($tools.Count)):" -ForegroundColor Green
                $tools | ForEach-Object {
                    Write-Host "  🔧 $($_.name)" -ForegroundColor Yellow
                    Write-Host "     $($_.description)" -ForegroundColor Gray
                }
            } catch {
                Write-Host "Raw: $result" -ForegroundColor Gray
            }
        }
    }
}

# ── Sync Query ────────────────────────────────────────────────

if ($Query) {
    Write-Host "Query: $Query" -ForegroundColor Cyan
    $payload = @{mode = "query"; command = $Query; replyTo = "claude-code"} | ConvertTo-Json -Compress
    $result = Send-AcpMessage -type "CLAUDE_BRIDGE" -payload $payload
    if ($result) {
        Write-Host $result -ForegroundColor Green
    }
}

# ── Async Task ────────────────────────────────────────────────

if ($Task) {
    Write-Host "Sending task to Agent..." -ForegroundColor Cyan
    $payload = @{mode = "task"; command = $Task; replyTo = "claude-code"} | ConvertTo-Json -Compress
    $result = Send-AcpMessage -type "CLAUDE_BRIDGE" -payload $payload
    if ($result) {
        Write-Host "Task queued: $result" -ForegroundColor Green
    }
}

# ── Firewalled Command ────────────────────────────────────────

if ($Command) {
    Write-Host "Executing command (requires trust)..." -ForegroundColor Cyan
    $payload = @{mode = "command"; command = $Command; replyTo = "claude-code"} | ConvertTo-Json -Compress
    $result = Send-AcpMessage -type "CLAUDE_BRIDGE" -payload $payload
    if ($result) {
        Write-Host $result -ForegroundColor Green
    }
}

# ── Review ────────────────────────────────────────────────────

if ($Review) {
    Write-Host "Triggering code review..." -ForegroundColor Cyan
    $payload = @{mode = "review"; command = ""; replyTo = "claude-code"} | ConvertTo-Json -Compress
    $result = Send-AcpMessage -type "CLAUDE_BRIDGE" -payload $payload
    if ($result) {
        Write-Host "Review queued: $result" -ForegroundColor Green
    }
}

# ── No args: show usage ───────────────────────────────────────

if (-not ($List -or $Query -or $Task -or $Command -or $Review)) {
    Write-Host @"
MengPaw MCP Client
==================
  -List                List all Agent tools (MCP tools/list)
  -Query <cmd>          Sync query (e.g. "agent.read /path")
  -Task <description>   Async task (Agent picks up from inbox)
  -Command <cmd>        Execute CLI command (requires trust)
  -Review               Trigger code review

Examples:
  .\mengpaw-mcp.ps1 -List
  .\mengpaw-mcp.ps1 -Query "agent.read /sdcard/Download/task.md"
  .\mengpaw-mcp.ps1 -Task "分析框架协议的问题"
  .\mengpaw-mcp.ps1 -Query "self.version"
"@
}
