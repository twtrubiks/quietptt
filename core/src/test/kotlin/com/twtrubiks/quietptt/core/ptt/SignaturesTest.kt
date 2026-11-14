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
 * 讀自己的簽名檔（`Session.signatures`）：主選單 `u` Enter →【個人設定區】游標移到「【個人檔案】」Enter → 游標移到「編輯簽名檔」Enter →
 * 讀 `showsignature` 印的畫面（放不下就答 n 翻頁，繞回第一批為止）→ Enter（取消）、左鍵兩次回主選單。
 * 主選單是真機的 fixture，其他畫面照 pttbbs `menu.c`／`user.c` 排的，**沒有真機畫面**。
 */
class SignaturesTest {
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

    @Test
    fun userMenusAreRecognized() {
        assertTrue(ScreenParser.parse(Screen.of(USER_MENU)) is Page.UserMenu)
        assertTrue(ScreenParser.parse(Screen.of(FILES_MENU)) is Page.UserMenu)
    }

    @Test
    fun signaturesOnOneScreenAreSplitAtTheHeaders() {
        val found = ScreenParser.signatures(Screen.of(FIRST_PAGE))
        assertEquals(listOf(1, 2, 4), found.map { it.number })
        assertEquals(listOf("第一個簽名檔", "", "  有空列、前面有空白"), found[0].lines)
        assertEquals(listOf("只有一列"), found[1].lines)
        // 畫面到底了：第 4 個只印出前 3 列
        assertEquals(listOf("四之一", "四之二", "四之三"), found[2].lines)
    }

    @Test
    fun aScreenWithoutSignaturesIsEmpty() {
        assertEquals(emptyList<SignatureFile>(), ScreenParser.signatures(Screen.of(NO_SIGNATURES)))
    }

    @Test
    fun readsEveryPageUntilItWrapsAndComesBackToTheMainMenu() = runBlocking {
        val ptt = bbs(FIRST_PAGE)
        val session = connect(ptt)
        val found = withTimeout(TEST_TIMEOUT_MS) { session.signatures() }
        assertEquals(listOf(1, 2, 4, 7), found.map { it.number })
        // 第 4 個在第一批被截掉，第二批重印了完整的
        assertEquals(listOf("四之一", "四之二", "四之三", "四之四", "四之五", "四之六"), found[2].lines)
        assertEquals(listOf("第七個"), found[3].lines)
        // 游標在第一項：個人檔案往下 3 列、編輯簽名檔往下 1 列；翻兩次（第二次繞回第一批）；Enter 取消、左鍵兩次。沒有送過 e、d
        assertEquals(
            listOf("u\r", Keys.DOWN.repeat(3) + "\r", Keys.DOWN + "\r", "n\r", "n\r", "\r", Keys.LEFT, Keys.LEFT),
            ptt.received,
        )
        assertTrue("${session.page}", session.page is Page.MainMenu)
    }

    @Test
    fun onePageIsNotTurned() = runBlocking {
        val ptt = bbs(ONLY_PAGE)
        val session = connect(ptt)
        val found = withTimeout(TEST_TIMEOUT_MS) { session.signatures() }
        assertEquals(listOf(3), found.map { it.number })
        assertEquals(listOf(listOf("唯一的簽名檔")), found.map { it.lines })
        assertEquals(listOf("u\r", Keys.DOWN.repeat(3) + "\r", Keys.DOWN + "\r", "\r", Keys.LEFT, Keys.LEFT), ptt.received)
        assertTrue("${session.page}", session.page is Page.MainMenu)
    }

    @Test
    fun noSignaturesAtAll() = runBlocking {
        val ptt = bbs(NO_SIGNATURES)
        val session = connect(ptt)
        assertEquals(emptyList<SignatureFile>(), withTimeout(TEST_TIMEOUT_MS) { session.signatures() })
        assertTrue("${session.page}", session.page is Page.MainMenu)
    }

    private suspend fun connect(ptt: FakePtt): Session {
        fakes += ptt.start()
        val session = Session(WebSocketTransport(client, url = ptt.url), scope)
        sessions += session
        session.connect()
        return session
    }

    /** [first] 是進編輯簽名檔看到的第一個畫面；是 [FIRST_PAGE] 的話答 n 換 [SECOND_PAGE]、再答 n 繞回來。 */
    private fun bbs(first: String): FakePtt {
        var at = MAIN_MENU
        return FakePtt(at) { input ->
            Thread.sleep(LAG_MS)
            val next = when {
                at == MAIN_MENU && input == "u\r" -> USER_MENU
                at == USER_MENU && input == Keys.DOWN.repeat(3) + "\r" -> FILES_MENU
                at == FILES_MENU && input == Keys.DOWN + "\r" -> first
                at == FIRST_PAGE && input == "n\r" -> SECOND_PAGE
                at == SECOND_PAGE && input == "n\r" -> FIRST_PAGE
                (at == FIRST_PAGE || at == SECOND_PAGE || at == ONLY_PAGE || at == NO_SIGNATURES) && input == "\r" -> FILES_MENU
                at == FILES_MENU && input == Keys.LEFT -> USER_MENU
                at == USER_MENU && input == Keys.LEFT -> MAIN_MENU
                else -> null
            }
            if (next != null) {
                at = next
                show(next)
            }
        }
    }

    companion object {
        const val LAG_MS = 100L
        const val TEST_TIMEOUT_MS = 20_000L

        val MAIN_MENU: String = Fixtures.text("main-menu")

        /** 【個人設定區】（pttbbs `userlist`，排法照主選單：游標「>」在第 20 欄、項目在第 22 欄）。 */
        val USER_MENU: String = listOf(
            "【個人設定區】                     批踢踢實業坊",
            "", "", "", "", "", "", "", "", "", "", "",
            "                    > (U)Customize    個人化設定",
            "                      (I)nfo          設定個人資料與密碼",
            "                      (V)Login View   選擇進站畫面",
            "                      (M)y Files      【個人檔案】 (名片,簽名檔...)",
            "                      (L)My Logs      【個人記錄】 (最近上線...)",
            "                      (R)egister      新增帳號認證",
            "                      (2)FA           設定兩階段認證",
            "", "", "", "",
            " 個人設定 【 立冬 】   11/7 週六 17:54 | xxxxxxxxxxxx | 線上26407人    (h)說明",
        ).joinToString("\n")

        /** 【個人檔案】（pttbbs `myfilelist`）。 */
        val FILES_MENU: String = listOf(
            "【個人檔案】                       批踢踢實業坊",
            "", "", "", "", "", "", "", "", "", "", "",
            "                    > (Q)ueryEdit     編輯名片檔",
            "                      (S)ignature     編輯簽名檔",
            "", "", "", "", "", "", "", "", "",
            " 個人檔案 【 立冬 】   11/7 週六 17:54 | xxxxxxxxxxxx | 線上26407人    (h)說明",
        ).joinToString("\n")

        /** `showsignature` 的第一批：第 2 列起，印到第 23 列放不下為止（第 4 個只印了 3 列）；還有下一批，問句有「(N)翻頁」。 */
        val FIRST_PAGE: String = (
            listOf(
                "簽名檔 (E)編輯 (D)刪除 (N)翻頁 (Q)取消？[Q] ",
                "",
                "【 簽名檔.1 】",
                "第一個簽名檔",
                "",
                "  有空列、前面有空白",
                "", "", "",
                "【 簽名檔.2 】",
                "只有一列",
                "", "", "", "", "",
                "", "", "", "",
                "【 簽名檔.4 】",
                "四之一",
                "四之二",
                "四之三",
            )
            ).joinToString("\n")

        /** 第二批：第 4 個重印完整的、再來是第 7 個。 */
        val SECOND_PAGE: String = listOf(
            "簽名檔 (E)編輯 (D)刪除 (N)翻頁 (Q)取消？[Q] ",
            "",
            "【 簽名檔.4 】",
            "四之一",
            "四之二",
            "四之三",
            "四之四",
            "四之五",
            "四之六",
            "【 簽名檔.7 】",
            "第七個",
        ).joinToString("\n")

        val ONLY_PAGE: String = listOf(
            "簽名檔 (E)編輯 (D)刪除 (Q)取消？[Q] ",
            "",
            "【 簽名檔.3 】",
            "唯一的簽名檔",
        ).joinToString("\n")

        val NO_SIGNATURES: String = "簽名檔 (E)編輯 (D)刪除 (Q)取消？[Q] "
    }
}
