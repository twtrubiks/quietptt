package com.twtrubiks.quietptt.core.ptt

import com.twtrubiks.quietptt.core.terminal.Keys
import com.twtrubiks.quietptt.core.terminal.Screen
import com.twtrubiks.quietptt.core.transport.FakePtt
import com.twtrubiks.quietptt.core.transport.WebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 我的最愛超過一頁（`Session.allFavorites`）。假的看板列表照 pttbbs 翻頁：一頁 20 列（`header_lines = 3`）；
 * PgDn（`board_cmd_pgdn`）游標往下 20，超過最後一筆就停在最後一筆（`psb.c`），已經在最後一筆再按繞回第 1 筆；Home 到第 1 筆；
 * 頁首是 (游標 / 20) * 20。畫面沒變就不送（游標已經在那裡）。
 */
class FavoritesPagesTest {
    private val client = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fakes = mutableListOf<FakePtt>()
    private val sessions = mutableListOf<Session>()

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
        fakes.forEach { it.close() }
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /** [total] 筆的我的最愛，游標一開始在 [cursor]（0 起），[separators] 是分隔線的序號（1 起）。 */
    private fun favorites(total: Int, cursor: Int = 0, separators: Set<Int> = emptySet()): FakePtt {
        var curr = cursor
        fun screen(): String {
            val base = curr / ROWS * ROWS
            val rows = (0 until ROWS).map { r ->
                val i = base + r
                if (i >= total) "" else row(i + 1, cursor = i == curr, separator = (i + 1) in separators)
            }
            return (HEADER + rows + FOOTER).joinToString("\n")
        }
        return FakePtt(screen()) { input ->
            Thread.sleep(LAG_MS)
            val before = screen()
            when (input) {
                Keys.PAGE_DOWN -> curr = if (curr == total - 1) 0 else minOf(curr + ROWS, total - 1)
                Keys.HOME -> curr = 0
                else -> return@FakePtt
            }
            val after = screen()
            if (after != before) show(after)
        }.also { fakes += it.start() }
    }

    private suspend fun connect(ptt: FakePtt): Session {
        val session = Session(WebSocketTransport(client, url = ptt.url), scope)
        sessions += session
        session.connect()
        return session
    }

    private fun all(ptt: FakePtt): List<Board> = runBlocking { withTimeout(TEST_TIMEOUT_MS) { connect(ptt).allFavorites() } }

    @Test
    fun oneShortPageSendsNothing() {
        val ptt = favorites(total = 7)
        assertEquals((1..7).toList(), all(ptt).map { it.index })
        assertEquals(emptyList<String>(), ptt.received)
    }

    @Test
    fun startsFromTheFirstPageAndGoesBackToIt() {
        // 從第 25 個板退回來：游標在第 2 頁。Home 到第 1 頁、PgDn 兩次（第 3 頁沒填滿，不再按）、Home 回第 1 頁
        val ptt = favorites(total = 45, cursor = 24)
        val boards = all(ptt)
        assertEquals((1..45).toList(), boards.map { it.index })
        assertEquals("Board45", boards.last().name)
        assertEquals(listOf(Keys.HOME, Keys.PAGE_DOWN, Keys.PAGE_DOWN, Keys.HOME), ptt.received)
    }

    @Test
    fun fullLastPageIsToldByTheCursorStayingOnIt() {
        // 剛好兩頁：第 2 頁填滿，再按 PgDn 游標移到最後一筆（同一頁，序號沒變大）就知道是最後一頁
        val ptt = favorites(total = 40)
        assertEquals((1..40).toList(), all(ptt).map { it.index })
        assertEquals(listOf(Keys.PAGE_DOWN, Keys.PAGE_DOWN, Keys.HOME), ptt.received)
    }

    @Test
    fun exactlyOneFullPage() {
        // 剛好 20 個：PgDn 只是把游標移到第 20 個，還在第 1 頁，不用 Home
        val ptt = favorites(total = 20)
        assertEquals((1..20).toList(), all(ptt).map { it.index })
        assertEquals(listOf(Keys.PAGE_DOWN), ptt.received)
    }

    @Test
    fun separatorsAreNotBoardsButStillFillThePage() {
        // 第 1 頁有兩條分隔線：看板只有 18 個，但頁面是滿的，要翻下一頁
        val ptt = favorites(total = 25, separators = setOf(5, 20))
        val boards = all(ptt)
        assertEquals((1..25).filter { it != 5 && it != 20 }, boards.map { it.index })
        assertTrue(boards.none { it.name.startsWith("-") })
        assertEquals(listOf(Keys.PAGE_DOWN, Keys.HOME), ptt.received)
    }

    @Test
    fun aSeparatorRowIsNotABoard() {
        val page = ScreenParser.parse(Screen.of((HEADER + listOf(row(1, true), row(2, false, separator = true)) + FOOTER).joinToString("\n")))
        assertEquals(listOf("Board01"), (page as Page.Favorites).boards.map { it.name })
    }

    private companion object {
        const val LAG_MS = 100L
        const val TEST_TIMEOUT_MS = 15_000L
        const val ROWS = 20

        val FIXTURE = Fixtures.text("favorites").lines()
        val HEADER = FIXTURE.take(3)
        val FOOTER = listOf(FIXTURE[23])

        /** 真機那列「      2 ˇMobileComm   資訊 ◎[通訊] …」換掉游標、序號、板名；分隔線照 `brdlist_separator`。 */
        fun row(number: Int, cursor: Boolean, separator: Boolean = false): String {
            val head = (if (cursor) ">" else " ") + "%6d".format(number) + " "
            if (separator) return head + "  " + "-".repeat(12) + " ".repeat(6) + "-".repeat(42)
            return head + "ˇ" + "Board%02d".format(number).padEnd(13) + FIXTURE[4].substring(22)
        }
    }
}
