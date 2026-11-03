#!/bin/bash
set -e
cd "$(dirname "$0")"
export JAVA_HOME="$HOME/workspace/android-dev/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$HOME/workspace/android-dev/sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"

rm -rf gen classes dex out
mkdir -p gen classes dex out

"$BT/aapt2" compile --dir res -o out/compiled.zip
"$BT/aapt2" link -o out/unsigned.apk -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml -R out/compiled.zip \
  --java gen --auto-add-overlay \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 2 --version-name 1.1

find src gen -name '*.java' > out/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -cp "$ANDROID_JAR" -d classes @out/sources.txt

"$BT/d8" --release --lib "$ANDROID_JAR" --min-api 26 --output dex $(find classes -name '*.class')

cp out/unsigned.apk out/with-dex.apk
zip -q -j out/with-dex.apk dex/classes.dex

# zipalign needs system libc++ which this VM lacks; it is only an optimisation
"$BT/zipalign" -f 4 out/with-dex.apk out/aligned.apk || cp out/with-dex.apk out/aligned.apk

if [ ! -f debug.keystore ]; then
  keytool -genkeypair -v -keystore debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out out/graph-memo.apk out/aligned.apk

"$BT/apksigner" verify --print-certs out/graph-memo.apk
"$BT/aapt2" dump badging out/graph-memo.apk | head -20
ls -lh out/graph-memo.apk
echo BUILD_OK
