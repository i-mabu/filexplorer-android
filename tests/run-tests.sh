#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"
[ -x "$JAVAC" ] || { JAVAC=javac; JAVA=java; }
mkdir -p build/test-classes
"$JAVAC" -encoding UTF-8 -d build/test-classes app/src/main/java/jp/filexplorer/app/FileOps.java tests/FileOpsTest.java
"$JAVA" -cp build/test-classes FileOpsTest
