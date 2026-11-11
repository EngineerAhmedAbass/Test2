#!/usr/bin/env bash
# بيشغّل التطبيق على المحاكي ويدوس على الأزرار ويصوّر الشاشات،
# ولو التطبيق قفل في أي خطوة بيطلع الخطأ.
set -u
APK="$1"
OUT=ui-shots
PKG=com.prayer.tv
mkdir -p "$OUT"

# محاكي Android TV: الشاشة أفقي 1920x1080 زي التليفزيون
adb shell wm size
adb shell wm density

adb install -r "$APK"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
# عشان رسالة "الظهور فوق البرامج" ماتظهرش في أول تشغيل
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow || true
adb logcat -c

shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
alive() {
  if ! adb shell pidof $PKG >/dev/null; then echo "APP DIED after: $1"; return 1; fi
}
key() { adb shell input keyevent "$1"; sleep 0.6; }
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  local xy
  xy=$(python3 - "$1" "$OUT/ui.xml" <<'EOF'
import re, sys
want, path = sys.argv[1], sys.argv[2]
xml = open(path, encoding="utf-8").read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n)
    if t and t.group(1).strip() == want:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        x1, y1, x2, y2 = map(int, b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
EOF
)
  if [ -z "$xy" ]; then echo "text not found: $1"; return 1; fi
  adb shell input tap $xy
}

fail=0
adb shell am start -n $PKG/.MainActivity
shot 01-main 8; alive "launch" || fail=1

# الشاشة الرئيسية بالريموت: تحت = صف الأزرار، يمين على الآخر = الإعدادات
key KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_DOWN
for i in 1 2 3 4 5; do key KEYCODE_DPAD_RIGHT; done
shot 02-main-focus-settings 1
key KEYCODE_DPAD_CENTER
shot 03-settings 3; alive "open settings" || fail=1
for i in 1 2 3 4; do key KEYCODE_DPAD_DOWN; done
shot 04-settings-voice 1; alive "move in settings" || fail=1
key KEYCODE_DPAD_CENTER
shot 05-settings-voice-options 1; alive "open options" || fail=1
key KEYCODE_BACK
key KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_DOWN
shot 06-settings-loudness 1; alive "loudness row" || fail=1
for i in $(seq 1 6); do key KEYCODE_DPAD_DOWN; done
shot 07-settings-prayer 1; alive "prayer rows" || fail=1
key KEYCODE_DPAD_CENTER
shot 08-prayer-options 1; alive "prayer options" || fail=1
for i in $(seq 1 12); do key KEYCODE_DPAD_DOWN; done
shot 09-settings-general 1; alive "general rows" || fail=1
key KEYCODE_BACK
key KEYCODE_BACK
shot 10-back-main 2; alive "back to main" || fail=1

# الأذان وشاشته: من الإعدادات خطوة شمال = تجربة الأذان
key KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_DOWN
for i in 1 2 3 4 5; do key KEYCODE_DPAD_RIGHT; done
key KEYCODE_DPAD_LEFT
key KEYCODE_DPAD_CENTER
shot 11-adhan 4; alive "adhan" || fail=1
key KEYCODE_BACK

adb logcat -d > "$OUT/logcat.txt"
if grep -n -A 30 "FATAL EXCEPTION" "$OUT/logcat.txt"; then fail=1; fi
exit $fail
