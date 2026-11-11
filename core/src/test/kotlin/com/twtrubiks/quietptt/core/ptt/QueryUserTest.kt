package com.twtrubiks.quietptt.core.ptt

import com.twtrubiks.quietptt.core.terminal.Keys
import com.twtrubiks.quietptt.core.transport.FakePtt
import com.twtrubiks.quietptt.core.transport.FakePtt.Companion.replaceRow
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查詢使用者（`Session.queryUser`）：主選單 `t` Enter →【休閒聊天區】游標移到「查詢網友」Enter → 打代號 Enter → 讀 `my_query` 的畫面 →
 * Enter、左鍵回主選單。主選單是真機的 fixture，其他畫面照 pttbbs `menu.c`／`talk.c` 排的，**沒有真機畫面**。
 */
class QueryUserTest {
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
    fun talkMenuIsRecognized() {
        assertTrue(ScreenParser.parse(com.twtrubiks.quietptt.core.terminal.Screen.of(TALK_MENU)) is Page.TalkMenu)
    }

    @Test
    fun queryMovesToTheItemTypesTheIdAndReadsTheFields() = runBlocking {
        val ptt = bbs()
        val session = connect(ptt)
        val info = withTimeout(TEST_TIMEOUT_MS) { session.queryUser("someone") }!!
        assertEquals("someone", info.id)
        assertEquals("小明", info.nickname)
        assertEquals("1234 次 (同天內只計一次)", info.fields.first { it.first == "登入次數" }.second)
        assertEquals("56 篇 (退:0)", info.fields.first { it.first == "有效文章" }.second)
        assertEquals("不在站上", info.fields.first { it.first == "目前動態" }.second)
        assertEquals(listOf("我的名片第一行", "第二行"), info.plan)
        // 游標在 Users（第一項），查詢網友在下一列：下移一列 Enter；不按 q（新版選單 q 是回上一層）
        assertEquals(listOf("t\r", Keys.DOWN + "\r", "someone\r", "\r", Keys.LEFT), ptt.received)
        assertTrue("${session.page}", session.page is Page.MainMenu)
    }

    @Test
    fun unknownIdComesBackToTheMenu() = runBlocking {
        val ptt = bbs()
        val session = connect(ptt)
        assertNull(withTimeout(TEST_TIMEOUT_MS) { session.queryUser("nobody") })
        assertEquals(listOf("t\r", Keys.DOWN + "\r", "nobody\r", Keys.LEFT), ptt.received)
    }

    private suspend fun connect(ptt: FakePtt): Session {
        fakes += ptt.start()
        val session = Session(WebSocketTransport(client, url = ptt.url), scope)
        sessions += session
        session.connect()
        return session
    }

    private fun bbs(): FakePtt {
        var at = MAIN_MENU
        return FakePtt(at) { input ->
            Thread.sleep(LAG_MS)
            val next = when {
                at == MAIN_MENU && input == "t\r" -> TALK_MENU
                at == TALK_MENU && input == Keys.DOWN + "\r" -> QUERY_PROMPT
                at == QUERY_PROMPT && input == "someone\r" -> QUERY_RESULT
                // 沒有這個人：my_query 什麼都不印，回到選單
                at == QUERY_PROMPT && input == "nobody\r" -> TALK_MENU
                at == QUERY_RESULT && input == "\r" -> TALK_MENU
                at == TALK_MENU && input == Keys.LEFT -> MAIN_MENU
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
        const val TEST_TIMEOUT_MS = 10_000L

        val MAIN_MENU: String = Fixtures.text("main-menu")

        /** 【休閒聊天區】（pttbbs `talklist`，排法照主選單：游標「>」在第 20 欄、項目在第 22 欄）。 */
        val TALK_MENU: String = listOf(
            "【休閒聊天區】                     批踢踢實業坊",
            "", "", "", "", "", "", "", "", "", "", "",
            "                    > (U)sers         線上使用者列表",
            "                      (Q)uery         查詢網友",
            "                      (T)alk          找人聊聊",
            "                      (C)hat          多人聊天室",
            "                      (W)atch Pet     查詢寵物",
            "", "", "", "", "", "",
            " 聊天說話 【 立冬 】   11/7 週六 17:54 | xxxxxxxxxxxx | 線上26407人    (h)說明",
        ).joinToString("\n")

        val QUERY_PROMPT: String = replaceRow("【查詢網友】", 1, "請輸入使用者代號: ")

        /** `my_query`：第 1 列起「《》」那幾列、名片檔，最下面 `pressanykey`。 */
        val QUERY_RESULT: String = listOf(
            "【查詢網友】",
            "《ＩＤ暱稱》someone (小明)               《經濟狀況》小康",
            "《登入次數》1234 次 (同天內只計一次)    《有效文章》56 篇 (退:0)",
            "《目前動態》不在站上                    《私人信箱》最近無新信件",
            "《上次上站》11/07/2026 17:54:01 Sat     《上次故鄉》1.2.3.*",
            "《 五子棋 》    0 勝     0 敗     0 和  《象棋戰績》    0 勝     0 敗     0 和",
            "",
            "我的名片第一行",
            "第二行",
            "", "", "", "", "", "", "", "", "", "", "", "", "", "",
            "                        請按任意鍵繼續                       ",
        ).joinToString("\n")
    }
}
