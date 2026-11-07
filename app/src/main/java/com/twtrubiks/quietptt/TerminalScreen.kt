package com.twtrubiks.quietptt

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.terminal.Keys
import kotlinx.coroutines.launch

/**
 * 終端畫面：原始的 24x80 加一排軟鍵。畫面辨識認不出來的時候（公告、驗證問題、改版）靠這一頁走出去。
 * TODO：顏色（Cell.attr）、觸控手勢（左右滑＝左右鍵、上下滑＝翻頁）、文字輸入框。
 */
@Composable
fun TerminalScreen(session: Session, onBack: () -> Unit) {
    val screen by session.screens.collectAsState()
    val scope = rememberCoroutineScope()
    fun send(keys: String) {
        scope.launch { runCatching { session.send(keys) } }
    }
    Column(Modifier.fillMaxSize()) {
        AppBar("終端畫面", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(4.dp)
                .testTag("terminal"),
        ) {
            for (line in screen.lines) {
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp)) {
            SoftKey("←") { send(Keys.LEFT) }
            SoftKey("→") { send(Keys.RIGHT) }
            SoftKey("↑") { send(Keys.UP) }
            SoftKey("↓") { send(Keys.DOWN) }
            SoftKey("PgUp") { send(Keys.PAGE_UP) }
            SoftKey("PgDn") { send(Keys.PAGE_DOWN) }
            SoftKey("Enter") { send(Keys.ENTER) }
            SoftKey("Esc") { send(Keys.ESC) }
            SoftKey("Space") { send(" ") }
        }
    }
}

@Composable
private fun SoftKey(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(horizontal = 2.dp)) { Text(label) }
}
