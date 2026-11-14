#!/usr/bin/env bash
# End-to-end check on an emulator: open the app, start a run, leave the app,
# keep moving, and make sure the lock-screen notification shows a growing distance.
set -x
PKG=app.alhaderech.run
adb install -r app.apk
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do adb shell pm grant $PKG android.permission.$p || true; done
adb emu geo fix 34.9528 32.0928
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 35
adb exec-out screencap -p > 1-open.png

tap_text() {
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/u.xml > u.xml
  local b
  b=$(grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" u.xml | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | tr -d '[]' | tr ',' ' ')
  if [ -z "$b" ]; then echo "not found: $1"; return 1; fi
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

adb shell input swipe 160 560 160 200 400
sleep 2
tap_text "התחל ריצה" || { adb shell input swipe 160 560 160 150 400; sleep 2; tap_text "התחל ריצה"; }
sleep 6
adb exec-out screencap -p > 2-running.png

lat=32.0928
move() { for i in $(seq 1 "$1"); do lat=$(echo "$lat + 0.00009" | bc -l); adb emu geo fix 34.9528 "$lat"; sleep 2; done; }
move 10
adb shell input keyevent 3   # home: the app goes to the background
move 25
adb shell cmd statusbar expand-notifications
sleep 2
adb exec-out screencap -p > 3-notification.png
adb shell dumpsys notification --noredact | grep -A40 "pkg=$PKG" | grep -E "android.title|android.text" > notif.txt
cat notif.txt
adb logcat -d | grep -E "AL-HADERECH|Capacitor/Console|AndroidRuntime: FATAL" | tail -40 > log.txt
cat log.txt
grep -q "ק״מ" notif.txt
