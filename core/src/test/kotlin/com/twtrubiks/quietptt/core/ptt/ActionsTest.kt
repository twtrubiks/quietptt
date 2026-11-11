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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 加入我的最愛、找看板、推文、刪文、改標題、編輯文章：畫面的辨識，以及 `Session` 送了什麼鍵。
 * 提示的畫面照 pttbbs `board.c`／`name.c`／`bbs.c`／`edit.c` 原始碼排的（真機畫面在 `RealActionScreensTest`）；真機不一樣時以真機為準改。
 */
class ActionsTest {
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
    fun favoritesListingAllBoardsIsTold() {
        val favorites = ScreenParser.parse(Screen.of(FAVORITES)) as Page.Favorites
        assertFalse(favorites.allBoards)
        val all = ScreenParser.parse(Screen.of(ALL_BOARDS)) as Page.Favorites
        assertTrue(all.allBoards)
        assertEquals(listOf("Stock", "MobileComm", "Soft_Job", "Gossiping"), all.boards.map { it.name })
    }

    @Test
    fun boardNamePromptAndCandidates() {
        val prompt = ScreenParser.parse(Screen.of(SEARCH_PROMPT))
        assertTrue("$prompt", prompt is Page.BoardNamePrompt && !prompt.adding && prompt.typed == "" && prompt.candidates == null)
        val typed = ScreenParser.parse(Screen.of(replaceRow(SEARCH_PROMPT, 1, "請輸入看板名稱(按空白鍵自動搜尋): Mob"))) as Page.BoardNamePrompt
        assertEquals("Mob", typed.typed)
        val listed = ScreenParser.parse(Screen.of(CANDIDATES)) as Page.BoardNamePrompt
        assertEquals("Mobile", listed.typed)
        assertEquals(listOf("MobileComm", "Mobile_Fav", "MobilePay", "Mobile-game", "MobileSales"), listed.candidates)
        assertFalse(listed.more)
        val more = ScreenParser.parse(Screen.of(replaceRow(CANDIDATES, 23, " ◆ 按空白鍵可列出更多項目                    [按任意鍵繼續]"))) as Page.BoardNamePrompt
        assertTrue(more.more)
        val adding = ScreenParser.parse(Screen.of(ADD_PROMPT)) as Page.BoardNamePrompt
        assertTrue(adding.adding)
        assertTrue(ScreenParser.parse(Screen.of(KEYWORD_PROMPT)) is Page.BoardKeywordPrompt)
    }

    @Test
    fun commentDeleteTitlePromptsAreRecognized() {
        assertTrue(ScreenParser.parse(Screen.of(PUSH_TYPE_PROMPT)) is Page.PushPrompt)
        val comment = ScreenParser.parse(Screen.of(COMMENT_PROMPT))
        assertTrue("$comment", comment is Page.CommentPrompt && comment.type == PushType.PUSH)
        val aligned = ScreenParser.parse(Screen.of(replaceRow(ARTICLE, 23, "噓 someone     :")))
        assertTrue("$aligned", aligned is Page.CommentPrompt && aligned.type == PushType.BOO)
        val confirm = ScreenParser.parse(Screen.of(COMMENT_CONFIRM))
        assertTrue("$confirm", confirm is Page.ConfirmPrompt)
        assertTrue(ScreenParser.parse(Screen.of(DELETE_PROMPT)) is Page.DeletePrompt)
        val title = ScreenParser.parse(Screen.of(TITLE_PROMPT))
        assertTrue("$title", title is Page.TitlePrompt && title.current == "[問題] 谷歌地圖GPS無法定位問題")
        val yn = ScreenParser.parse(Screen.of(TITLE_CONFIRM))
        assertTrue("$yn", yn is Page.ConfirmPrompt && yn.text == "確定(Y/N)?[n]")
        val editor = ScreenParser.parse(Screen.of(EDITOR)) as Page.Editor
        assertEquals(1, editor.line)
        assertEquals(12, (ScreenParser.parse(Screen.of(editorAt(12))) as Page.Editor).line)
        // 列表的標題裡有這些字不算
        val list = ScreenParser.parse(Screen.of(replaceRow(LIST, 5, Fixtures.articleRow(153894, title = "標題：請確定刪除(Y/N)?"))))
        assertTrue("$list", list is Page.ArticleList)
    }

    @Test
    fun editableBodyStopsBeforeTheSignature() {
        val text = ArticleText(
            listOf(
                " 作者  someone (nick)                                        看板  Test",
                " 標題  [測試] 標題",
                " 時間  Fri Nov  6 09:19:28 2026",
                "───────────────────────────────────────",
                "第一行",
                "",
                "第三行 --",
                "--",
                "※ 發信站: 批踢踢實業坊(ptt.cc), 來自: 1.2.3.4 (臺灣)",
                "推 a: 推文",
            ),
            lastLine = 9,
            complete = true,
        )
        assertEquals(listOf("第一行", "", "第三行 --"), text.editableBody())
        // 沒有「--」就到「※ 發信站」為止；什麼都沒有就整篇
        assertEquals(listOf("第一行"), ArticleText(text.lines.take(4) + "第一行" + text.lines[8], 6, true).editableBody())
        assertEquals(listOf("第一行"), ArticleText(text.lines.take(4) + "第一行", 5, true).editableBody())
    }

    // ---- 我的最愛 ----

    @Test
    fun favoriteEditPromptsAreRecognized() {
        val confirm = ScreenParser.parse(Screen.of(DELETE_FAVORITE_CONFIRM))
        assertTrue("$confirm", confirm is Page.ConfirmPrompt && confirm.text.startsWith("你確定刪除嗎"))
        assertTrue(ScreenParser.parse(Screen.of(MOVE_FAVORITE_PROMPT)) is Page.MoveFavoritePrompt)
        val cursor2 = ScreenParser.parse(Screen.of(FAVORITES_CURSOR_2)) as Page.Favorites
        assertEquals(2, cursor2.cursor)
    }

    @Test
    fun removeFavoriteMovesTheCursorPressesDAndAnswersY() = runBlocking {
        val ptt = bbs(FAVORITES)
        val session = connect(ptt)
        val boards = withTimeout(TEST_TIMEOUT_MS) { session.removeFavorite(Board(2, "MobileComm", "資訊", "")) }
        assertEquals(listOf("2\r", "d", "y\r"), ptt.received)
        assertEquals(listOf("Stock", "Soft_Job", "Gossiping"), boards.map { it.name })
    }

    @Test
    fun moveFavoriteAnswersTheNewPosition() = runBlocking {
        val ptt = bbs(FAVORITES)
        val session = connect(ptt)
        val boards = withTimeout(TEST_TIMEOUT_MS) { session.moveFavorite(Board(2, "MobileComm", "資訊", ""), 1) }
        assertEquals(listOf("2\r", "M", "1\r"), ptt.received)
        assertEquals(listOf("MobileComm", "Stock", "Soft_Job", "Gossiping"), boards.map { it.name })
    }

    @Test
    fun aStaleListDoesNotRemoveOrMoveAnotherBoard() = runBlocking {
        // App 列的是舊的：以為第 2 個是 Gossiping，PTT 上第 2 個是 MobileComm。游標移過去看到不對就停，不按 d、M
        for (action in listOf<suspend Session.(Board) -> Unit>({ removeFavorite(it) }, { moveFavorite(it, 1) })) {
            val ptt = bbs(FAVORITES)
            val session = connect(ptt)
            try {
                withTimeout(TEST_TIMEOUT_MS) { session.action(Board(2, "Gossiping", "綜合", "")) }
                fail("序號對到別的板不該動手")
            } catch (e: UnexpectedScreenException) {
                assertTrue(e.message!!, e.message!!.contains("MobileComm"))
            }
            assertEquals(listOf("2\r"), ptt.received)
        }
    }

    @Test
    fun aDeleteQuestionLeftOnScreenIsAnsweredNo() = runBlocking {
        val ptt = bbs(DELETE_FAVORITE_CONFIRM)
        val session = connect(ptt)
        val boards = withTimeout(TEST_TIMEOUT_MS) { session.allFavorites() }
        assertEquals(listOf("n\r"), ptt.received)
        assertEquals(listOf("Stock", "MobileComm", "Soft_Job", "Gossiping"), boards.map { it.name })
    }

    @Test
    fun moveFavoriteOutOfRangeIsReported() = runBlocking {
        val ptt = bbs(FAVORITES)
        val session = connect(ptt)
        try {
            withTimeout(TEST_TIMEOUT_MS) { session.moveFavorite(Board(2, "MobileComm", "資訊", ""), 99) }
            fail("超出範圍不該成功")
        } catch (e: UnexpectedScreenException) {
            assertTrue(e.message!!, e.message!!.contains("輸入範圍有誤"))
        }
        assertEquals(listOf("2\r", "M", "99\r", "\r"), ptt.received)
    }

    @Test
    fun addFavoriteTypesTheBoardNameAfterA() = runBlocking {
        val ptt = bbs(FAVORITES)
        val session = connect(ptt)
        assertTrue(withTimeout(TEST_TIMEOUT_MS) { session.addFavorite("Test") })
        assertEquals(listOf("a", "Test\r"), ptt.received)
    }

    @Test
    fun addFavoriteAlreadyThereSendsNothing() = runBlocking {
        val ptt = bbs(FAVORITES)
        val session = connect(ptt)
        assertFalse(withTimeout(TEST_TIMEOUT_MS) { session.addFavorite("mobilecomm") })
        assertEquals(emptyList<String>(), ptt.received)
    }

    @Test
    fun addFavoriteOfAMissingBoardFails() = runBlocking {
        val ptt = bbs(FAVORITES)
        val session = connect(ptt)
        try {
            withTimeout(TEST_TIMEOUT_MS) { session.addFavorite("NoSuchBoard") }
            fail("沒有這個板不該成功")
        } catch (e: UnexpectedScreenException) {
            assertTrue(e.message!!, e.message!!.contains("沒有這個看板"))
        }
        assertEquals(listOf("a", "NoSuchBoard\r"), ptt.received)
    }

    @Test
    fun searchBoardsByNameListsCandidatesThenCancels() = runBlocking {
        val ptt = bbs(FAVORITES)
        val session = connect(ptt)
        val boards = withTimeout(TEST_TIMEOUT_MS) { session.searchBoards("Mob").boards }
        assertEquals(listOf("MobileComm", "Mobile_Fav", "MobilePay", "Mobile-game", "MobileSales"), boards.map { it.name })
        // s、打字加空白、（PTT 自動補成 Mobile）刪掉 6 個字加 Enter 取消
        assertEquals(listOf("s", "Mob ", Keys.BACKSPACE.repeat(6) + "\r"), ptt.received)
        assertTrue(session.page is Page.Favorites)
    }

    @Test
    fun searchBoardsByKeywordListsAllBoardsWithSlash() = runBlocking {
        val ptt = bbs(FAVORITES)
        val session = connect(ptt)
        val boards = withTimeout(TEST_TIMEOUT_MS) { session.searchBoards("手機").boards }
        assertEquals(listOf("MobileComm"), boards.map { it.name })
        assertEquals("[通訊] 手機o平板o資費 請進!", boards[0].title)
        // y 列出全部、/ 關鍵字、左鍵清掉關鍵字、y 回到我的最愛
        assertEquals(listOf("y", "/", "手機\r", Keys.LEFT, "y"), ptt.received)
        assertTrue(session.page.let { it is Page.Favorites && !it.allBoards })
    }

    @Test
    fun searchBoardsByKeywordWithNoMatchDismissesTheMessage() = runBlocking {
        val ptt = bbs(FAVORITES)
        val session = connect(ptt)
        val boards = withTimeout(TEST_TIMEOUT_MS) { session.searchBoards("沒有這種板").boards }
        assertEquals(emptyList<Board>(), boards)
        assertEquals(listOf("y", "/", "沒有這種板\r", "\r", "y"), ptt.received)
        assertTrue(session.page.let { it is Page.Favorites && !it.allBoards })
    }

    @Test
    fun showFavoritesLeavesTheAllBoardsListing() = runBlocking {
        val ptt = bbs(ALL_BOARDS)
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) { session.showFavorites() }
        assertEquals(listOf("y"), ptt.received)
    }

    // ---- 推文 ----

    @Test
    fun pushAnswersTypeTextAndConfirm() = runBlocking {
        val ptt = bbs(ARTICLE)
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) { session.push("MobileComm", ArticleRef.Numbered(153897), PushType.BOO, "嘘一下") }
        assertEquals(listOf("X", "2", "嘘一下\r", "y\r"), ptt.received)
        assertTrue(session.page is Page.ArticleList)
    }

    @Test
    fun pushAsTheAuthorSkipsTheTypeQuestion() = runBlocking {
        val ptt = bbs(ARTICLE, owner = true)
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) { session.push("MobileComm", ArticleRef.Numbered(153897), PushType.PUSH, "補充") }
        assertEquals(listOf("X", "補充\r", "y\r"), ptt.received)
    }

    @Test
    fun pushDeniedSaysWhy() = runBlocking {
        val ptt = bbs(ARTICLE, canPush = false)
        val session = connect(ptt)
        try {
            withTimeout(TEST_TIMEOUT_MS) { session.push("MobileComm", ArticleRef.Numbered(153897), PushType.PUSH, "x") }
            fail("不給推不該成功")
        } catch (e: UnexpectedScreenException) {
            assertEquals("本板禁止快速連續推文，請再等 42 秒", e.message)
        }
        assertEquals(listOf("X", "\r"), ptt.received)
    }

    // ---- 文章資訊（Q）、同標題 ----

    @Test
    fun articleInfoBoxIsRead() {
        val screen = Screen.of(POST_INFO_153897)
        assertTrue(ScreenParser.parse(screen) is Page.PressAnyKey)
        val info = ScreenParser.articleInfo(screen)!!
        assertEquals("1f2-Ab_9", info.aid)
        assertEquals("https://www.ptt.cc/bbs/MobileComm/M.1762412345.A.1B2.html", info.url)
        assertEquals(listOf("這一篇文章值 34 Ptt幣"), info.notes)
        assertNull(ScreenParser.articleInfo(Screen.of(LIST)))
    }

    @Test
    fun articleInfoMovesTheCursorAndPressesQTogether() = runBlocking {
        val ptt = bbs(LIST)
        val session = connect(ptt)
        val info = withTimeout(TEST_TIMEOUT_MS) { session.articleInfo("MobileComm", ArticleRef.Numbered(153897)) }
        assertEquals("1f2-Ab_9", info.aid)
        // 按掉框用 Enter，不用 Q（框裡再按 Q 是切換列表顯示文章價格）
        assertEquals(listOf("153897\rQ", "\r"), ptt.received)
        assertTrue("${session.page}", session.page is Page.ArticleList)
    }

    @Test
    fun articleInfoOnTheCursorOnlyPressesQ() = runBlocking {
        // 游標已經在那篇上：跳過去 PTT 什麼都不回，不打序號
        val ptt = bbs(LIST)
        val session = connect(ptt)
        val info = withTimeout(TEST_TIMEOUT_MS) { session.articleInfo("MobileComm", ArticleRef.Numbered(153909)) }
        assertEquals("1fA0zz-Q", info.aid)
        assertEquals(listOf("Q", "\r"), ptt.received)
    }

    @Test
    fun articleInfoOfAPinnedArticleCountsFromTheEnd() = runBlocking {
        val ptt = bbs(LIST)
        val session = connect(ptt)
        val info = withTimeout(TEST_TIMEOUT_MS) { session.articleInfo("MobileComm", ArticleRef.Pinned(fromBottom = 1)) }
        assertEquals(listOf(Keys.END + Keys.UP + "Q", "\r"), ptt.received)
        assertEquals("1Zz9_-aB", info.aid)
        assertEquals(listOf("特殊文章，無價格記錄"), info.notes)
    }

    @Test
    fun articleInfoFromInsideTheArticleLeavesItFirst() = runBlocking {
        // 文章頁「⋮」的「查詢文章資訊」：終端開著那篇，左鍵退回列表（游標就在那篇上，只按 Q），框按掉停在列表；
        // 之後文章頁往下讀，自己再打開那篇（游標還在上面，只按 Enter）
        val ptt = bbs(ARTICLE)
        val session = connect(ptt)
        val info = withTimeout(TEST_TIMEOUT_MS) { session.articleInfo("MobileComm", ArticleRef.Numbered(153909)) }
        assertEquals("1fA0zz-Q", info.aid)
        assertTrue("${session.page}", session.page is Page.ArticleList)
        val page = withTimeout(TEST_TIMEOUT_MS) { session.openArticle("MobileComm", ArticleRef.Numbered(153909)) }
        assertTrue("$page", page.progress.firstLine <= 1)
        assertEquals(listOf(Keys.LEFT, "Q", "\r", "\r" + Keys.CTRL_L), ptt.received)
    }

    @Test
    fun subjectDropsTheReplyMark() {
        val list = ScreenParser.parse(Screen.of(LIST)) as Page.ArticleList
        assertEquals("[問題] 合約到期，無換機需求 怎選", list.articles.first { it.index == 153893 }.subject)
        assertEquals("[討論] s24u 綠線案例", list.articles.first { it.index == 153897 }.subject)
        assertEquals("[新聞] 標題", ArticleSummary(1, null, null, "11/07", "someone", "轉 [新聞] 標題").subject)
        // 記號後面一定有空白：這是一般文章的標題
        assertEquals("轉職問題", ArticleSummary(1, null, null, "11/07", "someone", "轉職問題").subject)
    }

    // ---- 刪文、改標題、編輯 ----

    @Test
    fun deleteMovesTheCursorThenAnswersY() = runBlocking {
        val ptt = bbs(LIST)
        val session = connect(ptt)
        val list = withTimeout(TEST_TIMEOUT_MS) { session.deleteArticle("MobileComm", 153897) }
        assertTrue(list.articles.first { it.index == 153897 }.deleted)
        assertEquals(listOf("153897\r", "d", "y\r"), ptt.received)
    }

    @Test
    fun aNoticeAfterDeletingIsDismissed() = runBlocking {
        // 真機（Test 板）：答 y 之後列表上疊一個「請按任意鍵繼續」；文章確實刪了，不能當成失敗
        val ptt = bbs(LIST, deleteNotice = true)
        val session = connect(ptt)
        val list = withTimeout(TEST_TIMEOUT_MS) { session.deleteArticle("MobileComm", 153897) }
        assertTrue(list.articles.first { it.index == 153897 }.deleted)
        assertEquals(listOf("153897\r", "d", "y\r", "\r"), ptt.received)
    }

    @Test
    fun deleteWithNoReactionFailsQuickly() = runBlocking {
        // 不是自己的文章：PTT 一個字都不回
        val ptt = bbs(LIST, mine = false)
        val session = connect(ptt)
        val started = System.currentTimeMillis()
        try {
            withTimeout(TEST_TIMEOUT_MS) { session.deleteArticle("MobileComm", 153909) }
            fail("沒反應不該成功")
        } catch (e: UnexpectedScreenException) {
            assertTrue(e.message!!, e.message!!.contains("沒有反應"))
        }
        assertTrue(System.currentTimeMillis() - started < Session.SHORT_TIMEOUT_MS + 3_000)
        assertEquals(listOf("d"), ptt.received)
    }

    @Test
    fun editTitleClearsTheOldOneAndConfirms() = runBlocking {
        val ptt = bbs(LIST)
        val session = connect(ptt)
        val list = withTimeout(TEST_TIMEOUT_MS) { session.editTitle("MobileComm", 153909, "[問題] 新標題") }
        assertEquals("[問題] 新標題", list.articles.first { it.index == 153909 }.title)
        assertEquals(listOf("T", Keys.ctrl('Y') + "[問題] 新標題\r", "y\r"), ptt.received)
    }

    @Test
    fun editTitleThatCostsMoneyIsDeclined() = runBlocking {
        val ptt = bbs(LIST)
        val session = connect(ptt)
        try {
            withTimeout(TEST_TIMEOUT_MS) { session.editTitle("MobileComm", 153909, "要錢") }
            fail("要扣稿費不該答 y")
        } catch (e: UnexpectedScreenException) {
            assertEquals("修改此標題需扣除全部稿費 120 Ptt幣", e.message)
        }
        assertEquals(listOf("T", Keys.ctrl('Y') + "要錢\r", "n\r"), ptt.received)
    }

    @Test
    fun editArticleDeletesTheOldBodyAndTypesTheNewOne() = runBlocking {
        val ptt = bbs(LIST)
        val session = connect(ptt)
        withTimeout(TEST_TIMEOUT_MS) {
            session.editArticle("MobileComm", 153909, oldBody = OLD_BODY, newBody = "新的第一行\n新的第二行")
        }
        assertEquals(
            listOf(
                "E", // 游標已在 153909 上，直接按 E
                Keys.DOWN.repeat(4), // 本文從第 5 行開始
                Keys.ctrl('Y').repeat(OLD_BODY.size),
                "新的第一行\r新的第二行\r",
                Keys.ctrl('X'),
                "s\r",
            ),
            ptt.received,
        )
        assertTrue(session.page is Page.ArticleList)
    }

    @Test
    fun editArticleGivesUpWhenTheFileDoesNotMatch() = runBlocking {
        val ptt = bbs(LIST)
        val session = connect(ptt)
        try {
            withTimeout(TEST_TIMEOUT_MS) {
                // 以為本文只有一列：刪一列之後游標停的不是「--」，要放棄
                session.editArticle("MobileComm", 153909, oldBody = OLD_BODY.take(1), newBody = "x")
            }
            fail("對不上不該存檔")
        } catch (e: UnexpectedScreenException) {
            assertTrue(e.message!!, e.message!!.contains("沒有存檔"))
        }
        assertEquals(listOf("E", Keys.DOWN.repeat(4), Keys.ctrl('Y'), Keys.ctrl('X'), "a\r"), ptt.received)
        assertTrue(session.page is Page.ArticleList)
    }

    private suspend fun connect(ptt: FakePtt): Session {
        fakes += ptt.start()
        val session = Session(WebSocketTransport(client, url = ptt.url), scope)
        sessions += session
        session.connect()
        return session
    }

    /**
     * 假 PTT：我的最愛（a 增加、s 找板名、y 全部看板、/ 關鍵字）、MobileComm 列表（d 刪、T 改標題、E 編輯）、文章（X 推文）。
     * 畫面照原始碼排，按鍵的反應也照原始碼。
     */
    private fun bbs(
        first: String,
        owner: Boolean = false,
        canPush: Boolean = true,
        mine: Boolean = true,
        deleteNotice: Boolean = false,
    ): FakePtt {
        var at = first
        var editorLines = EDITOR_FILE.toMutableList()
        var cursor = 1
        fun editorScreen(): String {
            val rows = (0 until 23).map { editorLines.getOrElse(it) { "" } }
            return (rows + editorStatus(cursor)).joinToString("\n")
        }
        return FakePtt(first) { input ->
            Thread.sleep(LAG_MS)
            val next: String? = when {
                // 我的最愛
                at == FAVORITES && input == "a" -> ADD_PROMPT
                at == ADD_PROMPT && input == "Test\r" -> FAVORITES_WITH_TEST
                at == ADD_PROMPT && input.endsWith("\r") -> FAVORITES
                at == FAVORITES && input == "s" -> SEARCH_PROMPT
                at == SEARCH_PROMPT && input == "Mob " -> CANDIDATES
                at == CANDIDATES && input == Keys.BACKSPACE.repeat(6) + "\r" -> FAVORITES
                at == FAVORITES && input == "y" -> ALL_BOARDS
                // 刪除、移動（pttbbs `fav_cmd_toggle_or_del`、`imovefav`，照原始碼排的）
                at == FAVORITES && input == "2\r" -> FAVORITES_CURSOR_2
                at == FAVORITES_CURSOR_2 && input == "d" -> DELETE_FAVORITE_CONFIRM
                at == DELETE_FAVORITE_CONFIRM && input == "y\r" -> FAVORITES_WITHOUT_2
                at == DELETE_FAVORITE_CONFIRM && input.endsWith("\r") -> FAVORITES_CURSOR_2
                at == FAVORITES_CURSOR_2 && input == "M" -> MOVE_FAVORITE_PROMPT
                at == MOVE_FAVORITE_PROMPT && input == "1\r" -> FAVORITES_MOVED_2_TO_1
                at == MOVE_FAVORITE_PROMPT && input.endsWith("\r") -> MOVE_FAVORITE_BAD
                at == MOVE_FAVORITE_BAD -> FAVORITES_CURSOR_2
                at == ALL_BOARDS && input == "y" -> FAVORITES
                at == ALL_BOARDS && input == "/" -> KEYWORD_PROMPT
                at == KEYWORD_PROMPT && input == "手機\r" -> KEYWORD_RESULTS
                at == KEYWORD_PROMPT && input.endsWith("\r") -> NO_KEYWORD_MATCH
                at == NO_KEYWORD_MATCH && input == "\r" -> ALL_BOARDS
                at == KEYWORD_RESULTS && input == Keys.LEFT -> ALL_BOARDS
                // 推文
                at == ARTICLE && input == "X" -> when {
                    !canPush -> PUSH_DENIED
                    owner -> replaceRow(COMMENT_PROMPT, 23, "→ someone:")
                    else -> PUSH_TYPE_PROMPT
                }
                at == PUSH_DENIED && input == "\r" -> ARTICLE
                at == PUSH_TYPE_PROMPT && input == "2" -> replaceRow(COMMENT_PROMPT, 23, "噓 someone:")
                at == PUSH_TYPE_PROMPT && input.length == 1 -> COMMENT_PROMPT
                at.startsWith(ARTICLE_BODY) && at != ARTICLE && input.endsWith("\r") && input != "y\r" -> COMMENT_CONFIRM
                at == COMMENT_CONFIRM && input == "y\r" -> LIST
                // 列表：游標、刪除、改標題、編輯
                at == LIST && input == "153897\r" -> LIST_ON_153897
                // 文章資訊（`Q`，`view_postinfo`）：移游標跟 Q 一起送；Enter 按掉框
                at == LIST && input == "153897\rQ" -> POST_INFO_153897
                at == LIST && input == "Q" -> POST_INFO_153909
                at == LIST && input == Keys.END + Keys.UP + "Q" -> POST_INFO_PINNED
                at.contains("文章代碼(AID)") && input == "\r" -> LIST
                // 文章頁「⋮」的「查詢文章資訊」：左鍵退回列表（游標停在剛剛那篇 153909），之後 Enter 再打開
                at == ARTICLE && input == Keys.LEFT -> LIST
                at == LIST && input == "\r" + Keys.CTRL_L -> ARTICLE
                at == LIST_ON_153897 && input == "d" -> replaceRow(LIST_ON_153897, 1, "請確定刪除(Y/N)?[N] ")
                // 真機：刪掉之後列表上疊一個「請按任意鍵繼續」（列表沒清掉）
                at.startsWith(LIST_HEADER) && at.lines()[1].startsWith("請確定刪除") && input == "y\r" ->
                    if (deleteNotice) DELETED_NOTICE else LIST_DELETED
                at == DELETED_NOTICE && input == "\r" -> LIST_DELETED
                at == LIST && input == "d" && mine -> replaceRow(LIST, 1, "請確定刪除(Y/N)?[N] ")
                at == LIST && input == "T" -> TITLE_PROMPT
                at == TITLE_PROMPT && input == Keys.ctrl('Y') + "要錢\r" -> FEE_CONFIRM
                at == FEE_CONFIRM && input == "n\r" -> LIST
                at == TITLE_PROMPT && input.endsWith("\r") -> TITLE_CONFIRM
                at == TITLE_CONFIRM && input == "y\r" -> LIST_RETITLED
                at == LIST && input == "E" -> {
                    editorLines = EDITOR_FILE.toMutableList()
                    cursor = 1
                    editorScreen()
                }
                at.contains("║插入│") && input == Keys.ctrl('X') -> SAVE_PROMPT
                at.contains("║插入│") && input.all { it == Keys.ctrl('Y')[0] } -> {
                    repeat(input.length) { if (cursor - 1 < editorLines.size) editorLines.removeAt(cursor - 1) }
                    editorScreen()
                }
                at.contains("║插入│") && input.startsWith(Keys.DOWN) -> {
                    cursor += input.length / Keys.DOWN.length
                    editorScreen()
                }
                at.contains("║插入│") -> {
                    // 插入模式打字：在游標那一列前面插入
                    val typed = input.removeSuffix("\r").split("\r")
                    editorLines.addAll(cursor - 1, typed)
                    cursor += typed.size
                    editorScreen()
                }
                at == SAVE_PROMPT && (input == "s\r" || input == "a\r") -> LIST
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
        const val TEST_TIMEOUT_MS = 12_000L

        val FAVORITES = Fixtures.text("favorites")
        val FAVORITES_WITH_TEST = replaceRow(FAVORITES, 7, boardRow(5, "Test", "測試", "測試板"))
        // 游標從第 1 筆（第 3 列）移到第 2 筆（第 4 列）
        val FAVORITES_CURSOR_2: String = FAVORITES.lines().let { rows ->
            replaceRow(replaceRow(FAVORITES, 3, " " + rows[3].drop(1)), 4, ">" + rows[4].drop(1))
        }
        val DELETE_FAVORITE_CONFIRM = replaceRow(FAVORITES_CURSOR_2, 23, "你確定刪除嗎? [N/y]")
        // 刪掉第 2 筆：後面的往上補、序號重排，游標留在第 4 列
        val FAVORITES_WITHOUT_2: String = FAVORITES.lines().let { rows ->
            var s = replaceRow(FAVORITES, 4, ">" + rows[5].drop(1).replaceFirst(" 3 ", " 2 "))
            s = replaceRow(s, 5, rows[6].replaceFirst(" 4 ", " 3 "))
            replaceRow(s, 6, "")
        }
        val MOVE_FAVORITE_PROMPT = replaceRow(FAVORITES_CURSOR_2, 22, "請輸入新次序:")
        val MOVE_FAVORITE_BAD = replaceRow(FAVORITES_CURSOR_2, 23, " ◆ 輸入範圍有誤!                                                [按任意鍵繼續]")
        // 第 2 筆移到第 1 位：兩筆對調序號、游標留在第 4 列
        val FAVORITES_MOVED_2_TO_1: String = FAVORITES.lines().let { rows ->
            replaceRow(replaceRow(FAVORITES, 3, " " + rows[4].drop(1).replaceFirst(" 2 ", " 1 ")), 4, ">" + rows[3].drop(1).replaceFirst(" 1 ", " 2 "))
        }
        // 我的最愛按 y 列出全部看板：第 1 列變「[y]只列最愛」，最下面那列還是「我的最愛」（真機 `board-keyword-results`；
        // 「看板列表」是分類、即時熱門看板的列表）
        val ALL_BOARDS = replaceRow(
            replaceRow(FAVORITES, 1, "[y]只列最愛 [→]進入 [s]找看板 [/]搜尋 [c]新文章 [S]排序 [PgUp]上頁 [PgDn]下頁"),
            23,
            Fixtures.text("board-keyword-results").lines()[23],
        )
        val SEARCH_PROMPT = replaceRow(
            replaceRow(FAVORITES, 0, "【 搜尋全站看板 】  (若要限定搜尋範圍為目前列表請改用 Ctrl-S)"),
            1,
            "請輸入看板名稱(按空白鍵自動搜尋): ",
        )
        val ADD_PROMPT = replaceRow(
            replaceRow(FAVORITES, 0, "【 增加我的最愛 】"),
            1,
            "請輸入欲加入的看板名稱(按空白鍵自動搜尋)：",
        )
        val CANDIDATES: String = run {
            var s = replaceRow(SEARCH_PROMPT, 1, "請輸入看板名稱(按空白鍵自動搜尋): Mobile")
            s = replaceRow(s, 2, "────────────────────────────────相關資訊一覽表────────────────────────────────")
            s = replaceRow(s, 3, "MobileComm   Mobile_Fav")
            s = replaceRow(s, 4, "MobilePay    ...")
            s = replaceRow(s, 5, "Mobile-game")
            s = replaceRow(s, 6, "MobileSales")
            (7..23).fold(s) { acc, row -> replaceRow(acc, row, "") }
        }
        val KEYWORD_PROMPT = replaceRow(ALL_BOARDS, 22, "請輸入看板中文關鍵字:")
        val KEYWORD_RESULTS: String = (4..6).fold(replaceRow(ALL_BOARDS, 3, boardRow(1, "MobileComm", "資訊", "[通訊] 手機o平板o資費 請進!"))) { acc, row ->
            replaceRow(acc, row, "")
        }
        val NO_KEYWORD_MATCH = replaceRow(ALL_BOARDS, 23, " ◆ 沒有任何看板標題有此關鍵字                          [按任意鍵繼續]")

        val LIST = Fixtures.text("article-list")
        val LIST_HEADER = LIST.lines()[0]
        val LIST_ON_153897 = LIST.replace(">153909", " 153909").replace(" 153897", ">153897")
        val LIST_DELETED = replaceRow(LIST_ON_153897, 8, Fixtures.articleRow(153897, push = "34", mark = '+', date = "11/06", author = "-", title = "(本文已被刪除) [chanifa]", cursor = ">"))
        val DELETE_PROMPT = replaceRow(LIST, 1, "請確定刪除(Y/N)?[N] ")
        val DELETED_NOTICE = replaceRow(
            replaceRow(replaceRow(LIST_DELETED, 1, "請確定刪除(Y/N)?[N] y"), 14, "正在刪除文章: [討論] s24u 綠線案例"),
            23,
            " ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄ 請按任意鍵繼續 ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄   ",
        )
        val TITLE_PROMPT = replaceRow(LIST, 22, "標題：[問題] 谷歌地圖GPS無法定位問題")
        val TITLE_CONFIRM = replaceRow(LIST, 22, "確定(Y/N)?[n] ")
        val FEE_CONFIRM = replaceRow(LIST, 22, "修改此標題需扣除全部稿費 120 Ptt幣，確定(Y/N)?[n] ")
        val LIST_RETITLED = replaceRow(LIST, 20, Fixtures.articleRow(153909, push = "  ", mark = '+', date = "11/07", author = "ansonptt", title = "[問題] 新標題", cursor = ">"))

        /**
         * 文章資訊的框（pttbbs `view_postinfo`，照原始碼排的）：疊在 [base] 游標那列 [cursorRow] 的下面（放不下、或超過畫面 2/3 就放上面），
         * 內容的右邊界「│」在第 76 欄；框的另一邊清一列，最下面 `pressanykey`。
         */
        fun postInfo(base: String, cursorRow: Int, aid: String, title: String, price: String = "這一篇文章值 34 Ptt幣"): String {
            fun boxRow(text: String) = text + " ".repeat(maxOf(1, 76 - text.sumOf { if (Screen.isWide(it)) 2 else 1 })) + "│"
            val box = listOf(
                "┌" + "─".repeat(37) + "┐",
                boxRow("│ 文章代碼(AID): #$aid (MobileComm) [ptt.cc] $title"),
                boxRow("│ 文章網址: https://www.ptt.cc/bbs/MobileComm/M.1762412345.A.1B2.html"),
                boxRow("│ $price"),
                "└" + "─".repeat(37) + "┘",
            )
            val below = cursorRow + box.size + 1 <= 23 && cursorRow < 15
            val first = if (below) cursorRow + 1 else cursorRow - box.size
            var s = replaceRow(base, if (below) first + box.size else first - 1, " ")
            box.forEachIndexed { i, line -> s = replaceRow(s, first + i, line) }
            return replaceRow(s, 23, " ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄ 請按任意鍵繼續 ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄   ")
        }
        val POST_INFO_153897 = postInfo(LIST_ON_153897, cursorRow = 8, aid = "1f2-Ab_9", title = "[討論] s24u 綠線案例")
        val POST_INFO_153909 = postInfo(LIST, cursorRow = 20, aid = "1fA0zz-Q", title = "[問題] 谷歌地圖GPS")
        val POST_INFO_PINNED = postInfo(LIST, cursorRow = 21, aid = "1Zz9_-aB", title = "[置底] 2024下半年閒聊區", price = "特殊文章，無價格記錄")

        val ARTICLE = Fixtures.text("article")
        val ARTICLE_BODY = ARTICLE.lines().take(23).joinToString("\n")
        val PUSH_TYPE_PROMPT = replaceRow(ARTICLE, 23, "您覺得這篇文章 1.值得推薦 2.給它噓聲 3.只加→註解 [1]? ")
        val COMMENT_PROMPT = replaceRow(ARTICLE, 23, "推 someone:")
        val COMMENT_CONFIRM = replaceRow(ARTICLE, 23, "推 someone:嘘一下                                        確定[y/N]:")
        val PUSH_DENIED = replaceRow(ARTICLE, 23, " ◆ 本板禁止快速連續推文，請再等 42 秒                  [按任意鍵繼續]")

        /** 照 pttbbs `brdlist_coldefs` 排一列看板：序號 7 欄、空白、未讀 2 欄、板名 13 欄、分類 4 欄、空白、◎、標題。 */
        fun boardRow(index: Int, name: String, category: String, title: String) =
            "%7d ".format(index) + "  " + name.padEnd(13) + category + " ◎" + title

        fun editorStatus(line: Int) = " 編輯文章  (^Z)說明 (^X)存檔 (^C)色碼 (^V)彩色   ║插入│aipr║%3d:  1".format(line)
        val EDITOR = replaceRow(List(24) { "" }.joinToString("\n"), 23, editorStatus(1))
        fun editorAt(line: Int) = replaceRow(EDITOR, 23, editorStatus(line))
        val OLD_BODY = listOf("第一行", "", "第三行")
        val EDITOR_FILE = listOf(
            "作者: ansonptt (安森) 看板: MobileComm",
            "標題: [問題] 谷歌地圖GPS無法定位問題",
            "時間: Fri Nov  7 10:00:00 2026",
            "",
        ) + OLD_BODY + listOf("--", "※ 發信站: 批踢踢實業坊(ptt.cc), 來自: 1.2.3.4 (臺灣)")
        val SAVE_PROMPT: String = replaceRow(
            replaceRow(List(24) { "" }.joinToString("\n"), 0, "檔案處理"),
            2,
            "確定要儲存檔案嗎？ ",
        )
    }
}
