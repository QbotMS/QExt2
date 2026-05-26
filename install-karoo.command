#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

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

printf '\n=== QExt2 -> Karoo installer ===\n\n'

ADB_BIN="$(find_adb || true)"
if [ -z "$ADB_BIN" ]; then
  printf 'BŁĄD: Nie znaleziono adb.\n'
  printf 'Zainstaluj Android Platform Tools albo uruchom raz Android Studio SDK Manager.\n'
  exit 1
fi

printf 'ADB: %s\n' "$ADB_BIN"
printf 'Projekt: %s\n\n' "$PROJECT_DIR"

printf '1/3 Buduję APK...\n'
cd "$PROJECT_DIR"
./gradlew app:assembleDebug

if [ ! -f "$APK_PATH" ]; then
  printf '\nBŁĄD: Nie znaleziono APK po buildzie:\n%s\n' "$APK_PATH"
  exit 1
fi

printf '\n2/3 Sprawdzam podłączone Karoo przez USB...\n'
"$ADB_BIN" start-server >/dev/null
DEVICES="$({ "$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" { print $1 }'; } || true)"
UNAUTHORIZED="$({ "$ADB_BIN" devices | awk 'NR > 1 && $2 == "unauthorized" { print $1 }'; } || true)"

if [ -n "$UNAUTHORIZED" ]; then
  printf '\nBŁĄD: Karoo jest podłączone, ale nieautoryzowane.\n'
  printf 'Odblokuj Karoo i zaakceptuj komunikat USB debugging / Allow USB debugging.\n'
  exit 1
fi

DEVICE_COUNT="$(printf '%s\n' "$DEVICES" | awk 'NF { count++ } END { print count + 0 }')"
if [ "$DEVICE_COUNT" -eq 0 ]; then
  printf '\nBŁĄD: Nie widzę Karoo przez adb.\n'
  printf 'Sprawdź kabel USB, tryb debugowania USB i czy Karoo jest odblokowane.\n'
  exit 1
fi

if [ "$DEVICE_COUNT" -gt 1 ]; then
  printf '\nBŁĄD: Wykryto więcej niż jedno urządzenie adb:\n%s\n' "$DEVICES"
  printf 'Odłącz pozostałe urządzenia i uruchom skrypt ponownie.\n'
  exit 1
fi

DEVICE_ID="$DEVICES"
printf 'Urządzenie: %s\n' "$DEVICE_ID"

printf '\n3/3 Instaluję APK na Karoo...\n'
"$ADB_BIN" -s "$DEVICE_ID" install -r "$APK_PATH"

printf '\nGOTOWE: QExt2 zostało zainstalowane na Karoo.\n'
printf 'Jeśli pole nie pojawi się w profilu, zrestartuj Karoo.\n'
