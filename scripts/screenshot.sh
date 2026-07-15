#!/bin/bash
# 📸 Screenshot capture utility for MengPaw
# Usage: ./scripts/screenshot.sh [name]
#   - Captures screenshot to .screenshots/ directory
#   - Auto-prunes to max 10 files (keeps newest)

SCREENSHOTS_DIR="$(cd "$(dirname "$0")/.." && pwd)/.screenshots"
MAX_SCREENSHOTS=10
ADB="/c/Users/a1138/Android/Sdk/platform-tools/adb.exe"

mkdir -p "$SCREENSHOTS_DIR"

# Generate filename
NAME="${1:-ss}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
FILENAME="${NAME}_${TIMESTAMP}.png"
FILEPATH="$SCREENSHOTS_DIR/$FILENAME"

# Wait for device
for i in 1 2 3; do
    state=$("$ADB" get-state 2>/dev/null)
    [ "$state" = "device" ] && break
    sleep 2
done

# Capture: use exec-out with -p flag piped to file
"$ADB" exec-out screencap -p > "$FILEPATH" 2>/dev/null

if [ -f "$FILEPATH" ] && [ -s "$FILEPATH" ]; then
    # Verify it's a PNG
    if file "$FILEPATH" 2>/dev/null | grep -q "PNG"; then
        echo "✅ Saved: $FILENAME ($(du -h "$FILEPATH" | cut -f1))"
    else
        echo "❌ Capture failed (invalid format)"
        rm -f "$FILEPATH"
        exit 1
    fi
else
    echo "❌ Capture failed (no file)"
    rm -f "$FILEPATH"
    exit 1
fi

# Prune: keep only the newest $MAX_SCREENSHOTS
COUNT=$(ls -1 "$SCREENSHOTS_DIR"/*.png 2>/dev/null | wc -l)
if [ "$COUNT" -gt "$MAX_SCREENSHOTS" ]; then
    TO_DELETE=$((COUNT - MAX_SCREENSHOTS))
    ls -t "$SCREENSHOTS_DIR"/*.png | tail -n "$TO_DELETE" | while read f; do
        rm "$f"
        echo "   🗑️ Pruned: $(basename "$f")"
    done
fi

echo "   📊 Total: $(ls -1 "$SCREENSHOTS_DIR"/*.png 2>/dev/null | wc -l)/$MAX_SCREENSHOTS"
