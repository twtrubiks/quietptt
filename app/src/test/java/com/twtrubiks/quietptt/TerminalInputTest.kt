package com.twtrubiks.quietptt

import org.junit.Assert.assertEquals
import org.junit.Test

/** 終端畫面的打字框與字母鍵送什麼：照打的送、Ctrl 只換英文字母、Enter 接在最後。 */
class TerminalInputTest {
    @Test
    fun textIsSentAsTyped() {
        assertEquals("1234", terminalInput("1234", ctrl = false, enter = false))
        assertEquals("推文 :) #1aB", terminalInput("推文 :) #1aB", ctrl = false, enter = false))
    }

    @Test
    fun enterGoesLast() {
        assertEquals("y\r", terminalInput("y", ctrl = false, enter = true))
        // 打字框空的也能只送 Enter
        assertEquals("\r", terminalInput("", ctrl = false, enter = true))
    }

    @Test
    fun ctrlTurnsLettersIntoControlKeys() {
        assertEquals("\u0018", terminalInput("x", ctrl = true, enter = false))
        assertEquals("\u0018", terminalInput("X", ctrl = true, enter = false))
        // Enter 不受 Ctrl 影響
        assertEquals("\u0003\r", terminalInput("c", ctrl = true, enter = true))
    }

    @Test
    fun ctrlLeavesDigitsSymbolsAndChineseAlone() {
        assertEquals("\u00181/中", terminalInput("x1/中", ctrl = true, enter = false))
    }
}
