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
 * 回文到看板：`y` 之後的提示、回文表單、引言太多的辨識，以及 `Session.replyPost` 送了什麼鍵。
 * 畫面照 pttbbs `do_reply`／`do_generalboardreply`／`do_post_article`／`edit.c` 原始碼排的（列表、表單上面的板規、存檔提示是真機的），
 * 真機畫面在 `RealActionScreensTest`；真機不一樣時以真機為準改。
 */
class ReplyPostTest {
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

    // ---- 畫面辨識 ----

    @Test
    fun replyPromptsAreRecognized() {
        val prompt = ScreenParser.parse(Screen.of(REPLY_PROMPT))
        assertTrue("$prompt", prompt is Page.ReplyPrompt && prompt.toBoard && !prompt.noReply)
        // 不能寄信的人只有 F、Q
        val boardOnly = ScreenParser.parse(Screen.of(replaceRow(LIST_ON_153897, 22, "▲ 回應至 (F)看板 (Q)取消？[F] ")))
        assertTrue("$boardOnly", boardOnly is Page.ReplyPrompt && boardOnly.toBoard)
        val cannot = ScreenParser.parse(Screen.of(CANNOT_REPLY))
        assertTrue("$cannot", cannot is Page.ReplyPrompt && !cannot.toBoard && !cannot.noReply)
        val noReply = ScreenParser.parse(Screen.of(NO_REPLY))
        assertTrue("$noReply", noReply is Page.ReplyPrompt && noReply.noReply)
        // 列表最下面那列的「(y)回應」不算
        assertTrue(ScreenParser.parse(Screen.of(LIST)) is Page.ArticleList)
        // 「無法發文」的訊息蓋在提示下面一列：是任意鍵
        assertTrue(ScreenParser.parse(Screen.of(replaceRow(REPLY_PROMPT, 23, " ◆ 無法發文: 未滿註冊天數" + " ".repeat(30) + "[按任意鍵繼續]"))) is Page.PressAnyKey)
    }

    @Test
    fun replyFormStepsAreReadFromTheBottom() {
        val keep = ScreenParser.parse(Screen.of(FORM))
        assertTrue("$keep", keep is Page.ReplyForm && keep.step == Page.ReplyForm.Step.KEEP_TITLE && keep.board == "MobileComm")
        assertEquals(Page.ReplyForm.Step.NEW_TITLE, (ScreenParser.parse(Screen.of(NEW_TITLE)) as Page.ReplyForm).step)
        assertEquals(Page.ReplyForm.Step.QUOTE, (ScreenParser.parse(Screen.of(QUOTE_KEPT)) as Page.ReplyForm).step)
        // 板規印完、標題還沒畫出來
        assertEquals(Page.ReplyForm.Step.OTHER, (ScreenParser.parse(Screen.of(replaceRow(replaceRow(FORM, 20, ""), 21, ""))) as Page.ReplyForm).step)
        // 一般發文的表單（有「種類：」）還是 PostForm
        assertTrue(ScreenParser.parse(Fixtures.screen("post-form")) is Page.PostForm)
    }

    @Test
    fun tooMuchQuoteIsRecognizedOverTheSavePrompt() {
        val page = ScreenParser.parse(Screen.of(QUOTE_RATIO))
        assertTrue("$page", page is Page.QuoteRatioPrompt)
    }

    // ---- 按鍵 ----

    @Test
    fun footerGoesAfterTheQuote() = runBlocking {
        val ptt = mobileComm()
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) {
            session.replyPost("MobileComm", 153897, ORIGINAL, PostReply("推一個", footer = "-----\nSent from QuietPTT"))
        }
        // 內文打在引言上面；到檔案最後一行的行尾（引言下面那列空的），Enter 一下再接
        assertEquals(
            listOf("推一個\r\r", Keys.ctrl('T') + Keys.ctrl('E') + "\r-----\rSent from QuietPTT", Keys.ctrl('X'), "s\r"),
            ptt.received.drop(5).take(4),
        )
    }

    @Test
    fun footerFollowsTheBodyWithoutAQuote() = runBlocking {
        val ptt = mobileComm()
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) {
            session.replyPost("MobileComm", 153897, ORIGINAL, PostReply("推一個", quote = false, footer = "-----\nSent from QuietPTT"))
        }
        assertEquals(listOf("推一個\r\r-----\rSent from QuietPTT", Keys.ctrl('X'), "s\r"), ptt.received.drop(5).take(3))
    }

    @Test
    fun replyKeepsTheTitleQuotesAndTypesAboveTheQuote() = runBlocking {
        val ptt = mobileComm()
        val session = connect(ptt)
        val list = withTimeout(TEST_TIMEOUT_MS) { session.replyPost("MobileComm", 153897, ORIGINAL, PostReply("我也遇到\n一樣的問題", signature = '1')) }
        assertTrue(list.isBoard("MobileComm"))
        // 游標移過去、y、看板、採用原標題、引用；內文打在引言上面（後面空一行）；存檔、簽名檔、任意鍵
        assertEquals(
            listOf("153897\r", "y", "f\r", "y\r", "y\r", "我也遇到\r一樣的問題\r\r", Keys.ctrl('X'), "s\r", "1\r", "\r"),
            ptt.received,
        )
    }

    @Test
    fun replyWithANewTitleAndNoQuote() = runBlocking {
        val ptt = mobileComm()
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) {
            session.replyPost("MobileComm", 153897, ORIGINAL, PostReply("推一個", title = "Re: 改個標題", quote = false))
        }
        assertEquals(
            listOf("153897\r", "y", "f\r", "n\r", Keys.ctrl('Y') + "Re: 改個標題\r", "n\r", "推一個", Keys.ctrl('X'), "s\r", "0\r", "\r"),
            ptt.received,
        )
    }

    @Test
    fun aChangedBoardIsNotRepliedTo() = runBlocking {
        val ptt = mobileComm()
        val session = connect(ptt)
        try {
            withTimeout(TEST_TIMEOUT_MS) {
                session.replyPost("MobileComm", 153897, ORIGINAL.copy(title = "[討論] 別篇"), PostReply("推一個"))
            }
            fail("對不上不該回")
        } catch (e: UnexpectedScreenException) {
            assertTrue(e.message!!, e.message!!.contains("不是要回的那篇"))
        }
        // 只移了游標，沒按 y
        assertEquals(listOf("153897\r"), ptt.received)
    }

    @Test
    fun aBoardThatCannotBeRepliedToSaysWhy() = runBlocking {
        val ptt = mobileComm(onY = CANNOT_REPLY)
        val session = connect(ptt)
        try {
            withTimeout(TEST_TIMEOUT_MS) { session.replyPost("MobileComm", 153897, ORIGINAL, PostReply("推一個")) }
            fail("無法回應至看板不該成功")
        } catch (e: UnexpectedScreenException) {
            assertTrue(e.message!!, e.message!!.contains("無法回應至看板"))
        }
        // 答 q（不改寄給作者）
        assertEquals(listOf("153897\r", "y", "q\r"), ptt.received)
    }

    @Test
    fun aNoReplyBoardIsAnsweredNo() = runBlocking {
        val ptt = mobileComm(onY = NO_REPLY)
        val session = connect(ptt)
        try {
            withTimeout(TEST_TIMEOUT_MS) { session.replyPost("MobileComm", 153897, ORIGINAL, PostReply("推一個")) }
            fail("不開放回文不該成功")
        } catch (e: UnexpectedScreenException) {
            assertEquals("這個板不開放回文", e.message)
        }
        // 「要改回信給作者嗎」答 n
        assertEquals(listOf("153897\r", "y", "n\r"), ptt.received)
    }

    @Test
    fun aClosedArticleIsNotRepliedTo() = runBlocking {
        val ptt = mobileComm(onY = CLOSED)
        val session = connect(ptt)
        try {
            withTimeout(TEST_TIMEOUT_MS) { session.replyPost("MobileComm", 153897, ORIGINAL, PostReply("推一個")) }
            fail("已結案不該回")
        } catch (e: UnexpectedScreenException) {
            assertTrue(e.message!!, e.message!!.contains("結案"))
        }
        // `vmsg` 按 Enter（不是 y）就是不回
        assertEquals(listOf("153897\r", "y", "\r"), ptt.received)
    }

    @Test
    fun tooMuchQuoteGoesBackToTheEditorAndAbandons() = runBlocking {
        val ptt = mobileComm(quoteRatio = true)
        val session = connect(ptt)
        try {
            withTimeout(TEST_TIMEOUT_MS) { session.replyPost("MobileComm", 153897, ORIGINAL, PostReply("+1")) }
            fail("引言太多不該成功")
        } catch (e: PostUnconfirmedException) {
            fail("還沒存，不能說可能已經發出去了：${e.message}")
        } catch (e: UnexpectedScreenException) {
            assertTrue(e.message!!, e.message!!.contains("引言"))
        }
        // e 回編輯器，Ctrl-X、a 放棄，任意鍵回列表；不答 w（強制寫入）
        assertEquals(
            listOf("153897\r", "y", "f\r", "y\r", "y\r", "+1\r\r", Keys.ctrl('X'), "s\r", "e\r", Keys.ctrl('X'), "a\r", "\r"),
            ptt.received,
        )
        assertFalse(ptt.received.contains("w\r"))
    }

    @Test
    fun aProblemAfterSavingSaysThePostMayBeOut() = runBlocking {
        val ptt = mobileComm(strangeAfterSave = true)
        val session = connect(ptt)
        try {
            withTimeout(TEST_TIMEOUT_MS) { session.replyPost("MobileComm", 153897, ORIGINAL, PostReply("推一個")) }
            fail("認不出存檔之後的畫面不該成功")
        } catch (e: PostUnconfirmedException) {
            assertTrue(e.message!!, e.message!!.contains("可能已經發出去了"))
        }
    }

    private suspend fun connect(ptt: FakePtt): Session {
        fakes += ptt.start()
        val session = Session(WebSocketTransport(client, url = ptt.url), scope)
        sessions += session
        session.connect()
        return session
    }

    /**
     * 停在 MobileComm 列表（游標在 153909）的假 PTT：打序號移游標，`y` 之後是 [onY]（預設「回應至 (F)看板 …」），
     * f → 回文表單 → 採用原標題／新標題 → 引用原文 → 編輯器 → Ctrl-X、s → 簽名檔 → 「順利貼出佈告」任意鍵 → 列表。
     * [quoteRatio]：s 之後是「引言比例超過 80%」；[strangeAfterSave]：s 之後停在認不出來的畫面。
     */
    private fun mobileComm(onY: String = REPLY_PROMPT, quoteRatio: Boolean = false, strangeAfterSave: Boolean = false): FakePtt {
        var at = LIST
        return FakePtt(LIST) { input ->
            Thread.sleep(LAG_MS)
            val next = when {
                at == LIST && input == "153897\r" -> LIST_ON_153897
                at == LIST_ON_153897 && input == "y" -> onY
                at == REPLY_PROMPT && input == "f\r" -> FORM
                (at == CANNOT_REPLY && input == "q\r") || (at == NO_REPLY && input == "n\r") || (at == CLOSED && input == "\r") -> LIST_ON_153897
                at == FORM && input == "y\r" -> QUOTE_KEPT
                at == FORM && input == "n\r" -> NEW_TITLE
                at == NEW_TITLE && input.startsWith(Keys.ctrl('Y')) && input.endsWith("\r") ->
                    replaceRow(replaceRow(FORM, 21, "新標題：" + input.drop(1).dropLast(1)), 22, QUOTE_ROW)
                asksQuote(at) && input == "y\r" -> EDITOR_QUOTED
                asksQuote(at) && input == "n\r" -> EDITOR
                isEditor(at) && input == Keys.ctrl('X') -> SAVE_PROMPT
                // 打內文：編輯器把字畫上去（哪一列都好，畫面變了就行）
                isEditor(at) -> replaceRow(at, 5, input.replace("\r", " "))
                at == SAVE_PROMPT && input == "s\r" -> when {
                    quoteRatio -> QUOTE_RATIO
                    strangeAfterSave -> STRANGE
                    else -> SIGNATURE_PROMPT
                }
                at == SAVE_PROMPT && input == "a\r" -> ABORTED
                at == QUOTE_RATIO && input == "e\r" -> EDITOR_QUOTED
                at == SIGNATURE_PROMPT && input.endsWith("\r") -> SAVED
                (at == SAVED || at == ABORTED) && input == "\r" -> LIST_ON_153897
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
        const val TEST_TIMEOUT_MS = 15_000L

        val LIST = Fixtures.text("article-list")
        val LIST_ON_153897 = LIST.replace(">153909", " 153909").replace(" 153897", ">153897")

        /** 列表上那一列（App 從看板點進文章時帶著的）。 */
        val ORIGINAL = ArticleSummary(153897, '+', 34, date = "11/06", author = "chanifa", title = "[討論] s24u 綠線案例")

        /** `do_generalboardreply`：第 22 列 `getdata`，列表留著。 */
        val REPLY_PROMPT = replaceRow(LIST_ON_153897, 22, "▲ 回應至 (F)看板 (M)作者信箱 (B)二者皆是 (Q)取消？[F] ")
        val CANNOT_REPLY = replaceRow(LIST_ON_153897, 22, "▲ 無法回應至看板。 改回應至 (M)作者信箱 (Q)取消？[Q] ")

        /** `do_reply`：`BRD_NOREPLY` 的 `vans`（最下面那列）。 */
        val NO_REPLY = replaceRow(LIST_ON_153897, 23, "很抱歉, 本板不開放回覆文章，要改回信給作者嗎？ [y/N]: ")

        /** `do_reply`：`FILE_SOLVED` 的 `vmsg`。 */
        val CLOSED = replaceRow(LIST_ON_153897, 23, " ◆ 此篇文章已結案, 是否真的要回應?(y/N)" + " ".repeat(20) + "[按任意鍵繼續]")

        /** `do_post_article`：板規（真機的發文畫面上半）、第 19 列「發表文章於」、`do_reply_title(20, …)`。 */
        val FORM: String = replaceRow(
            replaceRow(
                replaceRow(Fixtures.text("post-form"), 19, "發表文章於【 MobileComm 】 [通訊] 手機o平板o資費 請進! 看板"),
                20,
                "原標題: Re: [討論] s24u 綠線案例",
            ),
            21,
            "採用原標題[Y/n]? ",
        )
        val NEW_TITLE = replaceRow(FORM, 21, "新標題：Re: [討論] s24u 綠線案例")

        const val QUOTE_ROW = "請問要引用原文嗎(Y/N/All/Repost)？[Y] "

        /** 標題答完，`vedit2` → `do_quote` 在第 22 列問引用原文。 */
        val QUOTE_KEPT = replaceRow(replaceRow(FORM, 21, "採用原標題[Y/n]? y"), 22, QUOTE_ROW)

        fun asksQuote(screen: String): Boolean = screen.lines().getOrNull(22) == QUOTE_ROW

        const val EDITOR_STATUS = " 編輯文章  (^X)存檔 (^C)色碼         (Esc-h)按鍵 (^Z)說明║插入│aipr║  1:  1"
        val EDITOR = replaceRow(List(24) { "" }.joinToString("\n"), 23, EDITOR_STATUS)

        /** 引用了原文：第一列「※ 引述《作者 (暱稱)》之銘言：」、下面每列「: 」開頭；游標在 (0,0)。 */
        val EDITOR_QUOTED = replaceRow(replaceRow(EDITOR, 0, "※ 引述《chanifa (股師)》之銘言："), 1, ": 綠線又出現了")

        fun isEditor(screen: String): Boolean = screen.lines().getOrNull(23) == EDITOR_STATUS

        val SAVE_PROMPT = Fixtures.text("post-save-prompt")

        /** `check_quote`：存檔提示沒清，第 4 列說明、第 12 列從第 12 欄問。 */
        val QUOTE_RATIO = replaceRow(
            replaceRow(replaceRow(SAVE_PROMPT, 2, "確定要儲存檔案嗎？ s"), 4, "本篇文章的引言比例超過 80%，請您做些微的修正："),
            12,
            " ".repeat(12) + "(E)繼續編輯 (W)強制寫入？[E] ",
        )
        val ABORTED = Fixtures.text("post-aborted")
        val SIGNATURE_PROMPT = replaceRow(List(24) { "" }.joinToString("\n"), 0, "請選擇簽名檔 (1-9, 0=不加 x=隨機)[0]: ")
        val SAVED = Fixtures.text("post-saved")

        /** 認不出來、也不會再變的畫面。 */
        val STRANGE = (listOf("系統忙碌中") + List(23) { "" }).joinToString("\n")
    }
}
