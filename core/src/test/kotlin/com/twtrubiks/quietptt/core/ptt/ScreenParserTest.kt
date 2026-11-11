package com.twtrubiks.quietptt.core.ptt

import com.twtrubiks.quietptt.core.terminal.Attr
import com.twtrubiks.quietptt.core.terminal.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 畫面辨識。fixture 在 `core/src/test/resources/screens/`，大多是 tools/capture 在真的 PTT 錄的（見那裡的 README）。
 * 照 PTT 欄位排的單列用 [Fixtures.articleRow]。
 */
class ScreenParserTest {
    @Test
    fun recognizesLogin() {
        val page = ScreenParser.parse(Fixtures.screen("login"))
        assertTrue("$page", page is Page.Login)
        assertEquals(null, (page as Page.Login).error)
    }

    @Test
    fun recognizesLoginError() {
        val page = ScreenParser.parse(Fixtures.screen("login-wrong-password")) as Page.Login
        assertTrue("${page.error}", page.error?.startsWith("密碼不對或無此帳號") == true)
    }

    /** 登入途中的提示疊在同一個畫面上，最下面那個才是正在問的。 */
    private fun loginWith(vararg rows: Pair<Int, String>): Page {
        val lines = Fixtures.text("login").lines().toMutableList()
        for ((row, text) in rows) lines[row] = text
        return ScreenParser.parse(Screen.of(lines.joinToString("\n")))
    }

    @Test
    fun passwordPromptBelowTheAccountPrompt() {
        val page = loginWith(21 to "請輸入代號，或以 guest 參觀，或以 new 註冊：someone", 22 to "請輸入您的密碼：")
        assertTrue("$page", page is Page.PasswordPrompt)
    }

    @Test
    fun duplicateLoginQuestionBelowThePasswordPrompt() {
        val page = loginWith(21 to "請輸入代號：someone", 22 to "請輸入您的密碼：", 23 to "您想刪除其他重複登入的連線嗎？[Y/n]")
        assertTrue("$page", page is Page.DuplicateLoginPrompt)
    }

    @Test
    fun wrongPasswordMessageBelowThePasswordPromptIsLoginWithError() {
        val page = loginWith(21 to "請輸入代號：someone", 22 to "請輸入您的密碼：", 23 to "密碼不對或無此帳號。")
        assertTrue("$page", page is Page.Login && page.error == "密碼不對或無此帳號。")
    }

    @Test
    fun articleBodyQuotingAPromptIsStillAnArticle() {
        val lines = Fixtures.text("article").lines().toMutableList()
        lines[5] = "    請按任意鍵繼續"
        assertTrue(ScreenParser.parse(Screen.of(lines.joinToString("\n"))) is Page.ArticleView)
    }

    @Test
    fun recognizesMainMenu() {
        assertTrue(ScreenParser.parse(Fixtures.screen("main-menu")) is Page.MainMenu)
    }

    @Test
    fun welcomeAfterLoginAndBoardWelcomeAskForAnyKey() {
        assertTrue(ScreenParser.parse(Fixtures.screen("login-welcome")) is Page.PressAnyKey)
        assertTrue(ScreenParser.parse(Fixtures.screen("board-welcome")) is Page.PressAnyKey)
    }

    @Test
    fun parsesFavorites() {
        // 真機：人氣跟板主中間沒有空格（「爆!rayccccc/P…」），照欄位切
        val page = ScreenParser.parse(Fixtures.screen("favorites")) as Page.Favorites
        assertEquals(
            listOf(
                Board(1, "Stock", "學術", "[股票]", unread = true, popularity = Popularity("爆")),
                Board(2, "MobileComm", "資訊", "[通訊] 手機o平板o資費 請進!", unread = true, popularity = Popularity("HOT")),
                Board(3, "Soft_Job", "工作", "[軟工] One Last Kiss", unread = true, popularity = Popularity("10")),
                Board(4, "Gossiping", "綜合", "[八卦] WSS連線不通請改用SSH連", unread = true, popularity = Popularity("爆")),
            ),
            page.boards,
        )
    }

    @Test
    fun popularityKeepsTheColourPttDrewItIn() {
        // pttbbs board.c 的 col_nuser：「爆!」是幾千人只看得出顏色。Stock 那格上亮藍（`1;34`，5000 人以上）；
        // Soft_Job 換成「 3 」（10 人以下是「%2d 」，前面是空白、預設色）。fixture 沒有顏色，自己上
        val lines = Fixtures.text("favorites").lines().toMutableList()
        lines[5] = lines[5].replace("10 MOONY135", " 3 MOONY135")
        val plain = Screen.of(lines.joinToString("\n"))
        val screen = Screen(
            plain.cells.mapIndexed { row, cells ->
                if (row != 3) cells else cells.mapIndexed { col, cell -> if (col in 64..66) cell.copy(attr = Attr(fg = 4, bold = true)) else cell }
            },
        )
        val boards = (ScreenParser.parse(screen) as Page.Favorites).boards
        assertEquals(
            listOf(Popularity("爆", fg = 4, bold = true), Popularity("HOT"), Popularity("3"), Popularity("爆")),
            boards.map { it.popularity },
        )
    }

    @Test
    fun favoritesFolderReadBoardWithoutPopularityAndSeparatorLine() {
        val lines = Fixtures.text("favorites").lines().toMutableList()
        // brdlist_folder：「%7d %c MyFavFolder  目錄 □標題」
        lines[7] = "      5   MyFavFolder  目錄 □我的目錄"
        lines[8] = "      6   AskBoard     新手 ◎【問與板有關的問題，非ask板】        akaume"
        lines[9] = "------------      ------------------------------------------"
        val page = ScreenParser.parse(Screen.of(lines.joinToString("\n"))) as Page.Favorites
        assertEquals(
            listOf(
                Board(5, "MyFavFolder", "", "我的目錄", unread = false, isFolder = true),
                Board(6, "AskBoard", "新手", "【問與板有關的問題，非ask板】", unread = false),
            ),
            page.boards.drop(4),
        )
    }

    /** 第 0 列換成 [title]、其他列照 favorites fixture。 */
    private fun favoritesTitled(title: String): Page {
        val lines = Fixtures.text("favorites").lines().toMutableList()
        lines[0] = title
        return ScreenParser.parse(Screen.of(lines.joinToString("\n")))
    }

    @Test
    fun favoritesAreTitledBoardListOnTheRealSite() {
        // 真機截圖：按 f Enter 之後第 0 列是「【看板列表】」加置中的站名，沒有「我的最愛」
        val page = ScreenParser.parse(Fixtures.screen("favorites"))
        assertTrue("$page", page is Page.Favorites)
        assertTrue("$page", favoritesTitled("【我 的 最愛】") is Page.Unknown)
    }

    @Test
    fun favoritesAfterVisitingABoardCarryThatBoardOnTheRight() {
        // 真機：從看板退回我的最愛，標題列右邊多了「看板《MobileComm》」——以前被認成那個板的文章列表，卡 15 秒
        val page = ScreenParser.parse(Fixtures.screen("favorites-after-board"))
        assertTrue("$page", page is Page.Favorites && page.boards.size == 4)
    }

    @Test
    fun mainMenuAfterVisitingABoardIsStillTheMainMenu() {
        val lines = Fixtures.text("main-menu").lines().toMutableList()
        lines[0] = "【主功能表】                 批踢踢實業坊                    看板《Stock》"
        assertTrue(ScreenParser.parse(Screen.of(lines.joinToString("\n"))) is Page.MainMenu)
    }

    @Test
    fun boardTitleMentioningBoardListIsStillABoard() {
        val page = favoritesTitled("【板主:someone】        【看板列表】分享區         看板《MyFav》")
        assertTrue("$page", page is Page.ArticleList && page.board == "MyFav")
    }

    @Test
    fun parsesArticleList() {
        // 真機：MobileComm 進板的第一頁，18 篇加 2 篇置底，游標在最新那篇
        val page = ScreenParser.parse(Fixtures.screen("article-list")) as Page.ArticleList
        assertEquals("MobileComm", page.board)
        assertEquals((153892..153909).toList(), page.articles.mapNotNull { it.index })
        assertEquals(20, page.articles.size)
        val byIndex = page.articles.associateBy { it.index }
        assertEquals(ArticleSummary(153892, '+', 6, "11/05", "eulbos", "[討論] 大家買手機都捏上去多少?"), byIndex[153892])
        assertEquals(ArticleSummary(153893, '+', 1, "11/05", "askkkkk", "R: [問題] 合約到期，無換機需求 怎選"), byIndex[153893])
        assertEquals(ArticleSummary(153897, '+', 34, "11/06", "chanifa", "[討論] s24u 綠線案例"), byIndex[153897])
        assertEquals(ArticleSummary(153906, null, 13, "11/07", "-", "(本文已被刪除) [Amed21]"), byIndex[153906])
        assertEquals(ArticleSummary(153909, '+', null, "11/07", "ansonptt", "[問題] 谷歌地圖GPS無法定位問題"), byIndex[153909])
        assertEquals(
            listOf(
                ArticleSummary(null, 'm', 7, "7/18", "starskyjth", "[置底] 2024下半年閒聊區"),
                ArticleSummary(null, '!', null, "2/22", "starskyjth", "[公告] MobileComm 板規"),
            ),
            page.articles.takeLast(2),
        )
    }

    /** 標題列＋說明＋欄位名稱，下面接 [rows]（用 [Fixtures.articleRow] 照 PTT 的欄位排）。 */
    private fun articleList(vararg rows: String): Page.ArticleList {
        val text = (listOf("【板主:someone】          股票板          看板《Stock》", "", "") + rows).joinToString("\n")
        return ScreenParser.parse(Screen.of(text)) as Page.ArticleList
    }

    @Test
    fun pushCountRunsStraightIntoTheDate() {
        // 真機：MobileComm 整頁只認得出沒推文的那篇和置底文——推文數後面緊接著日期（「+ 511/07」），中間沒有空格
        val page = articleList(
            Fixtures.articleRow(735478, push = " 5", mark = '+', author = "kevin", title = "[新聞] 台積電"),
            Fixtures.articleRow(735479, push = "15", mark = '~', author = "bird", title = "[請益] 問題", kind = "R:"),
            Fixtures.articleRow(735480, push = "爆", author = "cat", title = "[心得] 心得"),
            Fixtures.articleRow(735481, push = "X2", mark = 'm', author = "dog", title = "[閒聊] 盤後"),
            Fixtures.articleRow(735482, push = "XX", author = "eagle", title = "[標的] 2330", kind = "轉"),
            Fixtures.articleRow(735483, push = "--", author = "fox", title = "[公告] 鎖起來", kind = "鎖"),
            Fixtures.articleRow(735484, author = "ansonptt", title = "[問題] 谷歌地圖GPS無法定位問題", cursor = ">"),
            Fixtures.articleRow(null, push = " 7", date = " 7/18", author = "starskyjth", title = "[置底] 2024下半年閒聊區"),
        )
        assertEquals(
            listOf(
                ArticleSummary(735478, '+', 5, "11/07", "kevin", "[新聞] 台積電"),
                ArticleSummary(735479, '~', 15, "11/07", "bird", "R: [請益] 問題"),
                ArticleSummary(735480, null, 100, "11/07", "cat", "[心得] 心得"),
                ArticleSummary(735481, 'm', -20, "11/07", "dog", "[閒聊] 盤後"),
                ArticleSummary(735482, null, -100, "11/07", "eagle", "轉 [標的] 2330"),
                ArticleSummary(735483, null, null, "11/07", "fox", "鎖 [公告] 鎖起來"),
                ArticleSummary(735484, null, null, "11/07", "ansonptt", "[問題] 谷歌地圖GPS無法定位問題"),
                ArticleSummary(null, null, 7, "7/18", "starskyjth", "[置底] 2024下半年閒聊區"),
            ),
            page.articles,
        )
    }

    @Test
    fun deletedArticleAndAuthorWithSpacesInTitle() {
        val page = articleList(Fixtures.articleRow(2581, author = "-", title = "(已被BlueBird5566刪除) <aikaimolie> 4-2-1"))
        assertEquals(listOf(ArticleSummary(2581, null, null, "11/07", "-", "(已被BlueBird5566刪除) <aikaimolie> 4-2-1")), page.articles)
    }

    @Test
    fun indexHiddenUnderTheOldStyleCursorComesFromTheNeighbours() {
        // 舊式游標「●」佔 0～1 欄，蓋掉 6 位數序號的第一位：「●35479」其實是 735479
        val hidden = "●" + Fixtures.articleRow(735479, title = "游標在這").drop(2)
        val page = articleList(Fixtures.articleRow(735478), hidden, Fixtures.articleRow(735480))
        assertEquals(listOf(735478, 735479, 735480), page.articles.map { it.index })
        assertEquals("游標在這", page.articles[1].title)
    }

    @Test
    fun truncatedIndexOfADeletedArticleComesFromTheNeighbours() {
        // 被刪的文章站方可能用 %7d 印序號，塞不進 6 欄就截成「 735…」
        val truncated = "  735…" + Fixtures.articleRow(735479, author = "-", title = "(本文已被刪除)").drop(7)
        val page = articleList(Fixtures.articleRow(735478), truncated)
        assertEquals(listOf(735478, 735479), page.articles.map { it.index })
    }

    @Test
    fun searchResultsAreTheListWhoseTitleSaysSeries() {
        // 真機（MobileComm 搜「問題」）：標題列右邊是「系列《MobileComm》」（不是「看板《》」）、最下面一列是「系列文章」，
        // 序號是結果裡的第幾筆（這個板有 15 萬多篇，結果 67366 筆）、游標停在最後一筆（最新的）
        val results = ScreenParser.parse(Fixtures.screen("search-results")) as Page.ArticleList
        assertEquals(true, results.filtered)
        assertEquals("MobileComm", results.board)
        assertEquals((67347..67366).toList(), results.articles.map { it.index })
        assertTrue(results.articles.all { it.title.contains("問題") })
        assertEquals(67366, results.cursor)
        assertEquals(false, (ScreenParser.parse(Fixtures.screen("article-list")) as Page.ArticleList).filtered)
    }

    @Test
    fun theHeaderLineTellsHowManyAreOnTheBoard() {
        // 真機：第 2 列欄位標題右邊「人氣:179」（pttbbs readtitle），搜尋結果也有
        assertEquals(179, (ScreenParser.parse(Fixtures.screen("article-list")) as Page.ArticleList).online)
        assertEquals(192, (ScreenParser.parse(Fixtures.screen("search-results")) as Page.ArticleList).online)
        // 冷靜中的板那裡是「[靜]」，沒有人數
        val cooldown = Fixtures.text("article-list").replaceFirst("人氣:179", "[靜]")
        assertEquals(null, (ScreenParser.parse(Screen.of(cooldown)) as Page.ArticleList).online)
    }

    @Test
    fun theCursorIsOnTheNewestArticleAfterEnteringTheBoard() {
        // 真機：進板之後「>」在最新的那篇（置底文上面那列）
        assertEquals(153909, (ScreenParser.parse(Fixtures.screen("article-list")) as Page.ArticleList).cursor)
        // 舊式游標「●」蓋掉序號的第一位：序號照上下列推
        val lines = Fixtures.text("article-list").lines().toMutableList()
        lines[20] = "●" + lines[20].substring(2)
        assertEquals(153909, (ScreenParser.parse(Screen.of(lines.joinToString("\n"))) as Page.ArticleList).cursor)
    }

    @Test
    fun aDeletedArticleHasADashForItsAuthor() {
        // 真機：「 153906  1311/07 -            □ (本文已被刪除) [Amed21]」，在它上面按 Enter PTT 什麼都不回
        val page = ScreenParser.parse(Fixtures.screen("article-list")) as Page.ArticleList
        assertEquals(listOf(153906), page.articles.filter { it.deleted }.map { it.index })
        assertEquals("(本文已被刪除) [Amed21]", page.articles.single { it.deleted }.title)
    }

    @Test
    fun theSearchPromptIsOnTheBottomRowOfThePlainList() {
        // 真機：按 / 之後最下面一列是「搜尋標題: 」，其他照舊（還是整個列表）
        val page = ScreenParser.parse(Fixtures.screen("search-title-prompt")) as Page.ArticleList
        assertEquals(false, page.filtered)
        assertTrue(page.screen.lines.last().contains(SearchKind.TITLE.prompt))
    }

    @Test
    fun noSearchResultsIsAMessageWaitingForAnyKey() {
        // 真機：「 ◆ 找不到符合條件的文章。 … [按任意鍵繼續]」（pttbbs `vshowmsg`）：右邊是「[按任意鍵繼續]」，不是整列置中的「請按任意鍵繼續」
        assertTrue(ScreenParser.parse(Fixtures.screen("search-no-results")) is Page.PressAnyKey)
    }

    @Test
    fun rowsThatAreNotArticlesAreSkipped() {
        val page = articleList("", "    本看板目前沒有文章", Fixtures.articleRow(1))
        assertEquals(listOf(1), page.articles.map { it.index })
    }

    @Test
    fun parsesArticleView() {
        val page = ScreenParser.parse(Fixtures.screen("article")) as Page.ArticleView
        assertEquals(ArticleProgress(page = 1, pages = 7, percent = 5, firstLine = 1, lastLine = 22), page.progress)
        assertEquals(23, page.body.size)
        assertTrue(page.body[0], page.body[0].startsWith(" 作者  chanifa") && page.body[0].endsWith("看板  MobileComm"))
    }

    @Test
    fun longArticleWithoutTotalPages() {
        // 真機：很長的文章一開始只寫「瀏覽 第 1 頁 (  1%)」，沒有「/總頁數」——以前認不出來，App 卡在打開文章
        val page = ScreenParser.parse(Fixtures.screen("article-no-total-pages")) as Page.ArticleView
        assertEquals(ArticleProgress(page = 1, pages = null, percent = 1, firstLine = 1, lastLine = 22), page.progress)
        assertTrue(!page.progress.isLast)
    }

    @Test
    fun unknownScreenFallsBack() {
        assertTrue(ScreenParser.parse(Fixtures.screen("unknown")) is Page.Unknown)
    }

    @Test
    fun classRootListsCategoriesWithCursorAndHotEntry() {
        // 真機：14 項，「即時熱門看板」是最後一項，不是第 1 項
        val page = ScreenParser.parse(Fixtures.screen("class-root")) as Page.ClassRoot
        assertEquals((1..14).toList(), page.entries.map { it.index })
        assertEquals(1, page.cursor)
        assertEquals(ClassEntry(1, "市民廣場     報告站長 PTT咬我"), page.entries.first())
        assertEquals("市民廣場", page.entries.first().name)
        assertEquals("報告站長 PTT咬我", page.entries.first().description)
        assertEquals(ClassEntry(12, "10/31～11/13 達卡青奧", unread = true), page.entries[11])
        val hot = page.entries.last()
        assertTrue("$hot", hot.isHot && !hot.unread)
        assertEquals(listOf(14), page.entries.filter { it.isHot }.map { it.index })
    }

    @Test
    fun classBoardListsAreInClassByTheirFooterNotTheirYHint() {
        // 真機：分類、即時熱門看板的列表第 1 列是「[y]只列最愛」（跟我的最愛按 y 列出全部一樣），最下面那列是「看板列表」
        val hot = ScreenParser.parse(Fixtures.screen("hot-boards")) as Page.Favorites
        assertTrue(hot.inClass && hot.allBoards)
        assertEquals((1..20).toList(), hot.boards.map { it.index })
        assertEquals("Gossiping", hot.boards.first().name)
        assertEquals(1, hot.cursor)
        // 人氣排名：前 4 個爆、接著 HOT、最後幾個是人數（真機）
        assertEquals(listOf("爆", "爆", "爆", "爆", "HOT"), hot.boards.take(5).map { it.popularity?.text })
        assertEquals(listOf("99", "97", "83"), hot.boards.takeLast(3).map { it.popularity?.text })
        val boards = ScreenParser.parse(Fixtures.screen("class-boards")) as Page.Favorites
        assertTrue(boards.inClass)
        assertEquals(Board(2, "AndroidDev", "程設", "[AndroidDev] 開發者們，歡迎光臨"), boards.boards[1])
        assertTrue(!(ScreenParser.parse(Fixtures.screen("favorites")) as Page.Favorites).inClass)
        // 我的最愛按 y 列出全部看板：第 1 列也是「[y]只列最愛」，但最下面那列還是「我的最愛」（真機）
        val all = ScreenParser.parse(Fixtures.screen("board-keyword-results")) as Page.Favorites
        assertTrue(all.allBoards && !all.inClass)
    }

    @Test
    fun subClassesAreFoldersWithTheirCategory() {
        val page = ScreenParser.parse(Fixtures.screen("class-folders")) as Page.Favorites
        assertEquals(15, page.boards.size)
        assertTrue(page.boards.all { it.isFolder })
        assertEquals(Board(1, "ComGame-Serv", "組務", "戰略高手服務中心", isFolder = true), page.boards.first())
        assertEquals("看板資料封存區", page.boards.last().title)
        // 子分類那一欄也可能有人數（真機：DigitalLife「 3 」）
        assertEquals(Popularity("3"), page.boards[2].popularity)
    }

    @Test
    fun mailMenuAndNoMailNotice() {
        val menu = ScreenParser.parse(Fixtures.screen("mail-menu")) as Page.MailMenu
        assertTrue(!menu.noMail)
        // 「 ◆ 您沒有來信 … [按任意鍵繼續]」：不是一般的任意鍵
        val empty = ScreenParser.parse(Fixtures.screen("mail-menu-empty"))
        assertTrue("$empty", empty is Page.MailMenu && empty.noMail)
    }

    @Test
    fun mailListRowsWithStateDateAuthorTitle() {
        val page = ScreenParser.parse(Fixtures.screen("mail-list")) as Page.MailList
        assertEquals(
            listOf(
                MailSummary(1, ' ', "5/21", "SYSOP", "[系統通知] 歡迎加入"),
                MailSummary(2, 'r', "11/07", "someone", "R: 回你的信"),
                MailSummary(3, ' ', "11/07", "[備.忘.錄]", "(someone) Re: 回你的信"),
                MailSummary(4, 'M', "11/08", "third", "轉 [問題] 轉寄的標題"),
                MailSummary(5, '+', "11/08", "another", "第一封信的標題"),
            ),
            page.mails,
        )
        assertEquals(5, page.cursor)
        assertEquals(listOf(4, 5), page.mails.filter { it.unread }.map { it.index })
    }

    @Test
    fun aPageWithReaderDependentTextHasNoLineNumbersButIsStillAnArticle() {
        // 真機（信箱裡的系統信）：「目前顯示: 第 a~b 行」被換成「▲此頁內容會依閱讀者不同,原文未必有您的資料」
        val page = ScreenParser.parse(Fixtures.screen("mail-system")) as Page.ArticleView
        assertEquals(ArticleProgress(page = 1, pages = 1, percent = 100, firstLine = 1, lastLine = 7), page.progress)
        val text = ArticleText.EMPTY + page
        assertTrue(text.complete)
        assertEquals(7, text.lines.size)
        // 有標頭的第 1 頁、還沒到最後：分隔線不算一行，最後一列是第 22 行（跟「目前顯示: 第 01~22 行」一樣）
        val article = Fixtures.text("article").lines().take(23) +
            "  瀏覽 第 1/3 頁 ( 30%)  ▲此頁內容含移位碼,可能會顯示偽造的系統訊息  (←)離開"
        val first = ScreenParser.parse(Screen.of(article.joinToString("\n"))) as Page.ArticleView
        assertEquals(1 to 22, first.progress.firstLine to first.progress.lastLine)
        // 後面幾頁算不出行號：認不得
        val later = Fixtures.text("mail-system").lines().take(23) + "  瀏覽 第 2/3 頁 ( 60%)  ▲此頁內容會依閱讀者不同,原文未必有您的資料  (←)離開"
        assertTrue(ScreenParser.parse(Screen.of(later.joinToString("\n"))) is Page.Unknown)
    }

    /** 有新信時標題列中間那格換成「你有新信件」（pttbbs `redraw_title`）：照樣認得出是哪一頁，[ScreenParser.newMail] 看得出有新信。 */
    @Test
    fun newMailReplacesTheMiddleOfTheTitleLine() {
        val menu = ScreenParser.parse(Fixtures.screen("main-menu"))
        assertEquals(false, ScreenParser.newMail(menu))
        val menuWithMail = ScreenParser.parse(Screen.of(Fixtures.text("main-menu").replaceFirst("批踢踢實業坊", "你有新信件")))
        assertTrue("$menuWithMail", menuWithMail is Page.MainMenu)
        assertEquals(true, ScreenParser.newMail(menuWithMail))
        // 文章列表中間那格是板的中文名稱，換掉之後右邊的「看板《MobileComm》」還在
        val list = ScreenParser.parse(Screen.of(Fixtures.text("article-list").replaceFirst("[通訊] 手機o平板o資費 請進!", "    你有新信件    ")))
        assertEquals("MobileComm", (list as Page.ArticleList).board)
        assertEquals(true, ScreenParser.newMail(list))
        // 文章內文第 0 列是「作者」那列，不是標題列：看不出來
        assertEquals(null, ScreenParser.newMail(ScreenParser.parse(Fixtures.screen("article"))))
    }
}
