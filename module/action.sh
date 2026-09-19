#!/system/bin/sh
MODDIR=${0%/*}
if pidof navi_root >/dev/null; then
  echo "DroidPilot root service is running."
else
  sh "$MODDIR/service.sh"
  echo "DroidPilot root service startup requested."
fi
