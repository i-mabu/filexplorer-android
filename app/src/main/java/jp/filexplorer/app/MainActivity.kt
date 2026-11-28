package jp.filexplorer.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

// ========================================
// 📁 MainActivity: FileXplorer の画面本体 (Jetpack Compose)
// ========================================
// ホーム (カテゴリ + 最近 + 容量) と閲覧 (エクスプローラー風リスト) を
// 1 つの Activity で切り替える。状態はフィールドに持ち、uiTick を進める
// ことで再コンポーズを促す素朴な構成にしている。
class MainActivity : ComponentActivity() {

    companion object {
        private const val MODE_HOME = 0
        private const val MODE_BROWSE = 1
        private const val MODE_CATEGORY = 2
        private const val MODE_RECENT = 3
        private const val MODE_SEARCH = 4

        // テキストプレビューの上限 (これを超えたら外部アプリに任せる)
        private const val TEXT_PREVIEW_LIMIT = 200 * 1024
    }

    // 🧭 表示状態
    private var mode = MODE_HOME
    private var currentDir: File? = null
    private var currentCategory = -1
    private var currentTitle = "ファイル"
    private var currentFiles: MutableList<File> = mutableListOf()
    private val homeActions = mutableListOf<String>()

    // ✅ 選択と履歴とクリップボード
    private val selected = LinkedHashSet<String>()
    private val dirHistory = ArrayDeque<File>()
    private var currentSearchQuery = ""
    private var clipboard: MutableList<File> = mutableListOf()
    private var clipboardCut = false

    // ⚙️ 保存される設定
    private lateinit var prefs: SharedPreferences
    private var sortMode = FileOps.SORT_NAME
    private var sortAsc = true
    private var showHidden = false

    // 🖥️ 再描画トリガとダイアログ状態
    private var uiTick by mutableIntStateOf(0)
    private var dialog by mutableStateOf<UiDialog?>(null)

    private fun internalRoot(): File = Environment.getExternalStorageDirectory() ?: filesDir

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("filexplorer", MODE_PRIVATE)
        sortMode = prefs.getInt("sortMode", FileOps.SORT_NAME)
        sortAsc = prefs.getBoolean("sortAsc", true)
        showHidden = prefs.getBoolean("showHidden", false)
        currentDir = internalRoot()
        setContent { AppScreen() }
        val restored = savedInstanceState != null && restoreState(savedInstanceState)
        if (!restored) showHome()
        checkPermission(forceOpen = false)
    }

    // ----------------------------------------
    // 💾 状態の保存 / 復元 (回転や再作成で閲覧位置・選択・クリップボードを失わない)
    // ----------------------------------------

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("mode", mode)
        out.putString("dir", currentDir?.absolutePath ?: "")
        out.putInt("category", currentCategory)
        out.putString("title", currentTitle)
        out.putString("query", currentSearchQuery)
        out.putStringArrayList("selected", ArrayList(selected))
        out.putStringArrayList("clipboard", ArrayList(clipboard.map { it.absolutePath }))
        out.putBoolean("clipCut", clipboardCut)
        out.putStringArrayList("history", ArrayList(dirHistory.map { it.absolutePath }))
    }

    private fun restoreState(bundle: Bundle): Boolean {
        try {
            val restoredMode = bundle.getInt("mode", MODE_HOME)
            bundle.getStringArrayList("selected")?.let { selected.addAll(it) }
            bundle.getStringArrayList("clipboard")?.let { paths ->
                for (path in paths) clipboard.add(File(path))
            }
            clipboardCut = bundle.getBoolean("clipCut", false)
            bundle.getStringArrayList("history")?.let { paths ->
                for (path in paths) dirHistory.add(File(path))
            }
            currentCategory = bundle.getInt("category", -1)
            currentSearchQuery = bundle.getString("query") ?: ""
            val dirPath = bundle.getString("dir") ?: ""
            when {
                restoredMode == MODE_BROWSE && File(dirPath).isDirectory -> {
                    mode = MODE_BROWSE
                    currentDir = File(dirPath)
                    currentTitle = currentDir!!.name.ifEmpty { currentDir!!.absolutePath }
                    refresh()
                    return true
                }
                restoredMode == MODE_CATEGORY && currentCategory >= 0 -> {
                    loadCategory(currentCategory)
                    return true
                }
                restoredMode == MODE_RECENT -> {
                    loadRecent()
                    return true
                }
                restoredMode == MODE_SEARCH && currentSearchQuery.isNotBlank() -> {
                    // 検索はクエリと起点フォルダを復元して再検索する
                    val base = File(dirPath)
                    currentDir = if (base.isDirectory) base else internalRoot()
                    runSearch(currentDir!!.absolutePath, currentSearchQuery)
                    return true
                }
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }

    private fun toast(message: String, long: Boolean = true) {
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // ========================================
    // 🖼️ 画面 (Compose)
    // ========================================

    @Composable
    private fun AppScreen() {
        @Suppress("UNUSED_VARIABLE") val tick = uiTick // 購読して再コンポーズのトリガにする
        BackHandler { handleBack() }
        MaterialTheme {
            Surface(color = Color.White, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().background(Color.White)) {
                    // ヘッダー (タイトル + 検索 + メニュー)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(headerBlue)
                            .padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "📂 $currentTitle" + if (selected.isEmpty()) "" else "（${selected.size}件選択）",
                            fontSize = 20.sp,
                            color = nameColor,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { askSearch() }) { Text("🔍", fontSize = 18.sp) }
                        TextButton(onClick = { dialog = UiDialog.Menu }) { Text("⋮", fontSize = 18.sp) }
                    }
                    if (mode == MODE_HOME) StorageInfo()
                    // パス表示 (閲覧中はタップでパス入力へ)
                    Text(
                        pathText(),
                        fontSize = 13.sp,
                        color = subColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = mode == MODE_BROWSE) { dialog = UiDialog.JumpPath }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (isEmptyList()) {
                            Text(
                                emptyText(),
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 24.dp)
                            )
                        } else {
                            FileList()
                        }
                    }
                    // 貼り付けバー (クリップボードがあり閲覧中のときだけ)
                    if (clipboard.isNotEmpty() && mode == MODE_BROWSE) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(pasteYellow)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Button(onClick = { doPaste() }) { Text("ここに貼り付け") }
                            Button(onClick = { clipboard.clear(); refresh() }) { Text("キャンセル") }
                        }
                    }
                    // 選択中アクションバー
                    if (selected.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(headerBlue)
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            SelectionButton("コピー") { setClipboard(cut = false) }
                            SelectionButton("切り取り") { setClipboard(cut = true) }
                            SelectionButton("名前変更") { askRenameSingle() }
                            SelectionButton("詳細") { showDetailsSingle() }
                            SelectionButton("削除") { askDeleteSelected() }
                        }
                    }
                }
            }
            Dialogs()
        }
    }

    @Composable
    private fun StorageInfo() {
        val root = internalRoot()
        val total = root.totalSpace
        val free = root.freeSpace
        if (total <= 0) return
        val used = total - free
        Text(
            "💾 内部ストレージ: ${FileOps.formatSize(used)} / ${FileOps.formatSize(total)} 使用中（空き ${FileOps.formatSize(free)}）",
            fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 6.dp, end = 12.dp)
        )
        LinearProgressIndicator(
            progress = { (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 2.dp, end = 12.dp, bottom = 4.dp)
                .height(10.dp)
        )
    }

    private fun pathText(): String = when (mode) {
        MODE_HOME -> "カテゴリ・保存場所から探せます（タップで開く。お気に入りは長押しで解除）"
        MODE_BROWSE -> currentDir!!.absolutePath + "  （タップで移動）"
        else -> currentTitle + "（内部ストレージ内を集めています）"
    }

    private fun isEmptyList(): Boolean =
        if (mode == MODE_HOME) homeActions.isEmpty() else currentFiles.isEmpty()

    private fun emptyText(): String = when (mode) {
        MODE_HOME -> ""
        MODE_BROWSE -> "このフォルダは空です"
        else -> "見つかりませんでした"
    }

    @Composable
    private fun FileList() {
        LazyColumn(Modifier.fillMaxSize()) {
            if (mode == MODE_HOME) {
                itemsIndexed(homeActions, key = { _, action -> action }) { pos, action ->
                    EntryRow(
                        icon = homeIcon(action),
                        name = homeLabel(action),
                        sub = homeSub(action),
                        selected = false,
                        onClick = { onItemClick(pos) },
                        onLongClick = { onItemLongClick(pos) }
                    )
                }
            } else {
                itemsIndexed(currentFiles, key = { _, f -> f.absolutePath }) { pos, f ->
                    val isSelected = selected.contains(f.absolutePath)
                    val sub = if (f.isDirectory) {
                        val children = f.listFiles()
                        "${children?.size ?: 0} 項目 • ${FileOps.formatDate(f.lastModified())}"
                    } else {
                        "${FileOps.formatSize(f.length())} • ${FileOps.formatDate(f.lastModified())}"
                    }
                    EntryRow(
                        icon = if (isSelected) "✅" else FileOps.iconFor(f),
                        name = f.name,
                        sub = sub,
                        selected = isSelected,
                        onClick = { onItemClick(pos) },
                        onLongClick = { onItemLongClick(pos) }
                    )
                }
            }
        }
    }

    // ========================================
    // 💬 ダイアログ群
    // ========================================

    @Composable
    private fun Dialogs() {
        when (val state = dialog) {
            null -> Unit
            UiDialog.Menu -> MenuDialog()
            UiDialog.Sort -> SortDialog()
            is UiDialog.Search -> InputDialog(
                title = "検索（" + state.baseName + " 以下）",
                initial = "",
                hint = "ファイル名の一部",
                positiveLabel = "検索"
            ) { value -> runSearch(state.basePath, value) }
            UiDialog.JumpPath -> InputDialog(
                title = "パスを入力して移動",
                initial = currentDir?.absolutePath ?: "",
                hint = null,
                positiveLabel = "移動"
            ) { value ->
                val target = File(value.trim())
                if (target.isDirectory) openFolder(target) else toast("フォルダが見つかりません")
            }
            is UiDialog.Create -> InputDialog(
                title = if (state.folder) "新規フォルダ" else "新規ファイル",
                initial = "",
                hint = if (state.folder) "フォルダ名" else "ファイル名 (例: memo.txt)",
                positiveLabel = "作成"
            ) { value -> doCreate(state.folder, value) }
            is UiDialog.Rename -> InputDialog(
                title = "名前変更",
                initial = File(state.path).name,
                hint = null,
                positiveLabel = "変更"
            ) { value -> doRename(state.path, value) }
            UiDialog.DeleteConfirm -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("削除しますか？") },
                text = { Text(selected.size.toString() + " 件を削除します。元に戻せません。") },
                confirmButton = {
                    TextButton(onClick = { dialog = null; doDelete() }) { Text("削除") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("キャンセル") } }
            )
            is UiDialog.Details -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("詳細") },
                text = { Text(state.message) },
                confirmButton = {
                    if (state.file.isFile) {
                        TextButton(onClick = { dialog = null; shareFile(state.file) }) { Text("共有") }
                    }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("閉じる") } }
            )
            is UiDialog.ImagePreview -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(state.file.name) },
                text = {
                    Image(
                        state.bitmap.asImageBitmap(),
                        contentDescription = state.file.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                    )
                },
                confirmButton = {
                    TextButton(onClick = { dialog = null; openExternal(state.file) }) { Text("外部アプリで開く") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("閉じる") } }
            )
            is UiDialog.TextPreview -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(state.file.name) },
                text = {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(max = 420.dp)
                    ) {
                        Text(state.text, fontSize = 13.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { dialog = null; openExternal(state.file) }) { Text("外部アプリで開く") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("閉じる") } }
            )
            is UiDialog.FavRemove -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("お気に入りから外しますか？") },
                text = { Text(state.path) },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        val favs = favorites().toMutableSet()
                        favs.remove(state.path)
                        prefs.edit().putStringSet("favorites", favs).apply()
                        showHome()
                    }) { Text("外す") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("キャンセル") } }
            )
            UiDialog.Permission -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("ファイルへのアクセス") },
                text = {
                    Text("内部ストレージ全体を見るには「すべてのファイルへのアクセス」の許可が必要です。設定画面で許可してください。許可しなくてもアプリ専用フォルダは使えます。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:$packageName")
                            startActivity(intent)
                        } catch (e: Exception) {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }) { Text("設定を開く") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("あとで") } }
            )
        }
    }

    @Composable
    private fun InputDialog(
        title: String,
        initial: String,
        hint: String?,
        positiveLabel: String,
        onConfirm: (String) -> Unit
    ) {
        var text by remember { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = hint?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { val value = text; dialog = null; onConfirm(value) }) {
                    Text(positiveLabel)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("キャンセル") } }
        )
    }

    @Composable
    private fun MenuDialog() {
        val items = buildMenuItems()
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("メニュー") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (item in items) {
                        Text(
                            item,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { dialog = null; onMenuItem(item) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("キャンセル") } }
        )
    }

    @Composable
    private fun SortDialog() {
        // ラベルの並び (インデックス) は applySort の引数と対応する
        val labels = listOf(
            "名前 ↑", "名前 ↓",
            "日付 新しい順", "日付 古い順",
            "サイズ 大きい順", "サイズ 小さい順",
            "種類順"
        )
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("並び替え") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    labels.forEachIndexed { index, label ->
                        Text(
                            label,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { dialog = null; applySort(index) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("キャンセル") } }
        )
    }

    // ========================================
    // 🏠 ホーム
    // ========================================

    private fun showHome() {
        mode = MODE_HOME
        currentTitle = "ファイル"
        selected.clear()
        dirHistory.clear()
        homeActions.clear()
        for (category in 0..5) homeActions.add("cat:$category")
        homeActions.add("recent")
        homeActions.add("download")
        homeActions.add("storage")
        homeActions.add("appfolder")
        for (fav in favorites()) homeActions.add("fav:$fav")
        refresh()
    }

    private fun homeSub(action: String): String = when {
        action.startsWith("cat:") -> "内部ストレージ内を検索"
        action == "recent" -> "最近更新された順"
        action == "download" -> "Download フォルダ"
        action == "storage" -> internalRoot().absolutePath
        action == "appfolder" -> filesDir.absolutePath
        action.startsWith("fav:") -> action.substring(4)
        else -> ""
    }

    private fun favorites(): Set<String> =
        LinkedHashSet(prefs.getStringSet("favorites", HashSet()) ?: emptySet())

    // ========================================
    // 🔄 表示の更新と移動
    // ========================================

    private fun refresh() {
        if (mode == MODE_BROWSE) {
            val files = FileOps.listDir(currentDir, showHidden)
            FileOps.sortFiles(files, sortMode, sortAsc)
            currentFiles = files
        }
        uiTick++
    }

    private fun openFolder(dir: File?) {
        if (dir == null || !dir.isDirectory) return
        if (mode == MODE_BROWSE && currentDir != null && dir != currentDir) {
            dirHistory.push(currentDir)
        }
        enterFolder(dir)
    }

    private fun openFolderNoHistory(dir: File) = enterFolder(dir)

    private fun enterFolder(dir: File) {
        mode = MODE_BROWSE
        currentDir = dir
        currentTitle = dir.name.ifEmpty { dir.absolutePath }
        selected.clear()
        refresh()
    }

    private fun loadCategory(category: Int) {
        mode = MODE_CATEGORY
        currentCategory = category
        currentTitle = FileOps.categoryLabel(category)
        selected.clear()
        currentFiles = mutableListOf()
        refresh()
        toast("検索中…", long = false)
        // 走査はバックグラウンドで行い、反映時にモード/カテゴリが
        // 変わっていたら結果を破棄する (競合ガード)
        Thread {
            val found = FileOps.collectByCategory(internalRoot(), category, 500, 8, showHidden)
            runOnUiThread {
                if (mode == MODE_CATEGORY && currentCategory == category) {
                    currentFiles = found
                    refresh()
                }
            }
        }.start()
    }

    private fun loadRecent() {
        mode = MODE_RECENT
        currentTitle = "最近使ったファイル"
        selected.clear()
        currentFiles = mutableListOf()
        refresh()
        Thread {
            val found = FileOps.collectRecent(internalRoot(), 100, 8, showHidden)
            runOnUiThread {
                if (mode == MODE_RECENT) {
                    currentFiles = found
                    refresh()
                }
            }
        }.start()
    }

    // ========================================
    // 👆 クリックと選択
    // ========================================

    private fun onItemClick(position: Int) {
        if (mode == MODE_HOME) {
            val action = homeActions[position]
            when {
                action.startsWith("cat:") -> loadCategory(action.substring(4).toInt())
                action == "recent" -> loadRecent()
                action == "download" -> openFolder(File(internalRoot(), "Download"))
                action == "storage" -> openFolder(internalRoot())
                action == "appfolder" -> openFolder(filesDir)
                action.startsWith("fav:") -> openFolder(File(action.substring(4)))
            }
            return
        }
        val file = currentFiles[position]
        if (selected.isNotEmpty()) {
            toggleSelect(file)
            refresh()
            return
        }
        if (file.isDirectory) openFolder(file) else openFile(file)
    }

    private fun onItemLongClick(position: Int) {
        if (mode == MODE_HOME) {
            val action = homeActions[position]
            if (action.startsWith("fav:")) {
                dialog = UiDialog.FavRemove(action.substring(4))
            }
            return
        }
        toggleSelect(currentFiles[position])
        refresh()
    }

    private fun toggleSelect(file: File) {
        val path = file.absolutePath
        if (!selected.add(path)) selected.remove(path)
    }

    // ========================================
    // 📖 ファイルを開く / プレビュー / 共有
    // ========================================

    private fun openFile(file: File) {
        when {
            FileOps.isImage(file.name) -> showImagePreview(file)
            FileOps.isTextLike(file.name) -> showTextPreview(file)
            else -> openExternal(file)
        }
    }

    private fun openExternal(file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(SimpleFileProvider.uriFor(file), FileOps.mimeType(file.name))
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            toast("開けるアプリが見つかりません")
        }
    }

    private fun shareFile(file: File) {
        try {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = FileOps.mimeType(file.name)
            intent.putExtra(Intent.EXTRA_STREAM, SimpleFileProvider.uriFor(file))
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "共有"))
        } catch (e: Exception) {
            toast("共有できませんでした")
        }
    }

    private fun showImagePreview(file: File) {
        // まず寸法だけ読み、表示に十分な大きさまで縮小してからデコードする (OOM 防止)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
        if (bitmap == null) {
            openExternal(file)
            return
        }
        dialog = UiDialog.ImagePreview(file, bitmap)
    }

    private fun showTextPreview(file: File) {
        if (file.length() > TEXT_PREVIEW_LIMIT) {
            openExternal(file)
            return
        }
        Thread {
            val text = try {
                val buffer = ByteArray(file.length().toInt())
                var offset = 0
                FileInputStream(file).use { input ->
                    var read: Int
                    while (input.read(buffer, offset, buffer.size - offset).also { read = it } > 0) {
                        offset += read
                    }
                }
                String(buffer, 0, offset, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                "（読み込めませんでした）"
            }
            runOnUiThread { dialog = UiDialog.TextPreview(file, text) }
        }.start()
    }

    // ========================================
    // 📋 メニューと並び替え
    // ========================================

    private fun buildMenuItems(): MutableList<String> {
        val items = mutableListOf<String>()
        items.add("🏠 ホームへ")
        items.add("🔍 検索")
        if (mode == MODE_BROWSE) {
            items.add("📁 新規フォルダ")
            items.add("📝 新規ファイル")
            items.add("⭐ このフォルダをお気に入りに")
        }
        items.add("↕️ 並び替え: " + sortLabel())
        items.add((if (showHidden) "✅" else "⬜") + " 隠しファイルを表示")
        items.add("🔐 すべてのファイルへのアクセス設定")
        items.add("🔄 更新")
        return items
    }

    private fun onMenuItem(item: String) {
        when {
            item.startsWith("🏠") -> showHome()
            item.startsWith("🔍") -> askSearch()
            item.startsWith("📁") -> dialog = UiDialog.Create(folder = true)
            item.startsWith("📝") -> dialog = UiDialog.Create(folder = false)
            item.startsWith("⭐") -> addFavorite(currentDir!!)
            item.startsWith("↕️") -> dialog = UiDialog.Sort
            item.contains("隠しファイル") -> {
                showHidden = !showHidden
                prefs.edit().putBoolean("showHidden", showHidden).apply()
                afterOperationRefresh()
            }
            item.startsWith("🔐") -> checkPermission(forceOpen = true)
            else -> refresh()
        }
    }

    private fun sortLabel(): String {
        val names = arrayOf("名前", "サイズ", "日付", "種類")
        return names[sortMode] + if (sortAsc) " ↑" else " ↓"
    }

    private fun applySort(which: Int) {
        when (which) {
            0 -> { sortMode = FileOps.SORT_NAME; sortAsc = true }
            1 -> { sortMode = FileOps.SORT_NAME; sortAsc = false }
            2 -> { sortMode = FileOps.SORT_DATE; sortAsc = false }
            3 -> { sortMode = FileOps.SORT_DATE; sortAsc = true }
            4 -> { sortMode = FileOps.SORT_SIZE; sortAsc = false }
            5 -> { sortMode = FileOps.SORT_SIZE; sortAsc = true }
            else -> { sortMode = FileOps.SORT_TYPE; sortAsc = true }
        }
        prefs.edit().putInt("sortMode", sortMode).putBoolean("sortAsc", sortAsc).apply()
        refresh()
    }

    // ========================================
    // 🔍 検索
    // ========================================

    private fun askSearch() {
        val base = if (mode == MODE_BROWSE) currentDir!! else internalRoot()
        dialog = UiDialog.Search(base.absolutePath, base.name)
    }

    private fun runSearch(basePath: String, rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return
        val base = File(basePath)
        mode = MODE_SEARCH
        currentSearchQuery = query
        currentTitle = "検索: $query"
        selected.clear()
        currentFiles = mutableListOf()
        refresh()
        Thread {
            val found = FileOps.searchRecursive(base, query, 500, 8, showHidden)
            FileOps.sortFiles(found, FileOps.SORT_NAME, ascending = true)
            runOnUiThread {
                if (mode == MODE_SEARCH && query == currentSearchQuery) {
                    currentFiles = found
                    refresh()
                }
            }
        }.start()
    }

    // ========================================
    // ✏️ 作成 / 名前変更 / 削除 / 詳細
    // ========================================

    private fun doCreate(folder: Boolean, rawName: String) {
        val name = rawName.trim()
        if (!FileOps.isSafeName(name)) {
            toast("使えない名前です")
            return
        }
        val target = File(currentDir, name)
        try {
            val created = if (folder) target.mkdirs() else target.createNewFile()
            if (!created) toast("作成できませんでした（同名あり等）")
        } catch (e: Exception) {
            toast("作成できませんでした: " + e.message)
        }
        refresh()
    }

    private fun askRenameSingle() {
        if (selected.size != 1) {
            toast("名前変更は1件だけ選んでください")
            return
        }
        dialog = UiDialog.Rename(selected.iterator().next())
    }

    private fun doRename(path: String, rawName: String) {
        val source = File(path)
        val name = rawName.trim()
        if (!FileOps.isSafeName(name)) {
            toast("使えない名前です")
            return
        }
        val dest = File(source.parentFile, name)
        if (dest.exists() || !source.renameTo(dest)) {
            toast("変更できませんでした")
        }
        selected.clear()
        afterOperationRefresh()
    }

    private fun askDeleteSelected() {
        if (selected.isEmpty()) return
        dialog = UiDialog.DeleteConfirm
    }

    private fun doDelete() {
        val targets = ArrayList(selected)
        selected.clear()
        Thread {
            var failures = 0
            for (path in targets) {
                if (!FileOps.deleteRecursive(File(path))) failures++
            }
            val failed = failures
            runOnUiThread {
                toast(if (failed == 0) "削除しました" else "一部削除できませんでした")
                afterOperationRefresh()
            }
        }.start()
    }

    private fun showDetailsSingle() {
        if (selected.size != 1) {
            toast("詳細は1件だけ選んでください（長押しで選択）")
            return
        }
        val file = File(selected.iterator().next())
        Thread {
            val sizeText: String
            var extra = ""
            if (file.isDirectory) {
                val counts = FileOps.countItems(file)
                sizeText = FileOps.formatSize(FileOps.totalSize(file))
                extra = "\n中身: ファイル " + counts[0] + " 件 / フォルダ " + counts[1] + " 件"
            } else {
                sizeText = FileOps.formatSize(file.length())
            }
            val typeLabel =
                if (file.isDirectory) "フォルダ"
                else FileOps.categoryLabel(FileOps.categoryOf(file.name))
            val message = "名前: ${file.name}\nパス: ${file.absolutePath}\n種類: $typeLabel" +
                "\nサイズ: $sizeText$extra" +
                "\n更新: ${FileOps.formatDate(file.lastModified())}" +
                "\n読み取り: ${if (file.canRead()) "可" else "不可"} / 書き込み: ${if (file.canWrite()) "可" else "不可"}"
            runOnUiThread { dialog = UiDialog.Details(file, message) }
        }.start()
    }

    // ========================================
    // 📋 コピー / 切り取り / 貼り付け
    // ========================================

    private fun setClipboard(cut: Boolean) {
        if (selected.isEmpty()) return
        clipboard.clear()
        for (path in selected) clipboard.add(File(path))
        clipboardCut = cut
        selected.clear()
        toast(
            clipboard.size.toString() + " 件を" + (if (cut) "切り取り" else "コピー") +
                "しました。貼り付け先のフォルダを開いてください"
        )
        refresh()
    }

    private fun doPaste() {
        if (clipboard.isEmpty() || mode != MODE_BROWSE) return
        val sources = ArrayList(clipboard)
        val cut = clipboardCut
        // 貼り付け先は開始時点のフォルダに固定する (実行中に移動しても変えない)
        val destDir = currentDir!!
        Thread {
            var failures = 0
            for (source in sources) {
                try {
                    if (cut && source.parentFile != null &&
                        source.parentFile!!.canonicalPath == destDir.canonicalPath
                    ) {
                        continue // 同じフォルダへの切り取り貼り付けは何もしない
                    }
                    if (cut) {
                        val dest = FileOps.uniqueFile(destDir, source.name)
                        if (!source.renameTo(dest)) {
                            FileOps.copyRecursive(source, destDir)
                            FileOps.deleteRecursive(source)
                        }
                    } else {
                        FileOps.copyRecursive(source, destDir)
                    }
                } catch (e: Exception) {
                    failures++
                }
            }
            val failed = failures
            runOnUiThread {
                clipboard.clear()
                selected.clear()
                toast(if (failed == 0) "貼り付けました" else "一部貼り付けできませんでした")
                refresh()
            }
        }.start()
    }

    private fun afterOperationRefresh() {
        when {
            mode == MODE_CATEGORY && currentCategory >= 0 -> loadCategory(currentCategory)
            mode == MODE_RECENT -> loadRecent()
            mode == MODE_SEARCH && currentSearchQuery.isNotEmpty() -> {
                // 検索結果も再検索して最新化する (検索起点フォルダは currentDir と一致する)
                runSearch((currentDir ?: internalRoot()).absolutePath, currentSearchQuery)
            }
            else -> refresh()
        }
    }

    private fun addFavorite(dir: File) {
        val favs = favorites().toMutableSet()
        favs.add(dir.absolutePath)
        prefs.edit().putStringSet("favorites", favs).apply()
        toast("お気に入りに追加しました（ホームに表示）")
    }

    // ========================================
    // 🔐 権限
    // ========================================

    private fun checkPermission(forceOpen: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                // 起動時の案内は初回だけ出す
                if (!forceOpen && prefs.getBoolean("permDialogShown", false)) return
                prefs.edit().putBoolean("permDialogShown", true).apply()
                dialog = UiDialog.Permission
            } else if (forceOpen) {
                toast("すでに許可されています", long = false)
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                1
            )
        } else if (forceOpen) {
            toast("すでに許可されています", long = false)
        }
    }

    // ========================================
    // ↩️ Back の処理
    // ========================================

    // 優先順位: 選択解除 → 履歴で親フォルダへ → ホームへ → 終了
    private fun handleBack() {
        if (selected.isNotEmpty()) {
            selected.clear()
            refresh()
            return
        }
        if (mode == MODE_BROWSE) {
            if (dirHistory.isNotEmpty()) openFolderNoHistory(dirHistory.pop()) else showHome()
            return
        }
        if (mode != MODE_HOME) {
            showHome()
            return
        }
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (mode == MODE_HOME) uiTick++
    }
}
