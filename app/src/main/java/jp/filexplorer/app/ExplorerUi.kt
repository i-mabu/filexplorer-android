package jp.filexplorer.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ========================================
// 🎨 ExplorerUi: 画面共通の色と一覧行コンポーネント
// ========================================

internal val headerBlue = Color(232, 240, 254)
internal val nameColor = Color(32, 33, 36)
internal val subColor = Color(95, 99, 104)
internal val pasteYellow = Color(254, 247, 224)

/** 選択中アクションバーのボタン (均等幅・小さめの文字)。 */
@Composable
internal fun RowScope.SelectionButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 2.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 12.sp)
    }
}

/** ファイル/フォルダ/ホーム項目を 1 行で表す共通行。タップと長押しを受ける。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EntryRow(
    icon: String,
    name: String,
    sub: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
