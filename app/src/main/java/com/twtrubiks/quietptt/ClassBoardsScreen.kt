package com.twtrubiks.quietptt

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.twtrubiks.quietptt.core.ptt.Board
import com.twtrubiks.quietptt.core.ptt.Session

/**
 * 分組討論區裡一個分類的看板列表（BePTT 的 `ClassboardFragment`）：[path] 是從【分類看板】一層一層點進來的序號，
 * [title] 是分類名稱（放在上面那條）。一進來叫 [Session.showClass]；子分類（「Σ」，[Board.isFolder]）點了再進一層（[onOpenClass]），
 * 看板點了進板（[onOpenBoard]）。列出來的留在 rememberSaveable（轉螢幕、從看板回來不重讀；回來時 PTT 那邊會再走一次對齊）。
 */
@Composable
fun ClassBoardsScreen(
    session: Session,
    path: List<Int>,
    title: String,
    onOpenBoard: (String) -> Unit,
    onOpenClass: (path: List<Int>, title: String) -> Unit,
    onTerminal: () -> Unit,
    onBack: () -> Unit,
) {
    var boards by rememberSaveable(path, stateSaver = BoardsSaver) { mutableStateOf<List<Board>?>(null) }
    val navigation = rememberNavigation(session, "class-$path") { boards = showClass(path) }
    Column(Modifier.fillMaxSize()) {
        AppBar(title, onBack = onBack) {
            TerminalButton(onTerminal)
        }
        BoardList(
            boards = boards,
            navigation = navigation,
            loadingText = "正在打開「$title」…",
            emptyText = "這個分類裡沒有看板",
            listTag = "class_boards",
            onOpenBoard = onOpenBoard,
            onOpenFolder = { onOpenClass(path + it.index, it.name) },
            onTerminal = onTerminal,
        )
    }
}
