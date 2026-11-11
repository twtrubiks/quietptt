package com.twtrubiks.quietptt

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.twtrubiks.quietptt.core.ptt.ArticleInfo
import com.twtrubiks.quietptt.core.ptt.ArticleRef
import com.twtrubiks.quietptt.core.ptt.ArticleSearch
import com.twtrubiks.quietptt.core.ptt.Session
import kotlinx.coroutines.launch

/**
 * 文章資訊（看板長按的「查詢文章資訊」，BePTT 也有）：一打開就叫 [Session.articleInfo]（列表上游標移到那篇按 `Q`，讀完按掉框，
 * 終端還在列表上）。查的時候轉圈；讀到列出文章代碼（AID）、文章網址、框裡其他的列（文章價格之類，照抄），
 * 點代碼或網址複製（BePTT 是長按複製）。讀不到說原因。查詢在 App 的 scope 裡跑：關掉對話框也會把 PTT 的框按掉。
 */
@Composable
fun ArticleInfoDialog(
    session: Session,
    board: String,
    article: ArticleRef,
    search: ArticleSearch?,
    title: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val query = remember(session, article) { Action() }
    var info by remember(session, article) { mutableStateOf<ArticleInfo?>(null) }
    LaunchedEffect(session, article) {
        query.launch(context.container) { info = session.articleInfo(board, article, search) }
    }

    /** 把 [text] 放進剪貼簿（Android 13 起系統自己會提示複製了）。 */
    fun copy(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("PTT", text)))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) context.toast("已複製")
        }
    }

    val found = info
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
        title = { Text("文章資訊") },
        text = {
            when {
                found != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    found.aid?.let { aid -> InfoRow("文章代碼", "#$aid", "article_info_aid") { copy("#$aid") } }
                    found.url?.let { url -> InfoRow("文章網址", url, "article_info_url") { copy(url) } }
                    for (note in found.notes) Text(note, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (found.aid == null && found.url == null && found.notes.isEmpty()) "沒有讀到文章資訊" else "點代碼或網址複製",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                query.error != null -> Text("查詢失敗：${query.error}", color = MaterialTheme.colorScheme.error)
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在取得文章資訊…")
                }
            }
        },
        modifier = Modifier.testTag("article_info"),
    )
}

/** 一項資訊：上面小字標籤、下面值；點了複製。 */
@Composable
private fun InfoRow(label: String, value: String, tag: String, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onCopy).padding(vertical = 2.dp).testTag(tag)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
