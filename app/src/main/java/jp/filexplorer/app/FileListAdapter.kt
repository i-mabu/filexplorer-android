package jp.filexplorer.app

import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter

// ===== 一覧用アダプタ (トップレベルクラス) =====
class FileListAdapter(private val act: MainActivity) :
    ArrayAdapter<String>(act, android.R.layout.simple_list_item_1, ArrayList()) {
    override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
        return act.buildRow(pos, convert)
    }
}
