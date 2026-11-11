package com.twtrubiks.quietptt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.twtrubiks.quietptt.core.ptt.Board
import com.twtrubiks.quietptt.core.ptt.Session

/**
 * 搜尋看板（BePTT 的 `activity_search_board`：最上面一個輸入框「輸入中英文搜尋看板」，下面是結果）。
 * 按鍵盤的搜尋鍵（或「搜尋」）叫 [Session.searchBoards]：英文找板名開頭（PTT 的板名自動補完）、中文找看板標題的關鍵字；
 * 結果一列一個看板（板名、有的話分類與標題），點了進板，右邊「加入最愛」叫 [Session.addFavorite]。
 * 搜尋與加入都在 App 的 scope 裡跑（中途離開也要讓 PTT 那邊回到我的最愛）。結果留在 rememberSaveable，轉螢幕不用重搜。
 * 不存搜尋紀錄（BePTT 有；這個 App 本機只留帳密）。
 */
@Composable
fun BoardSearchScreen(session: Session, onOpenBoard: (String) -> Unit, onTerminal: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    var query by rememberSaveable { mutableStateOf("") }
    var searched by rememberSaveable { mutableStateOf("") } // 上一次搜的字（空的就是還沒搜過）
    var results by rememberSaveable(stateSaver = SearchResultsSaver) { mutableStateOf(emptyList<Board>()) }
    // 板名補完的清單太長、PTT 只列了一頁（最後一格「...」）：提示多打幾個字
    var truncated by rememberSaveable { mutableStateOf(false) }
    val search = remember(session) { Action() }
    val adding = remember(session) { Action() }
    var addingName by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty() || search.active) return
        focus.clearFocus()
        searched = q
        search.launch(container) {
            val found = session.searchBoards(q)
            results = found.boards
            truncated = found.truncated
        }
    }

    fun add(board: String) {
        if (adding.active) return
        addingName = board
        adding.launch(container) {
            val added = session.addFavorite(board)
            context.toast(if (added) "已將 $board 加入我的最愛" else "$board 已經在我的最愛裡了")
        }
    }
    LaunchedEffect(adding.error) { adding.error?.let { context.toast("加入我的最愛失敗：$it") } }

    Column(Modifier.fillMaxSize()) {
        AppBar("搜尋看板", onBack = onBack) {
            TerminalButton(onTerminal)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.replace("\n", "") },
            singleLine = true,
            label = { Text("輸入中英文搜尋看板") },
            supportingText = { Text("英文找板名開頭（例如 Mobile），中文找看板標題（例如 手機）") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            trailingIcon = {
                TextButton(onClick = ::runSearch, enabled = query.isNotBlank() && !search.active, modifier = Modifier.testTag("board_search_go")) {
                    Text("搜尋")
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("board_search_query"),
        )
        when {
            search.active -> Centered {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在找「$searched」…")
            }
            search.error != null -> Centered {
                Text(search.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("board_search_error"))
                Spacer(Modifier.height(12.dp))
                Row {
                    TextButton(onClick = ::runSearch) { Text("重試") }
                    TextButton(onClick = onTerminal) { Text("切到終端畫面") }
                }
            }
            searched.isNotEmpty() && results.isEmpty() -> Centered {
                Text("找不到「$searched」", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("board_search_empty"))
            }
            else -> LazyColumn(Modifier.fillMaxSize().testTag("board_search_results")) {
                items(results, key = { it.name }) { board ->
                    BoardResultRow(
                        board,
                        adding = adding.active && addingName == board.name,
                        onOpen = { onOpenBoard(board.name) },
                        onAdd = { add(board.name) },
                    )
                }
                if (truncated) {
                    item(key = "truncated") {
                        Text(
                            "PTT 只列出前 ${results.size} 個，多打幾個字可以縮小範圍",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("board_search_truncated"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** 搜尋結果的一列：板名粗體、下面分類與標題（板名補完只有板名）、右邊「加入最愛」。 */
@Composable
private fun BoardResultRow(board: Board, adding: Boolean, onOpen: () -> Unit, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).testTag("board_result"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(board.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
            val detail = listOf(board.category, board.title).filter { it.isNotBlank() }.joinToString("  ")
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onAdd, enabled = !adding, modifier = Modifier.testTag("add_favorite")) {
            Text(if (adding) "加入中…" else "加入最愛")
        }
    }
}

/** 搜尋結果存進 saved state：一個看板一個字串「板名|分類|標題」（板名與分類裡沒有「|」）。 */
private val SearchResultsSaver = listSaver<List<Board>, String>(
    save = { boards -> boards.map { "${it.name}|${it.category}|${it.title}" } },
    restore = { saved ->
        saved.map {
            val parts = it.split("|", limit = 3)
            Board(index = 0, name = parts[0], category = parts.getOrElse(1) { "" }, title = parts.getOrElse(2) { "" })
        }
    },
)
