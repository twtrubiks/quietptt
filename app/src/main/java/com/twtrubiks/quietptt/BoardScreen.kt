package com.twtrubiks.quietptt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.twtrubiks.quietptt.core.ptt.Page
import com.twtrubiks.quietptt.core.ptt.Session

/**
 * 看板的文章列表。一進來叫 [Session.openBoard]，認出 [Page.ArticleList] 後列出這一頁的文章。
 * TODO：往上捲載入更舊的（PAGE_UP）、推文數上色、搜尋、置底文分開。
 */
@Composable
fun BoardScreen(
    session: Session,
    page: Page,
    board: String,
    onOpenArticle: (index: Int) -> Unit,
    onTerminal: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(session, board) {
        val current = page
        if (current !is Page.ArticleList || current.board != board) runCatching { session.openBoard(board) }
    }
    Column(Modifier.fillMaxSize()) {
        AppBar(board, onBack = onBack) {
            TextIconButton("終端", onClick = onTerminal, modifier = Modifier.testTag("terminal_button"))
        }
        when {
            page is Page.ArticleList && page.board == board -> LazyColumn(Modifier.fillMaxSize().testTag("article_list")) {
                itemsIndexed(page.articles) { row, article ->
                    ListItem(
                        headlineContent = { Text(article.title) },
                        supportingContent = { Text("${article.author}  ${article.date}") },
                        leadingContent = { Text(article.pushCount?.let { pushLabel(it) } ?: "") },
                        modifier = Modifier.clickable { onOpenArticle(article.index ?: row) },
                    )
                }
            }
            else -> WaitingForPage("正在打開 $board…", onTerminal)
        }
    }
}

/** 推文數照 PTT 的寫法：爆、X1～XX、數字。 */
fun pushLabel(count: Int): String = when {
    count >= 100 -> "爆"
    count <= -100 -> "XX"
    count <= -10 -> "X${-count / 10}"
    else -> count.toString()
}
