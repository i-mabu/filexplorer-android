package jp.filexplorer.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

// ===== 外部アプリへファイルを開かせるための最小 ContentProvider (androidx不要) =====
public class SimpleFileProvider extends ContentProvider {
    public static final String AUTHORITY = "jp.filexplorer.app.fileprovider";

    public static Uri uriFor(File f) {
        return Uri.parse("content://" + AUTHORITY + f.getAbsolutePath());
    }

    private File fileFor(Uri uri) { return new File(uri.getPath()); }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        if (!f.exists() || !f.isFile()) throw new FileNotFoundException(uri.toString());
        int m = mode != null && (mode.contains("w")) ? ParcelFileDescriptor.MODE_WRITE_ONLY
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(f, m);
    }

    @Override public String getType(Uri uri) { return FileOps.mimeType(fileFor(uri).getName()); }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = fileFor(uri);
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        if (f.exists()) c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
