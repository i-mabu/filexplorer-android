package jp.filexplorer.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

// ===== 外部アプリへファイルを開かせるための最小 ContentProvider (androidx不要) =====
// 読み取り専用に限定し、提供できるのは外部ストレージとアプリ用フォルダ配下だけにする
class SimpleFileProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "jp.filexplorer.app.fileprovider"

        @JvmStatic
        fun uriFor(f: File): Uri = Uri.parse("content://" + AUTHORITY + f.absolutePath)
    }

    private fun allowedRoots(): List<File> {
        val roots = mutableListOf<File>()
        val ext = Environment.getExternalStorageDirectory()
        if (ext != null) roots.add(ext)
        val c = context
        if (c != null) {
            roots.add(c.filesDir)
            val e = c.getExternalFilesDir(null)
            if (e != null) roots.add(e)
            // アプリ専用フォルダは Android/data/<pkg>/ 配下なので、その親まで許可する
            try {
                roots.add(File(Environment.getExternalStorageDirectory(), "Android/data/" + c.packageName))
            } catch (ignored: Exception) {}
        }
        return roots
    }

    @Throws(FileNotFoundException::class)
    private fun fileFor(uri: Uri): File {
        val f = File(uri.path ?: throw FileNotFoundException(uri.toString()))
        try {
            val canon = f.canonicalPath
            for (root in allowedRoots()) {
                val r = root.canonicalPath
                if (canon == r || canon.startsWith(r + File.separator)) return f
            }
        } catch (e: FileNotFoundException) { throw e }
        catch (e: Exception) { throw FileNotFoundException(uri.toString()) }
        throw FileNotFoundException("許可されていないパスです: $uri")
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        // 書き込みを含むモード ("rw"/"w"/"wa" 等) は一切許可しない (読み取り専用)
        if (mode != "r") {
            throw FileNotFoundException("読み取り専用です: $mode")
        }
        val f = fileFor(uri)
        if (!f.exists() || !f.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        return try { FileOps.mimeType(fileFor(uri).name) } catch (e: Exception) { "*/*" }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        try {
            val f = fileFor(uri)
            if (f.exists()) c.addRow(arrayOf<Any>(f.name, f.length()))
        } catch (ignored: Exception) {}
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
