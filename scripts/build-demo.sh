#!/bin/bash
# Low-disk offline build using already installed Kotlin compiler and official Android tools.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${CAMARADE_ANDROID_TOOLS:-$ROOT/.toolchain}"
GRADLE_LIB="${CAMARADE_GRADLE_LIB:-$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.14-bin/*/gradle-8.14/lib 2>/dev/null | head -1)}"
JDK="${CAMARADE_JDK:-/Library/Java/JavaVirtualMachines/jdk-17.0.2.jdk/Contents/Home}"
OUT="$ROOT/build/manual"
mkdir -p "$OUT/generated" "$OUT/dex" "$ROOT/apk"
"$TOOLS/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/resources.zip"
# AGP owns the package namespace; standalone aapt2 needs it in its generated input.
sed 's/<manifest /<manifest package="ai.camarade.app.demo" /' "$ROOT/app/src/main/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$TOOLS/aapt2" link -I "$TOOLS/android.jar" --manifest "$OUT/AndroidManifest.xml" --java "$OUT/generated" --auto-add-overlay -o "$OUT/base.apk" "$OUT/resources.zip"
SOURCES=("$ROOT/core/Trip.kt")
while IFS= read -r source; do SOURCES+=("$source"); done < <(find "$ROOT/app/src/main/java" -name '*.kt')
"$JDK/bin/java" -cp "$GRADLE_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 1.8 \
  -classpath "$TOOLS/android.jar:$GRADLE_LIB/kotlin-stdlib-2.0.21.jar:$GRADLE_LIB/annotations-24.0.1.jar" \
  "${SOURCES[@]}" -d "$OUT/classes.jar"
"$JDK/bin/java" -cp "$TOOLS/lib/d8-compatible.jar" com.android.tools.r8.D8 --lib "$TOOLS/android.jar" --min-api 28 --output "$OUT/dex" "$OUT/classes.jar" "$GRADLE_LIB/kotlin-stdlib-2.0.21.jar"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unaligned.apk" classes*.dex)
"$TOOLS/zipalign" -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"
if [ ! -f "$TOOLS/demo.keystore" ]; then
  "$JDK/bin/keytool" -genkeypair -keystore "$TOOLS/demo.keystore" -storepass android -keypass android -alias camarade-demo -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Camarade Demo, O=Local Development, C=KR'
fi
"$JDK/bin/java" -jar "$TOOLS/lib/apksigner.jar" sign --ks "$TOOLS/demo.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias camarade-demo --out "$ROOT/apk/carmarade-demo.apk" "$OUT/aligned.apk"
"$JDK/bin/java" -jar "$TOOLS/lib/apksigner.jar" verify --verbose "$ROOT/apk/carmarade-demo.apk"
"$TOOLS/aapt2" dump badging "$ROOT/apk/carmarade-demo.apk" | head -8
printf 'APK: %s\n' "$ROOT/apk/carmarade-demo.apk"
