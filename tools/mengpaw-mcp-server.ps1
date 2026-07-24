# MengPaw MCP Server — stdio-based MCP server for Claude Code integration
# Reads JSON-RPC from stdin, forwards to MengPaw Agent via ACP, writes response to stdout.
# Configure in .claude/mcp.json:
# {
#   "mcpServers": {
#     "mengpaw": {
#       "command": "powershell",
#       "args": ["-File", "tools/mengpaw-mcp-server.ps1"]
#     }
#   }
# }

$ErrorActionPreference = "SilentlyContinue"
$uri = "http://localhost:9876/acp"

# Ensure ADB forward is active
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
if (Test-Path $adb) {
    & $adb forward tcp:9876 tcp:9876 2>$null
}

while ($true) {
    $line = [Console]::In.ReadLine()
    if (-not $line) { break }

    try {
        # Forward JSON-RPC to MengPaw Agent via ACP
        $body = @{
            from = "claude-code"
            to = "*"
            type = "MCP_REQUEST"
            payload = $line
            ttl = 1
        } | ConvertTo-Json -Compress

        $response = Invoke-RestMethod -Uri $uri -Method POST -ContentType "application/json" -Body $body -TimeoutSec 30

        # Extract MCP response from ACP and write to stdout
        if ($response -match '"success":"true"' -or $response -match '"success": true') {
            # The MCP JSON-RPC response is in the data field
            $data = ($response | ConvertFrom-Json).data
            if ($data) {
                [Console]::Out.WriteLine($data)
            } else {
                [Console]::Out.WriteLine($response)
            }
        } else {
            # Error response
            $errResp = @{jsonrpc="2.0";error=@{code=-1;message="$response"};id=$null} | ConvertTo-Json -Compress
            [Console]::Out.WriteLine($errResp)
        }
    } catch {
        $errResp = @{jsonrpc="2.0";error=@{code=-1;message="ACP connection failed: $_"};id=$null} | ConvertTo-Json -Compress
        [Console]::Out.WriteLine($errResp)
    }
}
