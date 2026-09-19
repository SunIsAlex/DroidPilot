#!/data/data/com.termux/files/usr/bin/sh
set -eu
cd "$(dirname "$0")"
NAVI_APP_UID=$(su -c 'cmd package list packages -U dev.navix.agent' | sed -n 's/^package:dev.navix.agent uid:\([0-9]*\)$/\1/p')
[ -n "$NAVI_APP_UID" ] || { echo 'Install DroidPilot APK first'; exit 1; }
umask 077
setsid -f python "$PWD/bridge.py" --app-uid "$NAVI_APP_UID" > bridge.log 2>&1 < /dev/null
printf 'Codex bridge startup requested. Log: %s/bridge.log\n' "$PWD"
