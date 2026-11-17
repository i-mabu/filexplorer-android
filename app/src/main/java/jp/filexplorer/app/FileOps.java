package jp.filexplorer.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// ===== FileOps: GUI非依存の純粋ロジック (JVM単体テスト対象) =====
public final class FileOps {
    private FileOps() {}

    // カテゴリ定数 (enum を使うとこの環境の d8 が NPE で落ちるため int 定数にする)
    public static final int CAT_IMAGE = 0;
    public static final int CAT_VIDEO = 1;
    public static final int CAT_AUDIO = 2;
    public static final int CAT_DOCUMENT = 3;
    public static final int CAT_ARCHIVE = 4;
    public static final int CAT_APK = 5;
    public static final int CAT_TEXT = 6;
    public static final int CAT_CODE = 7;
    public static final int CAT_OTHER = 8;

    public static final int SORT_NAME = 0;
    public static final int SORT_SIZE = 1;
    public static final int SORT_DATE = 2;
    public static final int SORT_TYPE = 3;

    public static String extension(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        if (i <= 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    public static int categoryOf(String name) {
        String e = extension(name);
        switch (e) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp": case "svg": case "heic":
                return CAT_IMAGE;
            case "mp4": case "mkv": case "webm": case "mov": case "avi": case "3gp":
                return CAT_VIDEO;
            case "mp3": case "wav": case "m4a": case "aac": case "ogg": case "flac":
                return CAT_AUDIO;
            case "pdf": case "doc": case "docx": case "xls": case "xlsx": case "ppt": case "pptx":
            case "odt": case "ods": case "odp":
                return CAT_DOCUMENT;
            case "zip": case "gz": case "tar": case "7z": case "rar": case "xz": case "bz2":
                return CAT_ARCHIVE;
            case "apk": case "xapk": case "aab":
                return CAT_APK;
            case "txt": case "md": case "log": case "csv": case "tsv":
                return CAT_TEXT;
            case "json": case "xml": case "html": case "htm": case "css": case "js": case "ts":
            case "java": case "kt": case "py": case "c": case "cpp": case "h": case "sh": case "yml": case "yaml":
                return CAT_CODE;
            default:
                return CAT_OTHER;
        }
    }

    public static String categoryLabel(int c) {
        switch (c) {
            case CAT_IMAGE: return "画像";
            case CAT_VIDEO: return "動画";
            case CAT_AUDIO: return "音声";
            case CAT_DOCUMENT: return "ドキュメント";
            case CAT_ARCHIVE: return "圧縮ファイル";
            case CAT_APK: return "アプリ (APK)";
            case CAT_TEXT: return "テキスト";
            case CAT_CODE: return "コード / データ";
            default: return "その他";
        }
    }

    public static String iconFor(File f) {
        if (f == null) return "📄";
        if (f.isDirectory()) return "📁";
        switch (categoryOf(f.getName())) {
            case CAT_IMAGE: return "🖼️";
            case CAT_VIDEO: return "🎬";
            case CAT_AUDIO: return "🎵";
            case CAT_DOCUMENT: return "📕";
            case CAT_ARCHIVE: return "🗜️";
            case CAT_APK: return "📦";
            case CAT_TEXT: return "📝";
            case CAT_CODE: return "💻";
            default: return "📄";
        }
    }

    public static boolean isImage(String n) { return categoryOf(n) == CAT_IMAGE; }
    public static boolean isTextLike(String n) {
        int c = categoryOf(n);
        return c == CAT_TEXT || c == CAT_CODE;
    }

    public static String mimeType(String name) {
        String e = extension(name);
        switch (e) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "mp4": return "video/mp4";
            case "mkv": return "video/x-matroska";
            case "webm": return "video/webm";
            case "mov": return "video/quicktime";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "m4a": return "audio/mp4";
            case "ogg": return "audio/ogg";
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            case "apk": return "application/vnd.android.package-archive";
            case "txt": case "log": case "md": return "text/plain";
            case "csv": return "text/csv";
            case "json": return "application/json";
            case "xml": return "text/xml";
            case "html": case "htm": return "text/html";
            default: return "*/*";
        }
    }

    public static String formatSize(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        do { v /= 1024.0; i++; } while (v >= 1024 && i < u.length - 1);
        // 丸めると 1024 になる境界 (例: 1,048,575 B = 1023.999 KB) は単位を繰り上げる
        if (i < u.length - 1 && v >= 1023.995) { v /= 1024.0; i++; }
        if (v >= 100) return String.format(Locale.ROOT, "%.0f %s", v, u[i]);
        if (v >= 10) return String.format(Locale.ROOT, "%.1f %s", v, u[i]);
        return String.format(Locale.ROOT, "%.2f %s", v, u[i]);
    }

    public static String formatDate(long millis) {
        if (millis <= 0) return "-";
        return new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(new Date(millis));
    }

    public static boolean isHiddenName(String name) {
        return name != null && name.startsWith(".");
    }

    public static void sortFiles(List<File> files, final int mode, final boolean ascending) {
        Collections.sort(files, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                int r;
                switch (mode) {
                    case SORT_SIZE: r = Long.compare(sizeOf(a), sizeOf(b)); break;
                    case SORT_DATE: r = Long.compare(a.lastModified(), b.lastModified()); break;
                    case SORT_TYPE:
                        r = extension(a.getName()).compareToIgnoreCase(extension(b.getName()));
                        if (r == 0) r = a.getName().compareToIgnoreCase(b.getName());
                        break;
                    default: r = a.getName().compareToIgnoreCase(b.getName()); break;
                }
                if (r == 0 && mode != SORT_NAME) r = a.getName().compareToIgnoreCase(b.getName());
                return ascending ? r : -r;
            });
    }

    private static final int MAX_DEPTH = 32;

    public static boolean isSymbolicLink(File f) {
        try { return f != null && Files.isSymbolicLink(f.toPath()); }
        catch (Exception e) { return false; }
    }

    // child が parent 自身か、その配下にあるか (canonical path で判定)
    public static boolean isSameOrInside(File parent, File child) {
        try {
            String p = parent.getCanonicalPath();
            String c = child.getCanonicalPath();
            return c.equals(p) || c.startsWith(p + File.separator);
        } catch (Exception e) { return false; }
    }

    // ファイルは length、フォルダは直下だけでは測れないので length を使う (一覧の高速化のため)
    static long sizeOf(File f) { return f.isDirectory() ? 0 : f.length(); }

    public static long totalSize(File f) { return totalSizeRec(f, 0); }
    private static long totalSizeRec(File f, int depth) {
        if (f == null || !f.exists() || depth > MAX_DEPTH) return 0;
        if (f.isFile() || isSymbolicLink(f)) return f.length();
        long sum = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) sum += totalSizeRec(k, depth + 1);
        return sum;
    }

    // {ファイル数, フォルダ数} を再帰で数える
    public static int[] countItems(File dir) { return countItemsExact(dir); }

    public static int[] countItemsExact(File dir) { return countRec2(dir, 0); }
    private static int[] countRec2(File dir, int depth) {
        int[] c = {0, 0};
        if (dir == null || depth > MAX_DEPTH) return c;
        File[] kids = dir.listFiles();
        if (kids == null) return c;
        for (File k : kids) {
            if (k.isDirectory() && !isSymbolicLink(k)) { c[1]++; int[] sub = countRec2(k, depth + 1); c[0] += sub[0]; c[1] += sub[1]; }
            else c[0]++;
        }
        return c;
    }

    public static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return false;
        if (f.isDirectory() && !isSymbolicLink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        return f.delete();
    }

    public static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        dst.setLastModified(src.lastModified());
    }

    public static File copyRecursive(File src, File dstDir) throws IOException {
        // 自分自身や自分の配下へのコピーは、コピー先が走査対象に含まれて無限再帰になるため禁止
        if (src.isDirectory() && isSameOrInside(src, dstDir)) {
            throw new IOException("コピー先がコピー元フォルダの中にあります: " + dstDir);
        }
        File dst = uniqueFile(dstDir, src.getName());
        copyRecursiveInto(src, dst);
        return dst;
    }

    private static void copyRecursiveInto(File src, File dst) throws IOException {
        if (src.isDirectory() && !isSymbolicLink(src)) {
            if (!dst.exists() && !dst.mkdirs()) throw new IOException("フォルダを作成できません: " + dst);
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyRecursiveInto(k, new File(dst, k.getName()));
        } else {
            copyFile(src, dst);
        }
    }

    public static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int i = name.lastIndexOf('.');
        if (i > 0) { base = name.substring(0, i); ext = name.substring(i); }
        int n = 1;
        while (true) {
            File cand = new File(dir, base + " (" + n + ")" + ext);
            if (!cand.exists()) return cand;
            n++;
        }
    }

    public static List<File> listDir(File dir, boolean showHidden) {
        List<File> out = new ArrayList<>();
        if (dir == null) return out;
        File[] kids = dir.listFiles();
        if (kids == null) return out;
        for (File k : kids) {
            if (!showHidden && isHiddenName(k.getName())) continue;
            out.add(k);
        }
        return out;
    }

    public static List<File> searchRecursive(File root, String query, int maxResults, int maxDepth) {
        List<File> out = new ArrayList<>();
        if (root == null || query == null || query.trim().isEmpty()) return out;
        searchRec(root, query.toLowerCase(Locale.ROOT), out, maxResults, 0, maxDepth);
        return out;
    }
    private static void searchRec(File f, String q, List<File> out, int max, int depth, int maxDepth) {
        if (out.size() >= max || depth > maxDepth || f == null) return;
        File[] kids = f.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (out.size() >= max) return;
            if (k.getName().toLowerCase(Locale.ROOT).contains(q)) out.add(k);
            if (k.isDirectory() && !isSymbolicLink(k)) searchRec(k, q, out, max, depth + 1, maxDepth);
        }
    }

    private static final int MAX_VISITED = 8000;

    public static List<File> collectByCategory(File root, int cat, int maxResults, int maxDepth) {
        List<File> out = new ArrayList<>();
        collectRec(root, out, maxResults, 0, maxDepth, cat, new int[]{0});
        sortFilesByDateDesc(out);
        return out;
    }

    public static List<File> collectRecent(File root, int maxResults, int maxDepth) {
        List<File> out = new ArrayList<>();
        // 先に見つけた500件ではなく、走査した全体 (上限 MAX_VISITED 件) から新しい順に選ぶ
        collectRec(root, out, MAX_VISITED, 0, maxDepth, -1, new int[]{0});
        sortFilesByDateDesc(out);
        if (out.size() > maxResults) return new ArrayList<>(out.subList(0, maxResults));
        return out;
    }

    // visited は呼び出しごとのカウンタ配列で持つ (static だと並行実行で競合する)
    private static void collectRec(File f, List<File> out, int max, int depth, int maxDepth, int cat, int[] visited) {
        if (out.size() >= max || depth > maxDepth || f == null || visited[0] > MAX_VISITED) return;
        File[] kids = f.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            visited[0]++;
            if (visited[0] > MAX_VISITED) return;
            if (k.isFile()) {
                if (cat < 0 || categoryOf(k.getName()) == cat) out.add(k);
            } else if (k.isDirectory() && !isHiddenName(k.getName()) && !isSymbolicLink(k)) {
                collectRec(k, out, max, depth + 1, maxDepth, cat, visited);
            }
        }
    }

    public static void sortFilesByDateDesc(List<File> files) {
        Collections.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
    }

    public static String safeName(String name) {
        if (name == null) return "";
        return name.trim().replace("/", "_").replace("\\", "_").replace("\0", "");
    }

    public static boolean isSafeName(String name) {
        return name != null && !name.trim().isEmpty() && !name.contains("/") && !name.contains("\\")
                && !name.equals(".") && !name.equals("..");
    }
}
