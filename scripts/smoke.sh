#!/usr/bin/env bash
# End-to-end check on an emulator ("the bot"):
# open the app, start a run, leave the app, lock the screen, keep moving,
# check there is exactly ONE notification, that it stays put (its timer base never changes),
# that its pause button works from the notification, then come back, finish and play the fast replay.
# OLD=1 runs the same flow on an old APK (before the native service) to check the web fix for old installs.
set -x
PKG=app.alhaderech.run
RESULT=results.txt; : > $RESULT
check() { if eval "$2"; then echo "PASS $1" >> $RESULT; else echo "FAIL $1" >> $RESULT; fi; }

adb install -r app.apk
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do adb shell pm grant $PKG android.permission.$p || true; done
adb shell dumpsys deviceidle whitelist +$PKG || true   # same as approving "no battery limits"
adb emu geo fix 34.9528 32.0928
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 35
adb exec-out screencap -p > 1-open.png
# first open shows the "save to the cloud" sheet (new versions): "not now"
tap_text "לא עכשיו" || adb shell input tap 160 560
sleep 3
adb exec-out screencap -p > 1b-after-sheet.png

# find a view by its text (or content-desc) and tap its centre
tap_text() {
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/u.xml > u.xml
  local b
  b=$(grep -o "\(text\|content-desc\)=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" u.xml | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | tr -d '[]' | tr ',' ' ')
  if [ -z "$b" ]; then echo "not found: $1"; return 1; fi
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
# the app's notifications: one block per notification
notifs() { adb shell dumpsys notification --noredact | awk -v p="pkg=$PKG" '/NotificationRecord\(/{on=index($0,p)>0} on'; }

lat=32.0928
move() { for i in $(seq 1 "$1"); do lat=$(echo "$lat + 0.00009" | bc -l); adb emu geo fix 34.9528 "$lat"; sleep 2; done; }

adb shell input swipe 160 560 160 200 400
sleep 2
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
notifs > notif-1.txt
move 4
notifs > notif-2.txt
grep -E "android.title=|android.text=|when=|android.progress=" notif-1.txt | head -12 > notif.txt
cat notif.txt
COUNT=$(grep -c "NotificationRecord(" notif-1.txt)
check "one notification only (found $COUNT)" '[ "$COUNT" = 1 ]'
check "no 'ריצה פעילה' text" '! grep -q "ריצה פעילה" notif-1.txt'
check "says 'הריצה התחילה'" 'grep -q "הריצה התחילה" notif-1.txt'
W1=$(grep -o " when=[0-9]*" notif-1.txt | head -1); W2=$(grep -o " when=[0-9]*" notif-2.txt | head -1)
check "timer base stays the same ($W1 / $W2)" '[ -n "$W1" ] && [ "$W1" = "$W2" ]'
if [ -z "$OLD" ]; then
  check "distance in the notification" 'grep -q "android.title=.*ק״מ" notif-2.txt'
  T1=$(grep -o "android.title=String ([^)]*)" notif-1.txt | head -1); T2=$(grep -o "android.title=String ([^)]*)" notif-2.txt | head -1)
  check "distance grows ($T1 -> $T2)" '[ "$T1" != "$T2" ]'
  check "live timer (chronometer)" 'grep -q "android.showChronometer=Boolean (true)" notif-1.txt'
fi

adb shell locksettings clear --old 1234 || true
adb shell wm dismiss-keyguard; sleep 1
adb shell cmd statusbar expand-notifications; sleep 2
adb exec-out screencap -p > 4-shade.png
if [ -z "$OLD" ]; then
  # pause from the notification itself
  tap_text "השהה"
  sleep 3
  notifs > notif-paused.txt
  adb exec-out screencap -p > 4b-paused.png
  check "pause button in the notification" 'grep -q "בהשהיה" notif-paused.txt'
  tap_text "המשך"; sleep 3
  notifs > notif-resumed.txt
  check "resume button in the notification" 'grep -q "הריצה התחילה" notif-resumed.txt'
fi
adb shell cmd statusbar collapse
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 4
adb exec-out screencap -p > 5-back-in-app.png
move 4

# finish the run and play the fast replay on the summary screen
adb shell input swipe 160 560 160 200 400; sleep 1
tap_text "סיים" || adb shell input tap 85 415
sleep 3
adb exec-out screencap -p > 6-summary.png
sleep 1
notifs > notif-after.txt
check "notification gone after finishing" '[ "$(grep -c "NotificationRecord(" notif-after.txt)" = 0 ]'
tap_text "שחזור מהיר" || adb shell input tap 84 333
sleep 3
adb exec-out screencap -p > 7-replay.png
sleep 9
adb exec-out screencap -p > 8-replay-done.png

adb logcat -d -b crash > crash.txt
adb logcat -d > logcat-full.txt
adb logcat -d | grep -E "AL-HADERECH|Capacitor/Console|AndroidRuntime: FATAL|RunTracker" | tail -60 > log.txt
cat log.txt
check "no crash" '! grep -q "FATAL EXCEPTION" crash.txt'
cat $RESULT
! grep -q FAIL $RESULT
