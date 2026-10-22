#!/usr/bin/env bash
# Поднять headless-эмулятор для connectedAndroidTest (запускать внутри nix develop).
set -euo pipefail
AVD=mys-api35
if ! avdmanager list avd -c | grep -qx "$AVD"; then
  echo no | avdmanager create avd -n "$AVD" -k "system-images;android-35;google_apis;x86_64"
fi
if ! adb devices | grep -q '^emulator-'; then
  emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
    > "${TMPDIR:-/tmp}/mys-emulator.log" 2>&1 &
fi
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
echo "эмулятор готов"
