#!/usr/bin/env bash
# Home Control helper. Usage:
#   ./ha.sh install        build the APK and install it on the TV
#   ./ha.sh config [file]  push config.json (default ./config.json) to the app
#   ./ha.sh open           open the panel now
#   ./ha.sh keys           watch which remote buttons reach apps (needs "log_keys": true)
#   ./ha.sh key-on         enable the remote-button service (opt-in)
#   ./ha.sh key-off        disable it again
#   ./ha.sh remove         disable the service and uninstall the app
[ -f "$HOME/glass-tv-env.sh" ] && source "$HOME/glass-tv-env.sh"
set -euo pipefail
cd "$(dirname "$0")"

PKG=io.hapanel.tv
SVC="$PKG/$PKG.KeyService"
: "${TV_HOST:=192.168.100.14}"
ADB_BIN="${ADB:-$HOME/platform-tools/adb}"
SERIAL="$TV_HOST:${TV_PORT:-5555}"
adb() { "$ADB_BIN" -s "$SERIAL" "$@"; }
"$ADB_BIN" connect "$SERIAL" >/dev/null 2>&1 || true

enabled_services() { adb shell settings get secure enabled_accessibility_services | tr -d '\r'; }

case "${1:-}" in
  install)
    ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    echo "Installed. Next: ./ha.sh config config.json   then   ./ha.sh open"
    echo "(If you had the remote-key service on, run ./ha.sh key-on again: reinstalling resets it.)"
    ;;
  config)
    f="${2:-config.json}"
    [ -f "$f" ] || { echo "no $f (copy config.example.json to config.json and edit it)"; exit 1; }
    python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$f" 2>/dev/null \
      || { echo "$f is not valid JSON"; exit 1; }
    adb shell "mkdir -p /sdcard/Android/data/$PKG/files"
    adb push "$f" "/sdcard/Android/data/$PKG/files/config.json"
    echo "Config pushed. Reopen the panel to load it."
    ;;
  open)
    adb shell am start -n "$PKG/.PanelActivity" >/dev/null
    ;;
  keys)
    echo "Press remote buttons; each one that reaches apps prints a line. Ctrl-C to stop."
    adb logcat -c
    adb logcat -s HaPanel:I
    ;;
  key-on)
    cur="$(enabled_services)"
    case "$cur" in
      *"$SVC"*) echo "already on" ;;
      null|"") adb shell settings put secure enabled_accessibility_services "$SVC" ;;
      *) adb shell settings put secure enabled_accessibility_services "$cur:$SVC" ;;
    esac
    adb shell settings put secure accessibility_enabled 1
    echo "Remote-key service on. Set \"key\" in config.json and push it with ./ha.sh config."
    ;;
  key-off)
    cur="$(enabled_services)"
    if [ "$cur" != "null" ] && [ -n "$cur" ]; then
      new="$( { printf '%s' "$cur" | tr ':' '\n' | grep -v -x -F "$SVC" || true; } | paste -sd: - )"
      if [ -z "$new" ]; then adb shell settings delete secure enabled_accessibility_services
      else adb shell settings put secure enabled_accessibility_services "$new"; fi
    fi
    echo "Remote-key service off."
    ;;
  remove)
    "$0" key-off
    adb uninstall "$PKG"
    ;;
  *)
    sed -n '2,10p' "$0"
    ;;
esac
