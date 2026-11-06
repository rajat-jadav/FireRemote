#!/usr/bin/env bash
# Installs the receiver on a Fire TV over Wi-Fi and switches everything on.
#
# Usage:  ./scripts/install_to_firetv.sh <fire-tv-ip> [path-to-apk]
#
# Needs: adb on your PATH, and on the Fire TV:
#   Settings > My Fire TV > Developer Options > ADB debugging = ON
#   (and "Install unknown apps" allowed for ADB on newer Fire OS versions)
set -euo pipefail

IP="${1:?Usage: $0 <fire-tv-ip> [path-to-apk]}"
APK="${2:-tvreceiver/build/outputs/apk/debug/tvreceiver-debug.apk}"
DEVICE="$IP:5555"
PKG="com.rajat.fireremote.tv"
SERVICE="$PKG/$PKG.RemoteAccessibilityService"

echo "==> Connecting to $DEVICE (accept the prompt on the TV the first time)"
adb connect "$DEVICE"

echo "==> Installing $APK"
adb -s "$DEVICE" install -r "$APK"

echo "==> Enabling the accessibility service (keeping any services already enabled)"
CURRENT="$(adb -s "$DEVICE" shell settings get secure enabled_accessibility_services | tr -d '\r')"
if [ -z "$CURRENT" ] || [ "$CURRENT" = "null" ]; then
  NEW="$SERVICE"
elif [[ "$CURRENT" == *"$SERVICE"* ]]; then
  NEW="$CURRENT"
else
  NEW="$CURRENT:$SERVICE"
fi
adb -s "$DEVICE" shell settings put secure enabled_accessibility_services "$NEW"
adb -s "$DEVICE" shell settings put secure accessibility_enabled 1

echo "==> Allowing the app to start other apps from the background"
adb -s "$DEVICE" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true

echo "==> Opening the app (it shows the IP address and pairing PIN)"
adb -s "$DEVICE" shell am start -n "$PKG/.MainActivity"

echo "Done. Install phonecontroller-debug.apk on your phone, then enter the IP and PIN."
