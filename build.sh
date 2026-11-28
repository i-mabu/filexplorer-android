#!/usr/bin/env bash
# ============================================================
# FileXplorer ワンコマンドビルド
#
#   ./build.sh           テスト → ビルド → デバッグ署名
#                        → dist/filexplorer.apk
#   ./build.sh release   テスト → ビルド → リリース署名
#                        → dist/filexplorer-release.apk
#
# リリース署名には自分のキーストアを環境変数で指定します
# (スクリプト内に秘密情報は書きません):
#
#   RELEASE_KEYSTORE=/path/to/release.jks \
#   RELEASE_KEY_ALIAS=mykey \
#   RELEASE_KEYSTORE_PASS=xxxx \
#   ./build.sh release
#   (鍵パスワードが異なる場合だけ RELEASE_KEY_PASS も指定)
#
# 前提: JDK 17+ / Kotlin コンパイラ 2.0.21 / Android SDK
#       (platform 34、build-tools 34.0.0)。
#       SDK の場所は ANDROID_SDK_ROOT か ANDROID_HOME で指定。
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-debug}"
case "$MODE" in
  debug)   APK="dist/filexplorer.apk" ;;
  release) APK="dist/filexplorer-release.apk" ;;
  *) echo "使い方: $0 [debug|release]" >&2; exit 1 ;;
esac

# リリース署名の前提はビルド前にチェックする (後で気づくと時間が無駄になるため)
if [ "$MODE" = "release" ]; then
  [ -n "${RELEASE_KEYSTORE:-}" ] || { echo "[エラー] リリース署名には環境変数 RELEASE_KEYSTORE (キーストアのパス) が必要です" >&2; exit 1; }
  [ -f "$RELEASE_KEYSTORE" ] || { echo "[エラー] キーストアが見つかりません: $RELEASE_KEYSTORE" >&2; exit 1; }
  [ -n "${RELEASE_KEYSTORE_PASS:-}" ] || { echo "[エラー] 環境変数 RELEASE_KEYSTORE_PASS (キーストアのパスワード) が必要です" >&2; exit 1; }
fi

./build-apk.sh "$MODE"

echo
echo "=================================================="
echo "ビルド完了 ($MODE)。成果物:"
echo "  $APK"
ls -l "$APK" | awk '{print "  サイズ: " $5 " bytes"}'
if command -v sha256sum >/dev/null 2>&1; then
  echo "  SHA256: $(sha256sum "$APK" | cut -d' ' -f1)"
fi
if [ "$MODE" = "debug" ]; then
  echo "  ※ デバッグ鍵で署名しています。配布用は RELEASE_KEYSTORE 等の"
  echo "    環境変数を設定して ./build.sh release を実行してください。"
fi
echo "=================================================="
