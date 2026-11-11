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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 寄信、回信（`Session.sendMail`／`replyMail`）：畫面辨識與送了什麼鍵。
 * 主選單、私人信件區、信箱列表是真機的 fixture；寄信、回信的畫面照 pttbbs `mail.c`（`m_send`、`mail_reply`、`do_reply_title`、`hold_mail`）
 * 與 `edit.c`（`do_quote`、`write_file`）排的，**沒有真機畫面**。沒寫到的組合不回應（測試就會等到逾時而失敗）。
 */
class MailWriteTest {
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
    fun mailScreensAreRecognized() {
        fun step(screen: String) = (ScreenParser.parse(Screen.of(screen)) as Page.MailForm).step
        assertEquals(Page.MailForm.Step.RECIPIENT, step(RECIPIENT))
        assertEquals(Page.MailForm.Step.SUBJECT, step(SUBJECT))
        assertEquals(Page.MailForm.Step.QUOTA, step(QUOTA))
        assertEquals(Page.MailForm.Step.KEEP_TITLE, step(KEEP_TITLE))
        assertEquals(Page.MailForm.Step.NEW_TITLE, step(NEW_TITLE))
        // 「採用原標題」那列還在，第 22 列問引用原文：問的是引用
        assertEquals(Page.MailForm.Step.QUOTE, step(QUOTE))
        assertEquals(Page.MailForm.Step.REREGISTERED, step(REREGISTERED))
        assertEquals(Page.MailForm.Step.GROUP_REPLY, step(GROUP_REPLY))
        assertTrue(ScreenParser.parse(Screen.of(BACKUP)) is Page.BackupPrompt)
        // 簽名檔的畫面還在、第 22 列問自存底稿：問的是自存底稿（列號最下面的提示）
        assertTrue(ScreenParser.parse(Screen.of(replaceRow(BACKUP, 0, SIGNATURE_ROW))) is Page.BackupPrompt)
        // 回信畫面上 PTT 的訊息（vmsg）是任意鍵
        assertTrue(ScreenParser.parse(Screen.of(GIVE_UP)) is Page.PressAnyKey)
    }

    @Test
    fun sendMailLooksUpTheRecipientThenTypesSubjectAndBody() {
        val ptt = bbs(MAIN_MENU)
        val id = run(ptt) { sendMail(NewMail(to = "SomeOne", title = "你好", body = "安安\n\n晚安")) }
        assertEquals("someone", id)
        assertEquals(
            listOf(
                // 先查收信人（跟查詢網友一樣），回到主選單
                "t\r", Keys.DOWN + "\r", "SomeOne\r", "\r", Keys.LEFT,
                // 私人信件區：游標在 Read，往下一項是「站內寄信」；代號用 PTT 上的大小寫
                "M\r", Keys.DOWN + "\r", "someone\r", "你好\r",
                "安安\r\r晚安", Keys.ctrl('X'), "s\r", "0\r", "n\r", "\r",
            ),
            ptt.received,
        )
    }

    @Test
    fun aRecipientPttWouldCompleteToSomeoneElseIsNotMailed() {
        // PTT 的代號補完：打「some」只有一個人以它開頭時會換成 someone，不能寄
        val ptt = bbs(MAIN_MENU)
        val error = failure(ptt) { sendMail(NewMail(to = "some", title = "你好", body = "")) }
        assertEquals("PTT 上沒有 some 這個帳號（PTT 會把它補成 someone），沒有寄", error.message)
        assertEquals(listOf("t\r", Keys.DOWN + "\r", "some\r", "\r", Keys.LEFT), ptt.received)
    }

    @Test
    fun anUnknownRecipientIsNotMailed() {
        val ptt = bbs(MAIN_MENU)
        val error = failure(ptt) { sendMail(NewMail(to = "nobody", title = "你好", body = "")) }
        assertEquals("PTT 上沒有 nobody 這個帳號，沒有寄", error.message)
        assertEquals(listOf("t\r", Keys.DOWN + "\r", "nobody\r", Keys.LEFT), ptt.received)
    }

    @Test
    fun aFullMailboxIsSaidWithoutThePrompt() {
        val ptt = bbs(MAIN_MENU, recipient = FULL_MAILBOX)
        val error = failure(ptt) { sendMail(NewMail(to = "someone", title = "你好", body = "")) }
        assertEquals("使用者 [someone] 信箱已滿，無法收信", error.message)
        assertEquals(listOf("M\r", Keys.DOWN + "\r", "someone\r", "\r"), ptt.received.drop(5))
    }

    @Test
    fun noFreeMailLeftIsDeclinedNotPaid() {
        val ptt = bbs(MAIN_MENU, recipient = QUOTA)
        val error = failure(ptt) { sendMail(NewMail(to = "someone", title = "你好", body = "")) }
        assertEquals("今日免費額度(10封)已滿，寄此封信需 10 Ptt幣：App 不替你花 Ptt 幣，沒有寄", error.message)
        assertEquals(listOf("M\r", Keys.DOWN + "\r", "someone\r", "n\r", "\r"), ptt.received.drop(5))
    }

    @Test
    fun notSentAfterSavingSaysWhy() {
        // 存檔之後沒看到「已順利寄出」就回到選單：沒寄出去，是一般的失敗（可以重試）
        val ptt = bbs(MAIN_MENU, afterSignature = SAVE_FAILED)
        val error = failure(ptt) { sendMail(NewMail(to = "someone", title = "你好", body = "")) }
        assertTrue("$error", error !is MailUnconfirmedException)
        assertEquals("使用者 [someone] 信箱已滿，無法收信", error.message)
    }

    @Test
    fun aStrangeScreenAfterSavingMayHaveSentIt() {
        val ptt = bbs(MAIN_MENU, afterSignature = STRANGE)
        val error = failure(ptt) { sendMail(NewMail(to = "someone", title = "你好", body = "")) }
        assertTrue("$error", error is MailUnconfirmedException)
        assertTrue(error.message!!, error.message!!.contains("可能已經寄出去了"))
    }

    @Test
    fun replyKeepsTheTitleQuotesAndTypesAboveTheQuote() {
        val ptt = bbs(MAIL_LIST)
        run(ptt) { replyMail(2, SOMEONE, MailReply(body = "謝謝", backup = true)) }
        assertEquals(
            // 游標移到第 2 封、y；採用原標題 y、引用原文 y；內文打完空一行（引言在下面）；沒有簽名檔；自存底稿 y
            listOf("2\r", "y", "y\r", "y\r", "謝謝\r\r", Keys.ctrl('X'), "s\r", "y\r", "\r"),
            ptt.received,
        )
    }

    @Test
    fun replyWithANewTitleAndNoQuote() {
        val ptt = bbs(MAIL_LIST)
        run(ptt) { replyMail(2, SOMEONE, MailReply(body = "謝謝", title = "改個標題", quote = false)) }
        assertEquals(
            listOf("2\r", "y", "n\r", Keys.ctrl('Y') + "改個標題\r", "n\r", "謝謝", Keys.ctrl('X'), "s\r", "n\r", "\r"),
            ptt.received,
        )
    }

    @Test
    fun aChangedMailboxIsNotReplied() {
        val ptt = bbs(MAIL_LIST)
        val error = failure(ptt) { replyMail(2, SOMEONE.copy(title = "別封信"), MailReply(body = "謝謝")) }
        assertTrue(error.message!!, error.message!!.startsWith("信箱變了"))
        assertEquals(listOf("2\r"), ptt.received)
    }

    @Test
    fun aSystemMailCannotBeReplied() {
        val ptt = bbs(MAIL_LIST)
        val error = failure(ptt) { replyMail(1, null, MailReply(body = "謝謝")) }
        assertEquals("無法回信。", error.message)
        assertEquals(listOf("1\r", "y", "\r"), ptt.received)
    }

    @Test
    fun aReRegisteredSenderIsNotMailed() {
        val ptt = bbs(MAIL_LIST, reply = REREGISTERED)
        val error = failure(ptt) { replyMail(2, SOMEONE, MailReply(body = "謝謝")) }
        assertTrue(error.message!!, error.message!!.contains("重新註冊"))
        assertEquals(listOf("2\r", "y", "n\r", "\r"), ptt.received)
    }

    private suspend fun connect(ptt: FakePtt): Session {
        val session = Session(WebSocketTransport(client, url = ptt.url), scope)
        sessions += session
        session.connect()
        return session
    }

    private fun <T> run(ptt: FakePtt, block: suspend Session.() -> T): T = runBlocking { withTimeout(TEST_TIMEOUT_MS) { connect(ptt).block() } }

    private fun failure(ptt: FakePtt, block: suspend Session.() -> Unit): Exception {
        try {
            run(ptt, block)
        } catch (e: UnexpectedScreenException) {
            return e
        } catch (e: MailUnconfirmedException) {
            return e
        }
        fail("該寄不出去")
        throw AssertionError()
    }

    /**
     * 假 PTT：查詢網友（someone 存在；打「some」PTT 補成 someone；nobody 不存在）、私人信件區、寄信、信箱列表、回信。
     * [recipient] 是打了收信人之後的畫面（預設問主題）；[afterSignature] 是簽名檔答完之後的畫面（預設「已順利寄出」）；
     * [reply] 是按了 y 之後的畫面（預設問採用原標題）。
     */
    private fun bbs(
        first: String,
        recipient: String = SUBJECT,
        afterSignature: String = BACKUP,
        reply: String = KEEP_TITLE,
    ): FakePtt {
        var at = first
        var replying = false
        return FakePtt(first) { input ->
            Thread.sleep(LAG_MS)
            val next = when {
                at == MAIN_MENU && input == "t\r" -> QueryUserTest.TALK_MENU
                at == QueryUserTest.TALK_MENU && input == Keys.DOWN + "\r" -> QueryUserTest.QUERY_PROMPT
                at == QueryUserTest.QUERY_PROMPT && (input.equals("someone\r", ignoreCase = true) || input == "some\r") -> QueryUserTest.QUERY_RESULT
                at == QueryUserTest.QUERY_PROMPT && input == "nobody\r" -> QueryUserTest.TALK_MENU
                at == QueryUserTest.QUERY_RESULT && input == "\r" -> QueryUserTest.TALK_MENU
                at == QueryUserTest.TALK_MENU && input == Keys.LEFT -> MAIN_MENU
                at == MAIN_MENU && input == "M\r" -> MAIL_MENU
                at == MAIL_MENU && input == Keys.DOWN + "\r" -> RECIPIENT
                at == RECIPIENT && input == "someone\r" -> recipient
                at == SUBJECT && input.endsWith("\r") -> EDITOR
                at == QUOTA && input == "n\r" -> QUOTA_DECLINED
                at in setOf(FULL_MAILBOX, QUOTA_DECLINED, SAVE_FAILED) && input == "\r" -> if (replying) MAIL_LIST_ON_2 else MAIL_MENU
                at == MAIL_LIST && input == "1\r" -> MAIL_LIST_ON_1
                at == MAIL_LIST && input == "2\r" -> MAIL_LIST_ON_2
                at == MAIL_LIST_ON_1 && input == "y" -> NO_REPLY
                at == NO_REPLY && input == "\r" -> MAIL_LIST_ON_1
                at == MAIL_LIST_ON_2 && input == "y" -> {
                    replying = true
                    reply
                }
                at == KEEP_TITLE && input == "y\r" -> QUOTE
                at == KEEP_TITLE && input == "n\r" -> NEW_TITLE
                at == NEW_TITLE && input == Keys.ctrl('Y') + "改個標題\r" -> replaceRow(QUOTE, 4, "新標題：改個標題")
                asksQuote(at) && input == "y\r" -> EDITOR_QUOTED
                asksQuote(at) && input == "n\r" -> EDITOR
                at == REREGISTERED && input == "n\r" -> GIVE_UP
                at == GIVE_UP && input == "\r" -> MAIL_LIST_ON_2
                at.endsWith(EDITOR_STATUS) && input == Keys.ctrl('X') -> SAVE_PROMPT
                // 打內文：編輯器把字畫上去（哪一列都好，畫面變了就行）
                at.endsWith(EDITOR_STATUS) -> replaceRow(at, 10, input.replace("\r", " "))
                // 回信的人沒有簽名檔：存檔之後直接問自存底稿
                at == SAVE_PROMPT && input == "s\r" -> if (replying) afterSignature else SIGNATURE_PROMPT
                at == SIGNATURE_PROMPT && input.endsWith("\r") -> afterSignature
                at == BACKUP && input.endsWith("\r") -> SENT
                at == SENT && input == "\r" -> if (replying) MAIL_LIST_ON_2 else MAIL_MENU
                else -> null
            }
            if (next != null) {
                at = next
                show(next)
            }
        }.also { fakes += it.start() }
    }

    private companion object {
        const val LAG_MS = 100L
        const val TEST_TIMEOUT_MS = 20_000L

        val MAIN_MENU = Fixtures.text("main-menu")
        val MAIL_MENU = Fixtures.text("mail-menu")
        val MAIL_LIST = Fixtures.text("mail-list")
        val MAIL_LIST_ON_1 = listCursor(MAIL_LIST, 1)
        val MAIL_LIST_ON_2 = listCursor(MAIL_LIST, 2)

        /** 信箱列表第 2 封（fixture：「     2 r 11/07 someone        R: 回你的信」）。 */
        val SOMEONE = MailSummary(2, 'r', "11/07", "someone", "R: 回你的信")

        private val BLANK = List(24) { "" }.joinToString("\n")
        private const val ANY_KEY = "                        請按任意鍵繼續                       "

        /** `m_send`：`vs_hdr("站內寄信")`、第 1 列 `usercomplete`。 */
        val RECIPIENT = replaceRow(replaceRow(BLANK, 0, "【站內寄信】"), 1, "請輸入使用者代號: ")

        /** 打了代號：（沒有名片檔的人）「《個人名片》代號 目前沒有名片」接在代號後面，第 2 列問主題。 */
        val SUBJECT = replaceRow(replaceRow(RECIPIENT, 1, "請輸入使用者代號: someone《個人名片》someone 目前沒有名片"), 2, "主題：")

        /** 收不到：錯誤接在代號後面（`outs`），最下面 `pressanykey`。 */
        val FULL_MAILBOX = replaceRow(replaceRow(RECIPIENT, 1, "請輸入使用者代號: someone使用者 [someone] 信箱已滿，無法收信"), 23, ANY_KEY)

        /** `mail_precheck_quota`：第 22 列。 */
        val QUOTA = replaceRow(replaceRow(RECIPIENT, 1, "請輸入使用者代號: someone"), 22, "今日免費額度(10封)已滿，寄此封信需 10 Ptt幣，確定繼續？[y/N] ")
        val QUOTA_DECLINED = replaceRow(replaceRow(QUOTA, 22, "今日免費額度(10封)已滿，寄此封信需 10 Ptt幣，確定繼續？[y/N] n"), 23, ANY_KEY)

        /** `mail_reply`：`vs_hdr("回  信")`、`do_reply_title(3, …)`。 */
        val KEEP_TITLE = replaceRow(replaceRow(replaceRow(BLANK, 0, "【回  信】"), 3, "原標題: Re: 回你的信"), 4, "採用原標題[Y/n]? ")
        val NEW_TITLE = replaceRow(KEEP_TITLE, 4, "新標題：Re: 回你的信")

        const val QUOTE_ROW = "請問要引用原文嗎(Y/N/All/Repost)？[Y] "

        /** 標題答完：印收信人、標題（`prints("\n收信人: %s\n標  題: %s\n")`），進編輯器前第 22 列問引用原文（`do_quote`）。 */
        val QUOTE = replaceRow(
            replaceRow(replaceRow(replaceRow(KEEP_TITLE, 4, "採用原標題[Y/n]? y"), 6, "收信人: someone"), 7, "標  題: Re: 回你的信"),
            22,
            QUOTE_ROW,
        )

        /** 第 22 列在問引用原文。 */
        fun asksQuote(screen: String): Boolean = screen.lines().getOrNull(22) == QUOTE_ROW

        /** 寄件人的帳號被重新註冊過（`mail_reply`：第 2 列起警告、第 7 列問）。 */
        val REREGISTERED = replaceRow(
            replaceRow(replaceRow(BLANK, 0, "【回  信】"), 2, "警告: 此篇文章的原作者帳號已被重新註冊，您的信可能被無關的新使用者收到"),
            7,
            "確定仍然要寄信給對方嗎[y/N]? ",
        )
        val GIVE_UP = replaceRow(
            replaceRow(REREGISTERED, 7, "確定仍然要寄信給對方嗎[y/N]? n"),
            23,
            " ◆ 放棄寄信。" + " ".repeat(40) + "[按任意鍵繼續]",
        )

        /** 群組信件（`multi_reply`）。 */
        val GROUP_REPLY = replaceRow(replaceRow(BLANK, 0, "【群組信件】"), 2, "請問要回信給原發信者(r)還是全部的人(a)，或離開(q) [r/a/q]? ")

        /** 系統信：`mail_reply` 一開始就 `vmsg("無法回信。")`，疊在列表上。 */
        val NO_REPLY = replaceRow(MAIL_LIST_ON_1, 23, " ◆ 無法回信。" + " ".repeat(40) + "[按任意鍵繼續]")

        const val EDITOR_STATUS = " 編輯文章  (^Z)說明 (^X)存檔 (^C)色碼 (^V)彩色   ║插入│aipr║  1:  1"
        val EDITOR = replaceRow(BLANK, 23, EDITOR_STATUS)

        /** 引用之後（`do_quote`）：「※ 引述《…》之銘言：」、「: 」開頭的引言；游標在第一行。 */
        val EDITOR_QUOTED = replaceRow(replaceRow(EDITOR, 0, "※ 引述《someone (某人)》之銘言："), 1, ": 信的內容第一行")

        val SAVE_PROMPT: String = replaceRow(
            replaceRow(replaceRow(BLANK, 0, "【檔案處理】"), 1, "[S]發信 (T)改標題 (A)放棄 (E)繼續 (R/W/D)讀寫刪暫存檔"),
            2,
            "確定要儲存檔案嗎？ ",
        )
        val SIGNATURE_PROMPT = replaceRow(BLANK, 0, "請選擇簽名檔 (1-9, 0=不加 x=隨機)[0]: ")
        const val SIGNATURE_ROW = "請選擇簽名檔 (1-9, 0=不加 x=隨機)[0]: 0"

        /** `hold_mail`：第 22 列（上面可能還留著簽名檔的畫面）。 */
        val BACKUP = replaceRow(BLANK, 22, "已順利寄出，是否自存底稿(Y/N)？[N] ")

        /** 存檔之後沒寄出去（`save_mailbox` 失敗）：`clear()` 之後印原因、`pressanykey()`。 */
        val SAVE_FAILED = replaceRow(replaceRow(BLANK, 0, "使用者 [someone] 信箱已滿，無法收信"), 23, ANY_KEY)

        /** 寄完 `clear()` 再 `pressanykey()`。 */
        val SENT = replaceRow(BLANK, 23, ANY_KEY)

        /** 認不出來、也不會再變的畫面。 */
        val STRANGE = replaceRow(BLANK, 0, "系統忙碌中")

        /** 信箱的游標在第 0 欄；第 3 列是第 1 封。 */
        fun listCursor(screen: String, index: Int): String {
            val lines = screen.lines().take(24).toMutableList()
            for (row in 3 until 23) {
                if (lines[row].isNotEmpty()) lines[row] = (if (row == 2 + index) ">" else " ") + lines[row].substring(1)
            }
            return lines.joinToString("\n")
        }
    }
}
