package jp.filexplorer.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// ===== FileXplorer: Google Files 風のファイル管理アプリ =====
// ホーム = カテゴリ + ストレージ、閲覧 = エクスプローラー風リスト
public class MainActivity extends Activity {
    private static final int MODE_HOME = 0, MODE_BROWSE = 1, MODE_CATEGORY = 2, MODE_RECENT = 3, MODE_SEARCH = 4;

    private TextView titleView, pathView, storageView, emptyView;
    private ProgressBar storageBar;
    private ListView listView;
    private LinearLayout bottomBar, pasteBar;
    private FileListAdapter adapter;

    private int mode = MODE_HOME;
    private File currentDir;
    private int currentCategory = -1;
    private String currentTitle = "ファイル";
    private List<File> currentFiles = new ArrayList<>();
    private List<String> homeActions = new ArrayList<>(); // ホーム行のアクションID

    private final Set<String> selected = new LinkedHashSet<>();
    private final java.util.ArrayDeque<File> dirHistory = new java.util.ArrayDeque<>();
    private String currentSearchQuery = "";
    private List<File> clipboard = new ArrayList<>();
    private boolean clipboardCut = false;

    private SharedPreferences prefs;
    private int sortMode = FileOps.SORT_NAME;
    private boolean sortAsc = true;
    private boolean showHidden = false;

    private File internalRoot() {
        File f = Environment.getExternalStorageDirectory();
        return f != null ? f : getFilesDir();
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("filexplorer", MODE_PRIVATE);
        sortMode = prefs.getInt("sortMode", FileOps.SORT_NAME);
        sortAsc = prefs.getBoolean("sortAsc", true);
        showHidden = prefs.getBoolean("showHidden", false);
        currentDir = internalRoot();
        buildUi();
        if (savedInstanceState != null && restoreState(savedInstanceState)) {
            // 復元できた場合はホームに戻さない (回転などで閲覧位置とクリップボードを保持)
        } else {
            showHome();
        }
        checkPermission(false);
    }

    // ----- 状態の保存 / 復元 (画面回転や再作成で閲覧位置・選択・クリップボードを失わない) -----
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("mode", mode);
        out.putString("dir", currentDir == null ? "" : currentDir.getAbsolutePath());
        out.putInt("category", currentCategory);
        out.putString("title", currentTitle);
        out.putString("query", currentSearchQuery);
        out.putStringArrayList("selected", new ArrayList<>(selected));
        ArrayList<String> clip = new ArrayList<>();
        for (File f : clipboard) clip.add(f.getAbsolutePath());
        out.putStringArrayList("clipboard", clip);
        out.putBoolean("clipCut", clipboardCut);
        ArrayList<String> hist = new ArrayList<>();
        for (File f : dirHistory) hist.add(f.getAbsolutePath());
        out.putStringArrayList("history", hist);
    }
    private boolean restoreState(Bundle b) {
        try {
            int m = b.getInt("mode", MODE_HOME);
            ArrayList<String> sel = b.getStringArrayList("selected");
            if (sel != null) selected.addAll(sel);
            ArrayList<String> clip = b.getStringArrayList("clipboard");
            if (clip != null) for (String s : clip) clipboard.add(new File(s));
            clipboardCut = b.getBoolean("clipCut", false);
            ArrayList<String> hist = b.getStringArrayList("history");
            if (hist != null) for (String s : hist) dirHistory.add(new File(s));
            currentCategory = b.getInt("category", -1);
            currentSearchQuery = b.getString("query", "");
            String dir = b.getString("dir", "");
            if (m == MODE_BROWSE && dir != null && new File(dir).isDirectory()) {
                mode = MODE_BROWSE; currentDir = new File(dir);
                currentTitle = currentDir.getName().isEmpty() ? currentDir.getAbsolutePath() : currentDir.getName();
                refresh(); return true;
            }
            if (m == MODE_CATEGORY && currentCategory >= 0) { loadCategory(currentCategory); return true; }
            if (m == MODE_RECENT) { loadRecent(); return true; }
            return false;
        } catch (Exception e) { return false; }
    }

    // ----- UI構築 -----
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(10), dp(8), dp(10));
        header.setBackgroundColor(Color.rgb(232, 240, 254));
        titleView = new TextView(this);
        titleView.setTextSize(20);
        titleView.setTextColor(Color.rgb(32, 33, 36));
        titleView.setText("📂 ファイル");
        titleView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(titleView);
        Button searchBtn = new Button(this); searchBtn.setText("🔍");
        searchBtn.setOnClickListener(v -> askSearch());
        header.addView(searchBtn);
        Button menuBtn = new Button(this); menuBtn.setText("⋮");
        menuBtn.setOnClickListener(v -> showMenu());
        header.addView(menuBtn);
        root.addView(header);

        storageView = new TextView(this);
        storageView.setTextSize(13);
        storageView.setPadding(dp(12), dp(6), dp(12), 0);
        root.addView(storageView);
        storageBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        bp.setMargins(dp(12), dp(2), dp(12), dp(4));
        storageBar.setLayoutParams(bp);
        root.addView(storageBar);

        pathView = new TextView(this);
        pathView.setTextSize(13);
        pathView.setTextColor(Color.rgb(95, 99, 104));
        pathView.setPadding(dp(12), dp(6), dp(12), dp(6));
        pathView.setOnClickListener(v -> { if (mode == MODE_BROWSE) askJumpPath(); });
        root.addView(pathView);

        emptyView = new TextView(this);
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(16), dp(24), dp(16), dp(24));
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView);

        listView = new ListView(this);
        listView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        adapter = new FileListAdapter(this);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> onItemClick(pos));
        listView.setOnItemLongClickListener((p, v, pos, id) -> { onItemLongClick(pos); return true; });
        root.addView(listView);

        pasteBar = new LinearLayout(this);
        pasteBar.setOrientation(LinearLayout.HORIZONTAL);
        pasteBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        pasteBar.setBackgroundColor(Color.rgb(254, 247, 224));
        Button pasteBtn = new Button(this); pasteBtn.setText("ここに貼り付け");
        pasteBtn.setOnClickListener(v -> doPaste());
        pasteBar.addView(pasteBtn);
        Button cancelBtn = new Button(this); cancelBtn.setText("キャンセル");
        cancelBtn.setOnClickListener(v -> { clipboard.clear(); refresh(); });
        pasteBar.addView(cancelBtn);
        pasteBar.setVisibility(View.GONE);
        root.addView(pasteBar);

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bottomBar.setBackgroundColor(Color.rgb(232, 240, 254));
        addBottom("コピー", v -> setClipboard(false));
        addBottom("切り取り", v -> setClipboard(true));
        addBottom("名前変更", v -> askRenameSingle());
        addBottom("詳細", v -> showDetailsSingle());
        addBottom("削除", v -> askDeleteSelected());
        bottomBar.setVisibility(View.GONE);
        root.addView(bottomBar);

        setContentView(root);
    }
    private void addBottom(String label, View.OnClickListener l) {
        Button b = new Button(this); b.setText(label); b.setTextSize(12);
        b.setOnClickListener(l); bottomBar.addView(b);
    }

    // FileListAdapter から呼ばれる行描画 (インナークラスを使うとこの環境の d8 が落ちるため分離)
    View buildRow(int pos, View convert) {
            // 行の再利用 (convertView) でスクロールの GC 負荷を下げる
            LinearLayout row; TextView icon; TextView name; TextView sub;
            if (convert instanceof LinearLayout && ((LinearLayout) convert).getChildCount() == 2
                    && ((LinearLayout) convert).getChildAt(1) instanceof LinearLayout
                    && ((LinearLayout) ((LinearLayout) convert).getChildAt(1)).getChildCount() == 2) {
                row = (LinearLayout) convert;
                icon = (TextView) row.getChildAt(0);
                LinearLayout texts0 = (LinearLayout) row.getChildAt(1);
                name = (TextView) texts0.getChildAt(0);
                sub = (TextView) texts0.getChildAt(1);
                row.setBackgroundColor(Color.WHITE);
            } else {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                icon = new TextView(this); icon.setTextSize(26);
                LinearLayout texts = new LinearLayout(this);
                texts.setOrientation(LinearLayout.VERTICAL);
                name = new TextView(this); name.setTextSize(16); name.setTextColor(Color.rgb(32,33,36));
                sub = new TextView(this); sub.setTextSize(12); sub.setTextColor(Color.rgb(95,99,104));
                texts.addView(name); texts.addView(sub);
                row.addView(icon); row.addView(texts);
            }
            if (mode == MODE_HOME) {
                String act = homeActions.get(pos);
                icon.setText(homeIcon(act));
                name.setText(homeLabel(act));
                sub.setText(homeSub(act));
            } else {
                File f = currentFiles.get(pos);
                boolean sel = selected.contains(f.getAbsolutePath());
                icon.setText(sel ? "✅" : FileOps.iconFor(f));
                name.setText(f.getName());
                if (f.isDirectory()) {
                    File[] k = f.listFiles();
                    sub.setText((k == null ? 0 : k.length) + " 項目 • " + FileOps.formatDate(f.lastModified()));
                } else {
                    sub.setText(FileOps.formatSize(f.length()) + " • " + FileOps.formatDate(f.lastModified()));
                }
                if (sel) row.setBackgroundColor(Color.rgb(232, 240, 254));
            }
            return row;
    }

    // ----- ホーム -----
    private void showHome() {
        mode = MODE_HOME; currentTitle = "ファイル"; selected.clear(); dirHistory.clear();
        homeActions.clear();
        homeActions.add("cat:0"); homeActions.add("cat:1"); homeActions.add("cat:2");
        homeActions.add("cat:3"); homeActions.add("cat:4"); homeActions.add("cat:5");
        homeActions.add("recent"); homeActions.add("download");
        homeActions.add("storage"); homeActions.add("appfolder");
        for (String fav : favorites()) homeActions.add("fav:" + fav);
        updateStorageBar();
        refresh();
    }
    private String homeIcon(String act) {
        if (act.startsWith("cat:")) {
            switch (Integer.parseInt(act.substring(4))) {
                case FileOps.CAT_IMAGE: return "🖼️"; case FileOps.CAT_VIDEO: return "🎬"; case FileOps.CAT_AUDIO: return "🎵";
                case FileOps.CAT_DOCUMENT: return "📕"; case FileOps.CAT_ARCHIVE: return "🗜️"; case FileOps.CAT_APK: return "📦";
                default: return "📄";
            }
        }
        if (act.equals("recent")) return "🕘";
        if (act.equals("download")) return "⬇️";
        if (act.equals("storage")) return "💾";
        if (act.equals("appfolder")) return "📱";
        if (act.startsWith("fav:")) return "⭐";
        return "📁";
    }
    private String homeLabel(String act) {
        if (act.startsWith("cat:")) return FileOps.categoryLabel(Integer.parseInt(act.substring(4)));
        if (act.equals("recent")) return "最近使ったファイル";
        if (act.equals("download")) return "ダウンロード";
        if (act.equals("storage")) return "内部ストレージ";
        if (act.equals("appfolder")) return "アプリ専用フォルダ";
        if (act.startsWith("fav:")) return new File(act.substring(4)).getName();
        return act;
    }
    private String homeSub(String act) {
        if (act.startsWith("cat:")) return "内部ストレージ内を検索";
        if (act.equals("recent")) return "最近更新された順";
        if (act.equals("download")) return "Download フォルダ";
        if (act.equals("storage")) return internalRoot().getAbsolutePath();
        if (act.equals("appfolder")) return getFilesDir().getAbsolutePath();
        if (act.startsWith("fav:")) return act.substring(4);
        return "";
    }
    private Set<String> favorites() {
        return new LinkedHashSet<>(prefs.getStringSet("favorites", new HashSet<>()));
    }

    private void updateStorageBar() {
        File r = internalRoot();
        long total = r.getTotalSpace(), free = r.getFreeSpace();
        if (total > 0) {
            long used = total - free;
            storageView.setText("💾 内部ストレージ: " + FileOps.formatSize(used) + " / " + FileOps.formatSize(total)
                    + " 使用中（空き " + FileOps.formatSize(free) + "）");
            storageBar.setMax(100);
            storageBar.setProgress((int) (used * 100 / total));
            storageView.setVisibility(View.VISIBLE); storageBar.setVisibility(View.VISIBLE);
        }
    }

    // ----- 表示更新 -----
    private void refresh() {
        titleView.setText("📂 " + currentTitle + (selected.isEmpty() ? "" : "（" + selected.size() + "件選択）"));
        if (mode == MODE_HOME) {
            pathView.setText("カテゴリ・保存場所から探せます。長押しは使いません（タップで開く）");
            adapter.clear(); for (int i = 0; i < homeActions.size(); i++) adapter.add("");
            emptyView.setVisibility(homeActions.isEmpty() ? View.VISIBLE : View.GONE);
        } else if (mode == MODE_BROWSE) {
            pathView.setText(currentDir.getAbsolutePath() + "  （タップで移動）");
            List<File> files = FileOps.listDir(currentDir, showHidden);
            FileOps.sortFiles(files, sortMode, sortAsc);
            currentFiles = files;
            adapter.clear(); for (int i = 0; i < files.size(); i++) adapter.add("");
            emptyView.setText("このフォルダは空です");
            emptyView.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        } else {
            pathView.setText(currentTitle + "（内部ストレージ内を集めています）");
            adapter.clear(); for (int i = 0; i < currentFiles.size(); i++) adapter.add("");
            emptyView.setText("見つかりませんでした");
            emptyView.setVisibility(currentFiles.isEmpty() ? View.VISIBLE : View.GONE);
        }
        adapter.notifyDataSetChanged();
        bottomBar.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        pasteBar.setVisibility(!clipboard.isEmpty() && mode == MODE_BROWSE ? View.VISIBLE : View.GONE);
        if (mode != MODE_HOME) { storageView.setVisibility(View.GONE); storageBar.setVisibility(View.GONE); }
        else updateStorageBar();
    }

    private void openFolder(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        if (mode == MODE_BROWSE && currentDir != null && !dir.equals(currentDir)) dirHistory.push(currentDir);
        mode = MODE_BROWSE; currentDir = dir; currentTitle = dir.getName().isEmpty() ? dir.getAbsolutePath() : dir.getName();
        selected.clear(); refresh();
    }
    private void openFolderNoHistory(File dir) {
        mode = MODE_BROWSE; currentDir = dir; currentTitle = dir.getName().isEmpty() ? dir.getAbsolutePath() : dir.getName();
        selected.clear(); refresh();
    }

    private void loadCategory(int cat) {
        mode = MODE_CATEGORY; currentCategory = cat; currentTitle = FileOps.categoryLabel(cat); selected.clear();
        currentFiles = new ArrayList<>(); refresh();
        Toast.makeText(this, "検索中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<File> found = FileOps.collectByCategory(internalRoot(), cat, 500, 8);
            runOnUiThread(() -> { if (mode == MODE_CATEGORY && currentCategory == cat) { currentFiles = found; refresh(); } });
        }).start();
    }
    private void loadRecent() {
        mode = MODE_RECENT; currentTitle = "最近使ったファイル"; selected.clear(); currentFiles = new ArrayList<>(); refresh();
        new Thread(() -> {
            List<File> found = FileOps.collectRecent(internalRoot(), 100, 8);
            runOnUiThread(() -> { if (mode == MODE_RECENT) { currentFiles = found; refresh(); } });
        }).start();
    }

    // ----- クリック -----
    private void onItemClick(int pos) {
        if (mode == MODE_HOME) {
            String act = homeActions.get(pos);
            if (act.startsWith("cat:")) loadCategory(Integer.parseInt(act.substring(4)));
            else if (act.equals("recent")) loadRecent();
            else if (act.equals("download")) openFolder(new File(internalRoot(), "Download"));
            else if (act.equals("storage")) openFolder(internalRoot());
            else if (act.equals("appfolder")) openFolder(getFilesDir());
            else if (act.startsWith("fav:")) openFolder(new File(act.substring(4)));
            return;
        }
        File f = currentFiles.get(pos);
        if (!selected.isEmpty()) { toggleSelect(f); refresh(); return; }
        if (f.isDirectory()) openFolder(f); else openFile(f);
    }
    private void onItemLongClick(int pos) {
        if (mode == MODE_HOME) {
            String act = homeActions.get(pos);
            if (act.startsWith("fav:")) {
                String path = act.substring(4);
                new AlertDialog.Builder(this).setTitle("お気に入りから外しますか？")
                        .setMessage(path)
                        .setPositiveButton("外す", (d, w) -> {
                            Set<String> fav = favorites(); fav.remove(path);
                            prefs.edit().putStringSet("favorites", fav).apply();
                            showHome();
                        }).setNegativeButton("キャンセル", null).show();
            }
            return;
        }
        toggleSelect(currentFiles.get(pos)); refresh();
    }
    private void toggleSelect(File f) {
        String p = f.getAbsolutePath();
        if (selected.contains(p)) selected.remove(p); else selected.add(p);
    }

    // ----- ファイルを開く / プレビュー -----
    private void openFile(File f) {
        String name = f.getName();
        if (FileOps.isImage(name)) { showImagePreview(f); return; }
        if (FileOps.isTextLike(name)) { showTextPreview(f); return; }
        openExternal(f);
    }
    private void openExternal(File f) {
        try {
            Uri uri = SimpleFileProvider.uriFor(f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, FileOps.mimeType(f.getName()));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "開けるアプリが見つかりません", Toast.LENGTH_LONG).show();
        }
    }
    private void shareFile(File f) {
        try {
            Uri uri = SimpleFileProvider.uriFor(f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(FileOps.mimeType(f.getName()));
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "共有"));
        } catch (Exception e) { Toast.makeText(this, "共有できませんでした", Toast.LENGTH_LONG).show(); }
    }
    private void showImagePreview(File f) {
        // まず寸法だけ読み、表示に十分な大きさまで縮小してからデコードする (巨大写真での OOM 防止)
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        int sample = 1;
        int imgW = bounds.outWidth, imgH = bounds.outHeight;
        while (imgW / sample > 1024 || imgH / sample > 1024) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (bmp == null) { openExternal(f); return; }
        ImageView iv = new ImageView(this); iv.setImageBitmap(bmp); iv.setAdjustViewBounds(true);
        new AlertDialog.Builder(this).setTitle(f.getName()).setView(iv)
                .setPositiveButton("外部アプリで開く", (d, w) -> openExternal(f))
                .setNegativeButton("閉じる", null).show();
    }
    private void showTextPreview(File f) {
        if (f.length() > 200 * 1024) { openExternal(f); return; }
        new Thread(() -> {
            String text;
            try {
                byte[] buf = new byte[(int) f.length()];
                int off = 0;
                try (FileInputStream in = new FileInputStream(f)) {
                    int n; while ((n = in.read(buf, off, buf.length - off)) > 0) off += n;
                }
                text = new String(buf, 0, off, StandardCharsets.UTF_8);
            } catch (Exception e) { text = "（読み込めませんでした）"; }
            String shown = text;
            runOnUiThread(() -> {
                TextView tv = new TextView(this); tv.setText(shown); tv.setTextSize(13); tv.setPadding(dp(12), dp(12), dp(12), dp(12));
                ScrollView sv = new ScrollView(this); sv.addView(tv);
                new AlertDialog.Builder(this).setTitle(f.getName()).setView(sv)
                        .setPositiveButton("外部アプリで開く", (d, w) -> openExternal(f))
                        .setNegativeButton("閉じる", null).show();
            });
        }).start();
    }

    // ----- メニュー -----
    private void showMenu() {
        List<String> items = new ArrayList<>();
        items.add("🏠 ホームへ");
        items.add("🔍 検索");
        if (mode == MODE_BROWSE) { items.add("📁 新規フォルダ"); items.add("📝 新規ファイル"); items.add("⭐ このフォルダをお気に入りに"); }
        items.add("↕️ 並び替え: " + sortLabel());
        items.add((showHidden ? "✅" : "⬜") + " 隠しファイルを表示");
        items.add("🔐 すべてのファイルへのアクセス設定");
        items.add("🔄 更新");
        new AlertDialog.Builder(this).setTitle("メニュー").setItems(items.toArray(new String[0]), (d, w) -> {
            String s = items.get(w);
            if (s.startsWith("🏠")) showHome();
            else if (s.startsWith("🔍")) askSearch();
            else if (s.startsWith("📁")) askCreate(true);
            else if (s.startsWith("📝")) askCreate(false);
            else if (s.startsWith("⭐")) addFavorite(currentDir);
            else if (s.startsWith("↕️")) askSort();
            else if (s.contains("隠しファイル")) { showHidden = !showHidden; prefs.edit().putBoolean("showHidden", showHidden).apply(); refresh(); }
            else if (s.startsWith("🔐")) checkPermission(true);
            else refresh();
        }).show();
    }
    private String sortLabel() {
        String[] n = {"名前", "サイズ", "日付", "種類"};
        return n[sortMode] + (sortAsc ? " ↑" : " ↓");
    }
    private void askSort() {
        String[] labels = {"名前 ↑", "名前 ↓", "日付 新しい順", "日付 古い順", "サイズ 大きい順", "サイズ 小さい順", "種類順"};
        new AlertDialog.Builder(this).setTitle("並び替え").setItems(labels, (d, w) -> {
            switch (w) {
                case 0: sortMode = FileOps.SORT_NAME; sortAsc = true; break;
                case 1: sortMode = FileOps.SORT_NAME; sortAsc = false; break;
                case 2: sortMode = FileOps.SORT_DATE; sortAsc = false; break;
                case 3: sortMode = FileOps.SORT_DATE; sortAsc = true; break;
                case 4: sortMode = FileOps.SORT_SIZE; sortAsc = false; break;
                case 5: sortMode = FileOps.SORT_SIZE; sortAsc = true; break;
                default: sortMode = FileOps.SORT_TYPE; sortAsc = true; break;
            }
            prefs.edit().putInt("sortMode", sortMode).putBoolean("sortAsc", sortAsc).apply();
            refresh();
        }).show();
    }
    private void askJumpPath() {
        EditText et = new EditText(this); et.setText(currentDir.getAbsolutePath()); et.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("パスを入力して移動").setView(et)
                .setPositiveButton("移動", (d, w) -> {
                    File f = new File(et.getText().toString().trim());
                    if (f.isDirectory()) openFolder(f); else Toast.makeText(this, "フォルダが見つかりません", Toast.LENGTH_LONG).show();
                }).setNegativeButton("キャンセル", null).show();
    }
    private void askSearch() {
        EditText et = new EditText(this); et.setHint("ファイル名の一部");
        File base = mode == MODE_BROWSE ? currentDir : internalRoot();
        new AlertDialog.Builder(this).setTitle("検索（" + base.getName() + " 以下）").setView(et)
                .setPositiveButton("検索", (d, w) -> {
                    String q = et.getText().toString().trim(); if (q.isEmpty()) return;
                    mode = MODE_SEARCH; currentSearchQuery = q; currentTitle = "検索: " + q; selected.clear(); currentFiles = new ArrayList<>(); refresh();
                    new Thread(() -> {
                        List<File> found = FileOps.searchRecursive(base, q, 500, 8);
                        FileOps.sortFiles(found, FileOps.SORT_NAME, true);
                        runOnUiThread(() -> { if (mode == MODE_SEARCH && q.equals(currentSearchQuery)) { currentFiles = found; refresh(); } });
                    }).start();
                }).setNegativeButton("キャンセル", null).show();
    }

    // ----- 作成 / 名前変更 / 削除 / 詳細 -----
    private void askCreate(boolean folder) {
        EditText et = new EditText(this); et.setHint(folder ? "フォルダ名" : "ファイル名 (例: memo.txt)");
        new AlertDialog.Builder(this).setTitle(folder ? "新規フォルダ" : "新規ファイル").setView(et)
                .setPositiveButton("作成", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (!FileOps.isSafeName(name)) { Toast.makeText(this, "使えない名前です", Toast.LENGTH_LONG).show(); return; }
                    File f = new File(currentDir, name);
                    try {
                        boolean ok = folder ? f.mkdirs() : (f.createNewFile());
                        if (!ok) Toast.makeText(this, "作成できませんでした（同名あり等）", Toast.LENGTH_LONG).show();
                    } catch (Exception e) { Toast.makeText(this, "作成できませんでした: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                    refresh();
                }).setNegativeButton("キャンセル", null).show();
    }
    private void askRenameSingle() {
        if (selected.size() != 1) { Toast.makeText(this, "名前変更は1件だけ選んでください", Toast.LENGTH_LONG).show(); return; }
        File f = new File(selected.iterator().next());
        EditText et = new EditText(this); et.setText(f.getName());
        new AlertDialog.Builder(this).setTitle("名前変更").setView(et)
                .setPositiveButton("変更", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (!FileOps.isSafeName(name)) { Toast.makeText(this, "使えない名前です", Toast.LENGTH_LONG).show(); return; }
                    File dst = new File(f.getParentFile(), name);
                    if (dst.exists() || !f.renameTo(dst)) Toast.makeText(this, "変更できませんでした", Toast.LENGTH_LONG).show();
                    selected.clear(); afterOperationRefresh();
                }).setNegativeButton("キャンセル", null).show();
    }
    private void askDeleteSelected() {
        if (selected.isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("削除しますか？")
                .setMessage(selected.size() + " 件を削除します。元に戻せません。")
                .setPositiveButton("削除", (d, w) -> {
                    List<String> targets = new ArrayList<>(selected);
                    selected.clear();
                    new Thread(() -> {
                        int fail = 0;
                        for (String pth : targets) if (!FileOps.deleteRecursive(new File(pth))) fail++;
                        int f2 = fail;
                        runOnUiThread(() -> {
                            Toast.makeText(this, f2 == 0 ? "削除しました" : "一部削除できませんでした", Toast.LENGTH_LONG).show();
                            afterOperationRefresh();
                        });
                    }).start();
                }).setNegativeButton("キャンセル", null).show();
    }
    private void showDetailsSingle() {
        if (selected.size() != 1) {
            // 選択なしで押された場合は、選択を促す代わりに単独タップ相当として扱わない
            Toast.makeText(this, "詳細は1件だけ選んでください（長押しで選択）", Toast.LENGTH_LONG).show(); return;
        }
        File f = new File(selected.iterator().next());
        new Thread(() -> {
            String sizeText; String extra = "";
            if (f.isDirectory()) {
                int[] c = FileOps.countItems(f);
                sizeText = FileOps.formatSize(FileOps.totalSize(f));
                extra = "\n中身: ファイル " + c[0] + " 件 / フォルダ " + c[1] + " 件";
            } else sizeText = FileOps.formatSize(f.length());
            String msg = "名前: " + f.getName() + "\nパス: " + f.getAbsolutePath()
                    + "\n種類: " + (f.isDirectory() ? "フォルダ" : FileOps.categoryLabel(FileOps.categoryOf(f.getName())))
                    + "\nサイズ: " + sizeText + extra
                    + "\n更新: " + FileOps.formatDate(f.lastModified())
                    + "\n読み取り: " + (f.canRead() ? "可" : "不可") + " / 書き込み: " + (f.canWrite() ? "可" : "不可");
            runOnUiThread(() -> {
                AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("詳細").setMessage(msg)
                        .setNegativeButton("閉じる", null);
                if (f.isFile()) b.setPositiveButton("共有", (d, w) -> shareFile(f));
                b.show();
            });
        }).start();
    }

    // ----- コピー / 切り取り / 貼り付け -----
    private void setClipboard(boolean cut) {
        if (selected.isEmpty()) return;
        clipboard.clear();
        for (String p : selected) clipboard.add(new File(p));
        clipboardCut = cut; selected.clear();
        Toast.makeText(this, clipboard.size() + " 件を" + (cut ? "切り取り" : "コピー") + "しました。貼り付け先のフォルダを開いてください", Toast.LENGTH_LONG).show();
        refresh();
    }
    private void doPaste() {
        if (clipboard.isEmpty() || mode != MODE_BROWSE) return;
        List<File> srcs = new ArrayList<>(clipboard); boolean cut = clipboardCut;
        final File destDir = currentDir; // 貼り付け先は開始時点のフォルダに固定する (実行中に移動しても変えない)
        new Thread(() -> {
            int fail = 0;
            for (File src : srcs) {
                try {
                    if (cut && src.getParentFile() != null
                            && src.getParentFile().getCanonicalPath().equals(destDir.getCanonicalPath())) {
                        continue; // 同じフォルダへの切り取り貼り付けは何もしない
                    }
                    if (cut) {
                        File dst = FileOps.uniqueFile(destDir, src.getName());
                        if (!src.renameTo(dst)) { FileOps.copyRecursive(src, destDir); FileOps.deleteRecursive(src); }
                    } else FileOps.copyRecursive(src, destDir);
                } catch (Exception e) { fail++; }
            }
            int f2 = fail;
            runOnUiThread(() -> {
                clipboard.clear(); selected.clear();
                Toast.makeText(this, f2 == 0 ? "貼り付けました" : "一部貼り付けできませんでした", Toast.LENGTH_LONG).show();
                refresh();
            });
        }).start();
    }
    private void afterOperationRefresh() {
        if (mode == MODE_CATEGORY && currentCategory >= 0) loadCategory(currentCategory);
        else if (mode == MODE_RECENT) loadRecent();
        else refresh();
    }
    private void addFavorite(File dir) {
        Set<String> fav = favorites(); fav.add(dir.getAbsolutePath());
        prefs.edit().putStringSet("favorites", fav).apply();
        Toast.makeText(this, "お気に入りに追加しました（ホームに表示）", Toast.LENGTH_LONG).show();
    }

    // ----- 権限 -----
    private void checkPermission(boolean forceOpen) {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                if (!forceOpen && prefs.getBoolean("permDialogShown", false)) return; // 起動時の案内は初回だけ
                prefs.edit().putBoolean("permDialogShown", true).apply();
                new AlertDialog.Builder(this).setTitle("ファイルへのアクセス")
                        .setMessage("内部ストレージ全体を見るには「すべてのファイルへのアクセス」の許可が必要です。設定画面で許可してください。許可しなくてもアプリ専用フォルダは使えます。")
                        .setPositiveButton("設定を開く", (d, w) -> {
                            try {
                                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                i.setData(Uri.parse("package:" + getPackageName())); startActivity(i);
                            } catch (Exception e) {
                                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            }
                        }).setNegativeButton("あとで", null).show();
            } else if (forceOpen) Toast.makeText(this, "すでに許可されています", Toast.LENGTH_SHORT).show();
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        } else if (forceOpen) Toast.makeText(this, "すでに許可されています", Toast.LENGTH_SHORT).show();
    }

    @Override public void onBackPressed() {
        if (!selected.isEmpty()) { selected.clear(); refresh(); return; }
        if (mode == MODE_BROWSE) {
            if (!dirHistory.isEmpty()) openFolderNoHistory(dirHistory.pop());
            else showHome();
            return;
        }
        if (mode != MODE_HOME) { showHome(); return; }
        super.onBackPressed();
    }

    @Override protected void onResume() { super.onResume(); if (mode == MODE_HOME) updateStorageBar(); }
}
