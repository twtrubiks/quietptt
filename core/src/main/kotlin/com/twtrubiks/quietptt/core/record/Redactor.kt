package com.twtrubiks.quietptt.core.record

import com.twtrubiks.quietptt.core.terminal.Big5Uao

/**
 * 錄影、畫面快照存檔前把個人資料換掉：帳號換成同樣長度的 `x`（不分大小寫），IPv4 的數字換成 `0`。
 * 長度不變，畫面的欄位不會跑掉。
 *
 * bytes 版本照終端模擬的方式掃：跳脫序列（顏色、游標移動）當透明的，帳號中間夾了顏色也找得到；
 * Big5 雙位元組字整個當分隔，不會把字的後半（0x40～0x7E 剛好是英文字母）當成帳號的一部分換掉。
 */
object Redactor {
    fun redact(text: String, account: String?): String = redact(text, listOfNotNull(account))

    fun redact(bytes: ByteArray, account: String?): ByteArray = redact(bytes, listOfNotNull(account))

    /** [accounts] 裡的每一個都換掉（錄影工具分不清登入途中打的是帳號還是密碼時，兩個都當帳號遮）。 */
    fun redact(text: String, accounts: Collection<String>): String {
        var out = text
        // 長的先換：一個是另一個的一部分時，不會只換掉一半
        for (account in accounts.filter { it.isNotEmpty() }.sortedByDescending { it.length }) {
            // 前後不能是英數字：帳號是 ptt 的話，「ptt.cc」裡的 ptt 也會被換掉，但「pttxx」不會。
            // 前面是顏色碼的結尾（「m」）不算：編輯器把文章裡的 ESC 顯示成「*」，自己的推文列是「*[33m帳號*[m」（真機）；
            // 文字裡留著真的跳脫序列（「\u001b[33m帳號」）也一樣
            val word = Regex("(?:(?<![A-Za-z0-9])|(?<=$COLOR_CODE))" + Regex.escape(account) + "(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
            out = word.replace(out, "x".repeat(account.length))
        }
        return IPV4.replace(out) { m -> m.value.replace(DIGIT, "0") }
    }

    fun redact(bytes: ByteArray, accounts: Collection<String>): ByteArray {
        // 每個「看得到的英數字」在 bytes 裡的位置；跳脫序列跳過、Big5 字與控制字元放一個分隔
        val positions = ArrayList<Int>(bytes.size)
        val visible = StringBuilder(bytes.size)
        var state = State.GROUND
        var pendingLead = false
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            when (state) {
                State.ESC -> state = if (b == '['.code) State.CSI else if (b == '('.code || b == ')'.code) State.CHARSET else State.GROUND
                State.CSI -> if (b in 0x40..0x7E) state = State.GROUND
                State.CHARSET -> state = State.GROUND
                State.GROUND -> when {
                    b == 0x1B -> state = State.ESC
                    pendingLead -> {
                        pendingLead = false
                        separator(positions, visible)
                    }
                    Big5Uao.isLead(b) -> pendingLead = true
                    b in 0x20..0x7E -> {
                        positions += i
                        visible.append(b.toChar())
                    }
                    else -> separator(positions, visible)
                }
            }
        }
        val replaced = redact(visible.toString(), accounts)
        val out = bytes.copyOf()
        for (k in replaced.indices) {
            val pos = positions[k]
            if (pos >= 0 && replaced[k] != visible[k]) out[pos] = replaced[k].code.toByte()
        }
        return out
    }

    private fun separator(positions: MutableList<Int>, visible: StringBuilder) {
        positions += -1
        visible.append('\u0000')
    }

    private enum class State { GROUND, ESC, CSI, CHARSET }

    // 顏色碼（ESC 或編輯器顯示的「*」、「[」、數字與分號、結尾的字母）；放在 lookbehind 裡，長度要有上限
    private const val COLOR_CODE = """[\u001b*]\[[0-9;]{0,16}[A-Za-z]"""

    private val IPV4 = Regex("""(?<![\d.])\d{1,3}(?:\.\d{1,3}){3}(?![\d.])""")
    private val DIGIT = Regex("""\d""")
}
