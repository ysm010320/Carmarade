#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_LIB="${CAMARADE_GRADLE_LIB:-$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.14-bin/*/gradle-8.14/lib 2>/dev/null | head -1)}"
JAVA_BIN="${CAMARADE_JAVA:-$(command -v java)}"
mkdir -p "$ROOT/build/tests"
"$JAVA_BIN" -cp "$GRADLE_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$GRADLE_LIB/kotlin-stdlib-2.0.21.jar:$GRADLE_LIB/annotations-24.0.1.jar" \
  "$ROOT/core/Trip.kt" "$ROOT/core/DriveSim.kt" "$ROOT/core/InviteCode.kt" "$ROOT/core/Voice.kt" "$ROOT/tests/TripTest.kt" "$ROOT/tests/DriveSimTest.kt" "$ROOT/tests/InviteCodeTest.kt" "$ROOT/tests/VoiceTest.kt" -d "$ROOT/build/tests/tests.jar"
"$JAVA_BIN" -cp "$ROOT/build/tests/tests.jar:$GRADLE_LIB/kotlin-stdlib-2.0.21.jar" ai.camarade.core.TripTestKt
"$JAVA_BIN" -cp "$ROOT/build/tests/tests.jar:$GRADLE_LIB/kotlin-stdlib-2.0.21.jar" ai.camarade.core.DriveSimTestKt
"$JAVA_BIN" -cp "$ROOT/build/tests/tests.jar:$GRADLE_LIB/kotlin-stdlib-2.0.21.jar" ai.camarade.core.InviteCodeTestKt
"$JAVA_BIN" -cp "$ROOT/build/tests/tests.jar:$GRADLE_LIB/kotlin-stdlib-2.0.21.jar" ai.camarade.core.VoiceTestKt
