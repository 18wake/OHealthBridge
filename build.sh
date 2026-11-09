#!/usr/bin/env bash
# OHealthBridge 免 Gradle 构建脚本 (Windows Git-Bash + JDK17)
# 现代 libxposed API 模块: META-INF/xposed/{java_init.list,module.prop,scope.list}
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$ROOT/../analysis/tools"
SDK="$TOOLS/android-sdk"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
BUILD="$ROOT/build"
OUT_APK="$ROOT/OHealthBridge-v2.19.apk"

JAVAC="${JAVAC:-javac}"
AAPT="$BT/aapt.exe"
ZIPALIGN="$BT/zipalign.exe"
D8() { java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 "$@"; }
APKSIGNER() { java -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool "$@"; }

# Windows javac/d8 读不了 /c/... 风格路径, 统一转 C:/...
to_win() { cygpath -m "$1" 2>/dev/null || echo "$1"; }

echo "== 0. check toolchain =="
[ -f "$AJAR" ]   || { echo "missing android.jar: $AJAR"; exit 1; }
[ -f "$AAPT" ]   || { echo "missing aapt: $AAPT"; exit 1; }
[ -f "$BT/lib/d8.jar" ] || { echo "missing d8.jar"; exit 1; }

rm -rf "$BUILD"
mkdir -p "$BUILD/obj" "$BUILD/apistub" "$BUILD/dex" "$BUILD/unsigned" "$BUILD/meta/META-INF/xposed"

echo "== 1. compile libxposed API stub (compile-only, never dexed) =="
find "$ROOT/apistub" -name '*.java' | while read -r f; do to_win "$f"; done > "$BUILD/api_sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$(to_win "$AJAR")" \
    -d "$(to_win "$BUILD/apistub")" @"$(to_win "$BUILD/api_sources.txt")"

echo "== 2. compile module sources (Java 8, bootclasspath=android.jar) =="
find "$ROOT/src" -name '*.java' | while read -r f; do to_win "$f"; done > "$BUILD/module_sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$(to_win "$AJAR")" \
    -classpath "$(to_win "$BUILD/apistub")" \
    -d "$(to_win "$BUILD/obj")" \
    -nowarn @"$(to_win "$BUILD/module_sources.txt")"

echo "== 3. dex (module classes only, API stubs excluded) ="
(cd "$BUILD/obj" && find dev -name '*.class' | while read -r f; do to_win "$PWD/$f"; done) > "$BUILD/dex_inputs.txt"
D8 --release --lib "$AJAR" --output "$BUILD/dex" @"$(to_win "$BUILD/dex_inputs.txt")"

echo "== 4. package resources/manifest =="
"$AAPT" package -f \
    -M "$ROOT/AndroidManifest.xml" \
    -S "$ROOT/res" \
    -I "$AJAR" \
    -F "$BUILD/unsigned/base.apk"

echo "== 5. add classes.dex =="
(cd "$BUILD/dex" && "$AAPT" add "$BUILD/unsigned/base.apk" classes.dex)

echo "== 6. add META-INF/xposed files =="
cp "$ROOT/META-INF/xposed/java_init.list" "$ROOT/META-INF/xposed/module.prop" "$ROOT/META-INF/xposed/scope.list" \
   "$BUILD/meta/META-INF/xposed/"
(cd "$BUILD/meta" && "$AAPT" add "$(to_win "$BUILD/unsigned/base.apk")" \
    META-INF/xposed/java_init.list META-INF/xposed/module.prop META-INF/xposed/scope.list)

echo "== 7. zipalign =="
"$ZIPALIGN" -f 4 "$BUILD/unsigned/base.apk" "$BUILD/unsigned/aligned.apk"

echo "== 8. sign =="
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
        -keyalg RSA -keysize 2048 -validity 10000
fi
APKSIGNER sign --ks "$KS" --ks-pass pass:android --ks-key-alias androiddebugkey \
    --out "$OUT_APK" "$BUILD/unsigned/aligned.apk"
APKSIGNER verify --print-certs "$OUT_APK" | head -2

echo "== done: $OUT_APK =="
ls -la "$OUT_APK"
