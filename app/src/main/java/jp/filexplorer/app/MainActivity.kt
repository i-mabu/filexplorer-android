package jp.filexplorer.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.Image
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

// ===== FileXplorer: Google Files 風のファイル管理アプリ (UI は Jetpack Compose) =====
// ホーム = カテゴリ + ストレージ、閲覧 = エクスプローラー風リスト
class MainActivity : ComponentActivity() {
    companion object {
        private const val MODE_HOME = 0
        private const val MODE_BROWSE = 1
        private const val MODE_CATEGORY = 2
        private const val MODE_RECENT = 3
        private const val MODE_SEARCH = 4
    }

    // ----- ダイアログ状態 (Compose の AlertDialog で表示する) -----
    private sealed interface UiDialog {
        data object Menu : UiDialog
        data object Sort : UiDialog
        data class Search(val basePath: String, val baseName: String) : UiDialog
        data object JumpPath : UiDialog
        data class Create(val folder: Boolean) : UiDialog
        data class Rename(val path: String) : UiDialog
        data object DeleteConfirm : UiDialog
        data class Details(val file: File, val message: String) : UiDialog
        data class ImagePreview(val file: File, val bitmap: Bitmap) : UiDialog
        data class TextPreview(val file: File, val text: String) : UiDialog
        data class FavRemove(val path: String) : UiDialog
        data object Permission : UiDialog
    }

    private var mode = MODE_HOME
    private var currentDir: File? = null
    private var currentCategory = -1
    private var currentTitle = "ファイル"
    private var currentFiles: MutableList<File> = mutableListOf()
    private val homeActions = mutableListOf<String>() // ホーム行のアクションID

    private val selected = LinkedHashSet<String>()
    private val dirHistory = ArrayDeque<File>()
    private var currentSearchQuery = ""
    private var clipboard: MutableList<File> = mutableListOf()
    private var clipboardCut = false

    private lateinit var prefs: SharedPreferences
    private var sortMode = FileOps.SORT_NAME
    private var sortAsc = true
    private var showHidden = false

    // 再描画トリガとダイアログ表示状態 (フィールドの変更後に uiTick を進めると再コンポーズされる)
    private var uiTick by mutableIntStateOf(0)
    private var dialog by mutableStateOf<UiDialog?>(null)

    private fun internalRoot(): File {
        return Environment.getExternalStorageDirectory() ?: filesDir
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("filexplorer", MODE_PRIVATE)
        sortMode = prefs.getInt("sortMode", FileOps.SORT_NAME)
        sortAsc = prefs.getBoolean("sortAsc", true)
        showHidden = prefs.getBoolean("showHidden", false)
        currentDir = internalRoot()
        setContent { AppScreen() }
        if (savedInstanceState != null && restoreState(savedInstanceState)) {
            // 復元できた場合はホームに戻さない (回転などで閲覧位置とクリップボードを保持)
        } else {
            showHome()
        }
        checkPermission(false)
    }

    // ----- 状態の保存 / 復元 (画面回転や再作成で閲覧位置・選択・クリップボードを失わない) -----
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("mode", mode)
        out.putString("dir", currentDir?.absolutePath ?: "")
        out.putInt("category", currentCategory)
        out.putString("title", currentTitle)
        out.putString("query", currentSearchQuery)
        out.putStringArrayList("selected", ArrayList(selected))
        val clip = ArrayList<String>()
        for (f in clipboard) clip.add(f.absolutePath)
        out.putStringArrayList("clipboard", clip)
        out.putBoolean("clipCut", clipboardCut)
        val hist = ArrayList<String>()
        for (f in dirHistory) hist.add(f.absolutePath)
        out.putStringArrayList("history", hist)
    }

    private fun restoreState(b: Bundle): Boolean {
        try {
            val m = b.getInt("mode", MODE_HOME)
            val sel = b.getStringArrayList("selected")
            if (sel != null) selected.addAll(sel)
            val clip = b.getStringArrayList("clipboard")
            if (clip != null) for (s in clip) clipboard.add(File(s))
            clipboardCut = b.getBoolean("clipCut", false)
            val hist = b.getStringArrayList("history")
            if (hist != null) for (s in hist) dirHistory.add(File(s))
            currentCategory = b.getInt("category", -1)
            currentSearchQuery = b.getString("query") ?: ""
            val dir = b.getString("dir") ?: ""
            if (m == MODE_BROWSE && File(dir).isDirectory) {
                mode = MODE_BROWSE
                currentDir = File(dir)
                currentTitle = if (currentDir!!.name.isEmpty()) currentDir!!.absolutePath else currentDir!!.name
                refresh()
                return true
            }
            if (m == MODE_CATEGORY && currentCategory >= 0) { loadCategory(currentCategory); return true }
            if (m == MODE_RECENT) { loadRecent(); return true }
            return false
        } catch (e: Exception) { return false }
    }

    private fun toast(msg: String, long: Boolean = true) {
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // ================================================================
    //  Compose UI
    // ================================================================
    private val headerBlue = Color(232, 240, 254)
    private val nameColor = Color(32, 33, 36)
    private val subColor = Color(95, 99, 104)
    private val pasteYellow = Color(254, 247, 224)

    @Composable
    private fun AppScreen() {
        @Suppress("UNUSED_VARIABLE") val tick = uiTick // 購読して再コンポーズのトリガにする
        BackHandler { handleBack() }
        MaterialTheme {
            Surface(color = Color.White, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().background(Color.White)) {
                    // ヘッダー
                    Row(
                        Modifier.fillMaxWidth().background(headerBlue)
                            .padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "📂 $currentTitle" + if (selected.isEmpty()) "" else "（${selected.size}件選択）",
                            fontSize = 20.sp, color = nameColor, modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { askSearch() }) { Text("🔍", fontSize = 18.sp) }
                        TextButton(onClick = { dialog = UiDialog.Menu }) { Text("⋮", fontSize = 18.sp) }
                    }
                    if (mode == MODE_HOME) StorageInfo()
                    // パス表示 (閲覧中はタップでパス入力へ)
                    Text(
                        pathText(),
                        fontSize = 13.sp, color = subColor,
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = mode == MODE_BROWSE) { dialog = UiDialog.JumpPath }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (isEmptyList()) {
                            Text(
                                emptyText(), fontSize = 15.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)
                            )
                        } else {
                            FileList()
                        }
                    }
                    if (clipboard.isNotEmpty() && mode == MODE_BROWSE) {
                        Row(
                            Modifier.fillMaxWidth().background(pasteYellow)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Button(onClick = { doPaste() }) { Text("ここに貼り付け") }
                            Button(onClick = { clipboard.clear(); refresh() }) { Text("キャンセル") }
                        }
                    }
                    if (selected.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().background(headerBlue)
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            SelectionButton("コピー") { setClipboard(false) }
                            SelectionButton("切り取り") { setClipboard(true) }
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
        val r = internalRoot()
        val total = r.totalSpace
        val free = r.freeSpace
        if (total <= 0) return
        val used = total - free
        Text(
            "💾 内部ストレージ: ${FileOps.formatSize(used)} / ${FileOps.formatSize(total)} 使用中（空き ${FileOps.formatSize(free)}）",
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, end = 12.dp)
        )
        LinearProgressIndicator(
            progress = { (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, end = 12.dp, bottom = 4.dp).height(10.dp)
        )
    }

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.SelectionButton(label: String, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 8.dp)
        ) { Text(label, fontSize = 12.sp) }
    }

    private fun pathText(): String {
        return when (mode) {
            MODE_HOME -> "カテゴリ・保存場所から探せます。長押しは使いません（タップで開く）"
            MODE_BROWSE -> currentDir!!.absolutePath + "  （タップで移動）"
            else -> currentTitle + "（内部ストレージ内を集めています）"
        }
    }

    private fun isEmptyList(): Boolean {
        return if (mode == MODE_HOME) homeActions.isEmpty() else currentFiles.isEmpty()
    }

    private fun emptyText(): String {
        return when (mode) {
            MODE_HOME -> ""
            MODE_BROWSE -> "このフォルダは空です"
            else -> "見つかりませんでした"
        }
    }

    @Composable
    private fun FileList() {
        LazyColumn(Modifier.fillMaxSize()) {
            if (mode == MODE_HOME) {
                itemsIndexed(homeActions, key = { _, act -> act }) { pos, act ->
                    EntryRow(
                        icon = homeIcon(act), name = homeLabel(act), sub = homeSub(act), selected = false,
                        onClick = { onItemClick(pos) }, onLongClick = { onItemLongClick(pos) }
                    )
                }
            } else {
                itemsIndexed(currentFiles, key = { _, f -> f.absolutePath }) { pos, f ->
                    val sel = selected.contains(f.absolutePath)
                    val sub = if (f.isDirectory) {
                        val k = f.listFiles()
                        "${k?.size ?: 0} 項目 • ${FileOps.formatDate(f.lastModified())}"
                    } else {
                        "${FileOps.formatSize(f.length())} • ${FileOps.formatDate(f.lastModified())}"
                    }
                    EntryRow(
                        icon = if (sel) "✅" else FileOps.iconFor(f), name = f.name, sub = sub, selected = sel,
                        onClick = { onItemClick(pos) }, onLongClick = { onItemLongClick(pos) }
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun EntryRow(icon: String, name: String, sub: String, selected: Boolean,
                         onClick: () -> Unit, onLongClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (selected) headerBlue else Color.White)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 26.sp)
            Column {
                Text(name, fontSize = 16.sp, color = nameColor)
                Text(sub, fontSize = 12.sp, color = subColor)
            }
        }
    }

    // ----- ダイアログ群 -----
    @Composable
    private fun Dialogs() {
        when (val d = dialog) {
            null -> Unit
            UiDialog.Menu -> MenuDialog()
            UiDialog.Sort -> SortDialog()
            is UiDialog.Search -> InputDialog(
                title = "検索（" + d.baseName + " 以下）",
                initial = "", hint = "ファイル名の一部", positiveLabel = "検索"
            ) { v -> runSearch(d.basePath, v) }
            UiDialog.JumpPath -> InputDialog(
                title = "パスを入力して移動",
                initial = currentDir?.absolutePath ?: "", hint = null, positiveLabel = "移動"
            ) { v ->
                val f = File(v.trim())
                if (f.isDirectory) openFolder(f) else toast("フォルダが見つかりません")
            }
            is UiDialog.Create -> InputDialog(
                title = if (d.folder) "新規フォルダ" else "新規ファイル",
                initial = "", hint = if (d.folder) "フォルダ名" else "ファイル名 (例: memo.txt)",
                positiveLabel = "作成"
            ) { v -> doCreate(d.folder, v) }
            is UiDialog.Rename -> InputDialog(
                title = "名前変更",
                initial = File(d.path).name, hint = null, positiveLabel = "変更"
            ) { v -> doRename(d.path, v) }
            UiDialog.DeleteConfirm -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("削除しますか？") },
                text = { Text(selected.size.toString() + " 件を削除します。元に戻せません。") },
                confirmButton = { TextButton(onClick = { dialog = null; doDelete() }) { Text("削除") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("キャンセル") } }
            )
            is UiDialog.Details -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("詳細") },
                text = { Text(d.message) },
                confirmButton = {
                    if (d.file.isFile) TextButton(onClick = { dialog = null; shareFile(d.file) }) { Text("共有") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("閉じる") } }
            )
            is UiDialog.ImagePreview -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(d.file.name) },
                text = {
                    Image(
                        d.bitmap.asImageBitmap(), contentDescription = d.file.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                    )
                },
                confirmButton = { TextButton(onClick = { dialog = null; openExternal(d.file) }) { Text("外部アプリで開く") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("閉じる") } }
            )
            is UiDialog.TextPreview -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(d.file.name) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp)) {
                        Text(d.text, fontSize = 13.sp)
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null; openExternal(d.file) }) { Text("外部アプリで開く") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("閉じる") } }
            )
            is UiDialog.FavRemove -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("お気に入りから外しますか？") },
                text = { Text(d.path) },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        val fav = favorites().toMutableSet(); fav.remove(d.path)
                        prefs.edit().putStringSet("favorites", fav).apply()
                        showHome()
                    }) { Text("外す") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("キャンセル") } }
            )
            UiDialog.Permission -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("ファイルへのアクセス") },
                text = { Text("内部ストレージ全体を見るには「すべてのファイルへのアクセス」の許可が必要です。設定画面で許可してください。許可しなくてもアプリ専用フォルダは使えます。") },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        try {
                            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            i.data = Uri.parse("package:$packageName"); startActivity(i)
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
    private fun InputDialog(title: String, initial: String, hint: String?, positiveLabel: String,
                            onConfirm: (String) -> Unit) {
        var text by remember { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = hint?.let { { Text(it) } },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = { TextButton(onClick = { val v = text; dialog = null; onConfirm(v) }) { Text(positiveLabel) } },
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
                    for (s in items) {
                        Text(
                            s, fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { dialog = null; onMenuItem(s) }
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
        // ラベルの並び順 (インデックス) は applySort(w) の引数と対応する
        val labels = listOf("名前 ↑", "名前 ↓", "日付 新しい順", "日付 古い順", "サイズ 大きい順", "サイズ 小さい順", "種類順")
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("並び替え") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    labels.forEachIndexed { w, s ->
                        Text(
                            s, fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { dialog = null; applySort(w) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("キャンセル") } }
        )
    }

    // ================================================================
    //  ロジック (Compose化以前の View 版からそのまま移植したもの)
    // ================================================================
    // ----- ホーム -----
    private fun showHome() {
        mode = MODE_HOME; currentTitle = "ファイル"; selected.clear(); dirHistory.clear()
        homeActions.clear()
        homeActions.add("cat:0"); homeActions.add("cat:1"); homeActions.add("cat:2")
        homeActions.add("cat:3"); homeActions.add("cat:4"); homeActions.add("cat:5")
        homeActions.add("recent"); homeActions.add("download")
        homeActions.add("storage"); homeActions.add("appfolder")
        for (fav in favorites()) homeActions.add("fav:$fav")
        refresh()
    }

    private fun homeIcon(act: String): String {
        if (act.startsWith("cat:")) {
            return when (act.substring(4).toInt()) {
                FileOps.CAT_IMAGE -> "🖼️"; FileOps.CAT_VIDEO -> "🎬"; FileOps.CAT_AUDIO -> "🎵"
                FileOps.CAT_DOCUMENT -> "📕"; FileOps.CAT_ARCHIVE -> "🗜️"; FileOps.CAT_APK -> "📦"
                else -> "📄"
            }
        }
        if (act == "recent") return "🕘"
        if (act == "download") return "⬇️"
        if (act == "storage") return "💾"
        if (act == "appfolder") return "📱"
        if (act.startsWith("fav:")) return "⭐"
        return "📁"
    }

    private fun homeLabel(act: String): String {
        if (act.startsWith("cat:")) return FileOps.categoryLabel(act.substring(4).toInt())
        if (act == "recent") return "最近使ったファイル"
        if (act == "download") return "ダウンロード"
        if (act == "storage") return "内部ストレージ"
        if (act == "appfolder") return "アプリ専用フォルダ"
        if (act.startsWith("fav:")) return File(act.substring(4)).name
        return act
    }

    private fun homeSub(act: String): String {
        if (act.startsWith("cat:")) return "内部ストレージ内を検索"
        if (act == "recent") return "最近更新された順"
        if (act == "download") return "Download フォルダ"
        if (act == "storage") return internalRoot().absolutePath
        if (act == "appfolder") return filesDir.absolutePath
        if (act.startsWith("fav:")) return act.substring(4)
        return ""
    }

    private fun favorites(): Set<String> {
        return LinkedHashSet(prefs.getStringSet("favorites", HashSet()) ?: emptySet())
    }

    // ----- 表示更新 -----
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
        if (mode == MODE_BROWSE && currentDir != null && dir != currentDir) dirHistory.push(currentDir)
        mode = MODE_BROWSE; currentDir = dir
        currentTitle = if (dir.name.isEmpty()) dir.absolutePath else dir.name
        selected.clear(); refresh()
    }

    private fun openFolderNoHistory(dir: File) {
        mode = MODE_BROWSE; currentDir = dir
        currentTitle = if (dir.name.isEmpty()) dir.absolutePath else dir.name
        selected.clear(); refresh()
    }

    private fun loadCategory(cat: Int) {
        mode = MODE_CATEGORY; currentCategory = cat; currentTitle = FileOps.categoryLabel(cat); selected.clear()
        currentFiles = mutableListOf(); refresh()
        toast("検索中…", long = false)
        // 走査はバックグラウンドで行い、反映時にモード/カテゴリが変わっていたら結果を破棄する (競合ガード)
        Thread {
            val found = FileOps.collectByCategory(internalRoot(), cat, 500, 8)
            runOnUiThread { if (mode == MODE_CATEGORY && currentCategory == cat) { currentFiles = found; refresh() } }
        }.start()
    }

    private fun loadRecent() {
        mode = MODE_RECENT; currentTitle = "最近使ったファイル"; selected.clear(); currentFiles = mutableListOf(); refresh()
        Thread {
            val found = FileOps.collectRecent(internalRoot(), 100, 8)
            runOnUiThread { if (mode == MODE_RECENT) { currentFiles = found; refresh() } }
        }.start()
    }

    // ----- クリック -----
    private fun onItemClick(pos: Int) {
        if (mode == MODE_HOME) {
            val act = homeActions[pos]
            when {
                act.startsWith("cat:") -> loadCategory(act.substring(4).toInt())
                act == "recent" -> loadRecent()
                act == "download" -> openFolder(File(internalRoot(), "Download"))
                act == "storage" -> openFolder(internalRoot())
                act == "appfolder" -> openFolder(filesDir)
                act.startsWith("fav:") -> openFolder(File(act.substring(4)))
            }
            return
        }
        val f = currentFiles[pos]
        if (selected.isNotEmpty()) { toggleSelect(f); refresh(); return }
        if (f.isDirectory) openFolder(f) else openFile(f)
    }

    private fun onItemLongClick(pos: Int) {
        if (mode == MODE_HOME) {
            val act = homeActions[pos]
            if (act.startsWith("fav:")) {
                dialog = UiDialog.FavRemove(act.substring(4))
            }
            return
        }
        toggleSelect(currentFiles[pos]); refresh()
    }

    private fun toggleSelect(f: File) {
        val p = f.absolutePath
        if (selected.contains(p)) selected.remove(p) else selected.add(p)
    }

    // ----- ファイルを開く / プレビュー -----
    private fun openFile(f: File) {
        val name = f.name
        if (FileOps.isImage(name)) { showImagePreview(f); return }
        if (FileOps.isTextLike(name)) { showTextPreview(f); return }
        openExternal(f)
    }

    private fun openExternal(f: File) {
        try {
            val uri = SimpleFileProvider.uriFor(f)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, FileOps.mimeType(f.name))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            toast("開けるアプリが見つかりません")
        }
    }

    private fun shareFile(f: File) {
        try {
            val uri = SimpleFileProvider.uriFor(f)
            val i = Intent(Intent.ACTION_SEND)
            i.type = FileOps.mimeType(f.name)
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "共有"))
        } catch (e: Exception) { toast("共有できませんでした") }
    }

    private fun showImagePreview(f: File) {
        // まず寸法だけ読み、表示に十分な大きさまで縮小してからデコードする (巨大写真での OOM 防止)
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        val imgW = bounds.outWidth; val imgH = bounds.outHeight
        while (imgW / sample > 1024 || imgH / sample > 1024) sample *= 2
        val o = BitmapFactory.Options(); o.inSampleSize = sample
        val bmp = BitmapFactory.decodeFile(f.absolutePath, o)
        if (bmp == null) { openExternal(f); return }
        dialog = UiDialog.ImagePreview(f, bmp)
    }

    private fun showTextPreview(f: File) {
        // 200KB を超えるテキストはアプリ内プレビューせず外部アプリに任せる
        if (f.length() > 200 * 1024) { openExternal(f); return }
        Thread {
            var text: String
            try {
                val buf = ByteArray(f.length().toInt())
                var off = 0
                FileInputStream(f).use { input ->
                    var n: Int
                    while (input.read(buf, off, buf.size - off).also { n = it } > 0) off += n
                }
                text = String(buf, 0, off, StandardCharsets.UTF_8)
            } catch (e: Exception) { text = "（読み込めませんでした）" }
            val shown = text
            runOnUiThread { dialog = UiDialog.TextPreview(f, shown) }
        }.start()
    }

    // ----- メニュー -----
    private fun buildMenuItems(): MutableList<String> {
        val items = mutableListOf<String>()
        items.add("🏠 ホームへ")
        items.add("🔍 検索")
        if (mode == MODE_BROWSE) { items.add("📁 新規フォルダ"); items.add("📝 新規ファイル"); items.add("⭐ このフォルダをお気に入りに") }
        items.add("↕️ 並び替え: " + sortLabel())
        items.add((if (showHidden) "✅" else "⬜") + " 隠しファイルを表示")
        items.add("🔐 すべてのファイルへのアクセス設定")
        items.add("🔄 更新")
        return items
    }

    private fun onMenuItem(s: String) {
        when {
            s.startsWith("🏠") -> showHome()
            s.startsWith("🔍") -> askSearch()
            s.startsWith("📁") -> dialog = UiDialog.Create(true)
            s.startsWith("📝") -> dialog = UiDialog.Create(false)
            s.startsWith("⭐") -> addFavorite(currentDir!!)
            s.startsWith("↕️") -> dialog = UiDialog.Sort
            s.contains("隠しファイル") -> { showHidden = !showHidden; prefs.edit().putBoolean("showHidden", showHidden).apply(); refresh() }
            s.startsWith("🔐") -> checkPermission(true)
            else -> refresh()
        }
    }

    private fun sortLabel(): String {
        val n = arrayOf("名前", "サイズ", "日付", "種類")
        return n[sortMode] + if (sortAsc) " ↑" else " ↓"
    }

    private fun applySort(w: Int) {
        when (w) {
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

    private fun askSearch() {
        val base = if (mode == MODE_BROWSE) currentDir!! else internalRoot()
        dialog = UiDialog.Search(base.absolutePath, base.name)
    }

    private fun runSearch(basePath: String, rawQuery: String) {
        val q = rawQuery.trim(); if (q.isEmpty()) return
        val base = File(basePath)
        mode = MODE_SEARCH; currentSearchQuery = q; currentTitle = "検索: $q"; selected.clear(); currentFiles = mutableListOf(); refresh()
        Thread {
            val found = FileOps.searchRecursive(base, q, 500, 8)
            FileOps.sortFiles(found, FileOps.SORT_NAME, true)
            runOnUiThread { if (mode == MODE_SEARCH && q == currentSearchQuery) { currentFiles = found; refresh() } }
        }.start()
    }

    // ----- 作成 / 名前変更 / 削除 / 詳細 -----
    private fun doCreate(folder: Boolean, rawName: String) {
        val name = rawName.trim()
        if (!FileOps.isSafeName(name)) { toast("使えない名前です"); return }
        val f = File(currentDir, name)
        try {
            val ok = if (folder) f.mkdirs() else f.createNewFile()
            if (!ok) toast("作成できませんでした（同名あり等）")
        } catch (e: Exception) { toast("作成できませんでした: " + e.message) }
        refresh()
    }

    private fun askRenameSingle() {
        if (selected.size != 1) { toast("名前変更は1件だけ選んでください"); return }
        dialog = UiDialog.Rename(selected.iterator().next())
    }

    private fun doRename(path: String, rawName: String) {
        val f = File(path)
        val name = rawName.trim()
        if (!FileOps.isSafeName(name)) { toast("使えない名前です"); return }
        val dst = File(f.parentFile, name)
        if (dst.exists() || !f.renameTo(dst)) toast("変更できませんでした")
        selected.clear(); afterOperationRefresh()
    }

    private fun askDeleteSelected() {
        if (selected.isEmpty()) return
        dialog = UiDialog.DeleteConfirm
    }

    private fun doDelete() {
        val targets = ArrayList(selected)
        selected.clear()
        Thread {
            var fail = 0
            for (pth in targets) if (!FileOps.deleteRecursive(File(pth))) fail++
            val f2 = fail
            runOnUiThread {
                toast(if (f2 == 0) "削除しました" else "一部削除できませんでした")
                afterOperationRefresh()
            }
        }.start()
    }

    private fun showDetailsSingle() {
        if (selected.size != 1) {
            toast("詳細は1件だけ選んでください（長押しで選択）"); return
        }
        val f = File(selected.iterator().next())
        Thread {
            val sizeText: String
            var extra = ""
            if (f.isDirectory) {
                val c = FileOps.countItems(f)
                sizeText = FileOps.formatSize(FileOps.totalSize(f))
                extra = "\n中身: ファイル " + c[0] + " 件 / フォルダ " + c[1] + " 件"
            } else sizeText = FileOps.formatSize(f.length())
            val typeLabel = if (f.isDirectory) "フォルダ" else FileOps.categoryLabel(FileOps.categoryOf(f.name))
            val msg = "名前: ${f.name}\nパス: ${f.absolutePath}\n種類: $typeLabel\nサイズ: $sizeText$extra" +
                "\n更新: ${FileOps.formatDate(f.lastModified())}" +
                "\n読み取り: ${if (f.canRead()) "可" else "不可"} / 書き込み: ${if (f.canWrite()) "可" else "不可"}"
            runOnUiThread { dialog = UiDialog.Details(f, msg) }
        }.start()
    }

    // ----- コピー / 切り取り / 貼り付け -----
    private fun setClipboard(cut: Boolean) {
        if (selected.isEmpty()) return
        clipboard.clear()
        for (p in selected) clipboard.add(File(p))
        clipboardCut = cut; selected.clear()
        toast(clipboard.size.toString() + " 件を" + (if (cut) "切り取り" else "コピー") + "しました。貼り付け先のフォルダを開いてください")
        refresh()
    }

    private fun doPaste() {
        if (clipboard.isEmpty() || mode != MODE_BROWSE) return
        val srcs = ArrayList(clipboard)
        val cut = clipboardCut
        val destDir = currentDir!! // 貼り付け先は開始時点のフォルダに固定する (実行中に移動しても変えない)
        Thread {
            var fail = 0
            for (src in srcs) {
                try {
                    if (cut && src.parentFile != null
                        && src.parentFile!!.canonicalPath == destDir.canonicalPath) {
                        continue // 同じフォルダへの切り取り貼り付けは何もしない
                    }
                    if (cut) {
                        val dst = FileOps.uniqueFile(destDir, src.name)
                        if (!src.renameTo(dst)) { FileOps.copyRecursive(src, destDir); FileOps.deleteRecursive(src) }
                    } else FileOps.copyRecursive(src, destDir)
                } catch (e: Exception) { fail++ }
            }
            val f2 = fail
            runOnUiThread {
                clipboard.clear(); selected.clear()
                toast(if (f2 == 0) "貼り付けました" else "一部貼り付けできませんでした")
                refresh()
            }
        }.start()
    }

    private fun afterOperationRefresh() {
        if (mode == MODE_CATEGORY && currentCategory >= 0) loadCategory(currentCategory)
        else if (mode == MODE_RECENT) loadRecent()
        else refresh()
    }

    private fun addFavorite(dir: File) {
        val fav = favorites().toMutableSet(); fav.add(dir.absolutePath)
        prefs.edit().putStringSet("favorites", fav).apply()
        toast("お気に入りに追加しました（ホームに表示）")
    }

    // ----- 権限 -----
    private fun checkPermission(forceOpen: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                if (!forceOpen && prefs.getBoolean("permDialogShown", false)) return // 起動時の案内は初回だけ
                prefs.edit().putBoolean("permDialogShown", true).apply()
                dialog = UiDialog.Permission
            } else if (forceOpen) toast("すでに許可されています", long = false)
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        } else if (forceOpen) toast("すでに許可されています", long = false)
    }

    // Back の優先順位: 選択解除 → 履歴で親フォルダへ → ホームへ → 終了
    private fun handleBack() {
        if (selected.isNotEmpty()) { selected.clear(); refresh(); return }
        if (mode == MODE_BROWSE) {
            if (dirHistory.isNotEmpty()) openFolderNoHistory(dirHistory.pop())
            else showHome()
            return
        }
        if (mode != MODE_HOME) { showHome(); return }
        finish()
    }

    override fun onResume() { super.onResume(); if (mode == MODE_HOME) uiTick++ }
}
