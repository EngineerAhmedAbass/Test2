#!/usr/bin/env sh
# تجربة الريسيفر (G-MScreen) ضد ريسيفر وهمي.
# الاستخدام: remote/test/run_rcv.sh <workdir> [--json] [--platform N] [--full]
W="$1"; shift; R="$(cd "$(dirname "$0")/.." && pwd)"
rm -rf "$W"; mkdir -p "$W/classes"
J="$R/android/app/src/main/java/com/tvremote/app"
javac -encoding UTF-8 -d "$W/classes" "$J/Proto.java" "$J/TvClient.java" "$J/Remote.java" "$J/GmsClient.java" "$R/test/RcvTest.java" 2>&1 | grep -v _OPTIONS
python3 -I "$R/test/fake_receiver.py" "$W" "$@" & PID=$!
i=0; while ! grep -q ready "$W/rcv.log" 2>/dev/null; do i=$((i+1)); [ $i -gt 100 ] && break; sleep 0.1; done
timeout 30 java -cp "$W/classes" com.tvremote.app.RcvTest 2>&1 | grep -v _OPTIONS
sleep 0.3; kill $PID
echo "--- receiver log"; cat "$W/rcv.log"
