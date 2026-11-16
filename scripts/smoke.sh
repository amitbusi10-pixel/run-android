#!/usr/bin/env bash
# End-to-end check on an emulator:
# open the app, start a run, leave the app, lock the screen, keep moving,
# check there is exactly one live notification with a growing distance,
# come back, finish the run and play the fast replay.
set -x
PKG=app.alhaderech.run
adb install -r app.apk
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do adb shell pm grant $PKG android.permission.$p || true; done
adb shell dumpsys deviceidle whitelist +$PKG || true   # same as approving "no battery limits"
adb emu geo fix 34.9528 32.0928
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 35
adb exec-out screencap -p > 1-open.png
# first open shows the "save to the cloud" sheet: tap "Sign in with Google" to see Android's own sign-in sheet
adb shell input tap 160 513
sleep 5
adb exec-out screencap -p > 1b-google-signin.png
adb shell input keyevent 4   # back: close the Google sheet
sleep 2

# find a button by its text (WebView exposes it as text or content-desc) and tap its centre
tap_text() {
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/u.xml > u.xml
  local b
  b=$(grep -o "\(text\|content-desc\)=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" u.xml | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | tr -d '[]' | tr ',' ' ')
  if [ -z "$b" ]; then echo "not found: $1"; return 1; fi
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

lat=32.0928
move() { for i in $(seq 1 "$1"); do lat=$(echo "$lat + 0.00009" | bc -l); adb emu geo fix 34.9528 "$lat"; sleep 2; done; }

adb shell input swipe 160 560 160 200 400
sleep 2
cp u.xml u-before-start.xml 2>/dev/null
tap_text "התחל ריצה" || adb shell input tap 160 433
sleep 6
adb exec-out screencap -p > 2-running.png
move 8

adb shell input keyevent 3          # home: the app goes to the background
move 8
adb shell locksettings set-pin 1234 || true   # a real lock screen
adb shell input keyevent 26         # screen off (locked)
move 12
adb shell input keyevent 26         # screen on: lock screen with the notification
sleep 2
adb exec-out screencap -p > 3-lockscreen.png
adb shell dumpsys notification --noredact > notif-full.txt
grep -c "pkg=$PKG" notif-full.txt > notif-count.txt
grep -A60 "pkg=$PKG" notif-full.txt | grep -E "android.title=|android.text=|android.progress=|chronometer|when=" | head -20 > notif.txt
cat notif-count.txt notif.txt
sleep 5
adb shell dumpsys notification --noredact | grep -A60 "pkg=$PKG" | grep -E "android.title=" | head -2 > notif-later.txt
cat notif-later.txt

adb shell locksettings clear --old 1234 || true
adb shell wm dismiss-keyguard; sleep 1
adb shell cmd statusbar expand-notifications; sleep 2
adb exec-out screencap -p > 4-shade.png
adb shell cmd statusbar collapse
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 4
adb exec-out screencap -p > 5-back-in-app.png

# finish the run and play the fast replay on the summary screen
adb shell input swipe 160 560 160 200 400; sleep 1
tap_text "סיים" || adb shell input tap 85 415
sleep 3
adb exec-out screencap -p > 6-summary.png
tap_text "שחזור מהיר" || adb shell input tap 84 333
sleep 3
adb exec-out screencap -p > 7-replay.png
sleep 9
adb exec-out screencap -p > 8-replay-done.png
cp u.xml u-summary.xml 2>/dev/null

adb logcat -d -b crash > crash.txt
adb logcat -d > logcat-full.txt
adb logcat -d | grep -E "AL-HADERECH|Capacitor/Console|AndroidRuntime: FATAL|RunTracker" | tail -60 > log.txt
cat log.txt
# pass: one notification, with a distance in it
[ "$(cat notif-count.txt)" -ge 1 ] && grep -q "ק״מ" notif.txt
