#!/usr/bin/env bash
# Boots the LightOS emulator AVD (if not already running) and installs/launches
# the :tool app on it. One-time system-app setup (signing key + priv-app push)
# is assumed to already be done — see docs/system_app/README.md if not.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

AVD_NAME="${AVD_NAME:-LightPhone3}"
TOOL_APP_ID="$(grep -m1 '^id' tool/lighttool.toml | sed -E 's/.*"(.*)".*/\1/')"
TOOL_ACTIVITY="com.thelightphone.sdk.LightActivity"

wait_for_boot() {
    adb wait-for-device
    adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'
}

if adb devices | grep -q "^emulator-.*device$"; then
    echo "Emulator already running."
else
    echo "Booting $AVD_NAME..."
    nohup emulator -avd "$AVD_NAME" -writable-system >/tmp/light-emulator.log 2>&1 &
    disown
    wait_for_boot
fi

echo "Building and installing :tool..."
./gradlew :tool:installDebug

echo "Launching $TOOL_APP_ID..."
adb shell am start -n "$TOOL_APP_ID/$TOOL_ACTIVITY"
