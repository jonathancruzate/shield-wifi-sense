#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLCHAIN_DIR="$PROJECT_DIR/.toolchain"

JDK_HOME="$(find "$TOOLCHAIN_DIR" -maxdepth 1 -type d -name 'jdk*' | head -n 1)"
BUILD_TOOLS="$TOOLCHAIN_DIR/android-14"
PLATFORM_JAR="$TOOLCHAIN_DIR/android-11/android.jar"

export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$BUILD_TOOLS:$PATH"

BUILD_DIR="$PROJECT_DIR/app/build"
GEN_DIR="$BUILD_DIR/generated"
OBJ_DIR="$BUILD_DIR/intermediates/javac"
DEX_DIR="$BUILD_DIR/intermediates/dex"
APK_DIR="$BUILD_DIR/outputs/apk/debug"

rm -rf "$BUILD_DIR"
mkdir -p "$GEN_DIR" "$OBJ_DIR" "$DEX_DIR" "$APK_DIR"

echo "[1/5] Compiling resources with aapt2..."
"$BUILD_TOOLS/aapt2" compile --dir "$PROJECT_DIR/app/src/main/res" -o "$BUILD_DIR/resources.zip"

echo "[2/5] Linking resources & generating R.java..."
"$BUILD_TOOLS/aapt2" link \
  -I "$PLATFORM_JAR" \
  --manifest "$PROJECT_DIR/app/src/main/AndroidManifest.xml" \
  --java "$GEN_DIR" \
  -o "$BUILD_DIR/unaligned.apk" \
  "$BUILD_DIR/resources.zip"

echo "[3/5] Compiling Java sources with javac (target 1.8 / SDK 30)..."
"$JAVA_HOME/bin/javac" \
  -source 1.8 -target 1.8 \
  -bootclasspath "$PLATFORM_JAR" \
  -classpath "$PLATFORM_JAR" \
  -d "$OBJ_DIR" \
  "$GEN_DIR/com/jonathancruzate/shieldwifisense/R.java" \
  "$PROJECT_DIR/app/src/main/java/com/jonathancruzate/shieldwifisense/MainActivity.java"

echo "[4/5] Dexing bytecode with d8..."
CLASS_FILES=()
while IFS= read -r -d '' f; do
  CLASS_FILES+=("$f")
done < <(find "$OBJ_DIR" -name "*.class" -print0)

"$BUILD_TOOLS/d8" \
  --lib "$PLATFORM_JAR" \
  --min-api 26 \
  --output "$DEX_DIR" \
  "${CLASS_FILES[@]}"

echo "[5/5] Packaging, zipaligning, and signing debug APK..."
cp "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/with-dex.apk"
(cd "$DEX_DIR" && zip -q -u "$BUILD_DIR/with-dex.apk" classes.dex)

"$BUILD_TOOLS/zipalign" -f -p 4 "$BUILD_DIR/with-dex.apk" "$APK_DIR/shield-wifi-sense-unsigned.apk"

KEYSTORE="$PROJECT_DIR/.toolchain/debug.keystore"
if [[ ! -f "$KEYSTORE" ]]; then
  "$JAVA_HOME/bin/keytool" -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass android \
    -keypass android \
    -alias androiddebugkey \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

FINAL_APK="$APK_DIR/shield-wifi-sense-debug.apk"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$FINAL_APK" \
  "$APK_DIR/shield-wifi-sense-unsigned.apk"

"$BUILD_TOOLS/apksigner" verify --verbose "$FINAL_APK" | head -n 5
cp "$FINAL_APK" "$PROJECT_DIR/shield-wifi-sense-debug.apk"
echo "SUCCESS: Built $FINAL_APK (and $PROJECT_DIR/shield-wifi-sense-debug.apk)"
