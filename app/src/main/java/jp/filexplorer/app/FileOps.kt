package jp.filexplorer.app

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ========================================
// 🧮 FileOps: ファイル操作と分類・整形の純粋ロジック
// ========================================
// Android フレームワークに依存しない部分をここに集約し、
// JVM 単体テスト (tests/) の対象にしている。
object FileOps {

    // 📂 カテゴリ (ホーム画面の分類と対応する int 定数。
    //    SharedPreferences の保存値やテストの期待値と直結させるため enum にはしない)
    const val CAT_IMAGE = 0
    const val CAT_VIDEO = 1
    const val CAT_AUDIO = 2
    const val CAT_DOCUMENT = 3
    const val CAT_ARCHIVE = 4
    const val CAT_APK = 5
    const val CAT_TEXT = 6
    const val CAT_CODE = 7
    const val CAT_OTHER = 8

    // ↕️ 並び替えモード
    const val SORT_NAME = 0
    const val SORT_SIZE = 1
    const val SORT_DATE = 2
    const val SORT_TYPE = 3

    // 🌲 再帰走査の安全上限 (シンボリックリンクのループ等で暴走しないため)
    private const val MAX_DEPTH = 32
    private const val MAX_VISITED = 8000

    // ----------------------------------------
    // 🏷️ 拡張子とカテゴリ
    // ----------------------------------------

    /**
     * ファイル名から拡張子 (小文字、ドットなし) を取り出す。
     * 先頭ドットの隠しファイル名や末尾ドットは拡張子として扱わない。
     */
    @JvmStatic
    fun extension(name: String?): String {
        if (name == null) return ""
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    @JvmStatic
    fun categoryOf(name: String?): Int = when (extension(name)) {
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

    @JvmStatic
    fun categoryLabel(category: Int): String = when (category) {
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

    /** 一覧行に表示する絵文字アイコンを返す。 */
    @JvmStatic
    fun iconFor(file: File?): String {
        if (file == null) return "📄"
        if (file.isDirectory) return "📁"
        return when (categoryOf(file.name)) {
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
    fun isImage(name: String?): Boolean = categoryOf(name) == CAT_IMAGE

    /** アプリ内テキストプレビューの対象かどうか。 */
    @JvmStatic
    fun isTextLike(name: String?): Boolean {
        val c = categoryOf(name)
        return c == CAT_TEXT || c == CAT_CODE
    }

    @JvmStatic
    fun mimeType(name: String?): String = when (extension(name)) {
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

    // ----------------------------------------
    // 🔤 表示用の整形
    // ----------------------------------------

    @JvmStatic
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "-"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        do {
            value /= 1024.0
            unit++
        } while (value >= 1024 && unit < units.size - 1)
        // 丸めると 1024 になる境界 (例: 1,048,575 B ≒ 1023.999 KB) は単位を繰り上げる
        if (unit < units.size - 1 && value >= 1023.995) {
            value /= 1024.0
            unit++
        }
        return when {
            value >= 100 -> String.format(Locale.ROOT, "%.0f %s", value, units[unit])
            value >= 10 -> String.format(Locale.ROOT, "%.1f %s", value, units[unit])
            else -> String.format(Locale.ROOT, "%.2f %s", value, units[unit])
        }
    }

    @JvmStatic
    fun formatDate(millis: Long): String {
        if (millis <= 0) return "-"
        return SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(millis))
    }

    @JvmStatic
    fun isHiddenName(name: String?): Boolean = name != null && name.startsWith(".")

    // ----------------------------------------
    // ↕️ 並び替え
    // ----------------------------------------

    /**
     * 一覧を並び替える。フォルダは昇順・降順にかかわらず常に先頭に置き、
     * 方向は同じ種類同士の比較にだけ適用する。
     */
    @JvmStatic
    fun sortFiles(files: MutableList<File>, mode: Int, ascending: Boolean) {
        files.sortWith { a, b ->
            if (a.isDirectory != b.isDirectory) {
                if (a.isDirectory) -1 else 1
            } else {
                var result = when (mode) {
                    SORT_SIZE -> sortKeySize(a).compareTo(sortKeySize(b))
                    SORT_DATE -> a.lastModified().compareTo(b.lastModified())
                    SORT_TYPE -> {
                        var x = extension(a.name).compareTo(extension(b.name), ignoreCase = true)
                        if (x == 0) x = a.name.compareTo(b.name, ignoreCase = true)
                        x
                    }
                    else -> a.name.compareTo(b.name, ignoreCase = true)
                }
                if (result == 0 && mode != SORT_NAME) {
                    result = a.name.compareTo(b.name, ignoreCase = true)
                }
                if (ascending) result else -result
            }
        }
    }

    // サイズ比較用のキー。フォルダは一覧取得を速くするため 0 として扱う
    private fun sortKeySize(f: File): Long = if (f.isDirectory) 0 else f.length()

    @JvmStatic
    fun sortFilesByDateDesc(files: MutableList<File>) {
        files.sortWith { a, b -> b.lastModified().compareTo(a.lastModified()) }
    }

    // ----------------------------------------
    // 🌲 走査 (サイズ・件数・検索・収集)
    // ----------------------------------------

    @JvmStatic
    fun isSymbolicLink(f: File?): Boolean {
        return try {
            f != null && Files.isSymbolicLink(f.toPath())
        } catch (e: Exception) {
            false
        }
    }

    /** child が parent 自身か、その配下にあるかを canonical path で判定する。 */
    @JvmStatic
    fun isSameOrInside(parent: File, child: File): Boolean {
        return try {
            val p = parent.canonicalPath
            val c = child.canonicalPath
            c == p || c.startsWith(p + File.separator)
        } catch (e: Exception) {
            false
        }
    }

    @JvmStatic
    fun totalSize(f: File?): Long = totalSizeAt(f, 0)

    private fun totalSizeAt(f: File?, depth: Int): Long {
        if (f == null || !f.exists() || depth > MAX_DEPTH) return 0
        // シンボリックリンクはリンク先を辿らず、リンク自体のサイズで打ち切る (ループ防止)
        if (f.isFile || isSymbolicLink(f)) return f.length()
        var sum = 0L
        val children = f.listFiles()
        if (children != null) {
            for (child in children) sum += totalSizeAt(child, depth + 1)
        }
        return sum
    }

    /** {ファイル数, フォルダ数} を再帰で数える。 */
    @JvmStatic
    fun countItems(dir: File?): IntArray = countAt(dir, 0)

    /** 旧 Java 版から残る互換エイリアス (countItems と同一)。 */
    @JvmStatic
    fun countItemsExact(dir: File?): IntArray = countItems(dir)

    private fun countAt(dir: File?, depth: Int): IntArray {
        val counts = intArrayOf(0, 0)
        if (dir == null || depth > MAX_DEPTH) return counts
        val children = dir.listFiles() ?: return counts
        for (child in children) {
            if (child.isDirectory && !isSymbolicLink(child)) {
                counts[1]++
                val sub = countAt(child, depth + 1)
                counts[0] += sub[0]
                counts[1] += sub[1]
            } else {
                counts[0]++
            }
        }
        return counts
    }

    @JvmStatic
    fun listDir(dir: File?, showHidden: Boolean): MutableList<File> {
        val result = mutableListOf<File>()
        if (dir == null) return result
        val children = dir.listFiles() ?: return result
        for (child in children) {
            if (!showHidden && isHiddenName(child.name)) continue
            result.add(child)
        }
        return result
    }

    /**
     * root 以下を再帰検索する。showHidden が false のときは一覧と同じ方針で、
     * ドット始まりのファイル/フォルダを結果に含めず、ドットフォルダの中も辿らない。
     */
    @JvmStatic
    fun searchRecursive(
        root: File?,
        query: String?,
        maxResults: Int,
        maxDepth: Int,
        showHidden: Boolean = true
    ): MutableList<File> {
        val result = mutableListOf<File>()
        if (root == null || query.isNullOrBlank()) return result
        searchAt(root, query.lowercase(Locale.ROOT), result, maxResults, 0, maxDepth, showHidden)
        return result
    }

    private fun searchAt(
        dir: File?,
        queryLower: String,
        out: MutableList<File>,
        max: Int,
        depth: Int,
        maxDepth: Int,
        showHidden: Boolean
    ) {
        if (out.size >= max || depth > maxDepth || dir == null) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (out.size >= max) return
            if (!showHidden && isHiddenName(child.name)) continue
            if (child.name.lowercase(Locale.ROOT).contains(queryLower)) out.add(child)
            if (child.isDirectory && !isSymbolicLink(child)) {
                searchAt(child, queryLower, out, max, depth + 1, maxDepth, showHidden)
            }
        }
    }

    /** カテゴリに合うファイルを集め、新しい順に並べて返す。 */
    @JvmStatic
    fun collectByCategory(
        root: File?,
        category: Int,
        maxResults: Int,
        maxDepth: Int,
        showHidden: Boolean = true
    ): MutableList<File> {
        val result = mutableListOf<File>()
        collectAt(root, result, maxResults, 0, maxDepth, category, intArrayOf(0), showHidden)
        sortFilesByDateDesc(result)
        return result
    }

    /**
     * 最近使ったファイルを集める。先に見つけた分ではなく、走査した全体
     * (上限 MAX_VISITED 件) から新しい順に選ぶ。
     */
    @JvmStatic
    fun collectRecent(
        root: File?,
        maxResults: Int,
        maxDepth: Int,
        showHidden: Boolean = true
    ): MutableList<File> {
        val result = mutableListOf<File>()
        collectAt(root, result, MAX_VISITED, 0, maxDepth, -1, intArrayOf(0), showHidden)
        sortFilesByDateDesc(result)
        if (result.size > maxResults) return ArrayList(result.subList(0, maxResults))
        return result
    }

    // visited は呼び出しごとのカウンタで持つ (共有すると並行実行で競合するため)
    private fun collectAt(
        dir: File?,
        out: MutableList<File>,
        max: Int,
        depth: Int,
        maxDepth: Int,
        category: Int,
        visited: IntArray,
        showHidden: Boolean
    ) {
        if (out.size >= max || depth > maxDepth || dir == null || visited[0] > MAX_VISITED) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            visited[0]++
            if (visited[0] > MAX_VISITED) return
            if (!showHidden && isHiddenName(child.name)) continue
            if (child.isFile) {
                if (category < 0 || categoryOf(child.name) == category) out.add(child)
            } else if (child.isDirectory && !isSymbolicLink(child)) {
                collectAt(child, out, max, depth + 1, maxDepth, category, visited, showHidden)
            }
        }
    }

    // ----------------------------------------
    // ✏️ ファイル操作 (削除・コピー・名前)
    // ----------------------------------------

    @JvmStatic
    fun deleteRecursive(f: File?): Boolean {
        if (f == null || !f.exists()) return false
        // シンボリックリンクのフォルダは中身を辿らず、リンク自体だけを消す
        if (f.isDirectory && !isSymbolicLink(f)) {
            val children = f.listFiles()
            if (children != null) {
                for (child in children) deleteRecursive(child)
            }
        }
        return f.delete()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copyFile(source: File, dest: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    output.write(buffer, 0, read)
                }
            }
        }
        dest.setLastModified(source.lastModified())
    }

    /**
     * source を destDir の中へコピーし、コピー先の File を返す。
     * 同名がある場合は uniqueFile で重複を回避する。
     * 自分自身や自分の配下へのコピーは無限再帰になるため禁止する。
     */
    @JvmStatic
    @Throws(IOException::class)
    fun copyRecursive(source: File, destDir: File): File {
        if (source.isDirectory && isSameOrInside(source, destDir)) {
            throw IOException("コピー先がコピー元フォルダの中にあります: $destDir")
        }
        val dest = uniqueFile(destDir, source.name)
        copyInto(source, dest)
        return dest
    }

    @Throws(IOException::class)
    private fun copyInto(source: File, dest: File) {
        if (source.isDirectory && !isSymbolicLink(source)) {
            if (!dest.exists() && !dest.mkdirs()) {
                throw IOException("フォルダを作成できません: $dest")
            }
            val children = source.listFiles()
            if (children != null) {
                for (child in children) copyInto(child, File(dest, child.name))
            }
        } else {
            copyFile(source, dest)
        }
    }

    /** dir の中に name が無ければそのまま、あれば "名前 (1)" の形で空き名を探す。 */
    @JvmStatic
    fun uniqueFile(dir: File, name: String): File {
        val direct = File(dir, name)
        if (!direct.exists()) return direct
        var base = name
        var suffix = ""
        val dot = name.lastIndexOf('.')
        if (dot > 0) {
            base = name.substring(0, dot)
            suffix = name.substring(dot)
        }
        var n = 1
        while (true) {
            val candidate = File(dir, "$base ($n)$suffix")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    /**
     * 名前を安全な形に整形する (検証して弾く isSafeName とは別)。
     * 区切り文字はアンダースコアに置換し、NUL は取り除く。
     */
    @JvmStatic
    fun safeName(name: String?): String {
        if (name == null) return ""
        return name.trim()
            .replace("/", "_")
            .replace("\\", "_")
            .replace("\u0000", "")
    }

    @JvmStatic
    fun isSafeName(name: String?): Boolean {
        return name != null &&
            name.trim().isNotEmpty() &&
            !name.contains("/") &&
            !name.contains("\\") &&
            name != "." &&
            name != ".."
    }
}
