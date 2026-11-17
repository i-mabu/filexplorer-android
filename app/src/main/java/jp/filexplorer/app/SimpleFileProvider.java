package jp.filexplorer.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

// ===== 外部アプリへファイルを開かせるための最小 ContentProvider (androidx不要) =====
// レビューで強化: 読み取り専用に限定し、提供できるのは外部ストレージとアプリ用フォルダ配下だけにする
public class SimpleFileProvider extends ContentProvider {
    public static final String AUTHORITY = "jp.filexplorer.app.fileprovider";

    public static Uri uriFor(File f) {
        return Uri.parse("content://" + AUTHORITY + f.getAbsolutePath());
    }

    private List<File> allowedRoots() {
        List<File> roots = new ArrayList<>();
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null) roots.add(ext);
        Context c = getContext();
        if (c != null) {
            if (c.getFilesDir() != null) roots.add(c.getFilesDir());
            File e = c.getExternalFilesDir(null);
            if (e != null) roots.add(e);
            // アプリ専用フォルダは Android/data/<pkg>/ 配下なので、その親まで許可する
            try {
                File data = new File(Environment.getExternalStorageDirectory(), "Android/data/" + c.getPackageName());
                roots.add(data);
            } catch (Exception ignored) {}
        }
        return roots;
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        File f = new File(uri.getPath());
        try {
            String canon = f.getCanonicalPath();
            for (File root : allowedRoots()) {
                String r = root.getCanonicalPath();
                if (canon.equals(r) || canon.startsWith(r + File.separator)) return f;
            }
        } catch (Exception e) { throw new FileNotFoundException(uri.toString()); }
        throw new FileNotFoundException("許可されていないパスです: " + uri);
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        // 書き込みを含むモード ("rw"/"w"/"wa" 等) は一切許可しない (読み取り専用)
        if (mode != null && !mode.equals("r")) {
            throw new FileNotFoundException("読み取り専用です: " + mode);
        }
        File f = fileFor(uri);
        if (!f.exists() || !f.isFile()) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        try { return FileOps.mimeType(fileFor(uri).getName()); }
        catch (Exception e) { return "*/*"; }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            File f = fileFor(uri);
            if (f.exists()) c.addRow(new Object[]{f.getName(), f.length()});
        } catch (Exception ignored) {}
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
