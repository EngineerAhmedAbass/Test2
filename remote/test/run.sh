#!/usr/bin/env sh
# تجربة بروتوكول الريموت على الكمبيوتر ضد تليفزيون وهمي.
# الاستخدام: remote/test/run.sh <path/to/androidtvremote2 package dir> <workdir>
#   (androidtvremote2: pip download androidtvremote2 --no-deps ثم فك الـ wheel)
set -e
LIB="$1"; W="$2"; R="$(cd "$(dirname "$0")/.." && pwd)"
rm -rf "$W"; mkdir -p "$W/classes"
keytool -genkeypair -alias atvremote -keyalg RSA -keysize 2048 -dname "CN=atvremote" -validity 3650 \
  -storetype PKCS12 -keystore "$W/client.p12" -storepass testtest -keypass testtest >/dev/null 2>&1
keytool -exportcert -rfc -alias atvremote -keystore "$W/client.p12" -storepass testtest -file "$W/client.pem" >/dev/null 2>&1
javac -encoding UTF-8 -d "$W/classes" "$R"/android/app/src/main/java/com/tvremote/app/Proto.java \
  "$R"/android/app/src/main/java/com/tvremote/app/TvClient.java "$R"/test/ClientTest.java 2>&1 | grep -v _OPTIONS || true
python3 -I "$R/test/fake_tv.py" "$LIB" "$W" "$W/client.pem" & PID=$!
i=0; while ! grep -q ready "$W/tv.log" 2>/dev/null; do i=$((i+1)); [ $i -gt 100 ] && break; sleep 0.1; done
set +e
timeout 60 java -cp "$W/classes" com.tvremote.app.ClientTest "$W" 2>&1 | grep -v _OPTIONS; RC=$?
sleep 0.5; kill $PID
echo "--- TV log"; cat "$W/tv.log"
