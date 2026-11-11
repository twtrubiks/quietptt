package com.twtrubiks.quietptt.core.terminal

/** 送給 PTT 的按鍵（vt100 的跳脫序列）。 */
object Keys {
    const val ENTER = "\r"
    const val ESC = "\u001b"
    const val BACKSPACE = "\u007f"
    const val TAB = "\t"
    const val UP = "\u001b[A"
    const val DOWN = "\u001b[B"
    const val RIGHT = "\u001b[C"
    const val LEFT = "\u001b[D"
    const val HOME = "\u001b[1~"
    const val END = "\u001b[4~"
    const val PAGE_UP = "\u001b[5~"
    const val PAGE_DOWN = "\u001b[6~"

    /** Ctrl-L：PTT 清掉畫面整頁重畫（pttbbs `system_key_hook`，哪裡都能按、不算一個鍵）。 */
    const val CTRL_L = "\u000c"

    fun ctrl(letter: Char): String = (letter.uppercaseChar().code - 'A'.code + 1).toChar().toString()
}
