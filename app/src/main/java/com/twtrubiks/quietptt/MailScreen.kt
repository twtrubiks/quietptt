package com.twtrubiks.quietptt

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.twtrubiks.quietptt.core.ptt.ArticleHeader
import com.twtrubiks.quietptt.core.ptt.ArticleItem
import com.twtrubiks.quietptt.core.ptt.ArticleText
import com.twtrubiks.quietptt.core.ptt.MailSummary
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.ptt.header
import com.twtrubiks.quietptt.core.ptt.items

/**
 * 讀一封信（BePTT 把信件丟進文章內容頁）。一進來叫 [Session.wholeMail] 把整封讀完（信是同一個閱讀器，一頁一頁 PgDn 接起來、讀完退回信箱列表），
 * 排版照文章：標題卡（還沒讀到之前先用列表那一列 [preview] 畫）、本文一般字型換行（ASCII 圖等寬橫捲）、簽名檔縮小、網址可點、圖片按了才載，
 * 字級跟文章一樣（[ArticleTextScale]）。
 * 右上角「回信」進寫信頁（[WriteMailScreen]）；系統信、自己存的底稿（寄件人「[」或「-」開頭，PTT 說「無法回信。」）沒有這個鈕。
 * 不刪信。PTT 讀過就把未讀「+」拿掉。
 */
@Composable
fun MailScreen(session: Session, index: Int, preview: MailSummary?, onReply: () -> Unit, onTerminal: () -> Unit, onBack: () -> Unit) {
    var text by remember(session, index) { mutableStateOf<ArticleText?>(null) }
    val navigation = rememberNavigation(session, "mail-$index") { text = wholeMail(index) }
    val current = text
    Column(Modifier.fillMaxSize()) {
        AppBar("私人信件", onBack = onBack) {
            if (preview != null && canReply(preview.author)) {
                TextIconButton("回信", onClick = onReply, modifier = Modifier.testTag("reply_mail_button"))
            }
            TerminalButton(onTerminal)
        }
        val previewHeader = preview?.let { ArticleHeader(author = it.author, title = it.title, time = it.date) }
        if (current == null || navigation.error != null) {
            Column(Modifier.fillMaxSize()) {
                if (previewHeader != null) ArticleTextScale { HeaderCard(previewHeader, boardLabel = "收件人") }
                Box(Modifier.weight(1f)) { WaitingForPage("正在讀信…", onTerminal, navigation.error, navigation::retry) }
            }
        } else {
            val header = remember(current) { current.header ?: previewHeader }
            val items = remember(current) { current.items() }
            val artScroll = rememberScrollState()
            val images = remember(session, index) { OpenedImages() }
            SelectionContainer(Modifier.weight(1f)) {
                ArticleTextScale {
                    LazyColumn(Modifier.fillMaxSize().testTag("mail_body")) {
                        if (header != null) item(key = "header") { HeaderCard(header, boardLabel = "收件人") }
                        itemsIndexed(items, key = { row, _ -> row }) { _, item ->
                            when (item) {
                                is ArticleItem.Line -> BodyLine(item.text, item.colors, item.art, artScroll, images)
                                is ArticleItem.Signature -> Text(
                                    linkified(item.text),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
                                )
                                // 信裡不會有推文；萬一有（轉寄的文章）就印成一般的字
                                is ArticleItem.Comment -> Text(
                                    "${item.push.user}: ${item.push.text}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
                                )
                                // 信是整封讀完的（Session.wholeMail），不會缺一段
                                is ArticleItem.Gap -> Text(
                                    "（中間 ${item.size} 行沒讀到）",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 回得了的信：寄件人是「[」或「-」開頭的（系統信、「[備.忘.錄]」自存底稿、被刪的帳號）pttbbs `mail_reply` 說「無法回信。」。 */
fun canReply(author: String): Boolean = author.isNotEmpty() && !author.startsWith("[") && !author.startsWith("-")
