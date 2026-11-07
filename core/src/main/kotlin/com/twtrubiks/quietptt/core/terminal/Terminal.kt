package com.twtrubiks.quietptt.core.terminal

/**
 * 終端模擬：吃連線送來的 bytes，照 vt100 的跳脫序列與 Big5-UAO 更新 [ScreenBuffer]。
 *
 * 只做 PTT 會用到的：游標移動（CUP、CUU/CUD/CUF/CUB）、清畫面／清列（ED、EL）、顏色（SGR）、存取游標。
 * 其他序列認得出來就忽略，不會壞掉。
 *
 * 全形字的兩個 byte 中間可能夾跳脫序列（PTT 的彩色 ANSI 常這樣，一個字左右兩半不同色），
 * 所以 lead byte 先記著（[pendingLead]），等 trail byte 來才落字，顏色用後半的。
 */
class Terminal(rows: Int = Screen.ROWS, cols: Int = Screen.COLS) {
    val buffer = ScreenBuffer(rows, cols)

    private enum class State { GROUND, ESC, CSI, CHARSET }

    private var state = State.GROUND
    private val params = StringBuilder()
    private var pendingLead = -1
    private var attr = Attr.DEFAULT

    fun feed(bytes: ByteArray) {
        for (b in bytes) feed(b.toInt() and 0xFF)
    }

    fun snapshot(): Screen = buffer.snapshot()

    private fun feed(b: Int) {
        when (state) {
            State.GROUND -> ground(b)
            State.ESC -> escape(b)
            State.CHARSET -> state = State.GROUND
            State.CSI -> when (b) {
                in 0x30..0x3F -> params.append(b.toChar())
                in 0x20..0x2F -> Unit
                in 0x40..0x7E -> {
                    state = State.GROUND
                    csi(b.toChar())
                }
                else -> state = State.GROUND
            }
        }
    }

    private fun ground(b: Int) {
        when {
            b == 0x1B -> state = State.ESC
            pendingLead >= 0 && Big5Uao.isTrail(b) -> {
                buffer.put(Big5Uao.decode(pendingLead, b), attr, wide = true)
                pendingLead = -1
            }
            pendingLead >= 0 -> {
                // 不是合法的尾碼：前一個 byte 當壞字，這個 byte 重新判斷
                buffer.put(Big5Uao.REPLACEMENT, attr, wide = false)
                pendingLead = -1
                ground(b)
            }
            Big5Uao.isLead(b) -> pendingLead = b
            b == 0x7F -> Unit
            b < 0x20 -> control(b)
            else -> buffer.put(b.toChar(), attr, wide = false)
        }
    }

    private fun control(b: Int) {
        when (b) {
            0x0D -> buffer.carriageReturn()
            0x0A, 0x0B, 0x0C -> buffer.lineFeed()
            0x08 -> buffer.backspace()
            0x09 -> buffer.tab()
            else -> Unit // BEL、NUL 之類的忽略
        }
    }

    private fun escape(b: Int) {
        state = State.GROUND
        when (b.toChar()) {
            '[' -> {
                params.setLength(0)
                state = State.CSI
            }
            '7' -> buffer.saveCursor()
            '8' -> buffer.restoreCursor()
            'M' -> buffer.reverseLineFeed()
            'D' -> buffer.lineFeed()
            'E' -> {
                buffer.carriageReturn()
                buffer.lineFeed()
            }
            '(', ')' -> state = State.CHARSET
            else -> Unit
        }
    }

    private fun csi(final: Char) {
        val raw = params.toString()
        if (raw.startsWith("?")) return // 私有模式（游標顯示之類的）不影響畫面
        val nums = raw.split(';').map { it.toIntOrNull() }
        fun n(i: Int, default: Int = 1): Int = nums.getOrNull(i)?.takeIf { it > 0 } ?: default
        when (final) {
            'H', 'f' -> buffer.moveTo(n(0) - 1, n(1) - 1)
            'A' -> buffer.move(-n(0), 0)
            'B' -> buffer.move(n(0), 0)
            'C' -> buffer.move(0, n(0))
            'D' -> buffer.move(0, -n(0))
            'G' -> buffer.moveTo(buffer.row, n(0) - 1)
            'd' -> buffer.moveTo(n(0) - 1, buffer.col)
            'J' -> buffer.eraseDisplay(nums.getOrNull(0) ?: 0, attr)
            'K' -> buffer.eraseLine(nums.getOrNull(0) ?: 0, attr)
            'm' -> sgr(nums.map { it ?: 0 })
            's' -> buffer.saveCursor()
            'u' -> buffer.restoreCursor()
            else -> Unit
        }
    }

    private fun sgr(codes: List<Int>) {
        for (code in codes) {
            attr = when (code) {
                0 -> Attr.DEFAULT
                1 -> attr.copy(bold = true)
                5 -> attr.copy(blink = true)
                7 -> attr.copy(reverse = true)
                22 -> attr.copy(bold = false)
                25 -> attr.copy(blink = false)
                27 -> attr.copy(reverse = false)
                in 30..37 -> attr.copy(fg = code - 30)
                39 -> attr.copy(fg = Attr.DEFAULT_FG)
                in 40..47 -> attr.copy(bg = code - 40)
                49 -> attr.copy(bg = Attr.DEFAULT_BG)
                else -> attr
            }
        }
    }
}
