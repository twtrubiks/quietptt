package com.twtrubiks.quietptt.core.record

import com.twtrubiks.quietptt.core.ptt.Fixtures
import com.twtrubiks.quietptt.core.terminal.Big5Uao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingTest {
    private val menu = Fixtures.text("main-menu").trimEnd('\n').lines()

    /** 清掉畫面、從左上角開始印 [lines]（[fromRow] 是第一列印在第幾列，從 1 算）。 */
    private fun draw(lines: List<String>, fromRow: Int = 1, clear: Boolean = true): ByteArray =
        Big5Uao.encode((if (clear) "\u001b[H\u001b[2J" else "") + "\u001b[$fromRow;1H" + lines.joinToString("\r\n"))

    private fun sent(ms: Long, label: String) = Recording.Sent(ms, null, label)

    @Test
    fun aResponseIsEverythingBetweenTwoKeys() {
        val recording = Recording(
            listOf(
                Recording.Received(0, draw(listOf("歡迎"))),
                sent(1_000, "第一次"),
                Recording.Received(1_040, draw(listOf("甲"))),
                Recording.Received(1_050, draw(listOf("乙"), fromRow = 2, clear = false)),
                Recording.Received(1_170, draw(listOf("丙"), fromRow = 3, clear = false)),
                sent(2_000, "沒回應"),
                sent(3_000, "第二次"),
                Recording.Received(3_025, draw(listOf("丁"))),
            ),
        )
        val responses = Timing.responses(recording)
        assertEquals(listOf("第一次", "第二次"), responses.map { it.label })
        assertEquals(listOf(40L, 25L), responses.map { it.firstMs })
        assertEquals(listOf(3, 1), responses.map { it.chunks })
        assertEquals(listOf(10L, 120L), responses[0].gaps.map { it.ms })
        // 認不出來的畫面 Session 會繼續等，不算畫到一半
        assertTrue(responses[0].gaps.none { it.partial })
    }

    @Test
    fun aRecognisedPageThatIsStillBeingDrawnIsPartial() {
        val recording = Recording(
            listOf(
                sent(0, "回主選單"),
                Recording.Received(30, draw(menu.take(8))),
                Recording.Received(120, draw(menu.drop(8), fromRow = 9, clear = false)),
                sent(1_000, "再畫一次"),
                Recording.Received(1_030, draw(menu)),
                // 內容沒變（只是游標回到原位）：不算
                Recording.Received(1_130, Big5Uao.encode("\u001b[1;1H")),
            ),
        )
        val (split, whole) = Timing.responses(recording)
        assertEquals("MainMenu", split.page)
        assertEquals(listOf(90L), split.gaps.map { it.ms })
        assertTrue(split.gaps.single().partial)
        assertFalse(whole.gaps.single().partial)

        val report = Timing.report(recording, quietMs = 150)
        assertTrue(report, report.contains("送鍵 2 次，有回應的 2 次"))
        // 80 ms 的話 90 ms 那個間隔會被當成畫完；150 ms（現在）不會
        assertTrue(report, report.contains("  80 ms：2 個（100.0%），畫到一半 1 個、在 1 次回應裡"))
        assertTrue(report, report.contains(" 150 ms（現在）：0 個（0.0%），畫到一半 0 個、在 0 次回應裡"))
        assertTrue(report, report.contains("回主選單　MainMenu → MainMenu　← 畫到一半"))
    }

    @Test
    fun noResponsesStillGivesAReport() {
        assertEquals("時間量測：送鍵 0 次，有回應的 0 次（現在畫面 150 ms 沒變就當它畫完）", Timing.report(Recording(emptyList()), quietMs = 150))
    }
}
