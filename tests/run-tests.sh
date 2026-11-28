#!/usr/bin/env bash
# FileXplorer - JVM 単体テストの実行
# FileOps (純粋ロジック) とテスト本体を kotlinc でコンパイルし、JVM で実行する。
# Android SDK は不要で、JDK と Kotlin コンパイラだけがあれば動く。
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"

# kotlinc の場所: 環境変数 KOTLINC → KOTLIN_HOME → この VM の標準配置 → PATH の順
KOTLINC="${KOTLINC:-}"
if [ -z "$KOTLINC" ] && [ -n "${KOTLIN_HOME:-}" ]; then
  KOTLINC="$KOTLIN_HOME/bin/kotlinc"
fi
if [ -z "$KOTLINC" ] && [ -x "$HOME/workspace/tools/kotlin/bin/kotlinc" ]; then
  KOTLINC="$HOME/workspace/tools/kotlin/bin/kotlinc"
fi
if [ -z "$KOTLINC" ]; then
  KOTLINC="$(command -v kotlinc)"
fi
KOTLIN_HOME_DIR="$(cd "$(dirname "$KOTLINC")/.." && pwd)"
STDLIB="$KOTLIN_HOME_DIR/lib/kotlin-stdlib.jar"
[ -f "$STDLIB" ] || { echo "kotlin-stdlib.jar が見つかりません: $STDLIB" >&2; exit 1; }

OUT=build/test-classes
mkdir -p "$OUT"
"$KOTLINC" -nowarn -d "$OUT" \
  app/src/main/java/jp/filexplorer/app/FileOps.kt \
  tests/FileOpsTest.kt
java -cp "$OUT:$STDLIB" FileOpsTestKt
