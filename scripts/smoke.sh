#!/usr/bin/env bash
# End-to-end check on an emulator ("the bot"):
# open the app, start a run, leave the app, lock the screen, keep moving,
# check there is exactly ONE notification, that it stays put (its timer base never changes),
# that its pause button works from the notification, then come back, finish and play the fast replay.
# OLD=1 runs the same flow on an old APK (before the native service) to check the web fix for old installs.
set -x
PKG=app.alhaderech.run
RESULT=results.txt; : > $RESULT
# distinct notifications (the same one can appear twice in the report while it is being updated)
ncount() { grep -o "NotificationRecord([^ ]* pkg=[^ ]* user=[^ ]* id=[0-9]*" "$1" | grep -o "id=[0-9]*" | sort -u | wc -l | tr -d ' '; }
check() { if eval "$2"; then echo "PASS $1" >> $RESULT; else echo "FAIL $1" >> $RESULT; fi; }

adb install -r app.apk
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do adb shell pm grant $PKG android.permission.$p || true; done
adb shell dumpsys deviceidle whitelist +$PKG || true   # same as approving "no battery limits"
adb emu geo fix 34.9528 32.0928
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
# wait until the web app has really loaded (the emulator network is sometimes slow)
for i in $(seq 1 60); do adb logcat -d | grep -q "AL-HADERECH native ok" && break; sleep 2; done
sleep 8   # the cloud sheet opens 4 seconds after loading
adb exec-out screencap -p > 1-open.png
# first open shows the "save to the cloud" sheet (new versions): "not now"
tap_text "לא עכשיו" || adb shell input tap 160 560
sleep 3
tap_text "לא עכשיו" && sleep 2   # once more if the sheet was still sliding in
if [ -n "$OLD" ]; then
  # the old app shows "there is a new version": the button must open the download outside the app
  tap_text "עדכון" || adb shell input tap 87 133
  sleep 6
  adb exec-out screencap -p > 1c-update-tapped.png
  TOP=$(adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity")
  echo "$TOP" > update-top.txt
  check "update button opens the download ($TOP)" '! echo "$TOP" | grep -q "$PKG"'
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1; sleep 25
  adb shell input tap 160 560; sleep 2   # the cloud sheet, which waits until the update banner is gone
fi
adb exec-out screencap -p > 1b-after-sheet.png

# find a view by its text (or content-desc) and tap its centre
tap_text() {
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/u.xml > u.xml
  local b
  b=$(grep -o "\(text\|content-desc\)=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" u.xml | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | sed 's/\]\[/,/; s/[][]//g' | tr ',' ' ')
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
COUNT=$(ncount notif-1.txt)
check "one notification only (found $COUNT)" '[ "$COUNT" = 1 ]'
check "no 'ריצה פעילה' text" '! grep -E "android.(title|text)=" notif-1.txt | grep -q "ריצה פעילה"'
check "says 'הריצה התחילה'" 'grep -q "הריצה התחילה" notif-1.txt'
W1=$(grep -o " when=[0-9]*" notif-1.txt | head -1); W2=$(grep -o " when=[0-9]*" notif-2.txt | head -1)
check "timer base stays the same ($W1 / $W2)" '[ -n "$W1" ] && [ "$W1" = "$W2" ]'
if [ -z "$OLD" ]; then
  check "distance in the notification" 'grep -q "android.title=.*ק״מ" notif-2.txt'
  T1=$(grep -o "android.title=String ([^)]*)" notif-1.txt | head -1); T2=$(grep -o "android.title=String ([^)]*)" notif-2.txt | head -1)
  check "distance grows ($T1 -> $T2)" '[ "$T1" != "$T2" ]'
  check "custom layout with timer" 'grep -qE "contentView=|customContentView|RemoteViews" notif-1.txt'
fi

adb shell locksettings clear --old 1234 || true
adb shell input swipe 160 560 160 100 300; sleep 1
adb shell wm dismiss-keyguard; sleep 1
adb shell input keyevent 3; sleep 1
adb shell cmd statusbar expand-notifications; sleep 2
adb exec-out screencap -p > 4-shade.png
if [ -z "$OLD" ]; then
  # pause from the notification itself
  tap_text "השהה" || adb shell input tap 85 388
  cp u.xml u-shade.xml
  sleep 3
  notifs > notif-paused.txt
  adb exec-out screencap -p > 4b-paused.png
  check "pause button in the notification" 'grep -q "בהשהיה" notif-paused.txt'
  tap_text "המשך" || adb shell input tap 85 388; sleep 3
  notifs > notif-resumed.txt
  check "resume button in the notification" 'grep -q "הריצה התחילה" notif-resumed.txt && grep -q "בהשהיה" notif-paused.txt'
fi
adb shell cmd statusbar collapse
if [ -z "$OLD" ]; then
  # Android kills the app in the middle of the run (like low memory): the run must go on
  TB=$(notifs | grep -o "android.title=String ([0-9.]*" | head -1 | grep -o "[0-9.]*$")
  adb root; sleep 3; adb wait-for-device
  adb shell kill -9 $(adb shell pidof $PKG) || true
  sleep 8
  move 6
  notifs > notif-killed.txt
  adb exec-out screencap -p > 4c-after-kill.png
  check "notification back after Android killed the app" '[ "$(ncount notif-killed.txt)" = 1 ]'
  TK=$(grep -o "android.title=String ([0-9.]*" notif-killed.txt | head -1 | grep -o "[0-9.]*$")
  check "notification keeps counting while the app is closed ($TB -> $TK km)" '[ -n "$TK" ] && [ "$(echo "$TK > $TB + 0.03" | bc)" = 1 ]'
  # pause from the notification while the app is closed
  adb shell cmd statusbar expand-notifications; sleep 2
  tap_text "השהה" || adb shell input tap 85 388; sleep 3
  notifs > notif-killed-paused.txt
  check "pause works while the app is closed" 'grep -q "בהשהיה" notif-killed-paused.txt'
  tap_text "המשך" || adb shell input tap 85 388; sleep 2
  adb shell cmd statusbar collapse
fi
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 25
adb exec-out screencap -p > 5-back-in-app.png
move 4
if [ -z "$OLD" ]; then
  TA=$(notifs | grep -o "android.title=String ([0-9.]*" | head -1 | grep -o "[0-9.]*$")
  check "distance kept and grew after the kill ($TB -> $TA km)" '[ -n "$TA" ] && [ -n "$TB" ] && [ "$(echo "$TA > $TB + 0.05" | bc)" = 1 ]'
fi

# finish the run and play the fast replay on the summary screen
tap_text "לא עכשיו" && sleep 2   # the cloud sheet, if it is open
adb shell input swipe 160 560 160 200 400; sleep 1
tap_text "סיים" || adb shell input tap 85 415
sleep 3
adb exec-out screencap -p > 6-summary.png
sleep 1
notifs > notif-after.txt
check "notification gone after finishing" '[ "$(ncount notif-after.txt)" = 0 ]'
tap_text "שחזור מהיר" || adb shell input tap 84 333
sleep 3
adb exec-out screencap -p > 7-replay.png
sleep 9
adb exec-out screencap -p > 8-replay-done.png

if [ -z "$OLD" ]; then
  # start another run, then swipe the app away from the recent-apps list: the notification must disappear
  tap_text "ריצה" || adb shell input tap 285 600; sleep 2
  tap_text "התחל ריצה" || adb shell input tap 160 433; sleep 8
  notifs > notif-run2.txt
  check "second run shows a notification ($(ncount notif-run2.txt))" '[ "$(ncount notif-run2.txt)" = 1 ]'
  adb shell input keyevent 187; sleep 3
  adb exec-out screencap -p > 9-recents.png
  adb shell input swipe 250 300 250 0 300; sleep 4
  notifs > notif-swiped.txt
  check "notification gone after swiping the app away" '[ "$(ncount notif-swiped.txt)" = 0 ]'
  adb shell input keyevent 3
fi
adb logcat -d -b crash > crash.txt
adb logcat -d > logcat-full.txt
adb logcat -d | grep -E "AL-HADERECH|Capacitor/Console|AndroidRuntime: FATAL|RunTracker" | tail -60 > log.txt
cat log.txt
check "no crash of the app" '! grep -A1 "FATAL EXCEPTION" crash.txt | grep -q "Process: $PKG"'
cat $RESULT
! grep -q FAIL $RESULT
