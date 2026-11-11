package com.twtrubiks.quietptt.core.ptt

import com.twtrubiks.quietptt.core.terminal.Keys
import com.twtrubiks.quietptt.core.transport.FakePtt
import com.twtrubiks.quietptt.core.transport.FakePtt.Companion.replaceRow
import com.twtrubiks.quietptt.core.transport.WebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `Session` 對著假的 PTT（[FakePtt]，走真的 WebSocket）：登入流程送了什麼、等到什麼，走到各頁按了哪些鍵。
 *
 * 畫面是真機錄的 fixture：密碼提示出現在代號那列的下一列（真機確認）；重複登入、密碼錯誤、刪除錯誤嘗試
 * 沒錄畫面，位置照記憶放在更下面。每次回應都慢 [LAG_MS]（比 [Session.QUIET_MS] 久，像手機網路），確定 Session 不會拿按鍵之前的畫面當答案。
 */
class SessionTest {
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

    private suspend fun connect(ptt: FakePtt): Session {
        fakes += ptt.start()
        val session = Session(WebSocketTransport(client, url = ptt.url), scope)
        sessions += session
        session.connect()
        return session
    }

    /**
     * 登入流程的假 PTT。[afterPassword] 是密碼對了之後怎麼回（預設：歡迎畫面「請按任意鍵繼續」，再按一下到主選單）。
     * [duplicateLogin] 先問重複登入；[tooManyLogins] 是其他連線已經有 3 條：答 n 就斷線（pttbbs `multi_user_check` 的 `abort_bbs`）。
     */
    private fun loginServer(
        duplicateLogin: Boolean = false,
        tooManyLogins: Boolean = false,
        afterPassword: FakePtt.() -> Unit = { show(WELCOME) },
    ): FakePtt {
        var step = "account"
        var account = ""
        return FakePtt(LOGIN) { input ->
            Thread.sleep(LAG_MS)
            when {
                step == "account" -> {
                    account = input.removeSuffix("\r")
                    show(accountTyped(account))
                    Thread.sleep(LAG_MS)
                    show(passwordPrompt(account))
                    step = "password"
                }
                step == "password" && input == "$PASSWORD\r" ->
                    if (duplicateLogin) {
                        show(replaceRow(passwordPrompt(account), 23, "您想刪除其他重複登入的連線嗎？[Y/n] "))
                        step = "duplicate"
                    } else {
                        afterPassword()
                        step = "welcome"
                    }
                step == "password" -> {
                    show(replaceRow(passwordPrompt(account), 23, "密碼不對或無此帳號。請檢查大小寫及有無輸入錯誤。"))
                    step = "account"
                }
                step == "duplicate" && input == "y\r" -> {
                    // pttbbs 答完先睡 0～5 秒（`multi_user_check`）：畫面只多了回顯的「y」，停一陣子才往下走
                    show(replaceRow(passwordPrompt(account), 23, "您想刪除其他重複登入的連線嗎？[Y/n] y"))
                    Thread.sleep(LAG_MS * 3)
                    afterPassword()
                    step = "welcome"
                }
                step == "duplicate" && input == "n\r" -> {
                    // 答 n 一樣先睡；其他連線還有 3 條就不說原因直接斷線，不到 3 條照常往下走
                    show(replaceRow(passwordPrompt(account), 23, "您想刪除其他重複登入的連線嗎？[Y/n] n"))
                    Thread.sleep(LAG_MS * 3)
                    if (tooManyLogins) {
                        hangUp()
                    } else {
                        afterPassword()
                        step = "welcome"
                    }
                }
                step == "welcome" && input == "\r" -> {
                    show(MAIN_MENU)
                    step = "menu"
                }
            }
        }
    }

    @Test
    fun logsInThroughThePromptsStackedOnTheSameScreen() = runBlocking {
        val ptt = loginServer()
        val session = connect(ptt)
        assertEquals(LoginResult.Success, session.login("someone", PASSWORD))
        assertEquals(listOf("someone\r", "$PASSWORD\r", "\r"), ptt.received)
        assertTrue(session.page is Page.MainMenu)
    }

    @Test
    fun kicksTheOtherConnectionOnlyOnce() = runBlocking {
        val ptt = loginServer(duplicateLogin = true)
        val session = connect(ptt)
        assertEquals(LoginResult.Success, session.login("someone", PASSWORD))
        // 答完到往下走之間，畫面一直是這個提示（多了回顯的 y）：不能再答一次
        assertEquals(listOf("someone\r", "$PASSWORD\r", "y\r", "\r"), ptt.received)
    }

    @Test
    fun keepsTheOtherConnectionsWhenAskedTo() = runBlocking {
        // 設定關掉「重複登入時刪除其他連線」：答 n（一樣只答一次），其他連線不到 3 條就照常登入
        val ptt = loginServer(duplicateLogin = true)
        val session = connect(ptt)
        assertEquals(LoginResult.Success, session.login("someone", PASSWORD, kickOthers = false))
        assertEquals(listOf("someone\r", "$PASSWORD\r", "n\r", "\r"), ptt.received)
    }

    @Test
    fun keepingThreeOtherConnectionsIsTooManyLogins() = runBlocking {
        // 答 n 之後 PTT 直接斷線：不是一般的「連線被關掉」，是其他連線已經有 3 條
        val ptt = loginServer(duplicateLogin = true, tooManyLogins = true)
        val session = connect(ptt)
        val result = session.login("someone", PASSWORD, kickOthers = false)
        assertTrue("$result", result is LoginResult.TooManyLogins)
        assertEquals(listOf("someone\r", "$PASSWORD\r", "n\r"), ptt.received)
        assertTrue(session.closed.value)
    }

    @Test
    fun wrongPasswordIsBadCredentials() = runBlocking {
        val ptt = loginServer()
        val session = connect(ptt)
        val result = session.login("someone", "wrong")
        assertTrue("$result", result is LoginResult.BadCredentials && result.message.startsWith("密碼不對或無此帳號"))
        assertEquals(listOf("someone\r", "wrong\r"), ptt.received)
    }

    @Test
    fun doesNotPressEnterOnAScreenThatIsStillChanging() = runBlocking {
        // 「正在更新」這種過場畫面一下子就換掉：不該按 Enter，不然多的 Enter 會被後面的畫面吃掉
        val ptt = loginServer(afterPassword = {
            show(SYNCING)
            Thread.sleep(800)
            show(WELCOME)
        })
        val session = connect(ptt)
        assertEquals(LoginResult.Success, session.login("someone", PASSWORD))
        assertEquals(listOf("someone\r", "$PASSWORD\r", "\r"), ptt.received)
    }

    @Test
    fun nudgesAnUnknownScreenThatStaysPut() = runBlocking {
        // 認不出來的公告停著不動：等一下之後按一次 Enter 往下走
        val ptt = loginServer(afterPassword = { show(SYNCING) })
        val session = connect(ptt)
        assertEquals(LoginResult.Success, session.login("someone", PASSWORD))
        assertEquals(listOf("someone\r", "$PASSWORD\r", "\r"), ptt.received)
    }

    @Test
    fun hangingUpDuringLoginFailsRightAway() = runBlocking {
        val ptt = loginServer(afterPassword = {
            show(replaceRow("", 10, "系統過載，請稍後再來"))
            Thread.sleep(LAG_MS)
            hangUp()
        })
        val session = connect(ptt)
        val started = System.currentTimeMillis()
        val result = session.login("someone", PASSWORD)
        assertTrue("$result", result is LoginResult.Failed && result.screen.contains("系統過載"))
        assertTrue("等太久才發現斷線", System.currentTimeMillis() - started < 5_000)
        assertTrue(session.closed.value)
    }

    @Test
    fun noPasswordPromptIsAFailureNotAnException() = runBlocking {
        val ptt = FakePtt(LOGIN) // 送什麼都不回
        val session = connect(ptt)
        val result = session.login("someone", PASSWORD, timeoutMs = 1_500)
        assertTrue("$result", result is LoginResult.Failed)
    }

    /**
     * 登入之後的假 PTT：照目前的畫面與收到的按鍵換畫面，沒寫到的組合不回應（測試就會等到逾時而失敗）。
     * 畫面都是真機錄的，按鍵的反應照真機與 pttbbs 原始碼：
     * 進板的鍵一次送來（[Session] 的 `enterBoard`，真的 PTT 照順序吃先打的字）：我的最愛裡「`s` 板名 Enter Enter」
     * （`s` 板名 Enter 把游標移到那個板，再 Enter 進去）、主選單「`s` 板名 Enter」、看板裡「`s` 板名 Enter」，後面都是「End End」。
     * 換到別的板先跳進板畫面，第一個 End 按掉它、第二個到列表最後面（[end]）；[longNote]（進板畫面比一頁長）的話兩個 End 都被它吃掉，
     * 停在 [reentry]（再進板時 PTT 記得的位置，`getkeep`；沒給就是一進板的那頁）。找不到的板 PTT 什麼都不說、留在原地。
     * 序號 Enter 把游標移過去，再 Enter 開文章；文章裡 PgDn 翻頁、Home 回開頭、左鍵回列表。開文章的「序號 Enter Enter」一次送來也收。
     * 結尾的 Ctrl-L（[Keys.CTRL_L]）是整頁重畫：拿掉再看前面的鍵，只有 Ctrl-L 的話把現在的畫面再送一次。
     * 文章裡 `:` 行號 Enter 跳到有那一行的那頁、End 到最後一頁。
     * [reentry] 那頁按 End 到 [end]；游標在最後一列的那頁（[REMEMBERED_LAST_ROW]）按上鍵移上一列。
     */
    private fun bbs(
        first: String,
        articlePage: Int = 0,
        reentry: String? = null,
        end: String = ARTICLE_LIST_AT_END,
        longNote: Boolean = false,
    ): FakePtt {
        var at = first
        var page = articlePage
        var list = if (first == SEARCH_RESULTS) SEARCH_RESULTS else ARTICLE_LIST // 從文章按左鍵退回哪個列表
        // 換到別的板：進板畫面吃掉第一個 End、第二個到最後面；比一頁長的進板畫面兩個都吃掉
        fun entered() = if (longNote) reentry ?: ARTICLE_LIST else end
        val boardLists = setOf(FAVORITES, FAVORITES_AFTER_BOARD)
        return FakePtt(first) { received ->
            Thread.sleep(LAG_MS)
            val redraw = received.endsWith(Keys.CTRL_L)
            val input = received.removeSuffix(Keys.CTRL_L)
            val goto = GOTO_LINE.matchEntire(input)?.groupValues?.get(1)?.toInt()
            val next = when {
                at == MAIN_MENU && input == "f\r" -> FAVORITES
                // 進板（一次送來）
                at in boardLists && input == "sMobileComm\r\r" + END_END -> entered()
                at in boardLists && input == LEFTS + "sTest\r" + END_END -> TEST_LIST
                at == MAIN_MENU && input == "sMobileComm\r" + END_END -> entered()
                // 找不到的板：PTT 什麼都不說、留在原地（End 只移了游標）
                at == MAIN_MENU && input == "sNoSuchBoard\r" + END_END -> MAIN_MENU
                at == ARTICLE_LIST && input == "sNoSuchBoard\r" + END_END -> ARTICLE_LIST_AT_END
                at == ARTICLE_LIST && input == "sStock\r" + END_END -> STOCK_LIST
                // 重整：左鍵退出看板、再退到主選單，主選單的 s 進來（剛剛就在這個板，沒有進板畫面，End 直接到最後面）
                at == ARTICLE_LIST && input == Keys.LEFT + LEFTS + "sMobileComm\r" + END_END -> end
                // 游標已經在最後一筆時按 End：PTT 一個字都不回（這裡也不回）
                at == reentry && input == Keys.END -> end.takeIf { it != at }
                at == REMEMBERED_LAST_ROW && input == Keys.UP -> REMEMBERED_LAST_ROW_UP
                at == REMEMBERED_LAST_ROW_UP && input == Keys.END -> end
                at == ARTICLE_LIST && input == "153897\r" -> ARTICLE_LIST_ON_153897
                at == ARTICLE_LIST_ON_153897 && input == "\r" -> ARTICLE_PAGES[0].also { page = 0; list = ARTICLE_LIST }
                at == ARTICLE_LIST && input == "153897\r\r" -> ARTICLE_PAGES[0].also { page = 0; list = ARTICLE_LIST }
                // 游標就在 153909 上（一進板的樣子）：直接 Enter。真機：打 153909 Enter 跳到游標所在的那篇，PTT 什麼都不回（這裡也不回）
                at == ARTICLE_LIST && input == "\r" -> ARTICLE_PAGES[0].also { page = 0; list = ARTICLE_LIST }
                at == ARTICLE_LIST && input == Keys.END + "\r" -> ARTICLE_PAGES[0].also { page = 0; list = ARTICLE_LIST }
                // 搜尋（pttbbs read.c：/ 標題、a 作者、Z 推文數，提示在最下面一列；有結果變「系列文章」，沒有就是 vmsg 訊息）
                at == ARTICLE_LIST && input == "/" -> SEARCH_TITLE_PROMPT
                at == ARTICLE_LIST && input == "a" -> SEARCH_AUTHOR_PROMPT
                at == ARTICLE_LIST && input == "Z" -> SEARCH_PUSH_PROMPT
                at == SEARCH_TITLE_PROMPT && input == "綠線\r" -> SEARCH_RESULTS
                at == SEARCH_AUTHOR_PROMPT && input == "chanifa\r" -> SEARCH_RESULTS
                at == SEARCH_PUSH_PROMPT && input == "30\r" -> SEARCH_RESULTS
                at == SEARCH_TITLE_PROMPT && input == "沒有這種東西\r" -> SEARCH_NO_RESULTS
                at == SEARCH_NO_RESULTS && input == "\r" -> ARTICLE_LIST
                at == SEARCH_TITLE_PROMPT && input == "太頻繁\r" -> SEARCH_DENIED
                at == SEARCH_DENIED && input == "\r" -> ARTICLE_LIST
                at == SEARCH_RESULTS && input == "2\r" -> SEARCH_RESULTS_ON_2
                at == SEARCH_RESULTS_ON_2 && input == "\r" -> ARTICLE_PAGES[0].also { page = 0; list = SEARCH_RESULTS }
                at == SEARCH_RESULTS && input == "2\r\r" -> ARTICLE_PAGES[0].also { page = 0; list = SEARCH_RESULTS }
                at == SEARCH_RESULTS && input == Keys.LEFT -> ARTICLE_LIST
                at in ARTICLE_PAGES && input == Keys.PAGE_DOWN && page < ARTICLE_PAGES.lastIndex -> ARTICLE_PAGES[++page]
                at in ARTICLE_PAGES && input == Keys.HOME -> ARTICLE_PAGES[0].also { page = 0 }
                at in ARTICLE_PAGES && input == Keys.END -> ARTICLE_PAGES.last().also { page = ARTICLE_PAGES.lastIndex }
                at in ARTICLE_PAGES && goto != null -> {
                    page = ARTICLE_RANGES.indexOfLast { it.first <= goto }.coerceAtLeast(0)
                    ARTICLE_PAGES[page]
                }
                at in ARTICLE_PAGES && input == Keys.LEFT -> list
                // 真機：進過板再退回來，看板列表的標題列右邊會帶著那個板「看板《MobileComm》」
                (at == ARTICLE_LIST || at == STOCK_LIST) && input == Keys.LEFT -> FAVORITES_AFTER_BOARD
                else -> null
            }
            if (next != null) {
                at = next
                show(next)
            } else if (redraw && input.isEmpty()) {
                show(at)
            }
        }
    }

    @Test
    fun favoritesFromTheMainMenu() = runBlocking {
        val ptt = bbs(MAIN_MENU)
        val session = connect(ptt)
        val page = withTimeout(TEST_TIMEOUT_MS) { session.showFavorites() }
        assertEquals(4, page.boards.size)
        assertEquals(listOf("f\r"), ptt.received)
    }

    @Test
    fun alreadyOnTheRightPageSendsNothing() = runBlocking {
        val ptt = bbs(FAVORITES)
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) { session.showFavorites() }
        assertEquals(emptyList<String>(), ptt.received)
    }

    @Test
    fun aBoardOnThisPageOfFavoritesIsEnteredInOneWrite() = runBlocking {
        val ptt = bbs(FAVORITES)
        val session = connect(ptt)
        val page = withTimeout(TEST_TIMEOUT_MS) { session.showBoard("MobileComm") }
        assertEquals("MobileComm", page.board)
        // 一次送出：s、板名 Enter（游標移過去）、Enter（進板）、End（按掉進板畫面）、End（到最後面）、Ctrl-L（整頁重畫）
        assertEquals(listOf(ENTER_MOBILECOMM), ptt.received)
    }

    @Test
    fun aBoardNotOnThisPageGoesThroughTheMainMenu() = runBlocking {
        // 這頁看不到的板：列表的 s 不在列表裡會直接進板（多的 Enter 會開文章），所以左鍵退回主選單、用主選單的 s
        val ptt = bbs(FAVORITES)
        val session = connect(ptt)
        val page = withTimeout(TEST_TIMEOUT_MS) { session.showBoard("Test") }
        assertEquals("Test", page.board)
        assertEquals(listOf(LEFTS + "sTest\r" + END_END + Keys.CTRL_L), ptt.received)
    }

    @Test
    fun fromTheMainMenuSelectsTheBoardDirectly() = runBlocking {
        val ptt = bbs(MAIN_MENU)
        val session = connect(ptt)
        val page = withTimeout(TEST_TIMEOUT_MS) { session.showBoard("MobileComm") }
        assertEquals("MobileComm", page.board)
        assertEquals(listOf("sMobileComm\r" + END_END + Keys.CTRL_L), ptt.received)
    }

    @Test
    fun fromAnotherBoardSearchSwitchesDirectly() = runBlocking {
        val ptt = bbs(ARTICLE_LIST)
        val session = connect(ptt)
        val page = withTimeout(TEST_TIMEOUT_MS) { session.showBoard("Stock") }
        assertEquals("Stock", page.board)
        assertEquals(listOf("sStock\r" + END_END + Keys.CTRL_L), ptt.received)
    }

    @Test
    fun aBoardThatCannotBeEnteredFailsAfterOneTry() = runBlocking {
        // PTT 進不去的板什麼都不說、留在原地：送過一次又回到主選單（或原來的看板）就說進不了，不再送
        for (first in listOf(MAIN_MENU, ARTICLE_LIST)) {
            val ptt = bbs(first)
            val session = connect(ptt)
            val e = runCatching { withTimeout(TEST_TIMEOUT_MS) { session.showBoard("NoSuchBoard") } }.exceptionOrNull()
            assertTrue("$e", e is UnexpectedScreenException && e.message!!.startsWith("進不了 NoSuchBoard"))
            assertEquals(listOf("sNoSuchBoard\r" + END_END + Keys.CTRL_L), ptt.received)
        }
    }

    @Test
    fun openArticleJumpsToItsIndex() = runBlocking {
        val ptt = bbs(ARTICLE_LIST)
        val session = connect(ptt)
        val page = withTimeout(TEST_TIMEOUT_MS) { session.openArticle("MobileComm", ArticleRef.Numbered(153897)) }
        assertEquals(1, page.progress.page)
        assertEquals(listOf("153897\r\r" + Keys.CTRL_L), ptt.received)
    }

    @Test
    fun openTheArticleUnderTheCursorWithoutJumping() = runBlocking {
        // 一進板游標就停在最新的那篇（153909）：跳過去 PTT 不回任何東西，等畫面變會等到逾時，所以直接按 Enter
        val ptt = bbs(ARTICLE_LIST)
        val session = connect(ptt)
        val page = withTimeout(TEST_TIMEOUT_MS) { session.openArticle("MobileComm", ArticleRef.Numbered(153909)) }
        assertEquals(1, page.progress.firstLine)
        assertEquals(listOf("\r" + Keys.CTRL_L), ptt.received)
    }

    @Test
    fun openPinnedArticleFromTheEnd() = runBlocking {
        val ptt = bbs(ARTICLE_LIST)
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) { session.openArticle("MobileComm", ArticleRef.Pinned(fromBottom = 0)) }
        assertEquals(listOf(Keys.END + "\r" + Keys.CTRL_L), ptt.received)
    }

    @Test
    fun readsTheWholeArticlePageByPage() = runBlocking {
        // 真機錄的 7 頁：PgDn 一頁一頁翻，接起來是整篇；最後一頁之後不再按
        val ptt = bbs(ARTICLE_LIST)
        val session = connect(ptt)
        var text = ArticleText.EMPTY + withTimeout(TEST_TIMEOUT_MS) { session.openArticle("MobileComm", ArticleRef.Numbered(153897)) }
        while (!text.complete) text += withTimeout(TEST_TIMEOUT_MS) { session.nextArticlePage("MobileComm", ArticleRef.Numbered(153897)) }
        assertEquals(138, text.lastLine)
        assertTrue(text.lines.last().startsWith("推 yahappy4u"))
        withTimeout(TEST_TIMEOUT_MS) { session.nextArticlePage("MobileComm", ArticleRef.Numbered(153897)) }
        assertEquals(listOf("153897\r\r" + Keys.CTRL_L) + List(6) { Keys.PAGE_DOWN + Keys.CTRL_L }, ptt.received)
    }

    @Test
    fun copyingTheWholeArticleReadsEveryPageThenGoesBackToTheList() = runBlocking {
        // 看板長按「複製全文」：打開、PgDn 翻到最後一頁（不多按）、左鍵退回列表
        val ptt = bbs(ARTICLE_LIST)
        val session = connect(ptt)
        val text = withTimeout(TEST_TIMEOUT_MS) { session.wholeArticle("MobileComm", ArticleRef.Numbered(153897)) }
        assertTrue(text.complete)
        assertEquals(138, text.lastLine)
        assertTrue(text.lines.last().startsWith("推 yahappy4u"))
        assertEquals(listOf("153897\r\r" + Keys.CTRL_L) + List(6) { Keys.PAGE_DOWN + Keys.CTRL_L } + Keys.LEFT, ptt.received)
        assertTrue("${session.page}", session.page is Page.ArticleList)
        // 終端停在文章中間（轉過螢幕之類的）：先 Home 回開頭再翻
        val ptt2 = bbs(ARTICLE_PAGES[2], articlePage = 2)
        val session2 = connect(ptt2)
        val text2 = withTimeout(TEST_TIMEOUT_MS) { session2.wholeArticle("MobileComm", ArticleRef.Numbered(153897)) }
        assertEquals(text.lines, text2.lines)
        assertEquals(listOf(Keys.HOME + Keys.CTRL_L) + List(6) { Keys.PAGE_DOWN + Keys.CTRL_L } + Keys.LEFT, ptt2.received)
    }

    @Test
    fun anArticleOpenedWhileCopyingWaitsUntilTheCopyIsBackOnTheList() = runBlocking {
        // 複製還在翻頁時點了另一篇：那篇要等複製退回列表才開。插在兩頁中間的話，終端上開著的是正在複製的那篇，會被當成要開的
        val ptt = bbs(ARTICLE_LIST)
        val session = connect(ptt)
        val copy = async { session.wholeArticle("MobileComm", ArticleRef.Numbered(153897)) }
        withTimeout(TEST_TIMEOUT_MS) { while (Keys.PAGE_DOWN + Keys.CTRL_L !in ptt.received) delay(10) }
        // 153909 是一進板游標所在的那篇：退回列表之後游標在它上面，只按 Enter
        withTimeout(TEST_TIMEOUT_MS) { session.openArticle("MobileComm", ArticleRef.Numbered(153909)) }
        assertTrue(withTimeout(TEST_TIMEOUT_MS) { copy.await() }.complete)
        assertEquals(listOf("153897\r\r" + Keys.CTRL_L) + List(6) { Keys.PAGE_DOWN + Keys.CTRL_L } + Keys.LEFT + ("\r" + Keys.CTRL_L), ptt.received)
    }

    @Test
    fun reopeningAnArticleInTheMiddleGoesBackToTheTop() = runBlocking {
        // 轉螢幕之類的重進文章頁：終端停在第 3 頁，按 Home 回開頭（真機確認），App 才接得起來
        val ptt = bbs(ARTICLE_PAGES[2], articlePage = 2)
        val session = connect(ptt)
        val page = withTimeout(TEST_TIMEOUT_MS) { session.openArticle("MobileComm", ArticleRef.Numbered(153897)) }
        assertEquals(1, page.progress.firstLine)
        assertEquals(listOf(Keys.HOME + Keys.CTRL_L), ptt.received)
    }

    @Test
    fun reloadingAnArticleLeavesAndOpensItAgain() = runBlocking {
        // 「檢查新推文」：在文章裡按 Home 不會重讀檔案，要退回列表再打開。真機退回來游標停在那篇上，只按 Enter；
        // 假 PTT 退回的列表游標在 153909（fixture），所以這裡先跳序號
        val ptt = bbs(ARTICLE_PAGES[2], articlePage = 2)
        val session = connect(ptt)
        val page = withTimeout(TEST_TIMEOUT_MS) { session.reloadArticle("MobileComm", ArticleRef.Numbered(153897)) }
        assertEquals(1, page.progress.firstLine)
        assertEquals(listOf(Keys.LEFT, "153897\r\r" + Keys.CTRL_L), ptt.received)
        // 不在文章裡就直接打開
        val ptt2 = bbs(ARTICLE_LIST)
        val session2 = connect(ptt2)
        withTimeout(TEST_TIMEOUT_MS) { session2.reloadArticle("MobileComm", ArticleRef.Numbered(153897)) }
        assertEquals(listOf("153897\r\r" + Keys.CTRL_L), ptt2.received)
    }

    @Test
    fun theEndOfAnArticleIsOneKeyAway() = runBlocking {
        // 「到底」：End 一次到最後一頁，不一頁一頁翻
        val ptt = bbs(ARTICLE_LIST)
        val session = connect(ptt)
        val first = withTimeout(TEST_TIMEOUT_MS) { session.openArticle("MobileComm", ArticleRef.Numbered(153897)) }
        val end = withTimeout(TEST_TIMEOUT_MS) { session.articleEnd("MobileComm", ArticleRef.Numbered(153897)) }
        assertTrue(end.progress.isLast)
        val text = ArticleText.EMPTY + first + end
        assertEquals(listOf(23..115), text.gaps)
        // 已經在最後一頁就不按
        withTimeout(TEST_TIMEOUT_MS) { session.articleEnd("MobileComm", ArticleRef.Numbered(153897)) }
        assertEquals(listOf("153897\r\r" + Keys.CTRL_L, Keys.END + Keys.CTRL_L), ptt.received)
    }

    @Test
    fun aPageByLineUsesPageDownNextAndJumpsOtherwise() = runBlocking {
        val ptt = bbs(ARTICLE_PAGES[6], articlePage = 6)
        val session = connect(ptt)
        val ref = ArticleRef.Numbered(153897)
        // 最後一頁上要第 120 行：就在畫面上，不按
        assertEquals(116, withTimeout(TEST_TIMEOUT_MS) { session.articlePage("MobileComm", ref, line = 120) }.progress.firstLine)
        // 中間缺的第 100 行：跳過去（`:` 行號 Enter）
        val middle = withTimeout(TEST_TIMEOUT_MS) { session.articlePage("MobileComm", ref, line = 100) }
        assertTrue(middle.progress.firstLine <= 100 && middle.progress.lastLine >= 100)
        // 接著要下一頁的第 111 行：PgDn
        val next = withTimeout(TEST_TIMEOUT_MS) { session.articlePage("MobileComm", ref, line = 111) }
        assertTrue(next.progress.firstLine <= 111 && next.progress.lastLine >= 111)
        // 第 1 行：Home
        assertEquals(1, withTimeout(TEST_TIMEOUT_MS) { session.articlePage("MobileComm", ref, line = 1) }.progress.firstLine)
        assertEquals(listOf(":100\r" + Keys.CTRL_L, Keys.PAGE_DOWN + Keys.CTRL_L, Keys.HOME + Keys.CTRL_L), ptt.received)
    }

    @Test
    fun reloadingABoardLeavesItAndEntersAgain() = runBlocking {
        // 「重整」：左鍵退出看板、再退到主選單，主選單的 s 進來、End 到最後面，一次送出；拿到最新的那一頁
        val ptt = bbs(ARTICLE_LIST)
        val session = connect(ptt)
        val page = withTimeout(TEST_TIMEOUT_MS) { session.reloadBoard("MobileComm") }
        assertTrue("$page", page != null && page.isBoard("MobileComm") && !page.filtered)
        assertEquals(listOf(RELOAD), ptt.received)
        // 停在文章裡：先退回列表再做一樣的事
        val ptt2 = bbs(ARTICLE_PAGES[0])
        val session2 = connect(ptt2)
        withTimeout(TEST_TIMEOUT_MS) { session2.reloadBoard("MobileComm") }
        assertEquals(listOf(Keys.LEFT, RELOAD), ptt2.received)
        // 搜尋結果的「重整」：退出系列文章模式、再搜一次（同一個條件平常不會重搜，這裡要）
        val ptt3 = bbs(ARTICLE_LIST)
        val session3 = connect(ptt3)
        val search = ArticleSearch(SearchKind.TITLE, "綠線")
        withTimeout(TEST_TIMEOUT_MS) { session3.searchArticles("MobileComm", search) }
        val result = withTimeout(TEST_TIMEOUT_MS) { session3.reloadBoard("MobileComm", search) }
        assertTrue("$result", result != null && result.filtered)
        assertEquals(listOf("/", "綠線\r", Keys.LEFT, "/", "綠線\r"), ptt3.received)
    }

    @Test
    fun reloadingGoesToTheNewestEvenWhenPttRemembersAnOldPosition() = runBlocking {
        // 真機：同一條連線再進同一個板，停在上次游標的位置。一起送出的 End 走到最後面，不用再按
        val ptt = bbs(ARTICLE_LIST, reentry = REMEMBERED_MIDDLE)
        val page = withTimeout(TEST_TIMEOUT_MS) { connect(ptt).reloadBoard("MobileComm") }
        assertEquals(153909, page!!.articles.mapNotNull { it.index }.max())
        assertEquals(listOf(RELOAD), ptt.received)
        // 那頁就是最後面、游標在最後一列（沒有置底文的板）：分不出是不是尾巴，上移、End 回到原位，不會卡住
        val ptt3 = bbs(ARTICLE_LIST, end = REMEMBERED_LAST_ROW)
        val page3 = withTimeout(TEST_TIMEOUT_MS) { connect(ptt3).reloadBoard("MobileComm") }
        assertEquals(153889, page3!!.cursor)
        assertEquals(listOf(RELOAD, Keys.UP, Keys.END), ptt3.received)
    }

    @Test
    fun showingABoardForTheFirstTimeGoesToTheNewest() = runBlocking {
        // 進板一起送出的 End 到最新那頁（再進板 PTT 停在舊的位置也一樣）；已經在最新那頁（看得到置底文）就不再按
        val ptt = bbs(FAVORITES_AFTER_BOARD, reentry = REMEMBERED_MIDDLE)
        val session = connect(ptt)
        val page = withTimeout(TEST_TIMEOUT_MS) { session.showBoard("MobileComm", newest = true) }
        assertEquals(153909, page.articles.mapNotNull { it.index }.max())
        withTimeout(TEST_TIMEOUT_MS) { session.showBoard("MobileComm", newest = true) }
        assertEquals(listOf(ENTER_MOBILECOMM), ptt.received)
    }

    @Test
    fun aLongBoardNoteEatsBothEndsSoNewestPressesEndAgain() = runBlocking {
        // 進板畫面比一頁長：兩個 End 都被它吃掉（翻到最後、按掉任意鍵），PTT 停在上次游標的位置。
        // 舊的那頁看不到置底文、最下面那列不空：再按 End 到最後面
        val ptt = bbs(FAVORITES_AFTER_BOARD, reentry = REMEMBERED_MIDDLE, longNote = true)
        val page = withTimeout(TEST_TIMEOUT_MS) { connect(ptt).showBoard("MobileComm", newest = true) }
        assertEquals(153909, page.articles.mapNotNull { it.index }.max())
        assertEquals(listOf(ENTER_MOBILECOMM, Keys.END), ptt.received)
        // 游標在那頁最後一列：先上移一列再 End（直接 End 的話，已經是最後一筆時 PTT 不回、會等到逾時）
        val ptt2 = bbs(FAVORITES_AFTER_BOARD, reentry = REMEMBERED_LAST_ROW, longNote = true)
        val page2 = withTimeout(TEST_TIMEOUT_MS) { connect(ptt2).showBoard("MobileComm", newest = true) }
        assertEquals(153909, page2.articles.mapNotNull { it.index }.max())
        assertEquals(listOf(ENTER_MOBILECOMM, Keys.UP, Keys.END), ptt2.received)
        // 不帶 newest（從文章退回來的那種）：不再走
        val ptt3 = bbs(FAVORITES_AFTER_BOARD, reentry = REMEMBERED_MIDDLE, longNote = true)
        withTimeout(TEST_TIMEOUT_MS) { connect(ptt3).showBoard("MobileComm") }
        assertEquals(listOf(ENTER_MOBILECOMM), ptt3.received)
    }

    @Test
    fun searchByTitlePressesSlashThenTypesTheKeyword() = runBlocking {
        val ptt = bbs(ARTICLE_LIST)
        val session = connect(ptt)
        val page = withTimeout(TEST_TIMEOUT_MS) { session.searchArticles("MobileComm", ArticleSearch(SearchKind.TITLE, "綠線")) }
        assertTrue("$page", page != null && page.filtered)
        assertEquals(listOf(1, 2, 3), page!!.articles.map { it.index })
        assertEquals(listOf("/", "綠線\r"), ptt.received)
        // 同一次搜尋再叫一次不重搜
        withTimeout(TEST_TIMEOUT_MS) { session.searchArticles("MobileComm", ArticleSearch(SearchKind.TITLE, "綠線")) }
        assertEquals(listOf("/", "綠線\r"), ptt.received)
    }

    @Test
    fun searchByAuthorAndByPushCountUseTheirOwnKeys() = runBlocking {
        val ptt = bbs(ARTICLE_LIST)
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) { session.searchArticles("MobileComm", ArticleSearch(SearchKind.AUTHOR, "chanifa")) }
        assertEquals(listOf("a", "chanifa\r"), ptt.received)
        // 換一種搜尋：先按左鍵離開上一次的結果
        withTimeout(TEST_TIMEOUT_MS) { session.searchArticles("MobileComm", ArticleSearch(SearchKind.PUSH_COUNT, "30")) }
        assertEquals(listOf("a", "chanifa\r", Keys.LEFT, "Z", "30\r"), ptt.received)
    }

    @Test
    fun noResultsDismissesTheMessageAndReturnsNull() = runBlocking {
        val ptt = bbs(ARTICLE_LIST)
        val session = connect(ptt)
        val page = withTimeout(TEST_TIMEOUT_MS) { session.searchArticles("MobileComm", ArticleSearch(SearchKind.TITLE, "沒有這種東西")) }
        assertEquals(null, page)
        assertEquals(listOf("/", "沒有這種東西\r", "\r"), ptt.received)
        assertTrue(session.page.let { it is Page.ArticleList && !it.filtered })
    }

    @Test
    fun aDeniedSearchSaysWhy() = runBlocking {
        val ptt = bbs(ARTICLE_LIST)
        val session = connect(ptt)
        val e = runCatching { withTimeout(TEST_TIMEOUT_MS) { session.searchArticles("MobileComm", ArticleSearch(SearchKind.TITLE, "太頻繁")) } }
            .exceptionOrNull()
        assertTrue("$e", e is UnexpectedScreenException && e.message == "搜尋太頻繁，請於 20:15 後再試。")
        assertEquals(listOf("/", "太頻繁\r", "\r"), ptt.received)
        assertTrue(session.page.let { it is Page.ArticleList && !it.filtered })
    }

    @Test
    fun openingASearchResultJumpsWithinTheResults() = runBlocking {
        val search = ArticleSearch(SearchKind.TITLE, "綠線")
        val ptt = bbs(ARTICLE_LIST)
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) { session.searchArticles("MobileComm", search) }
        withTimeout(TEST_TIMEOUT_MS) { session.openArticle("MobileComm", ArticleRef.Numbered(2), search) }
        assertEquals(listOf("/", "綠線\r", "2\r\r" + Keys.CTRL_L), ptt.received)
        // 從文章退回來還在結果裡：再找更前面的結果不用重搜（結果只有 3 筆，第 1 筆已經在畫面上）
        val older = withTimeout(TEST_TIMEOUT_MS) { session.olderArticles("MobileComm", before = 2, search) }
        assertEquals(listOf(1), older.indices())
        assertEquals(listOf("/", "綠線\r", "2\r\r" + Keys.CTRL_L, Keys.LEFT), ptt.received)
    }

    @Test
    fun searchingTheAuthorFromAnArticleInOtherResultsLeavesBothFirst() = runBlocking {
        // 文章頁「⋮」的「尋找同作者文章」：終端停在標題搜尋結果裡的一篇。左鍵退出文章、左鍵退出那次的結果，再搜作者
        val byTitle = ArticleSearch(SearchKind.TITLE, "綠線")
        val ptt = bbs(ARTICLE_LIST)
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) { session.searchArticles("MobileComm", byTitle) }
        withTimeout(TEST_TIMEOUT_MS) { session.openArticle("MobileComm", ArticleRef.Numbered(2), byTitle) }
        val page = withTimeout(TEST_TIMEOUT_MS) { session.searchArticles("MobileComm", ArticleSearch(SearchKind.AUTHOR, "chanifa")) }
        assertTrue("$page", page != null && page.filtered)
        assertEquals(listOf("/", "綠線\r", "2\r\r" + Keys.CTRL_L, Keys.LEFT, Keys.LEFT, "a", "chanifa\r"), ptt.received)
    }

    @Test
    fun showBoardLeavesTheSearchResultsWithLeftKey() = runBlocking {
        val ptt = bbs(SEARCH_RESULTS)
        val session = connect(ptt)
        val page = withTimeout(TEST_TIMEOUT_MS) { session.showBoard("MobileComm") }
        assertTrue(!page.filtered)
        assertEquals(listOf(Keys.LEFT), ptt.received)
    }

    @Test
    fun searchingWhileInSomeoneElsesResultsLeavesThemFirst() = runBlocking {
        // 終端停在不知道誰搜的結果（例如在終端畫面自己搜的）：先退出來再搜
        val ptt = bbs(SEARCH_RESULTS)
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) { session.searchArticles("MobileComm", ArticleSearch(SearchKind.TITLE, "綠線")) }
        assertEquals(listOf(Keys.LEFT, "/", "綠線\r"), ptt.received)
    }

    @Test
    fun favoritesFromSearchResultsNeedTwoLeftKeys() = runBlocking {
        val ptt = bbs(SEARCH_RESULTS)
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) { session.showFavorites() }
        assertEquals(listOf(Keys.LEFT, Keys.LEFT), ptt.received)
    }

    @Test
    fun backFromArticleToBoardToFavoritesWithLeftKey() = runBlocking {
        val ptt = bbs(ARTICLE)
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) { session.showBoard("MobileComm") }
        assertEquals(listOf(Keys.LEFT), ptt.received)
        withTimeout(TEST_TIMEOUT_MS) { session.showFavorites() }
        assertEquals(listOf(Keys.LEFT, Keys.LEFT), ptt.received)
    }

    /**
     * 有 [count] 篇的 Stock 板，一頁 [PAGE] 篇，一開始停在最後一頁（游標在最新那篇）。
     * 序號 Enter 跳到那篇，[jumpTop] 決定跳過去之後頁首是哪一篇（真的 PTT 把那篇排在頁面哪裡還沒確認，三種都測）；PgUp 往前一頁。
     */
    private fun longBoard(count: Int = 65, jumpTop: (Int) -> Int): FakePtt {
        var top = count - PAGE + 1
        var cursor = count
        fun screen(): String {
            val rows = (top until top + PAGE).filter { it in 1..count }.map { n ->
                Fixtures.articleRow(n, push = " 3", mark = '+', author = "author$n", title = "[標的] 第 $n 篇", cursor = if (n == cursor) ">" else " ")
            }
            val header = listOf("【板主:sysop】                股票板                    看板《Stock》", "[←]離開 [→]閱讀", "  編號    日 期 作  者       文  章  標  題")
            return (header + rows).joinToString("\n")
        }
        return FakePtt(screen()) { input ->
            Thread.sleep(LAG_MS)
            val jump = input.removeSuffix("\r").takeIf { input.endsWith("\r") }?.toIntOrNull()
            when {
                input == Keys.PAGE_UP -> {
                    top = maxOf(1, top - PAGE)
                    cursor = maxOf(1, cursor - PAGE)
                }
                jump != null -> {
                    cursor = jump
                    top = jumpTop(jump).coerceIn(1, count - PAGE + 1)
                }
                else -> return@FakePtt
            }
            show(screen())
        }
    }

    private fun List<ArticleSummary>.indices() = mapNotNull { it.index }

    @Test
    fun olderArticlesJumpsToTheOneBeforeTheOldestListed() = runBlocking {
        // 跳過去的那篇排在頁面中間：一次就多了大半頁
        val ptt = longBoard { it - 10 }
        val session = connect(ptt)
        val older = withTimeout(TEST_TIMEOUT_MS) { session.olderArticles("Stock", before = 46) }
        assertEquals((35..45).toList(), older.indices())
        assertEquals(listOf("45\r"), ptt.received)
    }

    @Test
    fun olderArticlesPagesUpWhenTheJumpLandsAtTheTopOfThePage() = runBlocking {
        // 跳過去的那篇在頁首：只多了一篇，再按 PgUp 往前一頁
        val ptt = longBoard { it }
        val session = connect(ptt)
        val older = withTimeout(TEST_TIMEOUT_MS) { session.olderArticles("Stock", before = 46) }
        assertEquals((25..45).toList(), older.indices())
        assertEquals(listOf("45\r", Keys.PAGE_UP), ptt.received)
    }

    @Test
    fun olderArticlesWithTheJumpLandingAtTheBottom() = runBlocking {
        val ptt = longBoard { it - PAGE + 1 }
        val session = connect(ptt)
        val older = withTimeout(TEST_TIMEOUT_MS) { session.olderArticles("Stock", before = 46) }
        assertEquals((26..45).toList(), older.indices())
        assertEquals(listOf("45\r"), ptt.received)
    }

    @Test
    fun olderArticlesStopAtTheFirstOne() = runBlocking {
        // 第 1 篇已經在畫面上：不再按 PgUp（翻到最前面再往前可能繞到最後一頁）
        val ptt = longBoard { it }
        val session = connect(ptt)
        val older = withTimeout(TEST_TIMEOUT_MS) { session.olderArticles("Stock", before = 2) }
        assertEquals(listOf(1), older.indices())
        assertEquals(listOf("1\r"), ptt.received)
    }

    @Test
    fun unknownScreenFailsInsteadOfPressingKeys() = runBlocking {
        val ptt = bbs(Fixtures.text("unknown"))
        val session = connect(ptt)
        try {
            withTimeout(TEST_TIMEOUT_MS) { session.showFavorites() }
            fail("認不出來的畫面不該走得到我的最愛")
        } catch (e: UnexpectedScreenException) {
            assertTrue(e.screen.contains("認不出來的畫面"))
        }
        assertEquals(emptyList<String>(), ptt.received)
    }

    private companion object {
        const val PASSWORD = "secret"
        const val LAG_MS = 300L
        const val TEST_TIMEOUT_MS = 10_000L
        const val PAGE = 20
        const val ACCOUNT_PROMPT = "請輸入代號，或以 guest 參觀，或以 new 註冊: "

        val LOGIN = Fixtures.text("login")
        val MAIN_MENU = Fixtures.text("main-menu")
        val FAVORITES = Fixtures.text("favorites")
        val FAVORITES_AFTER_BOARD = Fixtures.text("favorites-after-board")
        val ARTICLE_LIST = Fixtures.text("article-list")
        val ARTICLE_LIST_ON_153897 = moveCursor(ARTICLE_LIST, from = 20, to = 8)
        val STOCK_LIST = ARTICLE_LIST.replace("看板《MobileComm》", "看板《Stock》")
        /** 不在我的最愛這頁上的板。 */
        val TEST_LIST = ARTICLE_LIST.replace("看板《MobileComm》", "看板《Test》")

        /** 進板一次送出的鍵，後面都接這兩個 End（第一個按掉進板畫面、第二個到最後面）。 */
        val END_END = Keys.END + Keys.END
        /** 從看板列表退回主選單的左鍵（多的在主選單只移游標）。 */
        val LEFTS = Keys.LEFT.repeat(Session.MAX_LIST_DEPTH)
        /** 我的最愛這頁看得到 MobileComm：`s` 板名 Enter（游標移過去）、Enter（進板）、End End、Ctrl-L，一次送出。 */
        val ENTER_MOBILECOMM = "sMobileComm\r\r" + END_END + Keys.CTRL_L
        /** 「重整」：左鍵退出看板、再退到主選單，主選單的 `s` 板名 Enter、End End、Ctrl-L，一次送出。 */
        val RELOAD = Keys.LEFT + LEFTS + "sMobileComm\r" + END_END + Keys.CTRL_L

        // 搜尋的提示在最下面一列（pttbbs `ask_filter_predicate`；三個提示、找不到的訊息都真機確認過，見 screens/search-*.txt）
        val SEARCH_TITLE_PROMPT = replaceRow(ARTICLE_LIST, 23, "搜尋標題: ")
        val SEARCH_AUTHOR_PROMPT = replaceRow(ARTICLE_LIST, 23, "搜尋作者: ")
        val SEARCH_PUSH_PROMPT = replaceRow(ARTICLE_LIST, 23, "搜尋推文數高於多少 (<0則搜噓文數) 的文章: ")
        /** 搜太頻繁（pttbbs `activity_alert` → `alert_screen`：標題列、空一列、原因，最下面 `pressanykey`；照原始碼寫的）。 */
        val SEARCH_DENIED: String = (
            listOf("【 使用限制 】                    未搜尋", "", "  搜尋太頻繁，請於 20:15 後再試。", "") +
                List(19) { "" } + listOf("                               請按任意鍵繼續")
            ).joinToString("\n")
        val SEARCH_NO_RESULTS = replaceRow(ARTICLE_LIST, 23, " ◆ 找不到符合條件的文章。" + " ".repeat(38) + "[按任意鍵繼續]")

        /**
         * 搜尋結果：3 筆（序號是結果裡的第幾筆）、游標在最後一筆；標題列右邊是「系列《MobileComm》」、最下面那列是「系列文章」
         * （真機的樣子見 screens/search-results.txt）。
         */
        val SEARCH_RESULTS: String = run {
            val lines = ARTICLE_LIST.replace("看板《MobileComm》", "系列《MobileComm》").lines().toMutableList()
            for (row in 3 until 23) lines[row] = ""
            lines[3] = Fixtures.articleRow(1, push = "34", mark = '+', date = "11/06", author = "chanifa", title = "[討論] s24u 綠線案例")
            lines[4] = Fixtures.articleRow(2, push = "10", mark = '+', author = "chanifa", title = "[討論] 國外Samsung fold8 綠線案例")
            lines[5] = Fixtures.articleRow(3, push = " 2", author = "someone", title = "R: [討論] 國外Samsung fold8 綠線案例", kind = "R:", cursor = ">")
            lines[23] = " 系列文章  (^P)發表 (y)回應 (X)推文 (d)刪除 (→)閱讀 (b)進板  (←)離開 (h)說明"
            lines.joinToString("\n")
        }
        val SEARCH_RESULTS_ON_2 = moveCursor(SEARCH_RESULTS, from = 5, to = 4)

        /** 按 End 之後：游標在最後一篇置底文上（一進板的那頁，游標換到最下面一列）。 */
        val ARTICLE_LIST_AT_END = moveCursor(ARTICLE_LIST, from = 20, to = 22)
        /** 再進板時 PTT 停在上次的位置（真機：在 #153870 退出重進，列的是 153860～153879）：整頁一般文章、游標在中間。 */
        val REMEMBERED_MIDDLE = articlePage(153872..153891, cursor = 153882)
        /** 一樣是舊的位置，但游標剛好在這頁最後一列：說不定已經是最後一筆（沒有置底文的板），End 可能沒反應。 */
        val REMEMBERED_LAST_ROW = articlePage(153870..153889, cursor = 153889)
        val REMEMBERED_LAST_ROW_UP = articlePage(153870..153889, cursor = 153888)
        val ARTICLE_PAGES: List<String> = Fixtures.replay("article-pages").map { it.toString() }

        /** [ARTICLE_PAGES] 每一頁是第幾行到第幾行（進度列「目前顯示: 第 a~b 行」）。 */
        val ARTICLE_RANGES: List<IntRange> = ARTICLE_PAGES.map { page ->
            val (a, b) = Regex("""第 (\d+)~(\d+) 行""").find(page.lines().last { it.isNotBlank() })!!.destructured
            a.toInt()..b.toInt()
        }

        /** pmore 的「跳至此行」：`:` 行號 Enter。 */
        private val GOTO_LINE = Regex(""":(\d+)\r""")
        val ARTICLE = ARTICLE_PAGES[0]
        val WELCOME = Fixtures.text("login-welcome")
        val SYNCING = replaceRow("", 10, "          正在更新與同步線上使用者及好友名單，系統負荷量大時會需時較久...")

        /** 一頁 20 篇一般文章（[numbers]），游標在 [cursor] 那篇；標題列、最下面一列跟一進板的那頁一樣。 */
        fun articlePage(numbers: IntRange, cursor: Int): String {
            val lines = ARTICLE_LIST.lines().toMutableList()
            numbers.forEachIndexed { i, n -> lines[3 + i] = Fixtures.articleRow(n, cursor = if (n == cursor) ">" else " ") }
            return lines.joinToString("\n")
        }

        /** 列表的游標「>」從第 [from] 列移到第 [to] 列。 */
        fun moveCursor(screen: String, from: Int, to: Int): String {
            val lines = screen.lines()
            return replaceRow(replaceRow(screen, from, " " + lines[from].drop(1)), to, ">" + lines[to].drop(1))
        }

        // 真機：代號在第 20 列，密碼提示在下一列
        fun accountTyped(account: String) = replaceRow(LOGIN, 20, ACCOUNT_PROMPT + account)

        fun passwordPrompt(account: String) = replaceRow(accountTyped(account), 21, "請輸入您的密碼:")
    }
}
