// FileOps の JVM 単体テスト。
// Android フレームワークに依存しない純粋ロジックだけを対象にし、
// 実ファイル操作は一時フォルダの中で行う。
import jp.filexplorer.app.FileOps
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

private var passedCount = 0
private var failedCount = 0

private fun verify(name: String, condition: Boolean) {
    if (condition) {
        passedCount++
    } else {
        failedCount++
        println("FAIL: $name")
    }
}

private fun verifyEq(name: String, expected: Any?, actual: Any?) {
    verify("$name (expected=$expected, actual=$actual)", expected == actual)
}

private fun writeText(file: File, text: String): File {
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { out -> out.write(text.toByteArray(StandardCharsets.UTF_8)) }
    return file
}

fun main() {
    testExtensionAndCategory()
    testLabelsAndIcons()
    testMimeTypes()
    testFormatting()
    testNames()

    val root = File(System.getProperty("java.io.tmpdir"), "filexplorer-test-" + System.nanoTime())
    root.mkdirs()
    try {
        testFileSystemOps(root)
        testSorting(root)
        testSearchAndCollect(root)
        testCopyGuardsAndSymlinks(root)
    } finally {
        FileOps.deleteRecursive(root)
    }

    println("FileOpsTest: $passedCount passed, $failedCount failed")
    if (failedCount > 0) System.exit(1)
    println("ALL TESTS PASSED")
}

// ----------------------------------------
// 🏷️ 拡張子とカテゴリ
// ----------------------------------------
private fun testExtensionAndCategory() {
    verifyEq("extension lowercases", "jpg", FileOps.extension("a.JPG"))
    verifyEq("extension none", "", FileOps.extension("README"))
    verifyEq("extension dotfile", "", FileOps.extension(".hidden"))
    verifyEq("extension trailing dot", "", FileOps.extension("name."))
    verifyEq("extension multi dot", "gz", FileOps.extension("a.tar.gz"))
    verifyEq("extension null", "", FileOps.extension(null))

    verifyEq("category image", FileOps.CAT_IMAGE, FileOps.categoryOf("x.png"))
    verifyEq("category video", FileOps.CAT_VIDEO, FileOps.categoryOf("x.mp4"))
    verifyEq("category audio", FileOps.CAT_AUDIO, FileOps.categoryOf("x.mp3"))
    verifyEq("category document", FileOps.CAT_DOCUMENT, FileOps.categoryOf("x.pdf"))
    verifyEq("category archive", FileOps.CAT_ARCHIVE, FileOps.categoryOf("x.zip"))
    verifyEq("category apk", FileOps.CAT_APK, FileOps.categoryOf("x.apk"))
    verifyEq("category text", FileOps.CAT_TEXT, FileOps.categoryOf("x.txt"))
    verifyEq("category code", FileOps.CAT_CODE, FileOps.categoryOf("x.java"))
    verifyEq("category other", FileOps.CAT_OTHER, FileOps.categoryOf("x.bin"))
    verifyEq("category null", FileOps.CAT_OTHER, FileOps.categoryOf(null))

    verify("isImage", FileOps.isImage("a.webp"))
    verify("isTextLike txt", FileOps.isTextLike("a.md"))
    verify("isTextLike json", FileOps.isTextLike("a.json"))
    verify("image is not text-like", !FileOps.isTextLike("a.png"))
}

private fun testLabelsAndIcons() {
    verifyEq("label image", "画像", FileOps.categoryLabel(FileOps.CAT_IMAGE))
    verifyEq("label video", "動画", FileOps.categoryLabel(FileOps.CAT_VIDEO))
    verifyEq("label audio", "音声", FileOps.categoryLabel(FileOps.CAT_AUDIO))
    verifyEq("label document", "ドキュメント", FileOps.categoryLabel(FileOps.CAT_DOCUMENT))
    verifyEq("label archive", "圧縮ファイル", FileOps.categoryLabel(FileOps.CAT_ARCHIVE))
    verifyEq("label apk", "アプリ (APK)", FileOps.categoryLabel(FileOps.CAT_APK))
    verifyEq("label text", "テキスト", FileOps.categoryLabel(FileOps.CAT_TEXT))
    verifyEq("label code", "コード / データ", FileOps.categoryLabel(FileOps.CAT_CODE))
    verifyEq("label other", "その他", FileOps.categoryLabel(FileOps.CAT_OTHER))
    verifyEq("label unknown falls back", "その他", FileOps.categoryLabel(99))

    verifyEq("icon null", "📄", FileOps.iconFor(null))
    verifyEq("icon image file", "🖼️", FileOps.iconFor(File("x.png")))
    verifyEq("icon archive file", "🗜️", FileOps.iconFor(File("x.zip")))
    verifyEq("icon unknown file", "📄", FileOps.iconFor(File("x.bin")))
}

private fun testMimeTypes() {
    verifyEq("mime png", "image/png", FileOps.mimeType("a.png"))
    verifyEq("mime jpg", "image/jpeg", FileOps.mimeType("a.jpg"))
    verifyEq("mime pdf", "application/pdf", FileOps.mimeType("a.pdf"))
    verifyEq("mime mp4", "video/mp4", FileOps.mimeType("a.mp4"))
    verifyEq("mime mp3", "audio/mpeg", FileOps.mimeType("a.mp3"))
    verifyEq("mime apk", "application/vnd.android.package-archive", FileOps.mimeType("a.apk"))
    verifyEq("mime json", "application/json", FileOps.mimeType("a.json"))
    verifyEq("mime csv", "text/csv", FileOps.mimeType("a.csv"))
    verifyEq("mime unknown", "*/*", FileOps.mimeType("a.unknown"))
    verifyEq("mime null", "*/*", FileOps.mimeType(null))
}

// ----------------------------------------
// 🔤 サイズ・日付の整形
// ----------------------------------------
private fun testFormatting() {
    verifyEq("size zero", "0 B", FileOps.formatSize(0))
    verifyEq("size bytes", "512 B", FileOps.formatSize(512))
    verifyEq("size just under 1KB", "1023 B", FileOps.formatSize(1023))
    verifyEq("size 1KB", "1.00 KB", FileOps.formatSize(1024))
    verifyEq("size 10KB one decimal", "10.0 KB", FileOps.formatSize(10 * 1024))
    verifyEq("size 1.5MB", "1.50 MB", FileOps.formatSize(1572864))
    verifyEq("size 100MB no decimals", "100 MB", FileOps.formatSize(100L * 1024 * 1024))
    verify("size GB", FileOps.formatSize(2L * 1024 * 1024 * 1024).contains("GB"))
    verify("size TB", FileOps.formatSize(3L * 1024 * 1024 * 1024 * 1024).contains("TB"))
    verifyEq("size negative", "-", FileOps.formatSize(-1))
    // 丸めると "1024 KB" になってしまう境界は単位を繰り上げる
    verifyEq("size boundary carries up", "1.00 MB", FileOps.formatSize(1048575))

    verifyEq("date zero", "-", FileOps.formatDate(0))
    verifyEq("date negative", "-", FileOps.formatDate(-5))
    verify("date has separators", FileOps.formatDate(1700000000000L).contains("/"))
}

// ----------------------------------------
// ✏️ 名前の整形と検証
// ----------------------------------------
private fun testNames() {
    verify("hidden name", FileOps.isHiddenName(".nomedia"))
    verify("not hidden", !FileOps.isHiddenName("a.txt"))
    verify("hidden null", !FileOps.isHiddenName(null))

    verify("safe name ok", FileOps.isSafeName("memo.txt"))
    verify("safe name rejects slash", !FileOps.isSafeName("a/b"))
    verify("safe name rejects backslash", !FileOps.isSafeName("a\\b"))
    verify("safe name rejects blank", !FileOps.isSafeName("  "))
    verify("safe name rejects dot", !FileOps.isSafeName("."))
    verify("safe name rejects dotdot", !FileOps.isSafeName(".."))
    verify("safe name rejects null", !FileOps.isSafeName(null))

    verifyEq("safeName slash", "a_b", FileOps.safeName("a/b"))
    verifyEq("safeName backslash", "a_b", FileOps.safeName("a\\b"))
    verifyEq("safeName trims", "memo.txt", FileOps.safeName("  memo.txt  "))
    verifyEq("safeName strips NUL", "ab", FileOps.safeName("a\u0000b"))
    verifyEq("safeName null", "", FileOps.safeName(null))
}

// ----------------------------------------
// 📂 ファイルシステム操作
// ----------------------------------------
private fun testFileSystemOps(root: File) {
    val sub = File(root, "sub")
    sub.mkdirs()
    val a = writeText(File(root, "a.txt"), "hello")
    writeText(File(sub, "b.txt"), "world!!")
    writeText(File(root, ".hidden"), "x")

    verifyEq("totalSize sums recursively", 13L, FileOps.totalSize(root))
    verifyEq("totalSize of null", 0L, FileOps.totalSize(null))
    val counts = FileOps.countItems(root)
    verifyEq("count files", 3, counts[0])
    verifyEq("count dirs", 1, counts[1])
    val countsExact = FileOps.countItemsExact(root)
    verify(
        "countItemsExact alias",
        countsExact[0] == counts[0] && countsExact[1] == counts[1]
    )
    verifyEq("count of null", 0, FileOps.countItems(null)[0] + FileOps.countItems(null)[1])

    verifyEq("listDir hides dotfiles", 2, FileOps.listDir(root, false).size)
    verifyEq("listDir shows dotfiles", 3, FileOps.listDir(root, true).size)
    verify("listDir of null is empty", FileOps.listDir(null, true).isEmpty())

    // 同名回避: 連番が進むこと、拡張子の無い名前でも付くこと
    verifyEq("uniqueFile first collision", "a (1).txt", FileOps.uniqueFile(root, "a.txt").name)
    verifyEq("uniqueFile free name", "new.txt", FileOps.uniqueFile(root, "new.txt").name)
    writeText(File(root, "a (1).txt"), "y")
    verifyEq("uniqueFile second collision", "a (2).txt", FileOps.uniqueFile(root, "a.txt").name)
    writeText(File(root, "README"), "r")
    verifyEq("uniqueFile no extension", "README (1)", FileOps.uniqueFile(root, "README").name)

    // コピー: 内容と更新日時が引き継がれる
    a.setLastModified(1500000000000L)
    val destDir = File(root, "dst")
    destDir.mkdirs()
    val copied = FileOps.copyRecursive(a, destDir)
    verify("copy exists with content", copied.exists() && copied.length() == 5L)
    verifyEq("copy keeps mtime", 1500000000000L, copied.lastModified())
    val copiedAgain = FileOps.copyRecursive(a, destDir)
    verifyEq("copy avoids collision", "a (1).txt", copiedAgain.name)
    val copiedDir = FileOps.copyRecursive(sub, destDir)
    verify("copy dir recursively", File(copiedDir, "b.txt").exists())

    verify("delete missing returns false", !FileOps.deleteRecursive(File(root, "nope")))
    verify("delete null returns false", !FileOps.deleteRecursive(null))
}

// ----------------------------------------
// ↕️ 並び替え
// ----------------------------------------
private fun testSorting(root: File) {
    val dir = File(root, "sort")
    dir.mkdirs()
    val small = writeText(File(dir, "small.txt"), "x")
    val large = writeText(File(dir, "large.txt"), "0123456789")
    val mid = writeText(File(dir, "mid.md"), "01234")
    val folder = File(dir, "folder")
    folder.mkdirs()
    small.setLastModified(1000000000000L)
    large.setLastModified(1800000000000L)
    mid.setLastModified(1500000000000L)

    // 名前順: フォルダ優先
    val byName = mutableListOf(large, folder, small)
    FileOps.sortFiles(byName, FileOps.SORT_NAME, true)
    verify("sort name folder first", byName[0].isDirectory)
    verifyEq("sort name ascending", "large.txt", byName[1].name)
    FileOps.sortFiles(byName, FileOps.SORT_NAME, false)
    verify("sort name desc folder still first", byName[0].isDirectory)
    verifyEq("sort name descending", "small.txt", byName[1].name)

    // サイズ順: ファイル同士は長さで並ぶ
    val bySize = mutableListOf(large, small, mid)
    FileOps.sortFiles(bySize, FileOps.SORT_SIZE, true)
    verifyEq("sort size ascending", "small.txt", bySize[0].name)
    verifyEq("sort size ascending last", "large.txt", bySize[2].name)
    FileOps.sortFiles(bySize, FileOps.SORT_SIZE, false)
    verifyEq("sort size descending", "large.txt", bySize[0].name)

    // 日付順
    val byDate = mutableListOf(mid, small, large)
    FileOps.sortFiles(byDate, FileOps.SORT_DATE, true)
    verifyEq("sort date ascending", "small.txt", byDate[0].name)
    FileOps.sortFiles(byDate, FileOps.SORT_DATE, false)
    verifyEq("sort date descending", "large.txt", byDate[0].name)

    // 種類順: 拡張子の辞書順、同拡張子は名前順
    val byType = mutableListOf(mid, large, small)
    FileOps.sortFiles(byType, FileOps.SORT_TYPE, true)
    verifyEq("sort type md before txt", "mid.md", byType[0].name)
    verifyEq("sort type same ext by name", "large.txt", byType[1].name)

    // 新しい順 (カテゴリ・最近の表示で使う)
    val byDateDesc = mutableListOf(small, large, mid)
    FileOps.sortFilesByDateDesc(byDateDesc)
    verifyEq("sortFilesByDateDesc newest first", "large.txt", byDateDesc[0].name)
}

// ----------------------------------------
// 🔍 検索と収集
// ----------------------------------------
private fun testSearchAndCollect(root: File) {
    val sub = File(root, "sub")
    val found = FileOps.searchRecursive(root, "b.txt", 10, 5)
    verifyEq("search finds nested", 2, found.size)
    verify("search is case-insensitive", FileOps.searchRecursive(root, "A.TXT", 10, 5).size >= 1)
    verifyEq("search blank query", 0, FileOps.searchRecursive(root, " ", 10, 5).size)
    verify("search null root", FileOps.searchRecursive(null, "x", 10, 5).isEmpty())

    // 隠しファイルの扱い: showHidden=false では結果にも走査にも含めない
    verify(
        "search excludes hidden when asked",
        FileOps.searchRecursive(root, ".hidden", 10, 5, showHidden = false).isEmpty()
    )
    verify(
        "search includes hidden by default",
        FileOps.searchRecursive(root, ".hidden", 10, 5).isNotEmpty()
    )
    val hiddenDir = File(root, ".hdir")
    writeText(File(hiddenDir, "secret.txt"), "s")
    verify(
        "search does not descend into hidden dirs when asked",
        FileOps.searchRecursive(root, "secret", 10, 5, showHidden = false).isEmpty()
    )

    // カテゴリ収集
    writeText(File(root, "pic.png"), "img")
    val images = FileOps.collectByCategory(root, FileOps.CAT_IMAGE, 10, 5)
    verifyEq("collect images", 1, images.size)
    verify(
        "collect excludes hidden when asked",
        FileOps.collectByCategory(root, FileOps.CAT_IMAGE, 10, 5, showHidden = false).size == 1
    )

    // 最近: 上限と新しい順
    val recents = FileOps.collectRecent(root, 3, 5)
    verify("recent respects limit", recents.size <= 3 && recents.isNotEmpty())
    val oldFile = writeText(File(root, "old.txt"), "o")
    oldFile.setLastModified(1000000000000L)
    val newFile = writeText(File(root, "new.txt"), "n")
    newFile.setLastModified(1900000000000L)
    val recentsAll = FileOps.collectRecent(root, 50, 5)
    verifyEq("recent newest first", "new.txt", recentsAll[0].name)
}

// ----------------------------------------
// 🛡️ コピーの安全ガードとシンボリックリンク
// ----------------------------------------
private fun testCopyGuardsAndSymlinks(root: File) {
    val sub = File(root, "sub")
    verify("inside child", FileOps.isSameOrInside(root, sub))
    verify("inside self", FileOps.isSameOrInside(root, root))
    verify("inside parent is false", !FileOps.isSameOrInside(sub, root))

    var rejectedSelf = false
    try {
        FileOps.copyRecursive(sub, sub)
    } catch (e: IOException) {
        rejectedSelf = true
    }
    verify("copy into itself rejected", rejectedSelf)

    var rejectedSub = false
    try {
        FileOps.copyRecursive(root, sub)
    } catch (e: IOException) {
        rejectedSub = true
    }
    verify("copy into subfolder rejected", rejectedSub)

    // シンボリックリンクのループで各走査が止まらないこと
    try {
        val target = writeText(File(root, "link-target.txt"), "t")
        Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
        val size = FileOps.totalSize(root)
        verify("symlink loop totalSize finite", size in 13..99999)
        val counts = FileOps.countItems(root)
        verify("symlink loop count finite", counts[0] >= 3 && counts[1] >= 1)
        verify("symlink loop search ok", FileOps.searchRecursive(root, "a.txt", 10, 8).size >= 1)
        verify("symlink loop collect ok", FileOps.collectRecent(root, 5, 8).size >= 1)
        verify("isSymbolicLink detects", FileOps.isSymbolicLink(File(root, "loop")))
        verify("delete symlink itself", FileOps.deleteRecursive(File(root, "loop")))
        verify("symlink target intact", target.exists())
    } catch (e: Exception) {
        println("symlink tests skipped: $e")
    }

    // 再帰削除
    val destDir = File(root, "dst")
    verify("delete dir recursively", FileOps.deleteRecursive(destDir))
    verify("deleted dir gone", !destDir.exists())
}
