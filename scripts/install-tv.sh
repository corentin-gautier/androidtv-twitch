#!/bin/bash
# Builds the release APK, installs it on the Android TV / Chromecast and launches it.
#
# Usage: scripts/install-tv.sh [adb-serial]
#
# Without a serial, the device is found through mDNS: it must be on the same network with
# Settings > System > Developer options > Wireless debugging turned on, and already paired
# with this computer (adb pair <ip>:<port>, once).
set -euo pipefail

cd "$(dirname "$0")/.."
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
PACKAGE="dev.androidtv.twitch"

serial="${1:-}"
if [ -z "$serial" ]; then
    serial="$("$ADB" mdns services | awk '/_adb-tls-connect/ {print $1 "._adb-tls-connect._tcp"; exit}')"
    if [ -z "$serial" ]; then
        echo "No device found. Turn on Wireless debugging on the TV, or pass an adb serial." >&2
        exit 1
    fi
fi

./gradlew :app:assembleRelease -q
"$ADB" -s "$serial" install -r app/build/outputs/apk/release/app-release.apk
"$ADB" -s "$serial" shell am start -n "$PACKAGE/.MainActivity"
