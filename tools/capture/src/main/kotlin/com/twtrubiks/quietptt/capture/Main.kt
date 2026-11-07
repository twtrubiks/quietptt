package com.twtrubiks.quietptt.capture

import com.twtrubiks.quietptt.core.ptt.ScreenParser
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.terminal.Keys
import com.twtrubiks.quietptt.core.transport.SshTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 在自己的電腦上連真的 PTT，把畫面存成測試用的 fixture。
 *
 * ```
 * ./gradlew :tools:capture:run -q --console=plain
 * ```
 *
 * 連上之後每次畫面安靜下來就印出 24 列文字與認出來的頁面；輸入一行指令：
 * - 直接打字就是送出那段文字（例如帳號）；`:enter`、`:up`、`:down`、`:left`、`:right`、`:pgup`、`:pgdn` 是按鍵
 * - `:pw 密碼`：送密碼加 Enter（不會印在畫面上）
 * - `:save 名稱`：把目前畫面存到 `tools/capture/out/名稱.txt`（整理過、去掉帳號之後搬到 `core/src/test/resources/screens/`）
 * - `:quit`
 */
fun main() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = Session(SshTransport(), scope)
    val out = File("tools/capture/out").apply { mkdirs() }
    println("連線中…")
    session.connect()
    show(session)
    while (true) {
        print("> ")
        val line = readlnOrNull() ?: break
        when {
            line == ":quit" -> break
            line == ":enter" -> session.send(Keys.ENTER)
            line == ":up" -> session.send(Keys.UP)
            line == ":down" -> session.send(Keys.DOWN)
            line == ":left" -> session.send(Keys.LEFT)
            line == ":right" -> session.send(Keys.RIGHT)
            line == ":pgup" -> session.send(Keys.PAGE_UP)
            line == ":pgdn" -> session.send(Keys.PAGE_DOWN)
            line.startsWith(":pw ") -> session.send(line.removePrefix(":pw ") + Keys.ENTER)
            line.startsWith(":save ") -> {
                val file = File(out, line.removePrefix(":save ").trim() + ".txt")
                file.writeText(session.screens.value.lines.joinToString("\n", postfix = "\n") { it.trimEnd() })
                println("存到 ${file.path}")
                continue
            }
            else -> session.send(line)
        }
        show(session)
    }
    session.close()
    scope.cancel()
}

private suspend fun show(session: Session) {
    val page = runCatching { session.awaitQuiet(timeoutMs = 5_000) }.getOrElse { ScreenParser.parse(session.screens.value) }
    println("┌" + "─".repeat(80) + "┐")
    session.screens.value.lines.forEach { println("│" + it.padEnd(80).take(80) + "│") }
    println("└" + "─".repeat(80) + "┘")
    println("辨識：${page::class.simpleName}")
}
