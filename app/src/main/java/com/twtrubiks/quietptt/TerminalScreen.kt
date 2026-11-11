package com.twtrubiks.quietptt

import android.content.ClipData
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.terminal.Keys
import com.twtrubiks.quietptt.core.terminal.Screen
import kotlinx.coroutines.launch

/**
 * 終端畫面：原始的 24x80 加軟鍵與打字框。畫面辨識認不出來的時候（公告、驗證問題、改版、App 不替使用者答的 y/n）靠這一頁走出去。
 * - 兩排軟鍵（橫向可捲）：方向、Enter、Esc、退格、翻頁；Ctrl（亮著時下一個字母變成 Ctrl 鍵）、常用的 ^C ^X ^Z ^L、y n q
 * - 打字框：系統鍵盤打英數、符號、中文（送出時轉 Big5-UAO，轉不了的字送「?」）。鍵盤的送出鍵與「送出↵」連 Enter 一起送，「送出」只送字
 * - 上下也能捲：游標那一列（PTT 等你打字的地方）換了、或鍵盤叫出來畫面變矮，就捲到看得到游標
 * 「複製」把目前 24 列的文字放進剪貼簿：在手機上就能抓畫面回報（跟 tools/capture 存的 fixture 同樣格式）。
 * TODO：顏色（Cell.attr）、觸控手勢（左右滑＝左右鍵、上下滑＝翻頁）。
 */
@Composable
fun TerminalScreen(session: Session, onBack: () -> Unit) {
    val screen by session.screens.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var copied by remember { mutableStateOf(false) }
    var ctrl by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    fun send(keys: String) {
        scope.launch { runCatching { session.send(keys) } }
    }
    // 字母鍵與打字框：Ctrl 亮著就轉成 Ctrl 鍵，送完熄掉（跟實體鍵盤按著 Ctrl 打一個字一樣）
    fun type(text: String, enter: Boolean = false) {
        send(terminalInput(text, ctrl, enter))
        ctrl = false
    }
    fun sendTyped(enter: Boolean) {
        type(typed, enter)
        typed = ""
    }
    Column(Modifier.fillMaxSize()) {
        AppBar("終端畫面", onBack = onBack) {
            TextButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("PTT 畫面", screen.toString())))
                        copied = true
                    }
                },
                modifier = Modifier.testTag("copy_screen_button"),
            ) { Text(if (copied) "已複製" else "複製") }
        }
        TerminalViewport(screen, Modifier.weight(1f).fillMaxWidth())
        KeyRow {
            SoftKey("←") { send(Keys.LEFT) }
            SoftKey("→") { send(Keys.RIGHT) }
            SoftKey("↑") { send(Keys.UP) }
            SoftKey("↓") { send(Keys.DOWN) }
            SoftKey("Enter") { send(Keys.ENTER) }
            SoftKey("Esc") { send(Keys.ESC) }
            SoftKey("Space") { send(" ") }
            SoftKey("⌫") { send(Keys.BACKSPACE) }
            SoftKey("Tab") { send(Keys.TAB) }
            SoftKey("Home") { send(Keys.HOME) }
            SoftKey("End") { send(Keys.END) }
            SoftKey("PgUp") { send(Keys.PAGE_UP) }
            SoftKey("PgDn") { send(Keys.PAGE_DOWN) }
        }
        KeyRow {
            FilterChip(
                selected = ctrl,
                onClick = { ctrl = !ctrl },
                label = { Text("Ctrl") },
                modifier = Modifier.padding(horizontal = 2.dp).testTag("ctrl_key"),
            )
            // 取消、編輯器的存檔選單、快速切換、整頁重畫
            SoftKey("^C") { send(Keys.ctrl('C')) }
            SoftKey("^X") { send(Keys.ctrl('X')) }
            SoftKey("^Z") { send(Keys.ctrl('Z')) }
            SoftKey("^L") { send(Keys.CTRL_L) }
            // 不用叫鍵盤就能答的
            SoftKey("y") { type("y") }
            SoftKey("n") { type("n") }
            SoftKey("q") { type("q") }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                placeholder = { Text(if (ctrl) "Ctrl ＋ 字母" else "英數、符號、中文") },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendTyped(enter = true) }),
                modifier = Modifier.weight(1f).testTag("terminal_input"),
            )
            TextButton(
                onClick = { sendTyped(enter = false) },
                enabled = typed.isNotEmpty(),
                modifier = Modifier.testTag("terminal_send"),
            ) { Text("送出") }
            Button(
                onClick = { sendTyped(enter = true) },
                modifier = Modifier.testTag("terminal_send_enter"),
            ) { Text("送出↵") }
        }
    }
}

/**
 * 打字框或字母鍵要送的字：[ctrl] 亮著時英文字母換成 Ctrl 鍵（`x` → ^X），其他字（數字、符號、中文）照送；
 * [enter] 最後加一個 Enter（不受 Ctrl 影響）。
 */
internal fun terminalInput(text: String, ctrl: Boolean, enter: Boolean): String = buildString {
    for (ch in text) append(if (ctrl && (ch in 'a'..'z' || ch in 'A'..'Z')) Keys.ctrl(ch) else ch.toString())
    if (enter) append(Keys.ENTER)
}

/** 終端那 24 列，上下也能捲；游標那一列換了、或鍵盤叫出來畫面變矮，就捲到看得到游標（PTT 等你打字的地方）。 */
@Composable
private fun TerminalViewport(screen: Screen, modifier: Modifier = Modifier) {
    val vertical = rememberScrollState()
    val density = LocalDensity.current
    var viewport by remember { mutableIntStateOf(0) }
    LaunchedEffect(screen.cursorRow, viewport) {
        if (viewport <= 0) return@LaunchedEffect
        val line = with(density) { TERMINAL_LINE_HEIGHT.toPx() }
        val pad = with(density) { TERMINAL_PADDING.toPx() }
        val top = (pad + screen.cursorRow * line).toInt()
        val bottom = (pad + (screen.cursorRow + 1) * line).toInt()
        when {
            top < vertical.value -> vertical.animateScrollTo(top)
            bottom > vertical.value + viewport -> vertical.animateScrollTo(bottom - viewport)
        }
    }
    Box(modifier.onSizeChanged { viewport = it.height }.verticalScroll(vertical)) {
        TerminalText(screen)
    }
}

/** 24 列等寬文字，橫向可捲。終端畫面與登入失敗時「看當時的畫面」共用。行高寫死，終端畫面才算得出游標那列在哪。 */
@Composable
fun TerminalText(screen: Screen, modifier: Modifier = Modifier) {
    Column(
        modifier
            .horizontalScroll(rememberScrollState())
            .padding(TERMINAL_PADDING)
            .testTag("terminal"),
    ) {
        for (line in screen.lines) {
            Text(
                line,
                fontFamily = FontFamily.Monospace,
                fontSize = TERMINAL_FONT_SIZE,
                lineHeight = TERMINAL_LINE_HEIGHT,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun SoftKey(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(horizontal = 2.dp)) { Text(label) }
}

private val TERMINAL_FONT_SIZE = 12.sp
private val TERMINAL_LINE_HEIGHT = 15.sp
private val TERMINAL_PADDING = 4.dp
