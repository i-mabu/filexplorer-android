import jp.filexplorer.app.FileOps
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

var passed = 0
var failed = 0

fun check(name: String, cond: Boolean) {
    if (cond) passed++ else { failed++; println("FAIL: $name") }
}

fun eq(name: String, exp: Any?, act: Any?) {
    check("$name expected=$exp actual=$act", exp == act)
}

fun write(f: File, s: String): File {
    f.parentFile?.mkdirs()
    FileOutputStream(f).use { o -> o.write(s.toByteArray(StandardCharsets.UTF_8)) }
    return f
}

fun main() {
    // extension / category
    eq("ext jpg", "jpg", FileOps.extension("a.JPG"))
    eq("ext none", "", FileOps.extension("README"))
    eq("ext dotfile", "", FileOps.extension(".hidden"))
    eq("cat image", FileOps.CAT_IMAGE, FileOps.categoryOf("x.png"))
    eq("cat video", FileOps.CAT_VIDEO, FileOps.categoryOf("x.mp4"))
    eq("cat audio", FileOps.CAT_AUDIO, FileOps.categoryOf("x.mp3"))
    eq("cat doc pdf", FileOps.CAT_DOCUMENT, FileOps.categoryOf("x.pdf"))
    eq("cat archive", FileOps.CAT_ARCHIVE, FileOps.categoryOf("x.zip"))
    eq("cat apk", FileOps.CAT_APK, FileOps.categoryOf("x.apk"))
    eq("cat text", FileOps.CAT_TEXT, FileOps.categoryOf("x.txt"))
    eq("cat code", FileOps.CAT_CODE, FileOps.categoryOf("x.java"))
    eq("cat other", FileOps.CAT_OTHER, FileOps.categoryOf("x.bin"))
    check("isImage", FileOps.isImage("a.webp"))
    check("isTextLike txt", FileOps.isTextLike("a.md"))
    check("isTextLike json", FileOps.isTextLike("a.json"))
    check("not text image", !FileOps.isTextLike("a.png"))
    // mime
    eq("mime png", "image/png", FileOps.mimeType("a.png"))
    eq("mime pdf", "application/pdf", FileOps.mimeType("a.pdf"))
    eq("mime unknown", "*/*", FileOps.mimeType("a.unknown"))
    // formatSize
    eq("size 0", "0 B", FileOps.formatSize(0))
    eq("size 512", "512 B", FileOps.formatSize(512))
    eq("size 1KB", "1.00 KB", FileOps.formatSize(1024))
    eq("size 1.5MB", "1.50 MB", FileOps.formatSize(1572864))
    check("size GB", FileOps.formatSize(2L * 1024 * 1024 * 1024).contains("GB"))
    check("date format", FileOps.formatDate(0) == "-")
    check("date nonempty", FileOps.formatDate(1700000000000L).contains("/"))
    // hidden / safe name
    check("hidden", FileOps.isHiddenName(".nomedia"))
    check("not hidden", !FileOps.isHiddenName("a.txt"))
    check("safe ok", FileOps.isSafeName("memo.txt"))
    check("safe ng slash", !FileOps.isSafeName("a/b"))
    check("safe ng empty", !FileOps.isSafeName("  "))
    check("safe ng dotdot", !FileOps.isSafeName(".."))
    eq("safeName slash", "a_b", FileOps.safeName("a/b"))

    // ファイルシステム操作 (一時フォルダで実施)
    val root = File(System.getProperty("java.io.tmpdir"), "filexplorer-test-" + System.nanoTime())
    root.mkdirs()
    try {
        val sub = File(root, "sub"); sub.mkdirs()
        val a = write(File(root, "a.txt"), "hello")
        write(File(sub, "b.txt"), "world!!")
        write(File(root, ".hidden"), "x")
        eq("totalSize", 13L, FileOps.totalSize(root))
        val counts = FileOps.countItems(root)
        eq("count files", 3, counts[0]); eq("count dirs", 1, counts[1])
        // listDir の隠しファイル絞り込み
        eq("list no hidden", 2, FileOps.listDir(root, false).size)
        eq("list with hidden", 3, FileOps.listDir(root, true).size)
        // uniqueFile (同名回避)
        eq("unique", "a (1).txt", FileOps.uniqueFile(root, "a.txt").name)
        eq("unique free", "new.txt", FileOps.uniqueFile(root, "new.txt").name)
        // copy
        val dstDir = File(root, "dst"); dstDir.mkdirs()
        val copied = FileOps.copyRecursive(a, dstDir)
        check("copy exists", copied.exists() && copied.length() == 5L)
        val copied2 = FileOps.copyRecursive(a, dstDir)
        eq("copy unique name", "a (1).txt", copied2.name)
        // フォルダのコピー
        val copiedDir = FileOps.copyRecursive(sub, dstDir)
        check("copy dir", File(copiedDir, "b.txt").exists())
        // search
        val found = FileOps.searchRecursive(root, "b.txt", 10, 5)
        eq("search hits", 2, found.size)
        check("search case", FileOps.searchRecursive(root, "A.TXT", 10, 5).size >= 1)
        eq("search empty query", 0, FileOps.searchRecursive(root, " ", 10, 5).size)
        // カテゴリ収集
        write(File(root, "pic.png"), "img")
        val imgs = FileOps.collectByCategory(root, FileOps.CAT_IMAGE, 10, 5)
        eq("collect image", 1, imgs.size)
        val recents = FileOps.collectRecent(root, 3, 5)
        check("recent limit", recents.size <= 3 && recents.isNotEmpty())
        // 最近ファイルは更新日時の新しい順
        val oldF = write(File(root, "old.txt"), "o"); oldF.setLastModified(1000000000000L)
        val newF = write(File(root, "new.txt"), "n"); newF.setLastModified(1900000000000L)
        val rec2 = FileOps.collectRecent(root, 50, 5)
        check("recent newest first", rec2[0].name == "new.txt")
        // 並び替え: フォルダ優先・名前昇順
        val list = mutableListOf(File(root, "z.txt"), sub, a)
        FileOps.sortFiles(list, FileOps.SORT_NAME, true)
        check("sort folder first", list[0].isDirectory)
        eq("sort name", "a.txt", list[1].name)
        FileOps.sortFiles(list, FileOps.SORT_NAME, false)
        eq("sort name desc", "z.txt", list[1].name)
        // formatSize の境界: 1,048,575 B が "1024 KB" と表示されないこと
        eq("size boundary", "1.00 MB", FileOps.formatSize(1048575))
        // isSameOrInside
        check("inside true", FileOps.isSameOrInside(root, sub))
        check("inside self", FileOps.isSameOrInside(root, root))
        check("inside false", !FileOps.isSameOrInside(sub, root))
        // 自分自身・配下へのコピーは拒否されること
        var rejected = false
        try { FileOps.copyRecursive(sub, sub) } catch (e: IOException) { rejected = true }
        check("copy into itself rejected", rejected)
        var rejected2 = false
        try { FileOps.copyRecursive(root, sub) } catch (e: IOException) { rejected2 = true }
        check("copy into subfolder rejected", rejected2)
        // シンボリックリンクのループで totalSize・件数・検索・収集が止まらないこと
        try {
            Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
            val sz = FileOps.totalSize(root)
            check("symlink loop totalSize finite", sz >= 13 && sz < 100000)
            val cc = FileOps.countItems(root)
            check("symlink loop count finite", cc[0] >= 3 && cc[1] >= 1)
            check("symlink loop search ok", FileOps.searchRecursive(root, "a.txt", 10, 8).size >= 1)
            check("symlink loop collect ok", FileOps.collectRecent(root, 5, 8).size >= 1)
            check("delete symlink itself", FileOps.deleteRecursive(File(root, "loop")))
            check("symlink target intact", a.exists())
        } catch (e: Exception) {
            println("symlink tests skipped: $e")
        }
        // 再帰削除
        check("delete dir", FileOps.deleteRecursive(dstDir))
        check("deleted gone", !dstDir.exists())
    } finally {
        FileOps.deleteRecursive(root)
    }
    println("FileOpsTest: $passed passed, $failed failed")
    if (failed > 0) System.exit(1)
    println("ALL TESTS PASSED")
}
