package com.twtrubiks.quietptt.capture

import com.twtrubiks.quietptt.core.ptt.Page
import com.twtrubiks.quietptt.core.ptt.ScreenParser
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.record.Redactor
import com.twtrubiks.quietptt.core.terminal.Big5Uao
import com.twtrubiks.quietptt.core.terminal.Keys
import com.twtrubiks.quietptt.core.terminal.Screen
import com.twtrubiks.quietptt.core.transport.SshTransport
import com.twtrubiks.quietptt.core.transport.WebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 連真的 PTT（跟 App 同一套 Transport 與 Terminal），邊走邊錄。雲端 VM 用 `--ws`（SSH 的 22 埠被擋）、帳密用 `:login` 從環境變數讀。
 *
 * ```
 * ./gradlew :tools:capture:installDist
 * tools/capture/build/install/capture/bin/capture
 * ```
 * （用 installDist 跑，`:pw` 才能不顯示密碼；`./gradlew :tools:capture:run -q --console=plain` 也可以，只是密碼會顯示在畫面上）
 * 預設走 SSH（跟 App 預設一樣）；加 `--ws` 走 WebSocket（wss://ws.ptt.cc/bbs），`--ws=網址` 連別的 WebSocket（測這個工具用）。
 *
 * 每送一次就等畫面安靜下來、印出 24 列與辨識結果，同時寫進 `tools/capture/out/`：
 * - `recording-時間.txt`：伺服器送來的 bytes、送出去的按鍵，各帶毫秒（格式見 `core/.../record/Recording`），測試拿來重播
 * - `screens-時間.txt`：每一步的畫面文字與辨識結果，給人看
 * - `:save 名稱` 另存目前畫面成 `名稱.txt`（fixture 格式）
 *
 * 個人資料：帳號換成同樣長度的 x、IP 的數字換成 0（三種檔都是）；密碼不寫進任何檔案（PTT 也不會回顯）。
 * 檔案在 `.gitignore` 裡。給別人之前還是打開 `screens-*.txt` 看一遍。
 */
fun main(args: Array<String>): Unit = runBlocking {
    // 終端機的編碼印不出中文（例如 POSIX 語系）就改用 UTF-8；Windows 的 Big5 終端機照舊
    if (System.getProperty("stdout.encoding")?.let { !Charset.forName(it).newEncoder().canEncode('看') } == true) {
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
    }
    // --e2e-write：發文、推文、改標題、編輯、刪文，只在 Test 板、只動自己發的那篇（見 WriteEndToEnd.kt）。使用者要求才跑
    if ("--e2e-write" in args) {
        writeEndToEnd()
        return@runBlocking
    }
    // --e2e [看板]：用 App 的 Session 照 App 的順序走一次（見 EndToEnd.kt），不錄影
    args.indexOf("--e2e").takeIf { it >= 0 }?.let { i ->
        endToEnd(args.getOrNull(i + 1)?.takeUnless { it.startsWith("--") } ?: "MobileComm")
        return@runBlocking
    }
    val out = File("tools/capture/out").apply { mkdirs() }
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val recorder = Recorder(File(out, "recording-$stamp.txt"), File(out, "screens-$stamp.txt"))
    // 環境變數裡的帳號一開始就遮（不管後來是用 :login 還是自己打的）
    System.getenv("PTT_ID")?.trim()?.takeIf { it.isNotEmpty() }?.let { recorder.secrets += it }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ws = args.firstOrNull { it == "--ws" || it.startsWith("--ws=") }
    val http = ws?.let { OkHttpClient() }
    val transport = when {
        ws == null -> SshTransport()
        ws == "--ws" -> WebSocketTransport(http!!)
        else -> WebSocketTransport(http!!, url = ws.removePrefix("--ws="))
    }
    val session = Session(RecordingTransport(transport, recorder), scope)
    println("連線中…（錄影存到 ${recorder.recordingFile.path}）")
    session.connect()
    var page = show(session, recorder, "（連上）", after = null)
    printHelp()
    while (!session.closed.value) {
        print("> ")
        val line = readlnOrNull() ?: break
        var before = session.screens.value
        val label: String = try {
            when {
                line == ":quit" -> break
                line == ":help" -> {
                    printHelp()
                    continue
                }
                line.startsWith(":save ") -> {
                    val file = File(out, line.removePrefix(":save ").trim() + ".txt")
                    file.writeText(Redactor.redact(session.screens.value.toString(), recorder.secrets) + "\n")
                    println("存到 ${file.path}")
                    continue
                }
                line.startsWith(":note ") -> {
                    recorder.note(line.removePrefix(":note ").trim())
                    println("記下了")
                    continue
                }
                // 登入途中（停在代號或密碼提示）打的字：送出去但不寫進檔案，而且不管是帳號還是密碼都加進要遮的字。
                // 不能只看「現在是哪一頁」分帳號密碼：伺服器回得慢時，印出來的可能還是上一個畫面
                // guest 不是個人資料：照一般文字送、照錄（不然登入頁「或以 guest 參觀」也會被遮掉）
                line.startsWith(":id ") || (!line.startsWith(":") && line.trim() != "guest" && (page is Page.Login || page is Page.PasswordPrompt)) -> {
                    val secret = line.removePrefix(":id ").trim()
                    recorder.secrets += secret
                    session.send(secret + Keys.ENTER)
                    val label = if (page is Page.PasswordPrompt) "<密碼> ENTER" else "<帳號> ENTER"
                    recorder.sent(null, label)
                    label
                }
                // 帳密從環境變數來（雲端 session 用：使用者在環境設定裡加 PTT_ID、PTT_PASSWORD，不用貼進對話）。
                // 兩個都不印、不寫進檔案，而且都加進要遮的字
                line == ":login" -> {
                    val id = System.getenv("PTT_ID")?.trim()
                    val password = System.getenv("PTT_PASSWORD")
                    if (id.isNullOrEmpty() || password.isNullOrEmpty()) {
                        println("沒有環境變數 PTT_ID、PTT_PASSWORD")
                        continue
                    }
                    recorder.secrets += id
                    recorder.secrets += password
                    session.send(id + Keys.ENTER)
                    recorder.sent(null, "<帳號> ENTER")
                    runCatching { session.awaitPage(timeoutMs = 10_000, after = before) { it is Page.PasswordPrompt } }
                    page = show(session, recorder, "<帳號> ENTER", after = null)
                    if (page !is Page.PasswordPrompt) {
                        println("沒等到「請輸入您的密碼」，密碼沒送出去")
                        continue
                    }
                    before = session.screens.value
                    session.send(password + Keys.ENTER)
                    recorder.sent(null, "<密碼> ENTER")
                    "<密碼> ENTER"
                }
                // 密碼：送出去，不寫進任何檔案（PTT 也不會回顯，不用遮）
                line == ":pw" || line.startsWith(":pw ") -> {
                    val password = if (line == ":pw") readPassword() else line.removePrefix(":pw ")
                    session.send(password + Keys.ENTER)
                    recorder.sent(null, "<密碼> ENTER")
                    "<密碼> ENTER"
                }
                // :ctrl-p、:ctrl-x…：Ctrl 加字母（發文表單是 Ctrl-P、編輯器存檔是 Ctrl-X）
                CTRL.matches(line) -> {
                    val keys = Keys.ctrl(line.last())
                    session.send(keys)
                    val name = "CTRL-" + line.last().uppercaseChar()
                    recorder.sent(Big5Uao.encode(keys), name)
                    name
                }
                line in KEYS -> {
                    val keys = KEYS.getValue(line)
                    session.send(keys)
                    val name = line.removePrefix(":").uppercase()
                    recorder.sent(Big5Uao.encode(keys), name)
                    name
                }
                line.startsWith(":") -> {
                    println("不認得 $line，:help 看指令")
                    continue
                }
                else -> {
                    // 文字裡的 \r 換成 Enter：「Stock\r」就是跟 App 一樣，板名連 Enter 一次送出
                    val text = line.replace("\\r", Keys.ENTER)
                    session.send(text)
                    // 說明欄：Stock\r 記成 "Stock" ENTER
                    val parts = line.split("\\r")
                    val shown = parts.mapIndexed { i, part ->
                        (if (part.isEmpty()) "" else "\"$part\"") + if (i < parts.lastIndex) " ENTER" else ""
                    }.joinToString("").trim()
                    recorder.sent(Big5Uao.encode(text), shown)
                    shown
                }
            }
        } catch (e: Exception) {
            println("送不出去：${e.message}")
            break
        }
        page = show(session, recorder, label, after = before)
    }
    if (session.closed.value) println("連線已經結束")
    recorder.save()
    println("錄影：${recorder.recordingFile.path}")
    println("畫面：${recorder.screensFile.path}")
    session.close()
    scope.cancel()
    http?.dispatcher?.executorService?.shutdown()
}

private val CTRL = Regex(":ctrl-[a-z]")

/** 按鍵指令。 */
private val KEYS = mapOf(
    ":enter" to Keys.ENTER,
    ":up" to Keys.UP,
    ":down" to Keys.DOWN,
    ":left" to Keys.LEFT,
    ":right" to Keys.RIGHT,
    ":pgup" to Keys.PAGE_UP,
    ":pgdn" to Keys.PAGE_DOWN,
    ":home" to Keys.HOME,
    ":end" to Keys.END,
    ":esc" to Keys.ESC,
    ":space" to " ",
    ":tab" to Keys.TAB,
    ":bs" to Keys.BACKSPACE,
)

private fun printHelp() {
    println(
        """
        |指令：
        |  文字           送出那段文字；\r 代表 Enter（例如 Stock\r）
        |                 停在「請輸入代號」時打的就是帳號、停在「請輸入您的密碼」時打的就是密碼（都不會寫進檔案）
        |  :pw            輸入密碼（用 installDist 跑時不會顯示）
        |  :login         用環境變數 PTT_ID、PTT_PASSWORD 登入（雲端 session 用；兩個都不會印出來、不會寫進檔案）
        |  :enter :up :down :left :right :pgup :pgdn :home :end :esc :space :tab :bs   按鍵
        |  :ctrl-p :ctrl-x …  Ctrl 加字母
        |  :note 文字     在錄影裡留一句話（例如「接下來進 Stock 板」）
        |  :save 名稱     把目前畫面另存成 名稱.txt
        |  :quit          結束並存檔
        """.trimMargin(),
    )
}

private fun readPassword(): String =
    System.console()?.readPassword("密碼（不會顯示）：")?.let(::String) ?: run {
        print("密碼（會顯示在畫面上）：")
        readln()
    }

/**
 * 等畫面從 [after] 變了、而且 0.5 秒沒再變才印（5 秒都沒變就印現在的），同時記進 screens 檔。
 * 比 App 的 150 ms 久：人在操作不差這一點，印出來的畫面才不會是回顯到一半的。
 */
private suspend fun show(session: Session, recorder: Recorder, label: String, after: Screen?): Page {
    val page = runCatching { session.awaitQuiet(timeoutMs = 5_000, quietMs = 500, after = after) }
        .getOrElse { ScreenParser.parse(session.screens.value) }
    val screen = session.screens.value
    println("┌" + "─".repeat(80) + "┐")
    // 印出來的也遮（帳號、IP）：終端機的輸出可能被記錄下來（雲端 session 的對話紀錄），不只檔案要遮。
    // 全形字佔兩欄：照欄寬補空白，右邊的框才對得齊（遮蔽不改長度）
    screen.lines.map { Redactor.redact(it, recorder.secrets) }.forEach { line ->
        println("│" + line + " ".repeat((80 - line.sumOf { if (Screen.isWide(it)) 2L else 1L }.toInt()).coerceAtLeast(0)) + "│")
    }
    println("└" + "─".repeat(80) + "┘")
    println("辨識：${page::class.simpleName}")
    recorder.screen(label, page, screen)
    return page
}
