#!/usr/bin/env bash
# FileXplorer - Gradle/AGP を使わないオフライン手動ビルド (aapt2 + kotlinc + D8)
#
# UI は Jetpack Compose (Material3)。Compose をこの方式でビルドする要点:
#   * Compose コンパイラプラグインは Maven 配布の
#     kotlin-compose-compiler-plugin を -Xplugin で使う
#     (kotlinc 同梱の compose-compiler.jar は embeddable 版で通常の kotlinc では動かない)
#   * dex 化は build-tools 同梱の d8 ではなく単体 R8 8.7.18 の D8 CLI を使う
#     (同梱 d8 は一部の匿名内部クラスで NPE になる既知の問題があるため)
#   * ライブラリ AAR の res は全て aapt2 compile して --auto-add-overlay でリンクし、
#     R.txt を持つ AAR は --extra-packages で各ライブラリの R を生成する
#   * Compose はメソッド数が 64k を超えるため classes*.dex を全て入れる (マルチ dex)
#   * AAR 内の .so は lib/<abi>/ に無圧縮で同梱し、zipalign -p でページ整列する
#   * ライブラリのマニフェストはマージしない (AGP が無いため)。
#     androidx.startup 等のライブラリ宣言コンポーネントは無効になるが、
#     Compose UI の動作には影響しない
#
# 依存ライブラリは libs/ に置く (tools/resolve.py が Google Maven /
# Maven Central から解決・取得する。初回のみネットワークが必要)。
# kotlin-stdlib は kotlinc 配布物 (2.0.21) を使い、libs/ 側の 1.8.x は使わない。
#
# 使い方:
#   ./build-apk.sh           単体テスト → ビルド → デバッグ署名 (dist/filexplorer.apk)
#   ./build-apk.sh release   単体テスト → ビルド → リリース署名 (dist/filexplorer-release.apk)
#     リリース署名は環境変数で指定する (スクリプト内に秘密情報は書かない):
#       RELEASE_KEYSTORE / RELEASE_KEYSTORE_PASS / RELEASE_KEY_ALIAS
#       RELEASE_KEY_PASS (省略時は RELEASE_KEYSTORE_PASS と同じ)
set -euo pipefail
cd "$(dirname "$0")"
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"

# --- ツールチェーンの解決 ---------------------------------------------
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/workspace/tools/android/sdk}}"
BUILD_TOOLS="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "android.jar が見つかりません: $ANDROID_JAR" >&2; exit 1; }
[ -x "$BUILD_TOOLS/aapt2" ] || { echo "aapt2 が見つかりません: $BUILD_TOOLS" >&2; exit 1; }
JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"
command -v "$JAVAC" >/dev/null 2>&1 || JAVAC=javac
command -v "$KEYTOOL" >/dev/null 2>&1 || KEYTOOL=keytool

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

MODE="${1:-debug}"
case "$MODE" in
  debug|release) ;;
  *) echo "使い方: $0 [debug|release]" >&2; exit 1 ;;
esac

# --- [1/8] 単体テスト ---------------------------------------------------
echo "[1/8] unit tests"
./tests/run-tests.sh

# --- [2/8] 依存とツール jar の準備 --------------------------------------
echo "[2/8] dependencies"
COMPOSE_PLUGIN="tools/kotlin-compose-compiler-plugin-2.0.21.jar"
R8_JAR="tools/r8-8.7.18.jar"
if [ ! -f "$COMPOSE_PLUGIN" ]; then
  echo "  downloading kotlin-compose-compiler-plugin 2.0.21"
  curl -sfL -o "$COMPOSE_PLUGIN" \
    "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compose-compiler-plugin/2.0.21/kotlin-compose-compiler-plugin-2.0.21.jar"
fi
if [ ! -f "$R8_JAR" ]; then
  echo "  downloading r8 8.7.18"
  curl -sfL -o "$R8_JAR" \
    "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.7.18/r8-8.7.18.jar"
fi
if [ ! -f libs/artifacts.txt ]; then
  echo "  resolving libraries (tools/resolve.py)"
  python3 tools/resolve.py
fi

BUILD=build/manual
AAR_ROOT=build/aars
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/libres" "$AAR_ROOT"

# ライブラリ jar の収集 (artifacts.txt の順序どおり)
COMPILE_JARS=()
DEX_JARS=()
AAR_DIRS=()
while IFS= read -r artifact; do
  case "$artifact" in
    kotlin-stdlib*) continue ;;  # stdlib は kotlinc 配布物を使う
  esac
  path="libs/$artifact"
  [ -f "$path" ] || {
    echo "依存アーティファクトがありません: $path (python3 tools/resolve.py を実行してください)" >&2
    exit 1
  }
  if [[ "$artifact" == *.aar ]]; then
    dir="$AAR_ROOT/${artifact%.aar}"
    AAR_DIRS+=("$dir")
    if [ ! -d "$dir" ]; then
      mkdir -p "$dir"
      unzip -qo "$path" -d "$dir"
    fi
    if [ -f "$dir/classes.jar" ]; then
      COMPILE_JARS+=("$dir/classes.jar")
      DEX_JARS+=("$dir/classes.jar")
    fi
  else
    # クラスを含まない KMP ルート jar は除外する (重複クラス回避)
    if [ "$(unzip -l "$path" | grep -c '\.class' || true)" -gt 0 ]; then
      COMPILE_JARS+=("$path")
      DEX_JARS+=("$path")
    fi
  fi
done < libs/artifacts.txt
CLASSPATH="$(IFS=:; echo "${COMPILE_JARS[*]}")"
echo "  ライブラリ jar (クラスあり): ${#COMPILE_JARS[@]} 件"

# --- [3/8] aapt2 compile (アプリ + ライブラリのリソース) ----------------
echo "[3/8] aapt2 compile"
"$BUILD_TOOLS/aapt2" compile --dir app/src/main/res -o "$BUILD/libres/app.zip"
RES_ZIPS=("$BUILD/libres/app.zip")
EXTRA_PACKAGES=()
for dir in "${AAR_DIRS[@]}"; do
  if [ -d "$dir/res" ] && [ -n "$(find "$dir/res" -type f -print -quit)" ]; then
    out="$BUILD/libres/$(basename "$dir").zip"
    "$BUILD_TOOLS/aapt2" compile --dir "$dir/res" -o "$out"
    RES_ZIPS+=("$out")
  fi
  if [ -f "$dir/R.txt" ]; then
    pkg="$(grep -o 'package="[^"]*"' "$dir/AndroidManifest.xml" | head -1 | cut -d'"' -f2)"
    [ -n "$pkg" ] && EXTRA_PACKAGES+=("$pkg")
  fi
done
echo "  リソースセット: ${#RES_ZIPS[@]} 件 / R パッケージ: ${#EXTRA_PACKAGES[@]} 件"

# --- [4/8] aapt2 link ----------------------------------------------------
echo "[4/8] aapt2 link"
EXTRA_ARGS=()
if [ "${#EXTRA_PACKAGES[@]}" -gt 0 ]; then
  EXTRA_ARGS=(--extra-packages "$(IFS=:; echo "${EXTRA_PACKAGES[*]}")")
fi
# aapt2 link には package 属性が要るため、マニフェストへ注入したコピーを使う
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.filexplorer.app"|' \
  app/src/main/AndroidManifest.xml > "$BUILD/AndroidManifest.xml"
"$BUILD_TOOLS/aapt2" link -o "$BUILD/app-unsigned.apk" -I "$ANDROID_JAR" \
  --manifest "$BUILD/AndroidManifest.xml" "${RES_ZIPS[@]}" \
  --java "$BUILD/gen" --auto-add-overlay "${EXTRA_ARGS[@]}" \
  --min-sdk-version 26 --target-sdk-version 34

# --- [5/8] kotlinc (Compose コンパイラプラグイン有効) --------------------
echo "[5/8] kotlinc"
"$KOTLINC" -nowarn -jvm-target 17 -cp "$ANDROID_JAR:$CLASSPATH" -d "$BUILD/classes" \
  -Xplugin="$COMPOSE_PLUGIN" \
  $(find app/src/main/java -name '*.kt') $(find "$BUILD/gen" -name '*.java')

# --- [6/8] javac (aapt2 が生成した R クラス群) ---------------------------
echo "[6/8] javac (generated R classes)"
"$JAVAC" --release 17 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$BUILD/classes" \
  $(find "$BUILD/gen" -name '*.java')

# --- [7/8] D8 (単体 R8 / マルチ dex) -------------------------------------
echo "[7/8] d8 (standalone R8)"
java ${R8_JAVA_OPTS:--Xmx2g} -cp "$R8_JAR" com.android.tools.r8.D8 \
  --min-api 26 --lib "$ANDROID_JAR" --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class') "${DEX_JARS[@]}" "$STDLIB"
ls "$BUILD/dex"

# --- [8/8] パッケージ + zipalign + 署名 ----------------------------------
echo "[8/8] package + zipalign + sign ($MODE)"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
( cd "$BUILD/dex" && zip -q -uj ../app-with-dex.apk classes*.dex )
# AAR 内ネイティブライブラリ → lib/<abi>/ (無圧縮で格納し zipalign -p でページ整列)
while IFS= read -r so; do
  rel="${so#*jni/}"
  mkdir -p "$BUILD/nativelib/lib/$(dirname "$rel")"
  cp "$so" "$BUILD/nativelib/lib/$rel"
done < <(find "${AAR_DIRS[@]}" -path '*/jni/*' -name '*.so')
if [ -d "$BUILD/nativelib" ]; then
  ( cd "$BUILD/nativelib" && zip -q -0 -r ../app-with-dex.apk lib )
fi
"$BUILD_TOOLS/zipalign" -f -p 4 "$BUILD/app-with-dex.apk" "$BUILD/app-aligned.apk"

if [ "$MODE" = "release" ]; then
  # リリース署名: 環境変数で自分のキーストアを指定する (秘密情報はスクリプトに書かない)
  KS="${RELEASE_KEYSTORE:-}"
  [ -n "$KS" ] || { echo "リリース署名には環境変数 RELEASE_KEYSTORE (キーストアのパス) が必要です" >&2; exit 1; }
  [ -f "$KS" ] || { echo "キーストアが見つかりません: $KS" >&2; exit 1; }
  KS_PASS="${RELEASE_KEYSTORE_PASS:-}"
  [ -n "$KS_PASS" ] || { echo "環境変数 RELEASE_KEYSTORE_PASS (キーストアのパスワード) が必要です" >&2; exit 1; }
  KEY_PASS="${RELEASE_KEY_PASS:-$KS_PASS}"
  ALIAS="${RELEASE_KEY_ALIAS:-}"
  OUT="dist/filexplorer-release.apk"
else
  KS="debug.keystore"
  KS_PASS="android"
  KEY_PASS="android"
  ALIAS="androiddebugkey"
  if [ ! -f "$KS" ]; then
    "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=FileXplorer,C=JP" >/dev/null 2>&1
  fi
  OUT="dist/filexplorer.apk"
fi
echo "署名キーストア: $KS"
ALIAS_OPT=()
[ -n "$ALIAS" ] && ALIAS_OPT=(--ks-key-alias "$ALIAS")
"$BUILD_TOOLS/apksigner" sign --ks "$KS" --ks-pass pass:"$KS_PASS" --key-pass pass:"$KEY_PASS" \
  "${ALIAS_OPT[@]}" --out "$BUILD/filexplorer.apk" "$BUILD/app-aligned.apk"
"$BUILD_TOOLS/apksigner" verify --print-certs "$BUILD/filexplorer.apk" | head -3
mkdir -p dist
cp "$BUILD/filexplorer.apk" "$OUT"
echo "OK: $OUT"
