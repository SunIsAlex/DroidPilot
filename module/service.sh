#!/system/bin/sh
MODDIR=${0%/*}
# Do not hold KernelSU's boot stage; the child waits for Android's package service.
(
  until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 3; done
  [ -f "$MODDIR/disable" ] && exit 0
  [ -f "$MODDIR/remove" ] && exit 0
  APP_UID=$(cmd package list packages -U dev.navix.agent | sed -n 's/^package:dev.navix.agent uid:\([0-9]*\)$/\1/p')
  [ -n "$APP_UID" ] || exit 0
  pidof navi_root >/dev/null && exit 0
  umask 077
  export CLASSPATH="$MODDIR/agent.apk"
  exec /system/bin/app_process /system/bin --nice-name=navi_root dev.navix.agent.RootDaemon "$APP_UID" >> "$MODDIR/daemon.log" 2>&1
) &
