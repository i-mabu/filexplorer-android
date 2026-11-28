# ファイル管理（FileXplorer）— 設計メモ（再実装）

この文書は、旧実装（v1.2.0）の全作り直しにあたってのパリティ対応表と
判断記録です。バージョンは 1.0.0 に振り直しました（versionCode も 1。
艦隊の作り直し標準に合わせたもので、aegis-android 等と同じ扱い）。

## パリティ対応表

旧実装の全ファイル（Kotlin 6 本、リソース、マニフェスト、テスト、
ビルドスクリプト、依存解決スクリプト）を棚卸しし、以下を再現した。

| 領域 | 内容 | 再現 |
|---|---|---|
| パッケージ / アプリ名 | `jp.filexplorer.app` / ファイル管理 | ✅ 同一 |
| 画面構成 | ホーム（カテゴリ 6 件・最近・ダウンロード・内部ストレージ・アプリ専用フォルダ・お気に入り）/ 閲覧 / カテゴリ / 最近 / 検索の 5 モード切替 | ✅ 同一 |
| ホーム | ストレージ使用量バー（使用量 / 総容量 / 空き容量）、お気に入りの長押し解除 | ✅ 同一（文言含む） |
| 閲覧 | フォルダ優先の一覧行（アイコン＋名前＋サイズ/項目数＋更新日時）、パスバーのタップでパス入力、Back の履歴スタック方式 | ✅ 同一 |
| 並び替え | 名前 / サイズ / 日付 / 種類 × 昇順・降順（フォルダは方向によらず先頭、同値は名前でタイブレーク） | ✅ 同一（`FileOps.sortFiles`） |
| 隠しファイル | ドット始まりの表示 ON/OFF（検索・収集でも同じ方針で除外し、ドットフォルダを辿らない） | ✅ 同一 |
| 検索 | 現在のフォルダ（ホームでは内部ストレージ全体）以下を再帰検索、上限 500 件・深さ 8、大文字小文字を区別しない | ✅ 同一 |
| カテゴリ収集 | 内部ストレージ全体からカテゴリ別に収集し新しい順、上限 500 件・深さ 8 | ✅ 同一 |
| 最近 | 走査全体（上限 8,000 件）から新しい順に最大 100 件 | ✅ 同一 |
| ファイル操作 | 複数選択、コピー / 切り取り / 貼り付け（同名は `名前 (1)` で回避、同じフォルダへの切り取り貼り付けは無効）、新規作成、名前変更、再帰削除（確認ダイアログ）、詳細、共有 | ✅ 同一 |
| 安全ガード | 自分自身・配下へのコピー禁止、シンボリックリンクを辿らない走査（深さ上限 32）、貼り付け先は開始時点のフォルダに固定 | ✅ 同一 |
| プレビュー | 画像は寸法からサンプリング（1024px まで）してアプリ内表示、テキストは 200KB までアプリ内表示、それ以外と失敗時は外部アプリ | ✅ 同一 |
| ContentProvider | 自前実装、読み取り専用（書き込みモードは拒否）、提供範囲は外部ストレージとアプリ用フォルダ配下のみ（canonical path で検証）、URI はセグメントごとにエスケープ | ✅ 同一 |
| 状態の保存 / 復元 | Bundle キー（mode / dir / category / title / query / selected / clipboard / clipCut / history）で閲覧位置・選択・クリップボード・履歴を復元 | ✅ 同一 |
| 保存データ | SharedPreferences `filexplorer` の 5 キー（sortMode / sortAsc / showHidden / favorites / permDialogShown、名前・型・既定値） | ✅ 同一（上書きインストールで引き継がれる） |
| 権限 | 6 件（READ/WRITE_EXTERNAL_STORAGE は maxSdkVersion 32、MANAGE_EXTERNAL_STORAGE、READ_MEDIA_IMAGES/VIDEO/AUDIO） | ✅ 同一（badging で照合済み） |
| 権限フロー | API 30+ は全ファイルアクセス設定へ案内（起動時のダイアログは初回だけ）、API 29 以下は実行時許可を要求 | ✅ 同一 |
| コンポーネント | MainActivity（exported、singleTop、MAIN/LAUNCHER）、SimpleFileProvider（authority `jp.filexplorer.app.fileprovider`、exported=false、grantUriPermissions） | ✅ 同一 |
| テーマ / アイコン | Theme.Material.NoActionBar、allowBackup、requestLegacyExternalStorage、アダプティブアイコン（背景 #3899DC + 前景 PNG）と従来 mipmap 一式、roundIcon | ✅ 同一（アイコン画像はバイナリ資産として引き継ぎ） |
| SDK | minSdk 26 / targetSdk 34 / compileSdk 34 | ✅ 同一 |
| FileOps API | カテゴリ定数 0–8、並び替え定数 0–3、全 public 関数のシグネチャと意味（`countItemsExact` の互換エイリアスを含む） | ✅ 同一 |
| ビルド | Gradle を使わないオフライン・パイプライン（aapt2 → kotlinc + Compose プラグイン → javac → D8（単体 R8 8.7.18）→ zipalign → apksigner、マルチ dex、AAR リソースのリンクと R 生成、.so の同梱）、build.sh / build.bat / build-apk.sh、依存解決 tools/resolve.py、リリース署名は RELEASE_* 環境変数のみ | ✅ 同一方式で新規作成 |
| 依存ライブラリ | Compose 1.7.6 / Material3 1.3.1 / activity-compose 1.9.3 を起点に解決した 79 件 | ✅ 同一（自作リゾルバの解決結果が旧 artifacts.txt と完全一致） |
| Gradle ファイル | Android Studio 用に AGP 8.7.3 + kotlin-android 2.0.21 の宣言を同梱 | ✅ 同一 |

## 判断記録

### バージョンのリセット（1.2.0 → 1.0.0、versionCode 4 → 1）

- 艦隊の作り直し標準に合わせ、版番号を振り直した。パッケージ名と
  保存データの形式は変えていないため、設定はそのまま引き継がれる。
- ただし Android の仕様上、versionCode が下がる APK は上書き
  インストールできない。旧 v1.2.0 を入れた端末へ本作を入れる場合は、
  先に旧版をアンインストールするか、配布時に versionCode の扱いを
  決める必要がある（既知事項として記録する）。

### バイナリ資産の引き継ぎ

- ランチャーアイコンの PNG 一式（mipmap 各解像度の ic_launcher /
  ic_launcher_round と drawable-nodpi のアダプティブ前景）と、
  リポジトリ直下の debug.keystore は、旧リポジトリからバイナリ資産
  としてそのまま引き継いだ。アイコンは確定済みの artwork であり、
  デバッグ鍵は署名の継続性（デバッグ版同士の上書き確認）のために
  同一である必要があるため。その他のコード・ドキュメント・
  スクリプトは全て新規に執筆した。
- 依存ライブラリ（libs/）は引き継がず、自作の tools/resolve.py で
  Maven から再取得する。解決結果（79 件）が旧版と一致することを
  artifacts.txt の比較で確認済み。

### テストの拡充

- 旧テスト（FileOps の 67 チェック）を全て含めたうえで、カテゴリ
  ラベルとアイコン、MIME タイプ、サイズ整形の境界（1023 B /
  100 MB / TB / 負数）、並び替え（サイズ・日付・種類・降順でも
  フォルダ優先）、uniqueFile の連番、検索・収集の隠しファイル
  除外、コピーの更新日時引き継ぎ、削除の失敗系などを追加し、
  129 チェックにした。実行方法は旧来どおり `tests/run-tests.sh`
  （kotlinc + JVM、Android SDK 不要）。

### コード構成

- 旧実装と同じファイル分割（MainActivity / FileOps /
  SimpleFileProvider / UiDialog / ExplorerUi / HomeActions）を
  踏襲した。対応関係が明確なほうがパリティを保ちやすく、この規模
  では分割を変える利益がないため。内部の実装は全て新規に執筆した。
- CI は単体テストのみの軽量構成にした（旧版 CI は APK ビルドまで
  含んでいた）。GitHub Free の分数配慮という艦隊方針に合わせた
  もので、APK の組み立て確認はローカルのワンコマンドビルドで行う。

## 検証範囲と既知事項

- JVM 単体テスト 129 件が全て通ることを確認済み。
- この VM で `./build.sh`（デバッグ署名）まで実施し、APK が組み立つ
  ことを確認した。`aapt2 dump badging` で package
  `jp.filexplorer.app` / versionCode 1 / versionName 1.0.0 /
  minSdk 26 / targetSdk 34 と権限 6 件を、旧版 APK と並べて照合した。
- **実機での動作確認は未実施**（この環境には実機がない）。画面表示、
  権限ダイアログ、全ファイルアクセスの実挙動、共有・外部アプリ
  連携などは実機で確認すること。署名付きリリースビルドは利用者側
  で行う。
- build.bat は Windows 実機での実行確認をしていない（スクリプトの
  構成は旧版の実績ある流れと艦隊の他アプリに沿って新規作成した）。
