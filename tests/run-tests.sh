#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"
KOTLINC="${KOTLINC:-}"
if [ -z "$KOTLINC" ]; then
  if [ -x "$HOME/workspace/tools/kotlin/bin/kotlinc" ]; then KOTLINC="$HOME/workspace/tools/kotlin/bin/kotlinc"; else KOTLINC="$(command -v kotlinc)"; fi
fi
KOTLIN_HOME_DIR="$(cd "$(dirname "$KOTLINC")/.." && pwd)"
STDLIB="$KOTLIN_HOME_DIR/lib/kotlin-stdlib.jar"
mkdir -p build/test-classes
"$KOTLINC" -nowarn -d build/test-classes app/src/main/java/jp/filexplorer/app/FileOps.kt tests/FileOpsTest.kt
java -cp "build/test-classes:$STDLIB" FileOpsTestKt
