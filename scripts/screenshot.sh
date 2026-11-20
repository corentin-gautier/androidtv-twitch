#!/bin/bash
# Retakes the README screenshot of the Following tab, without personal data: the app is started in
# demo mode, which shows a placeholder account and channels drawn at random from the most watched.
#
# Usage: scripts/screenshot.sh [adb-serial]
#
# The app must be installed and signed in (scripts/install-tv.sh). It is restarted twice: whatever
# is playing on the TV stops, and the app is left on its normal home screen.
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

capture="$(mktemp -t following).png"
"$ADB" -s "$serial" shell am force-stop "$PACKAGE"
"$ADB" -s "$serial" shell am start -n "$PACKAGE/.MainActivity" --ez demo true > /dev/null
sleep 10    # sign-in check, channel list, then the preview images
"$ADB" -s "$serial" exec-out screencap -p > "$capture"
sips -s format jpeg -s formatOptions 88 "$capture" --out docs/following.jpg > /dev/null
rm "$capture"

# Back to the normal app.
"$ADB" -s "$serial" shell am force-stop "$PACKAGE"
"$ADB" -s "$serial" shell am start -n "$PACKAGE/.MainActivity" > /dev/null
echo "Saved docs/following.jpg"
