package com.twtrubiks.quietptt.core.ptt

import com.twtrubiks.quietptt.core.terminal.Keys
import com.twtrubiks.quietptt.core.terminal.Screen
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 發文：表單、編輯器、存檔提示的辨識，以及 `Session.post` 送了什麼鍵。
 * 畫面照 pttbbs `do_general`／`edit.c` 原始碼排的（真機畫面在 `RealActionScreensTest`）；真機不一樣時以真機為準改 fixture 與 `ScreenParser`。
 */
class PostTest {
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
    fun postFormListsTheCategories() {
        val page = ScreenParser.parse(Screen.of(POST_FORM))
        assertTrue("$page", page is Page.PostForm)
        page as Page.PostForm
        assertEquals("Gossiping", page.board)
        assertEquals(listOf("問卦", "新聞", "爆卦", "公告", "協尋"), page.categories)
        assertFalse(page.titleAsked)
        assertTrue((ScreenParser.parse(Screen.of(TITLE_PROMPT)) as Page.PostForm).titleAsked)
    }

    @Test
    fun editorAndSavePromptsAreRecognized() {
        val editor = ScreenParser.parse(Screen.of(EDITOR))
        assertTrue("$editor", editor is Page.Editor && editor.insertMode && !editor.indentMode)
        val indented = ScreenParser.parse(Screen.of(replaceRow(EDITOR, 23, EDITOR_STATUS.replace("│aipr║", "│aIpr║"))))
        assertTrue("$indented", indented is Page.Editor && indented.indentMode)
        assertTrue(ScreenParser.parse(Screen.of(SAVE_PROMPT)) is Page.SavePrompt)
        assertTrue(ScreenParser.parse(Screen.of(SIGNATURE_PROMPT)) is Page.SignaturePrompt)
        assertTrue(ScreenParser.parse(Screen.of(ANONYMOUS_PROMPT)) is Page.AnonymousPrompt)
        assertTrue(ScreenParser.parse(Screen.of(CONFIRM_PROMPT)) is Page.ConfirmPrompt)
    }

    @Test
    fun postOptionsOpensTheFormThenCancelsIt() = runBlocking {
        val ptt = gossiping()
        val session = connect(ptt)
        val options = withTimeout(TEST_TIMEOUT_MS) { session.postOptions("Gossiping") }
        assertEquals(listOf("問卦", "新聞", "爆卦", "公告", "協尋"), options.categories)
        // Ctrl-P、種類不選、標題空白（pttbbs：標題空的就取消）
        assertEquals(listOf(Keys.ctrl('P'), "\r", "\r"), ptt.received)
    }

    @Test
    fun postTypesCategoryTitleBodyThenSaves() = runBlocking {
        val ptt = gossiping()
        val session = connect(ptt)
        val list = withTimeout(TEST_TIMEOUT_MS) {
            session.post("Gossiping", NewPost(category = 0, title = "有沒有安靜的八卦", body = "安安\n\n大家好", signature = '2'))
        }
        assertTrue(list.isBoard("Gossiping"))
        assertEquals(
            listOf(Keys.ctrl('P'), "1\r", "有沒有安靜的八卦\r", "安安\r\r大家好", Keys.ctrl('X'), "s\r", "2\r", "\r"),
            ptt.received,
        )
    }

    @Test
    fun postAddsTheFooterAfterABlankLine() = runBlocking {
        val ptt = gossiping()
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) {
            session.post("Gossiping", NewPost(category = null, title = "有尾巴", body = "安安\n", footer = "-----\nSent from QuietPTT"))
        }
        assertEquals("安安\r\r-----\rSent from QuietPTT", ptt.received[3])
    }

    @Test
    fun aProblemAfterSavingSaysThePostMayBeOut() = runBlocking {
        // 答了 s 之後停在認不出來的畫面：文章可能已經發出去了，不能當成一般的失敗讓人重試（會發兩篇）
        val ptt = gossiping(strangeAfterSave = true)
        val session = connect(ptt)
        try {
            withTimeout(TEST_TIMEOUT_MS) { session.post("Gossiping", NewPost(category = null, title = "怪怪的", body = "")) }
            fail("認不出存檔之後的畫面不該成功")
        } catch (e: PostUnconfirmedException) {
            assertTrue(e.message!!, e.message!!.contains("可能已經發出去了"))
        }
    }

    @Test
    fun noCategoryIsJustEnter() = runBlocking {
        val ptt = gossiping()
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) { session.post("Gossiping", NewPost(category = null, title = "沒分類", body = "")) }
        assertEquals(listOf(Keys.ctrl('P'), "\r", "沒分類\r", Keys.ctrl('X'), "s\r", "0\r", "\r"), ptt.received)
    }

    @Test
    fun notAllowedToPostSaysWhy() = runBlocking {
        val ptt = gossiping(canPost = false)
        val session = connect(ptt)
        try {
            withTimeout(TEST_TIMEOUT_MS) { session.postOptions("Gossiping") }
            fail("不能發文該丟例外")
        } catch (e: UnexpectedScreenException) {
            assertEquals("無法發文: 您在本看板沒有發表文章權限", e.message)
        }
        // 按掉訊息回到列表
        assertEquals(listOf(Keys.ctrl('P'), "\r"), ptt.received)
    }

    @Test
    fun imageUrlsFollowBePtt() {
        assertEquals("https://i.imgur.com/AbCdEfG.jpg", imageUrl("https://imgur.com/AbCdEfG"))
        assertEquals("https://i.imgur.com/AbCdEfG.jpg", imageUrl("https://i.imgur.com/AbCdEfG"))
        assertEquals("https://i.imgur.com/AbCdEfG.png", imageUrl("https://i.imgur.com/AbCdEfG.png"))
        assertEquals("https://i.imgur.com/AbCdEfG.gif", imageUrl("https://i.imgur.com/AbCdEfG.gifv"))
        assertEquals("https://i.imgur.com/AbCdEfG.gif?1", imageUrl("https://i.imgur.com/AbCdEfG.gifv?1"))
        assertEquals("https://i.imgur.com/AbCdEfG.jpg", imageUrl("https://imgur.dcard.tw/AbCdEfG"))
        assertEquals("https://example.com/a/b.JPG?x=1", imageUrl("https://example.com/a/b.JPG?x=1"))
        // http 換成 https（Android 不准明碼連線；使用者回報 http://i.imgur.com/…jpg 載不出來，同一張 https 可以）
        assertEquals("https://i.imgur.com/AbCdEfG.jpg", imageUrl("http://i.imgur.com/AbCdEfG.jpg"))
        assertEquals("https://i.imgur.com/AbCdEfG.jpg", imageUrl("http://imgur.com/AbCdEfG"))
        assertEquals("https://example.com/a/b.png", imageUrl("HTTP://example.com/a/b.png"))
        assertEquals(null, imageUrl("https://imgur.com/a/AbCdEfG"))
        assertEquals(null, imageUrl("https://imgur.com/gallery/AbCdEfG"))
        assertEquals(null, imageUrl("https://www.ptt.cc/bbs/Gossiping/M.1.A.1.html"))
        assertEquals(null, imageUrl("https://example.com/page.html"))
    }

    private suspend fun connect(ptt: FakePtt): Session {
        fakes += ptt.start()
        val session = Session(WebSocketTransport(client, url = ptt.url), scope)
        sessions += session
        session.connect()
        return session
    }

    /** 停在 Gossiping 列表的假 PTT：Ctrl-P 進表單、Enter 兩次取消；打了標題進編輯器，Ctrl-X 存檔、問簽名檔、回列表。 */
    private fun gossiping(canPost: Boolean = true, strangeAfterSave: Boolean = false): FakePtt {
        var at = LIST
        return FakePtt(LIST) { input ->
            Thread.sleep(LAG_MS)
            val next = when {
                at == LIST && input == Keys.ctrl('P') -> if (canPost) POST_FORM else NO_PERMISSION
                at == NO_PERMISSION && input == "\r" -> LIST
                at == POST_FORM && input.endsWith("\r") -> TITLE_PROMPT
                at == TITLE_PROMPT && input == "\r" -> LIST
                at == TITLE_PROMPT && input.endsWith("\r") -> EDITOR
                at.endsWith(EDITOR_STATUS) && input == Keys.ctrl('X') -> SAVE_PROMPT
                // 打內文：編輯器把字畫上去（哪一列都好，畫面變了就行）
                at.endsWith(EDITOR_STATUS) -> replaceRow(EDITOR, 0, input.replace("\r", " "))
                at == SAVE_PROMPT && input == "s\r" -> if (strangeAfterSave) STRANGE else SIGNATURE_PROMPT
                at == SIGNATURE_PROMPT && input.endsWith("\r") -> SAVED
                at == SAVED && input == "\r" -> LIST
                else -> null
            }
            if (next != null) {
                at = next
                show(next)
            }
        }
    }

    private companion object {
        const val LAG_MS = 300L
        const val TEST_TIMEOUT_MS = 10_000L

        val LIST = Fixtures.text("article-list").replace("看板《MobileComm》", "看板《Gossiping》")

        /** 認不出來、也不會再變的畫面。 */
        val STRANGE = (listOf("系統忙碌中") + List(23) { "" }).joinToString("\n")

        /** pttbbs `do_general`：第 19 列「發表文章於【 板名 】 敘述 看板」、第 21 列「種類：」（每個分類 `%4.4s`，也就是兩個中文字）。 */
        val POST_FORM: String = replaceRow(
            replaceRow(List(24) { "" }.joinToString("\n"), 19, "發表文章於【 Gossiping 】 [八卦] 安靜的八卦板 看板"),
            21,
            "種類：1.問卦 2.新聞 3.爆卦 4.公告 5.協尋 (1-5或不選)",
        )
        val TITLE_PROMPT = replaceRow(POST_FORM, 22, "標題：[問卦] ")
        const val EDITOR_STATUS = " 編輯文章  (^Z)說明 (^X)存檔 (^C)色碼 (^V)彩色   ║插入│aipr║  1:  1"
        val EDITOR = replaceRow(List(24) { "" }.joinToString("\n"), 23, EDITOR_STATUS)
        val SAVE_PROMPT: String = replaceRow(
            replaceRow(replaceRow(List(24) { "" }.joinToString("\n"), 0, "【 檔案處理 】"), 1, "[S]儲存 (A)放棄 (E)繼續 (R/W/D)讀寫刪暫存檔"),
            2,
            "確定要儲存檔案嗎？ ",
        )
        val SIGNATURE_PROMPT = replaceRow(List(24) { "" }.joinToString("\n"), 0, "請選擇簽名檔 (1-9, 0=不加 x=隨機)[0]: ")
        val ANONYMOUS_PROMPT = replaceRow(List(24) { "" }.joinToString("\n"), 1, "請輸入你想用的ID，或直接按[Enter]暱名，或按[r][R]用真名：")
        val CONFIRM_PROMPT = replaceRow(replaceRow(List(24) { "" }.joinToString("\n"), 3, "使用名稱: someone"), 4, "確定[y/N]? ")
        val SAVED = replaceRow(List(24) { "" }.joinToString("\n"), 23, "                               請按任意鍵繼續")
        val NO_PERMISSION = replaceRow(LIST, 23, " ◆ 無法發文: 您在本看板沒有發表文章權限" + " ".repeat(20) + "[按任意鍵繼續]")
    }
}
