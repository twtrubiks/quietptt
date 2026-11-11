package com.twtrubiks.quietptt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.twtrubiks.quietptt.core.ptt.ArticleItem
import com.twtrubiks.quietptt.core.ptt.ArticleRef
import com.twtrubiks.quietptt.core.ptt.ArticleSearch
import com.twtrubiks.quietptt.core.ptt.ArticleText
import com.twtrubiks.quietptt.core.ptt.PushReply
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.ptt.author
import com.twtrubiks.quietptt.core.ptt.editableBody
import com.twtrubiks.quietptt.core.ptt.items
import com.twtrubiks.quietptt.core.ptt.pushAnchors

/**
 * 編輯自己的文章（BePTT 的「編輯文章」）。一進來把整篇讀完（[Session.openArticle] 加 [Session.nextArticlePage] 翻到底），
 * 把本文（[editableBody]：標頭之後、簽名檔之前）放進多行輸入框；「儲存」先問一次，再叫 [Session.editArticle]：
 * 進 PTT 的編輯器把舊本文一行一行刪掉、打新的、存檔。走 App 的 scope、不給返回（跟發文一樣，不能留在編輯器裡）。
 * 存好了回文章（[onDone]），文章頁重新打開就是新的內容。[search] 有給的話 [index] 是那次搜尋結果裡的第幾筆。
 * 改了本文的話原文的顏色會不見（App 看到的是純文字）；本文沒改就不碰它。推文與簽名檔不會動到。
 *
 * 輸入框下面列出這篇的推文（跟文章頁同一個樣子 [CommentRow]）與推文之間作者以前寫的回應（只能看）。
 * 點一則推文：突顯同一個人的推文，並在它下面開一個輸入框寫回應（可以開好幾個；空的再點一次或按框裡的 ✕ 收起來）。
 * 「儲存」在畫面最下面固定的一列（捲到哪都按得到），旁邊寫著改了什麼（本文、幾則回應）；本文與回應一次存。
 * 存檔時回應插在那則推文下面（[PushReply]，位置是 [pushAnchors]）。推文是一進來讀到的那些，編輯途中新來的不會自己出現。
 */
@Composable
fun EditArticleScreen(session: Session, board: String, index: Int, search: ArticleSearch?, onDone: () -> Unit, onTerminal: () -> Unit, onBack: () -> Unit) {
    var article by remember(session, board, index) { mutableStateOf<ArticleText?>(null) }
    val navigation = rememberNavigation(session, "edit-$board-$index-${search?.kind}-${search?.query}") {
        var text = ArticleText.EMPTY + openArticle(board, ArticleRef.Numbered(index), search)
        while (!text.complete) text += nextArticlePage(board, ArticleRef.Numbered(index), search)
        article = text
    }
    val original = remember(article) { article?.editableBody() }
    // 第一則推文起的每一則（推文，與推文之間的字：作者以前寫的回應、「※ 編輯」），推文帶著它是第幾則（0 起，跟 anchors 對得上）
    val tail = remember(article) {
        val items = article?.items().orEmpty()
        val first = items.indexOfFirst { it is ArticleItem.Comment }
        var n = 0
        if (first < 0) emptyList() else items.drop(first).map { it to if (it is ArticleItem.Comment) n++ else -1 }
    }
    val commentCount = remember(tail) { tail.count { it.second >= 0 } }
    val anchors = remember(article) { article?.pushAnchors().orEmpty() }
    val author = remember(article) { article?.author }
    var body by rememberSaveable(board, index) { mutableStateOf<String?>(null) }
    var confirmOpen by rememberSaveable { mutableStateOf(false) }
    var highlighted by rememberSaveable(board, index) { mutableStateOf<String?>(null) }
    // 第幾則推文下面要插的回應（開著輸入框的都在裡面，還沒打字的是空字串）
    var replies by rememberSaveable(board, index) { mutableStateOf(HashMap<Int, String>()) }
    val saving = remember(session, board, index) { Action() }
    val container = LocalContext.current.container
    // 第一次讀到原文才填進輸入框（轉螢幕回來不要把改到一半的蓋掉）
    LaunchedEffect(original) { if (body == null && original != null) body = original.joinToString("\n") }

    fun save() {
        val old = original ?: return
        val new = body ?: return
        val inserts = replies.filterValues { it.isNotBlank() }.mapNotNull { (n, text) ->
            anchors.getOrNull(n)?.let { PushReply(it.fromEnd, it.line, text.trimEnd()) }
        }
        saving.launch(container) { session.editArticle(board, index, old, new, inserts, search) }
    }
    LaunchedEffect(saving.done) { if (saving.done) onDone() }
    BackHandler(enabled = saving.active) {}

    Column(Modifier.fillMaxSize()) {
        AppBar("編輯文章", onBack = if (saving.active) null else onBack) {
            TerminalButton(onTerminal)
        }
        val text = body
        when {
            text == null || navigation.error != null -> WaitingForPage("正在讀整篇文章…", onTerminal, navigation.error, navigation::retry)
            saving.active -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在存檔…請不要離開")
            }
            else -> {
                val colors = pushColors()
                val images = remember(session, board, index) { OpenedImages() }
                val bodyChanged = original != null && text != original.joinToString("\n")
                val replyCount = replies.values.count { it.isNotBlank() }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("edit_list")) {
                    item(key = "editor") {
                        Column(Modifier.padding(16.dp)) {
                            saving.error?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("edit_error"))
                                Row {
                                    TextButton(onClick = ::save) { Text("重試") }
                                    TextButton(onClick = onTerminal) { Text("切到終端畫面") }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            Text(
                                "標頭、簽名檔與推文不會動到。改了本文的話，原文的顏色存檔後會不見。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = text,
                                onValueChange = { body = it },
                                label = { Text("內文") },
                                minLines = 12,
                                modifier = Modifier.fillMaxWidth().testTag("edit_body"),
                            )
                        }
                    }
                    if (commentCount > 0) {
                        item(key = "comments_title") {
                            Column {
                                HorizontalDivider()
                                Text(
                                    "推文 $commentCount 則（點一則，在它下面寫回應）",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    itemsIndexed(tail, key = { i, _ -> "tail-$i" }) { i, (item, n) ->
                        when (item) {
                            is ArticleItem.Comment -> Column {
                                val user = item.push.user
                                // 字級跟文章頁一樣照設定的「文章字級」
                                ArticleTextScale {
                                    CommentRow(
                                        item,
                                        isAuthor = user == author,
                                        grouped = (tail.getOrNull(i - 1)?.first as? ArticleItem.Comment)?.push?.user == user,
                                        highlighted = user.equals(highlighted, ignoreCase = true),
                                        colors = colors,
                                        images = images,
                                        // 點一則：開它的回應框、突顯這個人；回應框開著又是空的，再點一次收起來
                                        onClick = {
                                            val open = replies[n]
                                            when {
                                                open == null -> {
                                                    replies = HashMap(replies).apply { put(n, "") }
                                                    highlighted = user
                                                }
                                                open.isBlank() -> {
                                                    replies = HashMap(replies).apply { remove(n) }
                                                    highlighted = null
                                                }
                                                else -> highlighted = user.takeUnless { it.equals(highlighted, ignoreCase = true) }
                                            }
                                        },
                                    )
                                }
                                replies[n]?.let { reply ->
                                    OutlinedTextField(
                                        value = reply,
                                        onValueChange = { replies = HashMap(replies).apply { put(n, it) } },
                                        label = { Text("回應 $user") },
                                        singleLine = true,
                                        // 一則回應一行；鍵盤的 Enter 是「完成」（收鍵盤），存檔按最下面的「儲存」
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        trailingIcon = {
                                            IconButton(
                                                onClick = { replies = HashMap(replies).apply { remove(n) } },
                                                modifier = Modifier.testTag("reply_remove_$n"),
                                            ) { Icon(Icons.Filled.Close, contentDescription = "不回應這則") }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
                                            .testTag("reply_$n"),
                                    )
                                }
                            }
                            // 推文之間的字（作者以前寫的回應）與「※ 編輯」：只能看
                            is ArticleItem.Line -> if (item.text.isNotBlank()) {
                                ArticleTextScale {
                                    Text(item.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                                }
                            }
                            is ArticleItem.Signature -> Text(
                                item.text,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
                            )
                            is ArticleItem.Gap -> {}
                        }
                    }
                }
                // 固定在最下面：改了什麼、儲存
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            bodyChanged && replyCount > 0 -> "本文已修改、$replyCount 則回應"
                            bodyChanged -> "本文已修改"
                            replyCount > 0 -> "$replyCount 則回應"
                            else -> "還沒有修改"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).testTag("edit_summary"),
                    )
                    Button(
                        onClick = { confirmOpen = true },
                        enabled = bodyChanged || replyCount > 0,
                        modifier = Modifier.testTag("save_button"),
                    ) { Text("儲存") }
                }
            }
        }
    }
    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("確定要儲存修改?") },
            text = { Text("PTT 會在文章最後加上「※ 編輯」的記錄。請在網路訊號良好的地方存檔，存檔時不要離開 App。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOpen = false
                        save()
                    },
                    modifier = Modifier.testTag("confirm_save"),
                ) { Text("儲存") }
            },
            dismissButton = { TextButton(onClick = { confirmOpen = false }) { Text("取消") } },
        )
    }
}
