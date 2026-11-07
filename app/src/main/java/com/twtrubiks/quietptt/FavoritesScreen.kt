package com.twtrubiks.quietptt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.twtrubiks.quietptt.core.ptt.Page
import com.twtrubiks.quietptt.core.ptt.Session

/**
 * 我的最愛（登入後的首頁）。一進來就叫 [Session.openFavorites]，畫面認出 [Page.Favorites] 後列出看板；
 * 認不出來的時候給「切到終端畫面」。
 * TODO：目錄（資料夾）進去、未讀標記、下拉重新整理。
 */
@Composable
fun FavoritesScreen(
    session: Session,
    page: Page,
    onOpenBoard: (String) -> Unit,
    onTerminal: () -> Unit,
    onSettings: () -> Unit,
) {
    LaunchedEffect(session) {
        if (page !is Page.Favorites) runCatching { session.openFavorites() }
    }
    Column(Modifier.fillMaxSize()) {
        AppBar("我的最愛") {
            TextIconButton("終端", onClick = onTerminal, modifier = Modifier.testTag("terminal_button"))
            TextIconButton("設定", onClick = onSettings, modifier = Modifier.testTag("settings_button"))
        }
        when (page) {
            is Page.Favorites -> LazyColumn(Modifier.fillMaxSize().testTag("favorites_list")) {
                items(page.boards, key = { it.index }) { board ->
                    ListItem(
                        headlineContent = { Text(board.name) },
                        supportingContent = { Text(board.title) },
                        trailingContent = { Text(board.category) },
                        modifier = Modifier.clickable(enabled = !board.isFolder) { onOpenBoard(board.name) },
                    )
                }
            }
            else -> WaitingForPage("正在打開我的最愛…", onTerminal)
        }
    }
}
