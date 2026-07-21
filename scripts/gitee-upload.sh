#!/bin/bash
TOKEN="a0808ed6b6665379a4702472d0ea9b70"
API="https://gitee.com/api/v5/repos/WowBlueStudio/MengPaw"
TAG="v0.6.1"

# Step 1: Create release with simple body
echo "=== Creating release ==="
RESP=$(curl -s -w "\n%{http_code}" -X POST "$API/releases" \
  -H "Content-Type: application/json" \
  -d "{\"access_token\":\"$TOKEN\",\"tag_name\":\"$TAG\",\"name\":\"MengPaw v0.6.1\",\"body\":\"Kernel completion + Goal/Mission modes + NotifyBus + QwenPaw skills + 17 permissions\",\"target_commitish\":\"master\"}")

HTTP_CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | head -n -1)
echo "HTTP: $HTTP_CODE"

# Extract ID
RELEASE_ID=$(echo "$BODY" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
echo "Release ID: $RELEASE_ID"

if [ -z "$RELEASE_ID" ] || [ "$HTTP_CODE" != "201" ]; then
  echo "Release may already exist, looking up..."
  RESP2=$(curl -s "$API/releases/tags/$TAG?access_token=$TOKEN")
  RELEASE_ID=$(echo "$RESP2" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
  echo "Existing Release ID: $RELEASE_ID"
fi

if [ -z "$RELEASE_ID" ]; then
  echo "FAILED: Could not create or find release"
  exit 1
fi

# Step 2: Upload Shell APK
echo ""
echo "=== Uploading Shell APK ==="
SHELL_RESP=$(curl -s -X POST "$API/releases/$RELEASE_ID/attach_files" \
  -F "access_token=$TOKEN" \
  -F "file=@D:/MengPaw/mengpaw-shell/build/outputs/apk/release/mengpaw-shell-v0.6.1-release.apk")
echo "$SHELL_RESP" | grep -o '"browser_download_url":"[^"]*"'

# Step 3: Upload Browser APK
echo ""
echo "=== Uploading Browser APK ==="
BROWSER_RESP=$(curl -s -X POST "$API/releases/$RELEASE_ID/attach_files" \
  -F "access_token=$TOKEN" \
  -F "file=@D:/MengPaw/mengpaw-browser/build/outputs/apk/release/mengpaw-browser-v0.4.0-release.apk")
echo "$BROWSER_RESP" | grep -o '"browser_download_url":"[^"]*"'

echo ""
echo "Done: https://gitee.com/WowBlueStudio/MengPaw/releases/tag/$TAG"
