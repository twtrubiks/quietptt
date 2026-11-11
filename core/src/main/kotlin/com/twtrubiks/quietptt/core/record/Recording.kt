package com.twtrubiks.quietptt.core.record

import java.util.Base64

/**
 * 錄下來的一條 PTT 連線：伺服器送來的 bytes 與送出去的按鍵，各帶從開始算的毫秒數。
 * `tools/capture` 錄（雲端 VM 走 WebSocket，或使用者自己的電腦），測試拿來重播：
 * 把 bytes 餵進 `Terminal` 看 `ScreenParser` 認不認得、假 PTT 照錄到的順序回應。
 *
 * 檔案是純文字，一行一個事件，欄位用 tab 分開：`毫秒  方向  base64  說明`
 * - `<`：伺服器送來的 bytes，原樣（Big5-UAO 與跳脫序列）；說明欄空著
 * - `>`：送出去的按鍵。帳號、密碼不留 bytes（base64 欄寫 `-`），只留說明（`<帳號> ENTER`）
 * - `#` 開頭的是註解（錄的時候用 `:note` 留的話也是）
 */
class Recording(val events: List<Event>) {
    sealed interface Event {
        val ms: Long
    }

    class Received(override val ms: Long, val bytes: ByteArray) : Event

    /** [bytes] 是 null 表示沒留（帳號、密碼）。 */
    class Sent(override val ms: Long, val bytes: ByteArray?, val label: String) : Event

    class Note(override val ms: Long, val text: String) : Event

    fun write(out: Appendable) {
        out.append(HEADER)
        for (event in events) {
            when (event) {
                is Received -> out.append("${event.ms}\t<\t${encode(event.bytes)}\t\n")
                is Sent -> out.append("${event.ms}\t>\t${event.bytes?.let(::encode) ?: "-"}\t${oneLine(event.label)}\n")
                is Note -> out.append("# ${event.ms} ${oneLine(event.text)}\n")
            }
        }
    }

    override fun toString(): String = buildString { write(this) }

    companion object {
        private val HEADER = """
            |# QuietPTT 錄影 v1（tools/capture）
            |# 一行一個事件，tab 分隔：毫秒  方向  base64  說明
            |#   <  伺服器送來的 bytes，原樣（Big5-UAO 與跳脫序列）
            |#   >  送出去的按鍵；帳號、密碼不留 bytes（base64 欄是 -）
            |# 帳號已換成同樣長度的 x，IP 的數字換成 0
            |
        """.trimMargin()

        fun parse(text: String): Recording {
            val events = ArrayList<Event>()
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                if (line.startsWith("#")) {
                    // 「# 毫秒 文字」是 :note 留的；檔頭的說明沒有毫秒，略過
                    val parts = line.removePrefix("#").trim().split(" ", limit = 2)
                    val ms = parts[0].toLongOrNull() ?: continue
                    events += Note(ms, parts.getOrElse(1) { "" })
                    continue
                }
                val fields = line.split("\t")
                require(fields.size >= 3) { "錄影檔格式不對：$line" }
                val ms = fields[0].toLong()
                when (fields[1]) {
                    "<" -> events += Received(ms, decode(fields[2]))
                    ">" -> events += Sent(ms, fields[2].takeIf { it != "-" }?.let(::decode), fields.getOrElse(3) { "" })
                    else -> error("錄影檔格式不對：$line")
                }
            }
            return Recording(events)
        }

        private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

        private fun oneLine(text: String): String = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    }
}
