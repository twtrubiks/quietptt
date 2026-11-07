package com.twtrubiks.quietptt

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import com.twtrubiks.quietptt.core.ptt.Page
import com.twtrubiks.quietptt.core.ptt.Session

/**
 * 文章頁。現在只顯示終端目前這一頁的 23 列（[Page.ArticleView.body]）。
 * TODO：用 [Session.readArticle] 翻完整篇再顯示、推文分段上色、網址可點（圖片不自動載，點了才載）、推文。
 */
@Composable
fun ArticleScreen(
    session: Session,
    page: Page,
    board: String,
    onTerminal: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppBar(board, onBack = onBack) {
            TextIconButton("終端", onClick = onTerminal, modifier = Modifier.testTag("terminal_button"))
        }
        when (page) {
            is Page.ArticleView -> LazyColumn(Modifier.fillMaxSize().testTag("article_body")) {
                items(page.body) { line -> Text(line, fontFamily = FontFamily.Monospace) }
            }
            else -> WaitingForPage("正在打開文章…", onTerminal)
        }
    }
}
