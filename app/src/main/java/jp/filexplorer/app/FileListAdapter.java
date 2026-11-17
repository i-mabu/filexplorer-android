package jp.filexplorer.app;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import java.util.ArrayList;

// ===== 一覧用アダプタ (トップレベルクラス) =====
public class FileListAdapter extends ArrayAdapter<String> {
    private final MainActivity act;
    public FileListAdapter(MainActivity act) {
        super(act, android.R.layout.simple_list_item_1, new ArrayList<>());
        this.act = act;
    }
    @Override public View getView(int pos, View convert, ViewGroup parent) {
        return act.buildRow(pos, convert);
    }
}
