#!/usr/bin/env sh
# تجربة التحكم في LG ضد تليفزيون وهمي.
# الاستخدام: remote/test/run_lg.sh <org.json jar> <workdir> [--no-wss] [--reject-signed]
# (محتاج: pip install websockets cryptography، و json.jar من Maven: org.json:json)
JSONJAR="$1"; W="$2"; shift 2; R="$(cd "$(dirname "$0")/.." && pwd)"
rm -rf "$W"; mkdir -p "$W/classes"
J="$R/android/app/src/main/java/com/tvremote/app"
javac -encoding UTF-8 -cp "$JSONJAR" -d "$W/classes" "$J/Proto.java" "$J/TvClient.java" "$J/Remote.java" "$J/L.java" "$J/Ws.java" "$J/LgClient.java" "$R/test/LgTest.java" 2>&1 | grep -v _OPTIONS
python3 -I "$R/test/fake_lg.py" "$W" "$@" & PID=$!
i=0; while ! grep -q ready "$W/lg.log" 2>/dev/null; do i=$((i+1)); [ $i -gt 100 ] && break; sleep 0.1; done
NP=""; case "$*" in *--no-pointer*) NP="-Dnopointer=1";; esac
timeout 60 java $NP -cp "$W/classes:$JSONJAR" com.tvremote.app.LgTest 2>&1 | grep -v _OPTIONS
sleep 0.5; kill $PID
echo "--- TV log"; cat "$W/lg.log"
