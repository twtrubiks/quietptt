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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.twtrubiks.quietptt.core.ptt.ArticleRef
import com.twtrubiks.quietptt.core.ptt.ArticleText
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.ptt.editableBody

/**
 * 編輯自己的文章（BePTT 的「編輯文章」）。一進來把整篇讀完（[Session.openArticle] 加 [Session.nextArticlePage] 翻到底），
 * 把本文（[editableBody]：標頭之後、簽名檔之前）放進多行輸入框；「儲存」先問一次，再叫 [Session.editArticle]：
 * 進 PTT 的編輯器把舊本文一行一行刪掉、打新的、存檔。走 App 的 scope、不給返回（跟發文一樣，不能留在編輯器裡）。
 * 存好了回文章（[onDone]），文章頁重新打開就是新的內容。
 * 原文的顏色會不見（App 看到的是純文字）、推文與簽名檔不會動到。
 */
@Composable
fun EditArticleScreen(session: Session, board: String, index: Int, onDone: () -> Unit, onTerminal: () -> Unit, onBack: () -> Unit) {
    var original by remember(session, board, index) { mutableStateOf<List<String>?>(null) }
    val navigation = rememberNavigation(session, "edit-$board-$index") {
        var text = ArticleText.EMPTY + openArticle(board, ArticleRef.Numbered(index))
        while (!text.complete) text += nextArticlePage(board, ArticleRef.Numbered(index))
        original = text.editableBody()
    }
    var body by rememberSaveable(board, index) { mutableStateOf<String?>(null) }
    var confirmOpen by rememberSaveable { mutableStateOf(false) }
    val saving = remember(session, board, index) { Action() }
    val container = LocalContext.current.container
    // 第一次讀到原文才填進輸入框（轉螢幕回來不要把改到一半的蓋掉）
    LaunchedEffect(original) { if (body == null && original != null) body = original!!.joinToString("\n") }

    fun save() {
        val old = original ?: return
        val new = body ?: return
        saving.launch(container) { session.editArticle(board, index, old, new) }
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
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                saving.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("edit_error"))
                    Row {
                        TextButton(onClick = ::save) { Text("重試") }
                        TextButton(onClick = onTerminal) { Text("切到終端畫面") }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "只改本文：標頭、簽名檔與推文不會動到。原文的顏色存檔後會不見。",
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
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onBack) { Text("取消") }
                    TextButton(
                        onClick = { confirmOpen = true },
                        enabled = original != null && text != original!!.joinToString("\n"),
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
