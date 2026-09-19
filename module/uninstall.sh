#!/system/bin/sh
# Only stop the process with this module's dedicated process name.
for NAVI_PID in $(pidof navi_root); do kill "$NAVI_PID"; done
