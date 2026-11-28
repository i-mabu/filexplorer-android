package jp.filexplorer.app

import java.io.File

// ========================================
// 🏠 HomeActions: ホーム画面の行アクションの表示名解決
// ========================================
// ホームの各行は "cat:<番号>" / "recent" / "download" / "storage" /
// "appfolder" / "fav:<絶対パス>" という文字列 ID で表す。
// ここでは ID からアイコンとラベルを引く純粋関数だけを置く
// (サブテキストは Activity の状態を使うため MainActivity 側)。

internal fun homeIcon(action: String): String {
    if (action.startsWith("cat:")) {
        return when (action.substring(4).toInt()) {
            FileOps.CAT_IMAGE -> "🖼️"
            FileOps.CAT_VIDEO -> "🎬"
            FileOps.CAT_AUDIO -> "🎵"
            FileOps.CAT_DOCUMENT -> "📕"
            FileOps.CAT_ARCHIVE -> "🗜️"
            FileOps.CAT_APK -> "📦"
            else -> "📄"
        }
    }
    return when (action) {
        "recent" -> "🕘"
        "download" -> "⬇️"
        "storage" -> "💾"
        "appfolder" -> "📱"
        else -> if (action.startsWith("fav:")) "⭐" else "📁"
    }
}

internal fun homeLabel(action: String): String {
    if (action.startsWith("cat:")) {
        return FileOps.categoryLabel(action.substring(4).toInt())
    }
    if (action.startsWith("fav:")) {
        return File(action.substring(4)).name
    }
    return when (action) {
        "recent" -> "最近使ったファイル"
        "download" -> "ダウンロード"
        "storage" -> "内部ストレージ"
        "appfolder" -> "アプリ専用フォルダ"
        else -> action
    }
}
