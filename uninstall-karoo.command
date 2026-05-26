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

printf '\n=== QExt2 -> Karoo uninstaller ===\n\n'

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
printf 'Urządzenie: %s\n' "$DEVICE_ID"
printf 'Odinstalowuję com.qext2.primary...\n'

if "$ADB_BIN" -s "$DEVICE_ID" uninstall com.qext2.primary; then
  printf '\nGOTOWE: QExt2 zostało odinstalowane z Karoo.\n'
else
  printf '\nQExt2 nie było zainstalowane albo odinstalowanie się nie powiodło.\n'
fi

pause
