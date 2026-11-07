package com.twtrubiks.quietptt.core.transport

import java.io.Closeable

/**
 * 跟 PTT 之間的一條連線：連上之後是一條雙向的 byte 流，終端模擬（`Terminal`）吃 [read] 吐出來的 bytes，
 * 按鍵用 [write] 送出去。上層不知道底下是 SSH 還是 WebSocket，兩種都吐一樣的 Big5-UAO bytes。
 *
 * 規則：不送 keep-alive、不自己重連。App 退到背景就由上層 [close]，回到前景再開一條新的。
 */
interface Transport : Closeable {
    /** 連上並準備好收資料。失敗丟例外（連不上、host key 對不上、逾時）。 */
    suspend fun connect()

    /** 下一段收到的 bytes；連線結束回傳 null。 */
    suspend fun read(): ByteArray?

    suspend fun write(bytes: ByteArray)

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
    }
}
