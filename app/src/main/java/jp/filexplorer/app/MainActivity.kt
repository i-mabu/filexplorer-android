package jp.filexplorer.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

// ===== FileXplorer: Google Files 風のファイル管理アプリ =====
// ホーム = カテゴリ + ストレージ、閲覧 = エクスプローラー風リスト
class MainActivity : Activity() {
    companion object {
        private const val MODE_HOME = 0
        private const val MODE_BROWSE = 1
        private const val MODE_CATEGORY = 2
        private const val MODE_RECENT = 3
        private const val MODE_SEARCH = 4
    }

    private lateinit var titleView: TextView
    private lateinit var pathView: TextView
    private lateinit var storageView: TextView
    private lateinit var emptyView: TextView
    private lateinit var storageBar: ProgressBar
    private lateinit var listView: ListView
    private lateinit var bottomBar: LinearLayout
    private lateinit var pasteBar: LinearLayout
    private lateinit var adapter: FileListAdapter

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
        buildUi()
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

    // ----- UI構築 -----
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(12), dp(10), dp(8), dp(10))
        header.setBackgroundColor(Color.rgb(232, 240, 254))
        titleView = TextView(this)
        titleView.textSize = 20f
        titleView.setTextColor(Color.rgb(32, 33, 36))
        titleView.text = "📂 ファイル"
        titleView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(titleView)
        val searchBtn = Button(this); searchBtn.text = "🔍"
        searchBtn.setOnClickListener { askSearch() }
        header.addView(searchBtn)
        val menuBtn = Button(this); menuBtn.text = "⋮"
        menuBtn.setOnClickListener { showMenu() }
        header.addView(menuBtn)
        root.addView(header)

        storageView = TextView(this)
        storageView.textSize = 13f
        storageView.setPadding(dp(12), dp(6), dp(12), 0)
        root.addView(storageView)
        storageBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        val bp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        bp.setMargins(dp(12), dp(2), dp(12), dp(4))
        storageBar.layoutParams = bp
        root.addView(storageBar)

        pathView = TextView(this)
        pathView.textSize = 13f
        pathView.setTextColor(Color.rgb(95, 99, 104))
        pathView.setPadding(dp(12), dp(6), dp(12), dp(6))
        pathView.setOnClickListener { if (mode == MODE_BROWSE) askJumpPath() }
        root.addView(pathView)

        emptyView = TextView(this)
        emptyView.textSize = 15f
        emptyView.gravity = Gravity.CENTER
        emptyView.setPadding(dp(16), dp(24), dp(16), dp(24))
        emptyView.visibility = View.GONE
        root.addView(emptyView)

        listView = ListView(this)
        listView.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        adapter = FileListAdapter(this)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ -> onItemClick(pos) }
        listView.setOnItemLongClickListener { _, _, pos, _ -> onItemLongClick(pos); true }
        root.addView(listView)

        pasteBar = LinearLayout(this)
        pasteBar.orientation = LinearLayout.HORIZONTAL
        pasteBar.setPadding(dp(8), dp(4), dp(8), dp(4))
        pasteBar.setBackgroundColor(Color.rgb(254, 247, 224))
        val pasteBtn = Button(this); pasteBtn.text = "ここに貼り付け"
        pasteBtn.setOnClickListener { doPaste() }
        pasteBar.addView(pasteBtn)
        val cancelBtn = Button(this); cancelBtn.text = "キャンセル"
        cancelBtn.setOnClickListener { clipboard.clear(); refresh() }
        pasteBar.addView(cancelBtn)
        pasteBar.visibility = View.GONE
        root.addView(pasteBar)

        bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.HORIZONTAL
        bottomBar.setPadding(dp(4), dp(4), dp(4), dp(4))
        bottomBar.setBackgroundColor(Color.rgb(232, 240, 254))
        addBottom("コピー") { setClipboard(false) }
        addBottom("切り取り") { setClipboard(true) }
        addBottom("名前変更") { askRenameSingle() }
        addBottom("詳細") { showDetailsSingle() }
        addBottom("削除") { askDeleteSelected() }
        bottomBar.visibility = View.GONE
        root.addView(bottomBar)

        setContentView(root)
    }

    private fun addBottom(label: String, l: View.OnClickListener) {
        val b = Button(this); b.text = label; b.textSize = 12f
        b.setOnClickListener(l); bottomBar.addView(b)
    }

    // FileListAdapter から呼ばれる行描画
    fun buildRow(pos: Int, convert: View?): View {
        // 行の再利用 (convertView) でスクロールの GC 負荷を下げる
        val row: LinearLayout
        val icon: TextView
        val name: TextView
        val sub: TextView
        val conv = convert as? LinearLayout
        if (conv != null && conv.childCount == 2 && conv.getChildAt(1) is LinearLayout
            && (conv.getChildAt(1) as LinearLayout).childCount == 2) {
            row = conv
            icon = row.getChildAt(0) as TextView
            val texts0 = row.getChildAt(1) as LinearLayout
            name = texts0.getChildAt(0) as TextView
            sub = texts0.getChildAt(1) as TextView
            row.setBackgroundColor(Color.WHITE)
        } else {
            row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), dp(10), dp(12), dp(10))
            icon = TextView(this); icon.textSize = 26f
            val texts = LinearLayout(this)
            texts.orientation = LinearLayout.VERTICAL
            name = TextView(this); name.textSize = 16f; name.setTextColor(Color.rgb(32, 33, 36))
            sub = TextView(this); sub.textSize = 12f; sub.setTextColor(Color.rgb(95, 99, 104))
            texts.addView(name); texts.addView(sub)
            row.addView(icon); row.addView(texts)
        }
        if (mode == MODE_HOME) {
            val act = homeActions[pos]
            icon.text = homeIcon(act)
            name.text = homeLabel(act)
            sub.text = homeSub(act)
        } else {
            val f = currentFiles[pos]
            val sel = selected.contains(f.absolutePath)
            icon.text = if (sel) "✅" else FileOps.iconFor(f)
            name.text = f.name
            if (f.isDirectory) {
                val k = f.listFiles()
                sub.text = "${k?.size ?: 0} 項目 • ${FileOps.formatDate(f.lastModified())}"
            } else {
                sub.text = "${FileOps.formatSize(f.length())} • ${FileOps.formatDate(f.lastModified())}"
            }
            if (sel) row.setBackgroundColor(Color.rgb(232, 240, 254))
        }
        return row
    }

    // ----- ホーム -----
    private fun showHome() {
        mode = MODE_HOME; currentTitle = "ファイル"; selected.clear(); dirHistory.clear()
        homeActions.clear()
        homeActions.add("cat:0"); homeActions.add("cat:1"); homeActions.add("cat:2")
        homeActions.add("cat:3"); homeActions.add("cat:4"); homeActions.add("cat:5")
        homeActions.add("recent"); homeActions.add("download")
        homeActions.add("storage"); homeActions.add("appfolder")
        for (fav in favorites()) homeActions.add("fav:$fav")
        updateStorageBar()
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

    private fun updateStorageBar() {
        val r = internalRoot()
        val total = r.totalSpace
        val free = r.freeSpace
        if (total > 0) {
            val used = total - free
            storageView.text = "💾 内部ストレージ: ${FileOps.formatSize(used)} / ${FileOps.formatSize(total)} 使用中（空き ${FileOps.formatSize(free)}）"
            storageBar.max = 100
            storageBar.progress = (used * 100 / total).toInt()
            storageView.visibility = View.VISIBLE; storageBar.visibility = View.VISIBLE
        }
    }

    // ----- 表示更新 -----
    private fun refresh() {
        titleView.text = "📂 $currentTitle" + if (selected.isEmpty()) "" else "（${selected.size}件選択）"
        if (mode == MODE_HOME) {
            pathView.text = "カテゴリ・保存場所から探せます。長押しは使いません（タップで開く）"
            adapter.clear(); for (i in homeActions.indices) adapter.add("")
            emptyView.visibility = if (homeActions.isEmpty()) View.VISIBLE else View.GONE
        } else if (mode == MODE_BROWSE) {
            pathView.text = currentDir!!.absolutePath + "  （タップで移動）"
            val files = FileOps.listDir(currentDir, showHidden)
            FileOps.sortFiles(files, sortMode, sortAsc)
            currentFiles = files
            adapter.clear(); for (i in files.indices) adapter.add("")
            emptyView.text = "このフォルダは空です"
            emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        } else {
            pathView.text = currentTitle + "（内部ストレージ内を集めています）"
            adapter.clear(); for (i in currentFiles.indices) adapter.add("")
            emptyView.text = "見つかりませんでした"
            emptyView.visibility = if (currentFiles.isEmpty()) View.VISIBLE else View.GONE
        }
        adapter.notifyDataSetChanged()
        bottomBar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        pasteBar.visibility = if (clipboard.isNotEmpty() && mode == MODE_BROWSE) View.VISIBLE else View.GONE
        if (mode != MODE_HOME) { storageView.visibility = View.GONE; storageBar.visibility = View.GONE }
        else updateStorageBar()
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
        Toast.makeText(this, "検索中…", Toast.LENGTH_SHORT).show()
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
                val path = act.substring(4)
                AlertDialog.Builder(this).setTitle("お気に入りから外しますか？")
                    .setMessage(path)
                    .setPositiveButton("外す") { _, _ ->
                        val fav = favorites().toMutableSet(); fav.remove(path)
                        prefs.edit().putStringSet("favorites", fav).apply()
                        showHome()
                    }.setNegativeButton("キャンセル", null).show()
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
            Toast.makeText(this, "開けるアプリが見つかりません", Toast.LENGTH_LONG).show()
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
        } catch (e: Exception) { Toast.makeText(this, "共有できませんでした", Toast.LENGTH_LONG).show() }
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
        val iv = ImageView(this); iv.setImageBitmap(bmp); iv.adjustViewBounds = true
        AlertDialog.Builder(this).setTitle(f.name).setView(iv)
            .setPositiveButton("外部アプリで開く") { _, _ -> openExternal(f) }
            .setNegativeButton("閉じる", null).show()
    }

    private fun showTextPreview(f: File) {
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
            runOnUiThread {
                val tv = TextView(this); tv.text = shown; tv.textSize = 13f; tv.setPadding(dp(12), dp(12), dp(12), dp(12))
                val sv = ScrollView(this); sv.addView(tv)
                AlertDialog.Builder(this).setTitle(f.name).setView(sv)
                    .setPositiveButton("外部アプリで開く") { _, _ -> openExternal(f) }
                    .setNegativeButton("閉じる", null).show()
            }
        }.start()
    }

    // ----- メニュー -----
    private fun showMenu() {
        val items = mutableListOf<String>()
        items.add("🏠 ホームへ")
        items.add("🔍 検索")
        if (mode == MODE_BROWSE) { items.add("📁 新規フォルダ"); items.add("📝 新規ファイル"); items.add("⭐ このフォルダをお気に入りに") }
        items.add("↕️ 並び替え: " + sortLabel())
        items.add((if (showHidden) "✅" else "⬜") + " 隠しファイルを表示")
        items.add("🔐 すべてのファイルへのアクセス設定")
        items.add("🔄 更新")
        AlertDialog.Builder(this).setTitle("メニュー").setItems(items.toTypedArray()) { _, w ->
            val s = items[w]
            when {
                s.startsWith("🏠") -> showHome()
                s.startsWith("🔍") -> askSearch()
                s.startsWith("📁") -> askCreate(true)
                s.startsWith("📝") -> askCreate(false)
                s.startsWith("⭐") -> addFavorite(currentDir!!)
                s.startsWith("↕️") -> askSort()
                s.contains("隠しファイル") -> { showHidden = !showHidden; prefs.edit().putBoolean("showHidden", showHidden).apply(); refresh() }
                s.startsWith("🔐") -> checkPermission(true)
                else -> refresh()
            }
        }.show()
    }

    private fun sortLabel(): String {
        val n = arrayOf("名前", "サイズ", "日付", "種類")
        return n[sortMode] + if (sortAsc) " ↑" else " ↓"
    }

    private fun askSort() {
        val labels = arrayOf("名前 ↑", "名前 ↓", "日付 新しい順", "日付 古い順", "サイズ 大きい順", "サイズ 小さい順", "種類順")
        AlertDialog.Builder(this).setTitle("並び替え").setItems(labels) { _, w ->
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
        }.show()
    }

    private fun askJumpPath() {
        val et = EditText(this); et.setText(currentDir!!.absolutePath); et.isSingleLine = true
        AlertDialog.Builder(this).setTitle("パスを入力して移動").setView(et)
            .setPositiveButton("移動") { _, _ ->
                val f = File(et.text.toString().trim())
                if (f.isDirectory) openFolder(f) else Toast.makeText(this, "フォルダが見つかりません", Toast.LENGTH_LONG).show()
            }.setNegativeButton("キャンセル", null).show()
    }

    private fun askSearch() {
        val et = EditText(this); et.hint = "ファイル名の一部"
        val base = if (mode == MODE_BROWSE) currentDir!! else internalRoot()
        AlertDialog.Builder(this).setTitle("検索（" + base.name + " 以下）").setView(et)
            .setPositiveButton("検索") { _, _ ->
                val q = et.text.toString().trim(); if (q.isEmpty()) return@setPositiveButton
                mode = MODE_SEARCH; currentSearchQuery = q; currentTitle = "検索: $q"; selected.clear(); currentFiles = mutableListOf(); refresh()
                Thread {
                    val found = FileOps.searchRecursive(base, q, 500, 8)
                    FileOps.sortFiles(found, FileOps.SORT_NAME, true)
                    runOnUiThread { if (mode == MODE_SEARCH && q == currentSearchQuery) { currentFiles = found; refresh() } }
                }.start()
            }.setNegativeButton("キャンセル", null).show()
    }

    // ----- 作成 / 名前変更 / 削除 / 詳細 -----
    private fun askCreate(folder: Boolean) {
        val et = EditText(this); et.hint = if (folder) "フォルダ名" else "ファイル名 (例: memo.txt)"
        AlertDialog.Builder(this).setTitle(if (folder) "新規フォルダ" else "新規ファイル").setView(et)
            .setPositiveButton("作成") { _, _ ->
                val name = et.text.toString().trim()
                if (!FileOps.isSafeName(name)) { Toast.makeText(this, "使えない名前です", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                val f = File(currentDir, name)
                try {
                    val ok = if (folder) f.mkdirs() else f.createNewFile()
                    if (!ok) Toast.makeText(this, "作成できませんでした（同名あり等）", Toast.LENGTH_LONG).show()
                } catch (e: Exception) { Toast.makeText(this, "作成できませんでした: " + e.message, Toast.LENGTH_LONG).show() }
                refresh()
            }.setNegativeButton("キャンセル", null).show()
    }

    private fun askRenameSingle() {
        if (selected.size != 1) { Toast.makeText(this, "名前変更は1件だけ選んでください", Toast.LENGTH_LONG).show(); return }
        val f = File(selected.iterator().next())
        val et = EditText(this); et.setText(f.name)
        AlertDialog.Builder(this).setTitle("名前変更").setView(et)
            .setPositiveButton("変更") { _, _ ->
                val name = et.text.toString().trim()
                if (!FileOps.isSafeName(name)) { Toast.makeText(this, "使えない名前です", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                val dst = File(f.parentFile, name)
                if (dst.exists() || !f.renameTo(dst)) Toast.makeText(this, "変更できませんでした", Toast.LENGTH_LONG).show()
                selected.clear(); afterOperationRefresh()
            }.setNegativeButton("キャンセル", null).show()
    }

    private fun askDeleteSelected() {
        if (selected.isEmpty()) return
        AlertDialog.Builder(this).setTitle("削除しますか？")
            .setMessage(selected.size.toString() + " 件を削除します。元に戻せません。")
            .setPositiveButton("削除") { _, _ ->
                val targets = ArrayList(selected)
                selected.clear()
                Thread {
                    var fail = 0
                    for (pth in targets) if (!FileOps.deleteRecursive(File(pth))) fail++
                    val f2 = fail
                    runOnUiThread {
                        Toast.makeText(this, if (f2 == 0) "削除しました" else "一部削除できませんでした", Toast.LENGTH_LONG).show()
                        afterOperationRefresh()
                    }
                }.start()
            }.setNegativeButton("キャンセル", null).show()
    }

    private fun showDetailsSingle() {
        if (selected.size != 1) {
            Toast.makeText(this, "詳細は1件だけ選んでください（長押しで選択）", Toast.LENGTH_LONG).show(); return
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
            runOnUiThread {
                val b = AlertDialog.Builder(this).setTitle("詳細").setMessage(msg)
                    .setNegativeButton("閉じる", null)
                if (f.isFile) b.setPositiveButton("共有") { _, _ -> shareFile(f) }
                b.show()
            }
        }.start()
    }

    // ----- コピー / 切り取り / 貼り付け -----
    private fun setClipboard(cut: Boolean) {
        if (selected.isEmpty()) return
        clipboard.clear()
        for (p in selected) clipboard.add(File(p))
        clipboardCut = cut; selected.clear()
        Toast.makeText(this, clipboard.size.toString() + " 件を" + (if (cut) "切り取り" else "コピー") + "しました。貼り付け先のフォルダを開いてください", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this, if (f2 == 0) "貼り付けました" else "一部貼り付けできませんでした", Toast.LENGTH_LONG).show()
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
        Toast.makeText(this, "お気に入りに追加しました（ホームに表示）", Toast.LENGTH_LONG).show()
    }

    // ----- 権限 -----
    private fun checkPermission(forceOpen: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                if (!forceOpen && prefs.getBoolean("permDialogShown", false)) return // 起動時の案内は初回だけ
                prefs.edit().putBoolean("permDialogShown", true).apply()
                AlertDialog.Builder(this).setTitle("ファイルへのアクセス")
                    .setMessage("内部ストレージ全体を見るには「すべてのファイルへのアクセス」の許可が必要です。設定画面で許可してください。許可しなくてもアプリ専用フォルダは使えます。")
                    .setPositiveButton("設定を開く") { _, _ ->
                        try {
                            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            i.data = Uri.parse("package:$packageName"); startActivity(i)
                        } catch (e: Exception) {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }.setNegativeButton("あとで", null).show()
            } else if (forceOpen) Toast.makeText(this, "すでに許可されています", Toast.LENGTH_SHORT).show()
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        } else if (forceOpen) Toast.makeText(this, "すでに許可されています", Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (selected.isNotEmpty()) { selected.clear(); refresh(); return }
        if (mode == MODE_BROWSE) {
            if (dirHistory.isNotEmpty()) openFolderNoHistory(dirHistory.pop())
            else showHome()
            return
        }
        if (mode != MODE_HOME) { showHome(); return }
        super.onBackPressed()
    }

    override fun onResume() { super.onResume(); if (mode == MODE_HOME) updateStorageBar() }
}
