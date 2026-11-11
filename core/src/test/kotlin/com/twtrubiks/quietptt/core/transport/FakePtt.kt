package com.twtrubiks.quietptt.core.transport

import com.twtrubiks.quietptt.core.terminal.Big5Uao
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 假的 ws.ptt.cc：MockWebServer 開一個 WebSocket，連上就送 [firstScreen]，之後每收到一個 frame 就交給 [onInput] 決定換什麼畫面。
 *
 * 畫面是 24 列文字（跟 fixture 一樣），送出去前用跳脫序列清畫面、一列一列定位，再編成 Big5-UAO 的 binary frame，
 * 跟真的 PTT 一樣是 bytes。`Session.send` 一次寫一個 frame，所以 [received] 的每一筆就是 App 送出的一次按鍵或文字。
 */
class FakePtt(
    private val firstScreen: String,
    private val onInput: FakePtt.(String) -> Unit = {},
) : WebSocketListener(), Closeable {
    private val server = MockWebServer()

    @Volatile
    private var socket: WebSocket? = null

    /** App 送來的每一個 frame（解成文字）。 */
    val received: MutableList<String> = CopyOnWriteArrayList()

    /** 給 `WebSocketTransport` 的網址（ws://，不是 wss://）。 */
    val url: String get() = server.url("/bbs").toString().replaceFirst("http", "ws")

    fun start(): FakePtt {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(this).build())
        server.start()
        return this
    }

    /** 連線時的 HTTP 請求（看 Origin）。 */
    fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "沒有收到連線" }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket = webSocket
        show(firstScreen)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val text = Big5Uao.decode(bytes.toByteArray())
        received += text
        onInput(text)
    }

    /** 整個畫面換成 [screen]；[cursorRow] 是畫完之後游標停在第幾列（0 起，行首），不給就停在最後寫的地方。 */
    fun show(screen: String, cursorRow: Int? = null) =
        sendRaw(render(screen) + (cursorRow?.let { Big5Uao.encode("\u001b[${it + 1};1H") } ?: ByteArray(0)))

    /** 原樣送出 bytes（測一個字被拆在兩個 frame 之類的）。 */
    fun sendRaw(bytes: ByteArray) {
        checkNotNull(socket) { "還沒有連線" }.send(bytes.toByteString())
    }

    /** 站方把連線關掉。 */
    fun hangUp() {
        socket?.close(1000, "bye")
    }

    override fun close() {
        // 伺服器端的 WebSocket 沒有 call，不能 cancel()
        runCatching { socket?.close(1001, null) }
        server.close()
    }

    companion object {
        /** 清畫面，然後每一列用 CUP 定位再寫字（不靠換行，滿 80 欄的列也不會捲動）。 */
        fun render(screen: String): ByteArray = Big5Uao.encode(
            buildString {
                append("\u001b[H\u001b[2J")
                screen.lines().take(24).forEachIndexed { row, line ->
                    if (line.isNotBlank()) append("\u001b[${row + 1};1H").append(line.trimEnd())
                }
            },
        )

        /** 把 [screen] 第 [row] 列換成 [text]（不足 24 列的補空列）。 */
        fun replaceRow(screen: String, row: Int, text: String): String {
            val lines = screen.lines().toMutableList()
            while (lines.size < 24) lines += ""
            lines[row] = text
            return lines.joinToString("\n")
        }
    }
}
