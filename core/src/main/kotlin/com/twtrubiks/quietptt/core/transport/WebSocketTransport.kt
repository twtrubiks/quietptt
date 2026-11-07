package com.twtrubiks.quietptt.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * WebSocket 連線：`wss://ws.ptt.cc/bbs`，要帶 `Origin: https://term.ptt.cc` 不然會被拒。
 * 畫面以 binary frame 送來，一個 frame 就是一段 Big5-UAO bytes。
 *
 * [client] 由 App 提供（整個 App 一個 OkHttpClient）；`pingInterval` 要維持預設的 0，不送 ping。
 */
class WebSocketTransport(
    private val client: OkHttpClient,
    private val url: String = PttHost.WS_URL,
    private val origin: String = PttHost.WS_ORIGIN,
) : Transport {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private var socket: WebSocket? = null

    override suspend fun connect() {
        val opened = CompletableDeferred<Unit>()
        val request = Request.Builder().url(url).header("Origin", origin).build()
        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    incoming.trySend(bytes.toByteArray())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // 伺服器只送 binary；文字 frame 照 Latin-1 當 bytes 收，不丟掉
                    incoming.trySend(text.toByteArray(Charsets.ISO_8859_1))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    incoming.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!opened.isCompleted) opened.completeExceptionally(t)
                    incoming.close(t)
                }
            },
        )
        withTimeout(Transport.CONNECT_TIMEOUT_MS.toLong()) { opened.await() }
    }

    override suspend fun read(): ByteArray? = incoming.receiveCatching().getOrNull()

    override suspend fun write(bytes: ByteArray) {
        val s = checkNotNull(socket) { "還沒連線" }
        check(s.send(bytes.toByteString())) { "連線已經關閉" }
    }

    override fun close() {
        socket?.close(NORMAL_CLOSURE, null)
        incoming.close()
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
