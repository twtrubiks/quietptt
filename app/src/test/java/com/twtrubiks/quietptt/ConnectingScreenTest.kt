package com.twtrubiks.quietptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectingScreenTest {
    @Test
    fun onlyAutomaticLoginsShowTheConnectingScreen() {
        assertEquals(AutoLoginProgress("someone", loggingIn = false), ConnectionState.Connecting("someone", automatic = true).autoLoginProgress())
        assertEquals(AutoLoginProgress("someone", loggingIn = true), ConnectionState.LoggingIn("someone", automatic = true).autoLoginProgress())
        // 在登入頁按的：登入頁自己轉圈
        assertNull(ConnectionState.Connecting("someone").autoLoginProgress())
        assertNull(ConnectionState.LoggingIn("someone").autoLoginProgress())
        assertNull(ConnectionState.Offline("連線中斷").autoLoginProgress())
    }

    @Test
    fun blocksStayInsideTheTerminalWindow() {
        val cells = PromptBlocks.cells
        assertEquals("同一格不能出現兩次", cells.size, cells.toSet().size)
        for ((column, row) in cells) {
            val left = PromptBlocks.LEFT + column * PromptBlocks.CELL
            val top = PromptBlocks.TOP + row * PromptBlocks.CELL
            // 終端視窗是 x 28～80、y 34～74（ic_launcher_foreground.xml），四邊再留 4 的圓角
            assertTrue("($column, $row)", left >= 32f && left + PromptBlocks.CELL <= 76f)
            assertTrue("($column, $row)", top >= 38f && top + PromptBlocks.CELL <= 70f)
        }
        // 游標是最後一列的一橫，在「>」的右邊
        val cursor = cells.drop(PromptBlocks.CURSOR_FROM)
        assertTrue(cursor.all { it.second == 4 })
        assertTrue(cursor.minOf { it.first } > cells.take(PromptBlocks.CURSOR_FROM).maxOf { it.first })
    }

    @Test
    fun blocksLightUpOneByOneThenTheCursorBlinks() {
        val count = PromptBlocks.cells.size
        // 一開始是空的終端（跟系統啟動畫面一樣）
        assertTrue((0 until count).all { PromptBlocks.alpha(it, 0) == 0f })
        // 照順序亮：前面的先亮好
        val midway = PromptBlocks.STEP_MS * 5
        assertEquals(1f, PromptBlocks.alpha(0, midway))
        assertTrue(PromptBlocks.alpha(5, midway) < 1f)
        assertEquals(0f, PromptBlocks.alpha(6, midway))
        // 拼好了
        val done = PromptBlocks.ASSEMBLED_MS
        assertTrue((0 until count).all { PromptBlocks.alpha(it, done) == 1f })
        // 之後只有游標閃，「>」一直亮著
        val off = done + PromptBlocks.BLINK_MS
        val on = done + 2 * PromptBlocks.BLINK_MS
        for (index in 0 until count) {
            val cursor = index >= PromptBlocks.CURSOR_FROM
            assertEquals("第 $index 格", if (cursor) 0f else 1f, PromptBlocks.alpha(index, off))
            assertEquals("第 $index 格", 1f, PromptBlocks.alpha(index, on))
        }
    }
}
