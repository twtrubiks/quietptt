package com.twtrubiks.quietptt

import com.twtrubiks.quietptt.core.ptt.ArticleRef
import com.twtrubiks.quietptt.core.ptt.ArticleSearch
import com.twtrubiks.quietptt.core.ptt.ArticleSummary
import com.twtrubiks.quietptt.core.ptt.SearchKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** 看板列表：置底文在最上面、接著新的在上；點的那一列要怎麼找到那篇（序號跳過去，置底文從最後一筆往上數）。 */
class ArticleListTest {
    private fun row(index: Int?, title: String = "標題") = ArticleSummary(index, null, null, "11/07", "someone", title)

    /** PTT 的順序（mergedWith 排好的）：序號由小到大，置底文在最後。 */
    private val articles = listOf(row(100), row(101), row(102), row(null, "板規"), row(null, "閒聊區"))

    @Test
    fun pinnedComeFirstReversedThenNewestFirst() {
        val rows = boardRows(articles)
        // 置底文也倒過來：PTT 列表最後一筆的置底文在最上面
        assertEquals(listOf("p0", "p1", "102", "101", "100"), rows.map { it.key })
        assertEquals(listOf("閒聊區", "板規"), rows.take(2).map { it.article.title })
        // 置底文從最後一筆往上數：最後一篇置底文是 fromBottom 0
        assertEquals(listOf(ArticleRef.Pinned(fromBottom = 0), ArticleRef.Pinned(fromBottom = 1)), rows.take(2).map { it.ref })
        assertEquals(ArticleRef.Numbered(102), rows[2].ref)
    }

    @Test
    fun boardsWithoutPinnedJustListNewestFirst() {
        assertEquals(listOf("101", "100"), boardRows(listOf(row(100), row(101))).map { it.key })
        assertEquals(emptyList<BoardRow>(), boardRows(emptyList()))
    }

    /** 長按的選單裡要不要有編輯／改標題／刪除：作者是自己、有序號、沒被刪、不在搜尋結果裡。 */
    @Test
    fun onlyOwnNumberedArticlesOutsideSearchCanBeManaged() {
        val mine = ArticleSummary(102, null, null, "11/07", "me", "標題")
        assert(ownArticle(mine, account = "me"))
        assert(!ownArticle(mine, account = "")) // 沒登入資訊
        assert(!ownArticle(mine.copy(author = "someone"), account = "me"))
        assert(!ownArticle(mine.copy(index = null), account = "me")) // 置底文
        assert(!ownArticle(mine.copy(author = "-", title = "(本文已被刪除) [me]"), account = "me"))
        // PTT 登入不分大小寫：打小寫登入，作者欄是註冊時的大小寫
        assert(ownArticle(mine.copy(author = "Me"), account = "me"))
        assert(sameAccount("Me", "mE"))
        assert(!sameAccount(null, "me"))
    }

    @Test
    fun pushLabelsLikePtt() {
        assertEquals(listOf("爆", "99", "0", "X1", "X9", "XX"), listOf(100, 99, 0, -10, -99, -100).map(::pushLabel))
    }

    @Test
    fun searchQueriesAreCheckedLikePttWould() {
        // pttbbs ask_filter_predicate：作者是代號（IDLEN 12、英數）、推文數是整數而且 0 等於取消、標題不能空
        assertEquals(null, searchProblem(SearchKind.TITLE, " 綠線 "))
        assertEquals("請輸入關鍵字", searchProblem(SearchKind.TITLE, "  "))
        assertEquals(null, searchProblem(SearchKind.AUTHOR, "chanifa"))
        assertEquals("代號只有英文與數字", searchProblem(SearchKind.AUTHOR, "chan ifa"))
        assertEquals("代號最多 12 個字", searchProblem(SearchKind.AUTHOR, "a".repeat(13)))
        assertEquals(null, searchProblem(SearchKind.PUSH_COUNT, "30"))
        assertEquals(null, searchProblem(SearchKind.PUSH_COUNT, "-5"))
        assertEquals("0 等於沒有條件，請輸入別的數字", searchProblem(SearchKind.PUSH_COUNT, "0"))
        assertEquals("請輸入整數", searchProblem(SearchKind.PUSH_COUNT, "爆"))
    }

    @Test
    fun sameTitleFromTheArticleHeaderDropsReplyMarks() {
        // 文章頁「⋮」的「尋找同標題文章」：標頭的完整標題拿掉開頭一層層的 Re:／Fw:（大小寫都有），原文跟回文都找得到
        assertEquals(ArticleSearch(SearchKind.TITLE, "[問題] 合約到期"), sameTitle("Re: [問題] 合約到期"))
        assertEquals(ArticleSearch(SearchKind.TITLE, "[問題] 合約到期"), sameTitle("RE: Re:[問題] 合約到期"))
        assertEquals(ArticleSearch(SearchKind.TITLE, "[新聞] 轉來的"), sameTitle("Fw: [新聞] 轉來的"))
        assertEquals(ArticleSearch(SearchKind.TITLE, "[問題] 合約到期"), sameTitle("[問題] 合約到期"))
        // 只拿掉開頭的：標題中間的「Re:」是字
        assertEquals(ArticleSearch(SearchKind.TITLE, "[討論] 關於 Re: 的用法"), sameTitle("[討論] 關於 Re: 的用法"))
        // 拿掉之後沒東西就不能搜
        assertEquals(null, sameTitle("Re: "))
    }

    @Test
    fun savedArticlesComeBackTheSame() {
        // 開文章再回來、轉螢幕：列出來的（含往下捲載入的）要一模一樣
        val articles = listOf(
            ArticleSummary(735478, '+', 15, "11/07", "kevin", "[新聞] 台積電 □ 有空格"),
            ArticleSummary(735481, null, null, "11/07", "-", "(已被someone刪除) <author> 4-2-1"),
            ArticleSummary(null, null, -100, " 1/01", "sysop", ""),
        )
        assertEquals(articles, decodeArticles(encodeArticles(articles)))
    }
}
