$token = "a0808ed6b6665379a4702472d0ea9b70"
$owner = "WowBlueStudio"
$repo = "MengPaw"
$tag = "v0.6.1"

# Read first 65 lines of CHANGELOG
$changelog = Get-Content "D:\MengPaw\CHANGELOG.md" -First 65 -Raw

# Create release
Write-Host "Creating Gitee release for $tag..."
$releaseBody = @{
    access_token = $token
    tag_name = $tag
    name = "MengPaw v0.6.1 — 内核能力补全 + 安全加固"
    body = $changelog
    target_commitish = "master"
} | ConvertTo-Json -Compress

$release = Invoke-RestMethod -Uri "https://gitee.com/api/v5/repos/$owner/$repo/releases" `
    -Method Post -Body $releaseBody -ContentType "application/json"

Write-Host "Release ID: $($release.id)"

# Upload Shell APK
Write-Host "Uploading shell APK..."
$shellApk = "D:\MengPaw\mengpaw-shell\build\outputs\apk\release\mengpaw-shell-v0.6.1-release.apk"
$shellResult = Invoke-RestMethod -Uri "https://gitee.com/api/v5/repos/$owner/$repo/releases/$($release.id)/attach_files" `
    -Method Post `
    -Form @{
        access_token = $token
        file = Get-Item $shellApk
    }
Write-Host "Shell APK: $($shellResult.browser_download_url)"

# Upload Browser APK
Write-Host "Uploading browser APK..."
$browserApk = "D:\MengPaw\mengpaw-browser\build\outputs\apk\release\mengpaw-browser-v0.4.0-release.apk"
$browserResult = Invoke-RestMethod -Uri "https://gitee.com/api/v5/repos/$owner/$repo/releases/$($release.id)/attach_files" `
    -Method Post `
    -Form @{
        access_token = $token
        file = Get-Item $browserApk
    }
Write-Host "Browser APK: $($browserResult.browser_download_url)"

Write-Host "Done! https://gitee.com/$owner/$repo/releases/tag/$tag"
