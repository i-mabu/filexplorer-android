package jp.filexplorer.app

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ===== FileOps: GUI非依存の純粋ロジック (JVM単体テスト対象) =====
object FileOps {
    // カテゴリと並び替えは enum ではなく int 定数で表す
    // (SharedPreferences の保存値や単体テストの期待値とそのまま対応させるため)
    const val CAT_IMAGE = 0
    const val CAT_VIDEO = 1
    const val CAT_AUDIO = 2
    const val CAT_DOCUMENT = 3
    const val CAT_ARCHIVE = 4
    const val CAT_APK = 5
    const val CAT_TEXT = 6
    const val CAT_CODE = 7
    const val CAT_OTHER = 8

    const val SORT_NAME = 0
    const val SORT_SIZE = 1
    const val SORT_DATE = 2
    const val SORT_TYPE = 3

    private const val MAX_DEPTH = 32
    private const val MAX_VISITED = 8000

    @JvmStatic
    fun extension(name: String?): String {
        if (name == null) return ""
        // 先頭のドット (隠しファイル名) や末尾のドットは拡張子として扱わない
        val i = name.lastIndexOf('.')
        if (i <= 0 || i == name.length - 1) return ""
        return name.substring(i + 1).lowercase(Locale.ROOT)
    }

    @JvmStatic
    fun categoryOf(name: String?): Int {
        return when (extension(name)) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic" -> CAT_IMAGE
            "mp4", "mkv", "webm", "mov", "avi", "3gp" -> CAT_VIDEO
            "mp3", "wav", "m4a", "aac", "ogg", "flac" -> CAT_AUDIO
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp" -> CAT_DOCUMENT
            "zip", "gz", "tar", "7z", "rar", "xz", "bz2" -> CAT_ARCHIVE
            "apk", "xapk", "aab" -> CAT_APK
            "txt", "md", "log", "csv", "tsv" -> CAT_TEXT
            "json", "xml", "html", "htm", "css", "js", "ts", "java", "kt", "py", "c", "cpp", "h", "sh", "yml", "yaml" -> CAT_CODE
            else -> CAT_OTHER
        }
    }

    @JvmStatic
    fun categoryLabel(c: Int): String {
        return when (c) {
            CAT_IMAGE -> "画像"
            CAT_VIDEO -> "動画"
            CAT_AUDIO -> "音声"
            CAT_DOCUMENT -> "ドキュメント"
            CAT_ARCHIVE -> "圧縮ファイル"
            CAT_APK -> "アプリ (APK)"
            CAT_TEXT -> "テキスト"
            CAT_CODE -> "コード / データ"
            else -> "その他"
        }
    }

    @JvmStatic
    fun iconFor(f: File?): String {
        if (f == null) return "📄"
        if (f.isDirectory) return "📁"
        return when (categoryOf(f.name)) {
            CAT_IMAGE -> "🖼️"
            CAT_VIDEO -> "🎬"
            CAT_AUDIO -> "🎵"
            CAT_DOCUMENT -> "📕"
            CAT_ARCHIVE -> "🗜️"
            CAT_APK -> "📦"
            CAT_TEXT -> "📝"
            CAT_CODE -> "💻"
            else -> "📄"
        }
    }

    @JvmStatic
    fun isImage(n: String?): Boolean = categoryOf(n) == CAT_IMAGE

    @JvmStatic
    fun isTextLike(n: String?): Boolean {
        val c = categoryOf(n)
        return c == CAT_TEXT || c == CAT_CODE
    }

    @JvmStatic
    fun mimeType(name: String?): String {
        return when (extension(name)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "txt", "log", "md" -> "text/plain"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "html", "htm" -> "text/html"
            else -> "*/*"
        }
    }

    @JvmStatic
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "-"
        if (bytes < 1024) return "$bytes B"
        val u = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = -1
        do { v /= 1024.0; i++ } while (v >= 1024 && i < u.size - 1)
        // 丸めると 1024 になる境界 (例: 1,048,575 B = 1023.999 KB) は単位を繰り上げる
        if (i < u.size - 1 && v >= 1023.995) { v /= 1024.0; i++ }
        return when {
            v >= 100 -> String.format(Locale.ROOT, "%.0f %s", v, u[i])
            v >= 10 -> String.format(Locale.ROOT, "%.1f %s", v, u[i])
            else -> String.format(Locale.ROOT, "%.2f %s", v, u[i])
        }
    }

    @JvmStatic
    fun formatDate(millis: Long): String {
        if (millis <= 0) return "-"
        return SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(millis))
    }

    @JvmStatic
    fun isHiddenName(name: String?): Boolean = name != null && name.startsWith(".")

    @JvmStatic
    fun sortFiles(files: MutableList<File>, mode: Int, ascending: Boolean) {
        files.sortWith { a, b ->
            // フォルダは昇順/降順によらず常に先頭 (方向は同種同士の比較にだけ適用する)
            if (a.isDirectory != b.isDirectory) {
                if (a.isDirectory) -1 else 1
            } else {
                var r = when (mode) {
                    SORT_SIZE -> sizeOf(a).compareTo(sizeOf(b))
                    SORT_DATE -> a.lastModified().compareTo(b.lastModified())
                    SORT_TYPE -> {
                        var x = extension(a.name).compareTo(extension(b.name), ignoreCase = true)
                        if (x == 0) x = a.name.compareTo(b.name, ignoreCase = true)
                        x
                    }
                    else -> a.name.compareTo(b.name, ignoreCase = true)
                }
                if (r == 0 && mode != SORT_NAME) r = a.name.compareTo(b.name, ignoreCase = true)
                if (ascending) r else -r
            }
        }
    }

    // ファイルは length、フォルダは一覧の高速化のため 0 とする
    private fun sizeOf(f: File): Long = if (f.isDirectory) 0 else f.length()

    @JvmStatic
    fun isSymbolicLink(f: File?): Boolean {
        return try { f != null && Files.isSymbolicLink(f.toPath()) } catch (e: Exception) { false }
    }

    // child が parent 自身か、その配下にあるか (canonical path で判定)
    @JvmStatic
    fun isSameOrInside(parent: File, child: File): Boolean {
        return try {
            val p = parent.canonicalPath
            val c = child.canonicalPath
            c == p || c.startsWith(p + File.separator)
        } catch (e: Exception) { false }
    }

    @JvmStatic
    fun totalSize(f: File?): Long = totalSizeRec(f, 0)

    private fun totalSizeRec(f: File?, depth: Int): Long {
        if (f == null || !f.exists() || depth > MAX_DEPTH) return 0
        // シンボリックリンクはリンク先を辿らずリンク自体のサイズで打ち切る (ループ防止)
        if (f.isFile || isSymbolicLink(f)) return f.length()
        var sum = 0L
        val kids = f.listFiles()
        if (kids != null) for (k in kids) sum += totalSizeRec(k, depth + 1)
        return sum
    }

    // {ファイル数, フォルダ数} を再帰で数える
    @JvmStatic
    fun countItems(dir: File?): IntArray = countRec(dir, 0)

    // Java 版からある互換用エイリアス (countItems と同一)
    @JvmStatic
    fun countItemsExact(dir: File?): IntArray = countItems(dir)

    private fun countRec(dir: File?, depth: Int): IntArray {
        val c = intArrayOf(0, 0)
        if (dir == null || depth > MAX_DEPTH) return c
        val kids = dir.listFiles() ?: return c
        for (k in kids) {
            if (k.isDirectory && !isSymbolicLink(k)) {
                c[1]++
                val sub = countRec(k, depth + 1)
                c[0] += sub[0]; c[1] += sub[1]
            } else c[0]++
        }
        return c
    }

    @JvmStatic
    fun deleteRecursive(f: File?): Boolean {
        if (f == null || !f.exists()) return false
        // シンボリックリンクのフォルダは中身を辿らずリンク自体だけを削除する
        if (f.isDirectory && !isSymbolicLink(f)) {
            val kids = f.listFiles()
            if (kids != null) for (k in kids) deleteRecursive(k)
        }
        return f.delete()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { out ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } > 0) out.write(buf, 0, n)
            }
        }
        dst.setLastModified(src.lastModified())
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copyRecursive(src: File, dstDir: File): File {
        // 自分自身や自分の配下へのコピーは、コピー先が走査対象に含まれて無限再帰になるため禁止
        if (src.isDirectory && isSameOrInside(src, dstDir)) {
            throw IOException("コピー先がコピー元フォルダの中にあります: $dstDir")
        }
        val dst = uniqueFile(dstDir, src.name)
        copyRecursiveInto(src, dst)
        return dst
    }

    @Throws(IOException::class)
    private fun copyRecursiveInto(src: File, dst: File) {
        if (src.isDirectory && !isSymbolicLink(src)) {
            if (!dst.exists() && !dst.mkdirs()) throw IOException("フォルダを作成できません: $dst")
            val kids = src.listFiles()
            if (kids != null) for (k in kids) copyRecursiveInto(k, File(dst, k.name))
        } else {
            copyFile(src, dst)
        }
    }

    @JvmStatic
    fun uniqueFile(dir: File, name: String): File {
        val f = File(dir, name)
        if (!f.exists()) return f
        var base = name
        var ext = ""
        val i = name.lastIndexOf('.')
        if (i > 0) { base = name.substring(0, i); ext = name.substring(i) }
        var n = 1
        while (true) {
            val cand = File(dir, "$base ($n)$ext")
            if (!cand.exists()) return cand
            n++
        }
    }

    @JvmStatic
    fun listDir(dir: File?, showHidden: Boolean): MutableList<File> {
        val out = mutableListOf<File>()
        if (dir == null) return out
        val kids = dir.listFiles() ?: return out
        for (k in kids) {
            if (!showHidden && isHiddenName(k.name)) continue
            out.add(k)
        }
        return out
    }

    // showHidden が false のときは、一覧 (listDir) と同じ方針でドット始まりの
    // ファイル/フォルダを結果に含めず、ドットフォルダの中も辿らない
    @JvmStatic
    fun searchRecursive(root: File?, query: String?, maxResults: Int, maxDepth: Int, showHidden: Boolean = true): MutableList<File> {
        val out = mutableListOf<File>()
        if (root == null || query.isNullOrBlank()) return out
        searchRec(root, query.lowercase(Locale.ROOT), out, maxResults, 0, maxDepth, showHidden)
        return out
    }

    private fun searchRec(f: File?, q: String, out: MutableList<File>, max: Int, depth: Int, maxDepth: Int, showHidden: Boolean) {
        if (out.size >= max || depth > maxDepth || f == null) return
        val kids = f.listFiles() ?: return
        for (k in kids) {
            if (out.size >= max) return
            if (!showHidden && isHiddenName(k.name)) continue
            if (k.name.lowercase(Locale.ROOT).contains(q)) out.add(k)
            if (k.isDirectory && !isSymbolicLink(k)) searchRec(k, q, out, max, depth + 1, maxDepth, showHidden)
        }
    }

    @JvmStatic
    fun collectByCategory(root: File?, cat: Int, maxResults: Int, maxDepth: Int, showHidden: Boolean = true): MutableList<File> {
        val out = mutableListOf<File>()
        collectRec(root, out, maxResults, 0, maxDepth, cat, intArrayOf(0), showHidden)
        sortFilesByDateDesc(out)
        return out
    }

    @JvmStatic
    fun collectRecent(root: File?, maxResults: Int, maxDepth: Int, showHidden: Boolean = true): MutableList<File> {
        val out = mutableListOf<File>()
        // 先に見つけた500件ではなく、走査した全体 (上限 MAX_VISITED 件) から新しい順に選ぶ
        collectRec(root, out, MAX_VISITED, 0, maxDepth, -1, intArrayOf(0), showHidden)
        sortFilesByDateDesc(out)
        if (out.size > maxResults) return ArrayList(out.subList(0, maxResults))
        return out
    }

    // visited は呼び出しごとのカウンタ配列で持つ (共有カウンタにすると並行実行で競合するため)
    private fun collectRec(f: File?, out: MutableList<File>, max: Int, depth: Int, maxDepth: Int, cat: Int, visited: IntArray, showHidden: Boolean) {
        if (out.size >= max || depth > maxDepth || f == null || visited[0] > MAX_VISITED) return
        val kids = f.listFiles() ?: return
        for (k in kids) {
            visited[0]++
            if (visited[0] > MAX_VISITED) return
            if (!showHidden && isHiddenName(k.name)) continue
            if (k.isFile) {
                if (cat < 0 || categoryOf(k.name) == cat) out.add(k)
            } else if (k.isDirectory && !isSymbolicLink(k)) {
                collectRec(k, out, max, depth + 1, maxDepth, cat, visited, showHidden)
            }
        }
    }

    @JvmStatic
    fun sortFilesByDateDesc(files: MutableList<File>) {
        files.sortWith { a, b -> b.lastModified().compareTo(a.lastModified()) }
    }

    // safeName は区切り文字等を置換して安全な名前に整形する (検証して弾く isSafeName とは別)
    @JvmStatic
    fun safeName(name: String?): String {
        if (name == null) return ""
        return name.trim().replace("/", "_").replace("\\", "_").replace("\u0000", "")
    }

    @JvmStatic
    fun isSafeName(name: String?): Boolean {
        return name != null && name.trim().isNotEmpty() && !name.contains("/") && !name.contains("\\")
                && name != "." && name != ".."
    }
}
