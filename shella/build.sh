#!/usr/bin/env bash
# Builds shella-alwan.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs ANDROID_HOME with platforms;android-34 and build-tools;34.0.0, and a JDK 17.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT"/{assets,res,classes,gen,dex}

# 1. The game page with the MQTT library inlined (no external files needed at runtime).
python3 - <<'EOF'
html = open('web/index.html', encoding='utf-8').read()
lib = open('web/mqtt.min.js', encoding='utf-8').read()
tag = '<script src="mqtt.min.js"></script>'
assert tag in html
html = html.replace(tag, '<script>' + lib.replace('</script', '<\\/script') + '</script>')
open('build/assets/index.html', 'w', encoding='utf-8').write(html)
EOF

# 2. Resources and manifest.
"$BT/aapt2" compile --dir android/res -o "$OUT/res/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest android/AndroidManifest.xml \
  -A "$OUT/assets" --java "$OUT/gen" "$OUT/res/res.zip"

# 3. Java -> dex.
javac -source 8 -target 8 -nowarn -encoding UTF-8 -bootclasspath "$JAR" -classpath "$JAR" -d "$OUT/classes" \
  $(find android/java "$OUT/gen" -name '*.java') -Xlint:-options
# d8 from build-tools 34 crashes on this machine's JDK 21, so use a newer standalone D8.
D8_JAR=tools/r8-8.5.35.jar
[ -f "$D8_JAR" ] || { mkdir -p tools && curl -sSfL -o "$D8_JAR" https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.5.35/r8-8.5.35.jar; }
java -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 21 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q -u ../base.apk classes.dex)

# 4. Align and sign (fixed key so new versions install over old ones).
KS=android/shella.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass shellaalwan -keypass shellaalwan -alias shella \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Shella Alwan" >/dev/null
fi
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:shellaalwan --key-pass pass:shellaalwan --ks-key-alias shella \
  --out shella-alwan.apk "$OUT/aligned.apk"
"$BT/apksigner" verify shella-alwan.apk
ls -lh shella-alwan.apk
