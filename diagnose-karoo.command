#!/usr/bin/env bash
set -euo pipefail

find_adb() {
  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return 0
  fi

  for candidate in \
    "$HOME/Library/Android/sdk/platform-tools/adb" \
    "/opt/homebrew/bin/adb" \
    "/usr/local/bin/adb"; do
    if [ -x "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

pause() {
  printf '\nNaciśnij Enter, aby zamknąć okno...'
  read -r _ || true
  osascript -e 'tell application "Terminal" to close first window'
}

printf '\n=== QExt2 -> Karoo diagnostics ===\n\n'

ADB_BIN="$(find_adb || true)"
if [ -z "$ADB_BIN" ]; then
  printf 'BŁĄD: Nie znaleziono adb.\n'
  pause
  exit 1
fi

"$ADB_BIN" start-server >/dev/null
DEVICES="$({ "$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" { print $1 }'; } || true)"
DEVICE_COUNT="$(printf '%s\n' "$DEVICES" | awk 'NF { count++ } END { print count + 0 }')"

if [ "$DEVICE_COUNT" -eq 0 ]; then
  printf 'BŁĄD: Nie widzę Karoo przez adb.\n'
  pause
  exit 1
fi

if [ "$DEVICE_COUNT" -gt 1 ]; then
  printf 'BŁĄD: Wykryto więcej niż jedno urządzenie adb:\n%s\n' "$DEVICES"
  pause
  exit 1
fi

DEVICE_ID="$DEVICES"
printf 'Urządzenie: %s\n\n' "$DEVICE_ID"

printf '1/5 Pakiet QExt2:\n'
"$ADB_BIN" -s "$DEVICE_ID" shell pm list packages | grep 'com.qext2.primary' || true

printf '\n2/5 Wersja pakietu:\n'
"$ADB_BIN" -s "$DEVICE_ID" shell dumpsys package com.qext2.primary | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime' || true

printf '\n3/5 Service extension:\n'
"$ADB_BIN" -s "$DEVICE_ID" shell dumpsys package com.qext2.primary | grep -A 20 -B 5 'QExt2PrimaryExtension' || true

printf '\n4/5 Czy Karoo widzi intent KAROO_EXTENSION w pakiecie:\n'
"$ADB_BIN" -s "$DEVICE_ID" shell cmd package query-intent-services -a io.hammerhead.karooext.KAROO_EXTENSION com.qext2.primary || true

printf '\n5/5 Ostatnie logi związane z QExt2 / KarooExtension / Extensions:\n'
"$ADB_BIN" -s "$DEVICE_ID" logcat -d -v time | grep -Ei 'qext2|karooext|karoo extension|extensioninfo|extensions' | tail -120 || true

printf '\n=== Koniec diagnostyki ===\n'
pause
