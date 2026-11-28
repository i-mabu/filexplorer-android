# 📁 ファイル管理（FileXplorer）— Android用ファイル管理アプリ

| 項目 | 内容 |
|---|---|
| バージョン | v1.0.0（versionCode 1、app/build.gradle） |
| 状態 | 作り直し版（旧 v1.2.0 の再実装）。実機確認は未実施 |
| 稼働先 | Android 8.0 (API 26) 以上の端末 |
| 入手先 | このリポジトリからビルド（「🔨 ビルド」参照） |
| 実装 | Kotlin（Jetpack Compose / Material3、minSdk 26 / targetSdk 34） |
| パッケージ | jp.filexplorer.app |
| 変更履歴 | [CHANGELOG.md](CHANGELOG.md) / 設計判断は [DESIGN.md](DESIGN.md) |

## 📖 概要

Google Files のような使い勝手を目指した、Android用ファイル管理（エクスプローラー風）アプリです。
ファイル処理は Android 標準 API + Kotlin 標準ライブラリのみ、UI は Jetpack Compose
（Compose 1.7.6 / Material3、ライブラリは `libs/` に解決・同梱）で実装しています。

## ✨ 機能

### 🏠 ホーム（Google Files 風）

- カテゴリから探す: 画像 / 動画 / 音声 / ドキュメント / 圧縮ファイル / アプリ(APK)
  （内部ストレージ内を再帰的に集めて新しい順に表示）
- 最近使ったファイル（更新日時順、最大100件）
- ダウンロードフォルダへのショートカット
- 内部ストレージの使用量バー（使用量 / 総容量 / 空き容量）
- お気に入りフォルダ（閲覧画面のメニューから登録するとホームに表示、長押しで解除）

### 📂 閲覧（エクスプローラー風）

- フォルダ優先の一覧（アイコン＋名前＋サイズ/項目数＋更新日時）、タップで遷移、Backで親へ（ルートではホームへ）
- パスバーのタップでパスを直接入力して移動
- 並び替え: 名前 / 日付 / サイズ / 種類、昇順・降順（設定は保存されます）
- 隠しファイル（ドット始まり）の表示 ON/OFF
- 検索: 現在のフォルダ（ホームでは内部ストレージ全体）以下を再帰検索

### 🛠️ ファイル操作

- 長押しで複数選択モード → コピー / 切り取り → 別フォルダで「ここに貼り付け」
  （同名がある場合は `名前 (1)` のように自動で重複回避）
- 新規フォルダ / 新規ファイル作成、名前変更（1件選択時）、削除（確認ダイアログ付き、フォルダは再帰削除）
- 詳細: パス、種類、サイズ（フォルダは中身の合計と件数）、更新日時、読み書き可否
- 共有（詳細画面から。外部アプリ連携は自前の ContentProvider 経由）

### 📖 ファイルを開く

- 画像: アプリ内プレビュー（寸法からサンプリングして表示）
- テキスト系（txt / md / json / xml / csv / log / コード類、200KBまで）: アプリ内プレビュー
- その他: 自前の ContentProvider 経由で外部アプリに開かせる（androidx不要の実装）

## ⚙️ 設定と保存データ

SharedPreferences ファイル名は `filexplorer` です（旧版と同一形式で引き継がれます）。

| キー | 型 | 内容 | 既定値 |
|---|---|---|---|
| `sortMode` | Int | 並び替えモード（0=名前 / 1=サイズ / 2=日付 / 3=種類） | 0 |
| `sortAsc` | Boolean | 並び替えの昇順・降順 | true |
| `showHidden` | Boolean | 隠しファイルを表示するか | false |
| `favorites` | StringSet | お気に入りフォルダの絶対パス | 空 |
| `permDialogShown` | Boolean | 権限の案内ダイアログを表示済みか | false |

## 🔐 権限

| 権限 | 用途 |
|---|---|
| MANAGE_EXTERNAL_STORAGE | Android 11 以降の「すべてのファイルへのアクセス」（内部ストレージ全体の閲覧） |
| READ_MEDIA_IMAGES / READ_MEDIA_VIDEO / READ_MEDIA_AUDIO | Android 13 以降のメディア種別ごとの読み取り |
| READ_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE（maxSdkVersion 32） | Android 12 以前のストレージ読み書き |

- Android 11以降: 内部ストレージ全体を見るには「すべてのファイルへのアクセス」が必要です。
  初回起動時に案内ダイアログが出ます（メニュー →「すべてのファイルへのアクセス設定」からも開けます）。
  許可しなくてもアプリ専用フォルダは使えます。
- Android 10以前: ストレージの読み書き権限を実行時に要求します。

## 📂 ファイル構成

`app/src/main/java/jp/filexplorer/app/` 配下（Kotlin）。

| ファイル | 役割 |
|---|---|
| `MainActivity.kt` | 画面本体（Compose UI・状態管理・ファイル操作の呼び出し） |
| `FileOps.kt` | ファイル操作と一覧取得の純粋ロジック（単体テスト対象） |
| `SimpleFileProvider.kt` | 共有用の ContentProvider（読み取り専用、提供範囲を限定） |
| `UiDialog.kt` | ダイアログ状態の型定義 |
| `ExplorerUi.kt` | 共通の色と一覧行 UI |
| `HomeActions.kt` | ホーム行のアイコン / ラベル |

## 🔨 ビルド

Gradle/AGPを使わないオフライン手動ビルド（aapt2 + kotlinc + D8）です。
依存ライブラリは `tools/resolve.py` が Google Maven / Maven Central から
`libs/` へ解決・取得します（初回ビルド時のみネットワークと python3 が必要）。
dex 化は build-tools 同梱 d8 の不具合を避けるため単体 R8 8.7.18 の D8 CLI を使い、
マルチ dex で同梱します。

**ワンコマンドビルド**（テスト → ビルド → 署名 → 成果物表示まで一括）:

```sh
./build.sh      # Linux / macOS (引数なし=デバッグ署名 / release でリリース署名)
build.bat       # Windows (同上)
```

前提: JDK 17+（21推奨）/ Kotlin コンパイラ 2.0.21 / Android SDK（platform 34・build-tools 34.0.0）。
SDK の場所は環境変数 `ANDROID_SDK_ROOT`（または `ANDROID_HOME`）で指定します。
Kotlin は `KOTLINC` / `KOTLIN_HOME` / PATH の順に探します。

デフォルト（引数なし）ではデバッグ鍵で署名します（上書きインストール用）。
配布用に自分のリリース鍵で署名する場合は、`release` を付け、環境変数で
キーストアを指定してください（鍵情報はスクリプトに書かれません）。
成果物は `dist/filexplorer-release.apk` になります:

```sh
RELEASE_KEYSTORE=/path/to/release.jks RELEASE_KEY_ALIAS=mykey RELEASE_KEYSTORE_PASS=xxxx ./build.sh release
```

個別に実行する場合:

```sh
./tests/run-tests.sh   # 単体テスト (FileOps)
./build-apk.sh         # build.sh の本体 (引数なし=debug / release で dist/filexplorer-release.apk)
```

Android Studio で開くための Gradle ファイル（AGP 8.7.3 + kotlin-android 2.0.21）も同梱しています。

## 🧪 テスト

- 単体テストは `tests/run-tests.sh`（kotlinc + JVM、Android SDK 不要）。
  FileOps の分類・整形・並び替え・検索/収集・コピー/削除・名前処理を検証します。
- CI は単体テストのみの軽量構成です（APK の組み立てはローカルの
  ワンコマンドビルドで確認します）。

## ⚠️ 注意

- 実機での動作は未検証です（この環境に実機/エミュレータがないため）
- カテゴリ・最近・検索の走査は内部ストレージ全体が対象のため、ファイル数が非常に多い環境では時間がかかることがあります（走査上限 8,000件 / 深さ 8 で打ち切り）
- ごみ箱、圧縮ファイルの解凍、クラウドストレージ連携は未実装です
