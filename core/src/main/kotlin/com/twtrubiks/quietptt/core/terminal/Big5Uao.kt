package com.twtrubiks.quietptt.core.terminal

/**
 * Big5-UAO（Unicode 補完計畫）編碼：PTT 送來的中文是這個，標準 Big5 之外還多了日文假名、簡體字等。
 * 對照表 `uao250-b2u.txt`（big5 → unicode，每行 `0xA4A4 0x4E2D`）放在 resources，App 與 JVM 測試都讀同一份。
 *
 * 所有雙位元組字都佔終端的 2 欄（全形）。
 */
object Big5Uao {
    const val REPLACEMENT = '�'

    /** 第一個 byte（lead）的範圍。 */
    fun isLead(b: Int): Boolean = b in 0x81..0xFE

    /** 第二個 byte（trail）的範圍。 */
    fun isTrail(b: Int): Boolean = b in 0x40..0x7E || b in 0xA1..0xFE

    fun decode(lead: Int, trail: Int): Char {
        val ch = toUnicode[(lead shl 8) or trail]
        return if (ch == '\u0000') REPLACEMENT else ch
    }

    /** 整段 bytes 解成字串（測試、工具用；終端模擬是一個 byte 一個 byte 餵 [Terminal]）。 */
    fun decode(bytes: ByteArray): String = buildString {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (isLead(b) && i + 1 < bytes.size && isTrail(bytes[i + 1].toInt() and 0xFF)) {
                append(decode(b, bytes[i + 1].toInt() and 0xFF))
                i += 2
            } else {
                append(if (b < 0x80) b.toChar() else REPLACEMENT)
                i++
            }
        }
    }

    /** 送出去用（推文、搜尋）：ASCII 原樣，中文查反向表，表裡沒有的換成 `?`。 */
    fun encode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length * 2)
        for (ch in text) {
            if (ch.code < 0x80) {
                out.write(ch.code)
            } else {
                val code = toBig5[ch]
                if (code == null) {
                    out.write('?'.code)
                } else {
                    out.write(code shr 8)
                    out.write(code and 0xFF)
                }
            }
        }
        return out.toByteArray()
    }

    private val toUnicode: CharArray by lazy {
        val table = CharArray(0x10000)
        val stream = checkNotNull(Big5Uao::class.java.getResourceAsStream("/uao250-b2u.txt")) { "找不到 uao250-b2u.txt" }
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val sp = line.indexOf(' ')
                if (sp < 0) continue
                val big5 = line.substring(2, sp).toInt(16)
                val unicode = line.substring(sp + 3).trim().toInt(16)
                table[big5] = unicode.toChar()
            }
        }
        table
    }

    /** 反向表。同一個 Unicode 對到好幾個 Big5 碼時，標準 Big5 範圍（0xA140 以上）優先。 */
    private val toBig5: Map<Char, Int> by lazy {
        val map = HashMap<Char, Int>(toUnicode.size)
        for (code in toUnicode.indices) {
            val ch = toUnicode[code]
            if (ch == '\u0000') continue
            val existing = map[ch]
            if (existing == null || (existing < STANDARD_START && code >= STANDARD_START)) map[ch] = code
        }
        map
    }

    private const val STANDARD_START = 0xA140
}
