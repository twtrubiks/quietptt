package com.twtrubiks.quietptt.core.ptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 畫面辨識。fixture 在 `core/src/test/resources/screens/`：
 * 現在的幾張是照記憶寫的，**不是真的畫面**，拿 tools/capture 抓到真的之後要換掉、把斷言改成真的內容。
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

    @Test
    fun recognizesMainMenu() {
        assertTrue(ScreenParser.parse(Fixtures.screen("main-menu")) is Page.MainMenu)
    }

    @Test
    fun parsesFavorites() {
        val page = ScreenParser.parse(Fixtures.screen("favorites")) as Page.Favorites
        assertEquals(
            listOf(
                Board(1, "Gossiping", "綜合", "[八卦] 板規八卦", unread = true),
                Board(2, "C_Chat", "閒談", "[希洽] 大家一起來聊天", unread = false),
                Board(3, "Stock", "學術", "[股版] 今天也要認真看盤", unread = true),
                Board(4, "MyFolder", "", "我的目錄", unread = false, isFolder = true),
            ),
            page.boards,
        )
    }

    @Test
    fun parsesArticleList() {
        val page = ScreenParser.parse(Fixtures.screen("article-list")) as Page.ArticleList
        assertEquals("Gossiping", page.board)
        assertEquals(
            listOf(
                ArticleSummary(12345, null, 100, "11/07", "someone", "[問卦] 有沒有今天天氣的八卦"),
                ArticleSummary(12346, 'M', 12, "11/07", "another", "Re: [問卦] 有沒有今天天氣的八卦"),
                ArticleSummary(12347, null, -20, "11/07", "third", "[新聞] 某某新聞"),
                ArticleSummary(null, null, null, "11/01", "sysop", "[公告] 板規"),
            ),
            page.articles,
        )
    }

    @Test
    fun parsesArticleView() {
        val page = ScreenParser.parse(Fixtures.screen("article")) as Page.ArticleView
        assertEquals(ArticleProgress(page = 1, pages = 3, firstLine = 1, lastLine = 23), page.progress)
        assertEquals(23, page.body.size)
        assertTrue(page.body[0], page.body[0].startsWith("作者  someone") && page.body[0].endsWith("看板  Gossiping"))
    }

    @Test
    fun unknownScreenFallsBack() {
        assertTrue(ScreenParser.parse(Fixtures.screen("unknown")) is Page.Unknown)
    }
}
