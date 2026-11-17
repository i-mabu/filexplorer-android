#!/usr/bin/env bash
# FileXplorer - オフライン手動ビルド (Gradle/AGPを使わず aapt2 + kotlinc + d8 でAPKを作る)
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
BUILD=build/manual
rm -rf "$BUILD"; mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"
echo "[1/7] unit tests"
./tests/run-tests.sh
echo "[2/7] aapt2 compile"
"$BT/aapt2" compile --dir app/src/main/res -o "$BUILD/compiled.zip"
echo "[3/7] aapt2 link"
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.filexplorer.app"|' \
  app/src/main/AndroidManifest.xml > "$BUILD/AndroidManifest.xml"
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" -I "$AJAR" \
  --manifest "$BUILD/AndroidManifest.xml" "$BUILD/compiled.zip" \
  --java "$BUILD/gen" --min-sdk-version 26 --target-sdk-version 34
echo "[4/7] kotlinc (+ javac for generated R.java)"
find app/src/main/java -name '*.kt' > "$BUILD/sources.txt"
"$KOTLINC" -nowarn -jvm-target 17 -cp "$AJAR" -d "$BUILD/classes" @"$BUILD/sources.txt"
find "$BUILD/gen" -name '*.java' > "$BUILD/rsources.txt"
if [ -s "$BUILD/rsources.txt" ]; then "$JAVAC" --release 17 -encoding UTF-8 -cp "$AJAR" -d "$BUILD/classes" @"$BUILD/rsources.txt"; fi
echo "[5/7] d8 (アプリ本体 + kotlin-stdlib)"
"$BT/d8" --min-api 26 --output "$BUILD/dex" $(find "$BUILD/classes" -name '*.class') "$STDLIB"
echo "[6/7] package + zipalign"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
( cd "$BUILD/dex" && zip -q -uj ../app-with-dex.apk classes.dex )
"$BT/zipalign" -f -p 4 "$BUILD/app-with-dex.apk" "$BUILD/app-aligned.apk"
echo "[7/7] sign ($MODE)"
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
