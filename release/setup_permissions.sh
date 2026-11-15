#!/usr/bin/env bash
# ==============================================================================
# setup_permissions.sh — One-Click ADB Setup for Puppeteer on Android
# ==============================================================================
set -euo pipefail

PACKAGE="com.usq.shell"

echo "=== Checking ADB device connection ==="
if ! command -v adb &>/dev/null; then
  echo "Error: 'adb' command not found. Please install android-tools or add adb to PATH."
  exit 1
fi

DEVICES=$(adb devices | grep -v "List" | grep "device$" || true)
if [ -z "$DEVICES" ]; then
  echo "Error: No Android device detected via ADB. Ensure USB Debugging is enabled."
  exit 1
fi
echo "Connected device detected."

echo "=== 1. Battery Doze Whitelist (Immunity against MIUI task killer) ==="
adb shell "dumpsys deviceidle whitelist +$PACKAGE" || true

echo "=== 2. Special AppOps Privileges ==="
adb shell "appops set $PACKAGE WRITE_SETTINGS allow"
adb shell "appops set $PACKAGE MANAGE_EXTERNAL_STORAGE allow"
adb shell "appops set $PACKAGE GET_USAGE_STATS allow"
adb shell "appops set $PACKAGE SYSTEM_ALERT_WINDOW allow"

echo "=== 3. Do Not Disturb (DND) Control Policy ==="
adb shell "cmd notification allow_dnd $PACKAGE" || true

echo "=== 4. Hardware Secure Settings & Logcat Access ==="
adb shell "pm grant $PACKAGE android.permission.WRITE_SECURE_SETTINGS" || true
adb shell "pm grant $PACKAGE android.permission.READ_LOGS" || true

echo "=== 5. Runtime Permissions (Location, Sensors, Media, Storage) ==="
PERMS=(
  "android.permission.ACCESS_FINE_LOCATION"
  "android.permission.ACCESS_COARSE_LOCATION"
  "android.permission.ACCESS_BACKGROUND_LOCATION"
  "android.permission.ACTIVITY_RECOGNITION"
  "android.permission.BODY_SENSORS"
  "android.permission.READ_EXTERNAL_STORAGE"
  "android.permission.WRITE_EXTERNAL_STORAGE"
  "android.permission.READ_MEDIA_IMAGES"
  "android.permission.READ_MEDIA_AUDIO"
  "android.permission.READ_MEDIA_VIDEO"
  "android.permission.ACCESS_MEDIA_LOCATION"
  "android.permission.POST_NOTIFICATIONS"
  "android.permission.READ_PHONE_STATE"
  "android.permission.READ_CONTACTS"
  "android.permission.RECORD_AUDIO"
  "android.permission.CAMERA"
  "android.permission.READ_SMS"
  "android.permission.SEND_SMS"
  "android.permission.CALL_PHONE"
  "android.permission.READ_CALL_LOG"
)

for p in "${PERMS[@]}"; do
  adb shell "pm grant $PACKAGE $p" 2>/dev/null || true
done

echo "=============================================================================="
echo "✅ All Puppeteer permissions and Doze whitelist successfully granted!"
echo "=============================================================================="
