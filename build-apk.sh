#!/usr/bin/env bash
# FileXplorer - オフライン手動ビルド (Gradle/AGPを使わず aapt2 + kotlinc + D8 でAPKを作る)
# UI は Jetpack Compose。Compose 化に伴うパイプラインの要点 (compose-spike で検証済み):
#   * Compose コンパイラプラグインは Maven 版 kotlin-compose-compiler-plugin を -Xplugin で使う
#     (kotlinc 同梱の compose-compiler.jar は embeddable コンパイラ用で通常の kotlinc では動かない)
#   * デクサは build-tools 34.0.0 の d8 ではなく単体 R8 8.7.18 の D8 CLI を使う
#     (同梱 d8 は lifecycle の匿名内部クラスで NPE クラッシュするため)
#   * ライブラリ AAR の res は全部 aapt2 compile して --auto-add-overlay でリンクし、
#     R.txt を持つ AAR は --extra-packages で各ライブラリの R を生成する
#   * Compose のメソッド数は 64k を超えるため classes*.dex を全部入れる (マルチ dex)
#   * AAR 内の .so (androidx.graphics.path) は lib/<abi>/ に同梱し zipalign -p でページ整列する
#   * ライブラリのマニフェストはマージしない (AGP が無いため)。androidx.startup 等の
#     ライブラリ宣言コンポーネントは無効のままになるが、Compose UI の動作には影響しない
# 依存ライブラリは libs/ (tools/resolve.py が Google Maven / Maven Central から解決・取得)。
# kotlin-stdlib は kotlinc 配布物 (2.0.21) を使い、libs/ 側の 1.8.x は使わない。
# 使い方:
#   ./build-apk.sh           単体テスト → ビルド → デバッグ署名 (dist/filexplorer.apk)
#   ./build-apk.sh release   単体テスト → ビルド → リリース署名 (dist/filexplorer-release.apk)
#     リリース署名は環境変数で指定する (スクリプト内に秘密情報は書かない):
#       RELEASE_KEYSTORE / RELEASE_KEYSTORE_PASS / RELEASE_KEY_ALIAS
#       RELEASE_KEY_PASS (省略時は RELEASE_KEYSTORE_PASS と同じ)
set -euo pipefail
cd "$(dirname "$0")"
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/workspace/tools/android/sdk}}"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
[ -f "$AJAR" ] || { echo "android.jar が見つかりません: $AJAR" >&2; exit 1; }
[ -x "$BT/aapt2" ] || { echo "aapt2 が見つかりません: $BT" >&2; exit 1; }
JAVAC="$JAVA_HOME/bin/javac"; KEYTOOL="$JAVA_HOME/bin/keytool"
KOTLINC="${KOTLINC:-}"
if [ -z "$KOTLINC" ]; then
  if [ -x "$HOME/workspace/tools/kotlin/bin/kotlinc" ]; then KOTLINC="$HOME/workspace/tools/kotlin/bin/kotlinc"; else KOTLINC="$(command -v kotlinc)"; fi
fi
KOTLIN_HOME_DIR="$(cd "$(dirname "$KOTLINC")/.." && pwd)"
STDLIB="$KOTLIN_HOME_DIR/lib/kotlin-stdlib.jar"
[ -f "$STDLIB" ] || { echo "kotlin-stdlib.jar が見つかりません: $STDLIB" >&2; exit 1; }
command -v "$JAVAC" >/dev/null 2>&1 || JAVAC=javac
command -v "$KEYTOOL" >/dev/null 2>&1 || KEYTOOL=keytool
# ビルドモード (引数なし=debug)
MODE="${1:-debug}"
case "$MODE" in
  debug|release) ;;
  *) echo "使い方: $0 [debug|release]" >&2; exit 1 ;;
esac

echo "[1/8] unit tests"
./tests/run-tests.sh

echo "[2/8] dependencies (libs/ とツール jar の確認)"
COMPOSE_PLUGIN="tools/kotlin-compose-compiler-plugin-2.0.21.jar"
R8_JAR="tools/r8-8.7.18.jar"
if [ ! -f "$COMPOSE_PLUGIN" ]; then
  echo "  downloading kotlin-compose-compiler-plugin 2.0.21"
  curl -sfL -o "$COMPOSE_PLUGIN" "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compose-compiler-plugin/2.0.21/kotlin-compose-compiler-plugin-2.0.21.jar"
fi
if [ ! -f "$R8_JAR" ]; then
  echo "  downloading r8 8.7.18"
  curl -sfL -o "$R8_JAR" "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.7.18/r8-8.7.18.jar"
fi
if [ ! -f libs/artifacts.txt ]; then
  echo "  resolving libraries (tools/resolve.py)"
  python3 tools/resolve.py
fi

BUILD=build/manual
AARS=build/aars
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/libres" "$AARS"

# ライブラリ jar の収集 (artifacts.txt の順序どおり。build/aars の古い展開物を拾わない)
CP_JARS=(); DEX_JARS=(); AAR_DIRS=()
while IFS= read -r f; do
  case "$f" in kotlin-stdlib*) continue;; esac   # stdlib は kotlinc 配布物を使う
  path="libs/$f"
  [ -f "$path" ] || { echo "依存アーティファクトがありません: $path (python3 tools/resolve.py を実行してください)" >&2; exit 1; }
  if [[ "$f" == *.aar ]]; then
    d="$AARS/${f%.aar}"
    AAR_DIRS+=("$d")
    if [ ! -d "$d" ]; then mkdir -p "$d"; unzip -qo "$path" -d "$d"; fi
    if [ -f "$d/classes.jar" ]; then
      CP_JARS+=("$d/classes.jar"); DEX_JARS+=("$d/classes.jar")
    fi
  else
    # クラスを含まない KMP ルート jar は除外する (重複クラス回避)
    if [ "$(unzip -l "$path" | grep -c '\.class' || true)" -gt 0 ]; then
      CP_JARS+=("$path"); DEX_JARS+=("$path")
    fi
  fi
done < libs/artifacts.txt
CP="$(IFS=:; echo "${CP_JARS[*]}")"
echo "  ライブラリ jar (クラスあり): ${#CP_JARS[@]} 件"

echo "[3/8] aapt2 compile (アプリ + ライブラリのリソース)"
"$BT/aapt2" compile --dir app/src/main/res -o "$BUILD/libres/app.zip"
RES_ZIPS=("$BUILD/libres/app.zip")
EXTRA_PKGS=()
for d in "${AAR_DIRS[@]}"; do
  if [ -d "$d/res" ] && [ -n "$(find "$d/res" -type f -print -quit)" ]; then
    out="$BUILD/libres/$(basename "$d").zip"
    "$BT/aapt2" compile --dir "$d/res" -o "$out"
    RES_ZIPS+=("$out")
  fi
  if [ -f "$d/R.txt" ]; then
    pkg="$(grep -o 'package="[^"]*"' "$d/AndroidManifest.xml" | head -1 | cut -d'"' -f2)"
    [ -n "$pkg" ] && EXTRA_PKGS+=("$pkg")
  fi
done
echo "  リソースセット: ${#RES_ZIPS[@]} 件 / R パッケージ: ${#EXTRA_PKGS[@]} 件"

echo "[4/8] aapt2 link (--auto-add-overlay / --extra-packages)"
EXTRA_ARG=()
if [ "${#EXTRA_PKGS[@]}" -gt 0 ]; then
  EXTRA_ARG=(--extra-packages "$(IFS=:; echo "${EXTRA_PKGS[*]}")")
fi
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.filexplorer.app"|' \
  app/src/main/AndroidManifest.xml > "$BUILD/AndroidManifest.xml"
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" -I "$AJAR" \
  --manifest "$BUILD/AndroidManifest.xml" "${RES_ZIPS[@]}" \
  --java "$BUILD/gen" --auto-add-overlay "${EXTRA_ARG[@]}" \
  --min-sdk-version 26 --target-sdk-version 34

echo "[5/8] kotlinc (Compose コンパイラプラグイン有効)"
"$KOTLINC" -nowarn -jvm-target 17 -cp "$AJAR:$CP" -d "$BUILD/classes" \
  -Xplugin="$COMPOSE_PLUGIN" \
  $(find app/src/main/java -name '*.kt') $(find "$BUILD/gen" -name '*.java')

echo "[6/8] javac (生成された R クラス群)"
"$JAVAC" --release 17 -encoding UTF-8 -cp "$AJAR" -d "$BUILD/classes" \
  $(find "$BUILD/gen" -name '*.java')

echo "[7/8] d8 (単体 R8 8.7.18 / マルチ dex)"
java ${R8_JAVA_OPTS:--Xmx2g} -cp "$R8_JAR" com.android.tools.r8.D8 \
  --min-api 26 --lib "$AJAR" --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class') "${DEX_JARS[@]}" "$STDLIB"
ls "$BUILD/dex"

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
"$BT/zipalign" -f -p 4 "$BUILD/app-with-dex.apk" "$BUILD/app-aligned.apk"
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
  KS_PASS="android"; KEY_PASS="android"; ALIAS="androiddebugkey"
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
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:"$KS_PASS" --key-pass pass:"$KEY_PASS" "${ALIAS_OPT[@]}" \
  --out "$BUILD/filexplorer.apk" "$BUILD/app-aligned.apk"
"$BT/apksigner" verify --print-certs "$BUILD/filexplorer.apk" | head -3
mkdir -p dist; cp "$BUILD/filexplorer.apk" "$OUT"
echo "OK: $OUT"
