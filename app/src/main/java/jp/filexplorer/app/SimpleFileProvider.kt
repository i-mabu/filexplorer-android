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

// ========================================
// 📤 SimpleFileProvider: 外部アプリへファイルを開かせるための最小 ContentProvider
// ========================================
// androidx の FileProvider を使わずに自前で実装している。
// 読み取り専用に限定し、渡せるのは外部ストレージとアプリ用フォルダ配下だけにする。
class SimpleFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "jp.filexplorer.app.fileprovider"

        /**
         * ファイルを指す content:// URI を作る。パスセグメントごとにエスケープする
         * ので、# や ? を含むファイル名でも URI が壊れない
         * (受信側では uri.path でデコード済みのパスが得られる)。
         */
        @JvmStatic
        fun uriFor(file: File): Uri {
            val builder = StringBuilder("content://").append(AUTHORITY)
            for (segment in file.absolutePath.split('/')) {
                if (segment.isEmpty()) continue
                builder.append('/').append(Uri.encode(segment))
            }
            return Uri.parse(builder.toString())
        }
    }

    private fun allowedRoots(): List<File> {
        val roots = mutableListOf<File>()
        Environment.getExternalStorageDirectory()?.let { roots.add(it) }
        val ctx = context
        if (ctx != null) {
            roots.add(ctx.filesDir)
            ctx.getExternalFilesDir(null)?.let { roots.add(it) }
            // アプリ専用フォルダは Android/data/<pkg> 配下なので、その場所自体も許可する
            try {
                roots.add(File(Environment.getExternalStorageDirectory(), "Android/data/" + ctx.packageName))
            } catch (ignored: Exception) {
                // 外部ストレージが無い環境では追加しない
            }
        }
        return roots
    }

    @Throws(FileNotFoundException::class)
    private fun fileFor(uri: Uri): File {
        val path = uri.path ?: throw FileNotFoundException(uri.toString())
        val file = File(path)
        // canonical path で許可ルート配下かを確認し、.. 等での範囲外アクセスを防ぐ
        try {
            val canonical = file.canonicalPath
            for (root in allowedRoots()) {
                val rootPath = root.canonicalPath
                if (canonical == rootPath || canonical.startsWith(rootPath + File.separator)) {
                    return file
                }
            }
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            throw FileNotFoundException(uri.toString())
        }
        throw FileNotFoundException("許可されていないパスです: $uri")
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        // 書き込みを含むモード ("w" / "rw" / "wa" 等) は一切許可しない (読み取り専用)
        if (mode != "r") {
            throw FileNotFoundException("読み取り専用です: $mode")
        }
        val file = fileFor(uri)
        if (!file.exists() || !file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        return try {
            FileOps.mimeType(fileFor(uri).name)
        } catch (e: Exception) {
            "*/*"
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        try {
            val file = fileFor(uri)
            if (file.exists()) {
                cursor.addRow(arrayOf<Any>(file.name, file.length()))
            }
        } catch (ignored: Exception) {
            // 参照できない URI では空のカーソルを返す
        }
        return cursor
    }

    // 読み取り専用なので、書き込み系の操作は何もしない
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
