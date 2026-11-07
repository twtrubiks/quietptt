package com.twtrubiks.quietptt.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTest {
    private val terminal = Terminal()

    private fun feed(text: String, vararg big5: Byte) {
        terminal.feed(text.toByteArray(Charsets.ISO_8859_1))
        if (big5.isNotEmpty()) terminal.feed(big5)
    }

    @Test
    fun printsAsciiAndMovesCursor() {
        feed("hello\r\nworld")
        val screen = terminal.snapshot()
        assertEquals("hello", screen.line(0).trimEnd())
        assertEquals("world", screen.line(1).trimEnd())
        assertEquals(1, screen.cursorRow)
        assertEquals(5, screen.cursorCol)
    }

    @Test
    fun cursorPositionIsOneBased() {
        feed("\u001b[3;5HX")
        val screen = terminal.snapshot()
        assertEquals('X', screen.cells[2][4].ch)
        assertEquals("    X", screen.line(2).trimEnd())
    }

    @Test
    fun big5CharactersTakeTwoColumns() {
        // 中文
        feed("", 0xA4.toByte(), 0xA4.toByte(), 0xA4.toByte(), 0xE5.toByte())
        val screen = terminal.snapshot()
        assertEquals("中文", screen.line(0).trimEnd())
        assertTrue(screen.cells[0][1].wideTail)
        assertEquals(4, screen.cursorCol)
        // 照欄位切：第 2 欄起是「文」
        assertEquals("文", screen.text(0, 2, 4))
    }

    @Test
    fun escapeSequenceBetweenTheTwoBytesOfOneCharacter() {
        // 一個字的左半紅、右半藍：lead byte 之後先來 SGR，再來 trail byte
        terminal.feed(byteArrayOf(0x1b, '['.code.toByte(), '3'.code.toByte(), '1'.code.toByte(), 'm'.code.toByte(), 0xA4.toByte()))
        terminal.feed(byteArrayOf(0x1b, '['.code.toByte(), '3'.code.toByte(), '4'.code.toByte(), 'm'.code.toByte(), 0xA4.toByte()))
        val screen = terminal.snapshot()
        assertEquals("中", screen.line(0).trimEnd())
        assertEquals(4, screen.cells[0][0].attr.fg)
    }

    @Test
    fun sgrSetsColorsAndResets() {
        feed("\u001b[1;33;44mA\u001b[mB")
        val screen = terminal.snapshot()
        assertEquals(Attr(fg = 3, bg = 4, bold = true), screen.cells[0][0].attr)
        assertEquals(Attr.DEFAULT, screen.cells[0][1].attr)
    }

    @Test
    fun eraseLineAndDisplay() {
        feed("abcdef\r\nsecond\u001b[1;3H\u001b[K")
        var screen = terminal.snapshot()
        assertEquals("ab", screen.line(0).trimEnd())
        assertEquals("second", screen.line(1).trimEnd())
        feed("\u001b[2J")
        screen = terminal.snapshot()
        assertTrue(screen.lines.all { it.isBlank() })
    }

    @Test
    fun lineFeedAtTheBottomScrolls() {
        for (i in 0 until 24) feed("line$i\r\n")
        val screen = terminal.snapshot()
        assertEquals("line1", screen.line(0).trimEnd())
        assertEquals("line23", screen.line(22).trimEnd())
        assertEquals(23, screen.cursorRow)
    }

    @Test
    fun overwritingHalfOfAWideCharacterClearsTheOtherHalf() {
        feed("", 0xA4.toByte(), 0xA4.toByte())
        feed("\u001b[1;2Hx")
        val screen = terminal.snapshot()
        assertEquals(" x", screen.line(0).trimEnd())
    }

    @Test
    fun privateModeSequencesAreIgnored() {
        feed("\u001b[?25lA\u001b[?25h")
        assertEquals("A", terminal.snapshot().line(0).trimEnd())
    }
}
