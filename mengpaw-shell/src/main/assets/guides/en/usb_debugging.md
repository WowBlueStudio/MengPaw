# USB Debugging Guide

USB debugging is the standard channel between your device and a computer — used for installing APKs, capturing logs, and running ADB commands. MengPaw's APK delivery and crash investigation (`adb logcat` / `dumpsys dropbox`) rely on it.

## Enable it

1. Open Settings → "About tablet / About phone"
2. Tap "Build number" 7 times until "You are now a developer" appears
3. Go back to Settings and open the new "Developer options"
4. Turn on "USB debugging" and accept the risk prompt
5. Connect via USB cable, check "Always allow" and confirm

## Common uses

- **Install APK**: `adb install mengpaw-shell-vX.Y.Z-release.apk`
- **Capture crash logs**: `adb logcat -d > crash.txt`
- **Inspect system Dropbox crashes**: `adb shell dumpsys dropbox --print`
- **Wireless debugging**: `adb tcpip 5555` then `adb connect <device-ip>:5555` (port may change per pairing)

## Notes

- Don't check "Always allow" on public computers — it grants unrestricted access
- Turn off USB debugging after use to reduce risk
- If the computer can't see the device, check whether the cable supports data transfer
