package com.twtrubiks.quietptt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.ptt.UserInfo

/**
 * 查詢使用者（點推文的「查詢 代號」、看板長按的「查詢作者資料」，BePTT 的查詢網友）：一打開就叫 [Session.queryUser]（終端會離開文章或看板、回到主選單；
 * 之後文章頁要讀下一頁時會自己再打開那篇，看板頁開文章、載入更早的、重整也會自己走回那個板）。查的時候轉圈；查到列出 PTT 給的每一欄（「《標籤》值」照抄）與名片檔，
 * 沒有這個人、或查不了就說原因。查詢在 App 的 scope 裡跑：關掉對話框也會把 PTT 的畫面走完。
 */
@Composable
fun UserInfoDialog(session: Session, id: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val query = remember(session, id) { Action() }
    var info by remember(session, id) { mutableStateOf<UserInfo?>(null) }
    var notFound by remember(session, id) { mutableStateOf(false) }
    LaunchedEffect(session, id) {
        query.launch(context.container) {
            val found = session.queryUser(id)
            if (found == null) notFound = true else info = found
        }
    }
    val found = info
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
        title = { Text(found?.let { listOfNotNull(it.id, it.nickname?.let { nick -> "($nick)" }).joinToString(" ") } ?: id) },
        text = {
            when {
                found != null -> SelectionContainer {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((label, value) in found.fields) {
                            Row {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(72.dp),
                                )
                                Text(value, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (found.plan.isNotEmpty()) {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Text("名片", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                found.plan.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                query.error != null -> Text("查詢失敗：${query.error}", color = MaterialTheme.colorScheme.error)
                notFound -> Text("沒有 $id 這個使用者")
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在查詢…")
                }
            }
        },
        modifier = Modifier.testTag("user_info"),
    )
}
