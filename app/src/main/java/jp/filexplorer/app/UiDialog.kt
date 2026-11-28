package jp.filexplorer.app

import android.graphics.Bitmap
import java.io.File

// ========================================
// 💬 UiDialog: 表示中ダイアログの状態を表す型
// ========================================
// null のときはダイアログなし。MainActivity がこの値を Compose の
// AlertDialog 群へ対応付けて表示する。
sealed interface UiDialog {

    /** メニュー (項目は MainActivity 側で組み立てる) */
    data object Menu : UiDialog

    /** 並び替えの選択肢 */
    data object Sort : UiDialog

    /** 検索キーワードの入力 (basePath 以下を検索する) */
    data class Search(val basePath: String, val baseName: String) : UiDialog

    /** パスを直接入力して移動 */
    data object JumpPath : UiDialog

    /** 新規作成 (folder=true でフォルダ、false でファイル) */
    data class Create(val folder: Boolean) : UiDialog

    /** 名前変更 (対象の絶対パスを保持する) */
    data class Rename(val path: String) : UiDialog

    /** 削除の確認 */
    data object DeleteConfirm : UiDialog

    /** 詳細表示 (本文は組み立て済みの文字列) */
    data class Details(val file: File, val message: String) : UiDialog

    /** 画像プレビュー (デコード済みビットマップを保持する) */
    data class ImagePreview(val file: File, val bitmap: Bitmap) : UiDialog

    /** テキストプレビュー (読み込み済みの本文を保持する) */
    data class TextPreview(val file: File, val text: String) : UiDialog

    /** お気に入り解除の確認 */
    data class FavRemove(val path: String) : UiDialog

    /** すべてのファイルへのアクセス許可の案内 */
    data object Permission : UiDialog
}
