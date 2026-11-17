import jp.filexplorer.app.FileOps;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class FileOpsTest {
    static int passed = 0, failed = 0;
    static void check(String name, boolean cond) {
        if (cond) { passed++; } else { failed++; System.out.println("FAIL: " + name); }
    }
    static void eq(String name, Object exp, Object act) {
        check(name + " expected=" + exp + " actual=" + act, exp == null ? act == null : exp.equals(act));
    }
    static File write(File f, String s) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
        return f;
    }
    public static void main(String[] args) throws Exception {
        // extension / category
        eq("ext jpg", "jpg", FileOps.extension("a.JPG"));
        eq("ext none", "", FileOps.extension("README"));
        eq("ext dotfile", "", FileOps.extension(".hidden"));
        eq("cat image", FileOps.CAT_IMAGE, FileOps.categoryOf("x.png"));
        eq("cat video", FileOps.CAT_VIDEO, FileOps.categoryOf("x.mp4"));
        eq("cat audio", FileOps.CAT_AUDIO, FileOps.categoryOf("x.mp3"));
        eq("cat doc pdf", FileOps.CAT_DOCUMENT, FileOps.categoryOf("x.pdf"));
        eq("cat archive", FileOps.CAT_ARCHIVE, FileOps.categoryOf("x.zip"));
        eq("cat apk", FileOps.CAT_APK, FileOps.categoryOf("x.apk"));
        eq("cat text", FileOps.CAT_TEXT, FileOps.categoryOf("x.txt"));
        eq("cat code", FileOps.CAT_CODE, FileOps.categoryOf("x.java"));
        eq("cat other", FileOps.CAT_OTHER, FileOps.categoryOf("x.bin"));
        check("isImage", FileOps.isImage("a.webp"));
        check("isTextLike txt", FileOps.isTextLike("a.md"));
        check("isTextLike json", FileOps.isTextLike("a.json"));
        check("not text image", !FileOps.isTextLike("a.png"));
        // mime
        eq("mime png", "image/png", FileOps.mimeType("a.png"));
        eq("mime pdf", "application/pdf", FileOps.mimeType("a.pdf"));
        eq("mime unknown", "*/*", FileOps.mimeType("a.unknown"));
        // formatSize
        eq("size 0", "0 B", FileOps.formatSize(0));
        eq("size 512", "512 B", FileOps.formatSize(512));
        eq("size 1KB", "1.00 KB", FileOps.formatSize(1024));
        eq("size 1.5MB", "1.50 MB", FileOps.formatSize(1572864));
        check("size GB", FileOps.formatSize(2L*1024*1024*1024).contains("GB"));
        check("date format", FileOps.formatDate(0).equals("-"));
        check("date nonempty", FileOps.formatDate(1700000000000L).contains("/"));
        // hidden / safe name
        check("hidden", FileOps.isHiddenName(".nomedia"));
        check("not hidden", !FileOps.isHiddenName("a.txt"));
        check("safe ok", FileOps.isSafeName("memo.txt"));
        check("safe ng slash", !FileOps.isSafeName("a/b"));
        check("safe ng empty", !FileOps.isSafeName("  "));
        check("safe ng dotdot", !FileOps.isSafeName(".."));
        eq("safeName slash", "a_b", FileOps.safeName("a/b"));

        // filesystem ops in temp dir
        File root = new File(System.getProperty("java.io.tmpdir"), "filexplorer-test-" + System.nanoTime());
        root.mkdirs();
        try {
            File sub = new File(root, "sub"); sub.mkdirs();
            File a = write(new File(root, "a.txt"), "hello");
            File b = write(new File(sub, "b.txt"), "world!!");
            write(new File(root, ".hidden"), "x");
            eq("totalSize", 13L, FileOps.totalSize(root));
            int[] counts = FileOps.countItems(root);
            eq("count files", 3, counts[0]); eq("count dirs", 1, counts[1]);
            // listDir hidden filter
            eq("list no hidden", 2, FileOps.listDir(root, false).size());
            eq("list with hidden", 3, FileOps.listDir(root, true).size());
            // uniqueFile
            eq("unique", "a (1).txt", FileOps.uniqueFile(root, "a.txt").getName());
            eq("unique free", "new.txt", FileOps.uniqueFile(root, "new.txt").getName());
            // copy
            File dstDir = new File(root, "dst"); dstDir.mkdirs();
            File copied = FileOps.copyRecursive(a, dstDir);
            check("copy exists", copied.exists() && copied.length() == 5);
            File copied2 = FileOps.copyRecursive(a, dstDir);
            eq("copy unique name", "a (1).txt", copied2.getName());
            // copy folder
            File copiedDir = FileOps.copyRecursive(sub, dstDir);
            check("copy dir", new File(copiedDir, "b.txt").exists());
            // search
            List<File> found = FileOps.searchRecursive(root, "b.txt", 10, 5);
            eq("search hits", 2, found.size()); // sub/b.txt + dst/sub copy's b.txt may add; at least check >=1 via contains logic below
            check("search case", FileOps.searchRecursive(root, "A.TXT", 10, 5).size() >= 1);
            eq("search empty query", 0, FileOps.searchRecursive(root, " ", 10, 5).size());
            // category collect
            write(new File(root, "pic.png"), "img");
            List<File> imgs = FileOps.collectByCategory(root, FileOps.CAT_IMAGE, 10, 5);
            eq("collect image", 1, imgs.size());
            List<File> recents = FileOps.collectRecent(root, 3, 5);
            check("recent limit", recents.size() <= 3 && !recents.isEmpty());
            // sort: folders first, name asc
            List<File> list = new ArrayList<>();
            list.add(new File(root, "z.txt")); list.add(sub); list.add(a);
            FileOps.sortFiles(list, FileOps.SORT_NAME, true);
            check("sort folder first", list.get(0).isDirectory());
            eq("sort name", "a.txt", list.get(1).getName());
            FileOps.sortFiles(list, FileOps.SORT_NAME, false);
            eq("sort name desc", "z.txt", list.get(1).getName());
            // delete recursive
            check("delete dir", FileOps.deleteRecursive(dstDir));
            check("deleted gone", !dstDir.exists());
        } finally {
            FileOps.deleteRecursive(root);
        }
        System.out.println("FileOpsTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
        System.out.println("ALL TESTS PASSED");
    }
}
