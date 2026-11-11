package com.twtrubiks.quietptt.core.ptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重播真的 PTT 錄影（`recordings/article-pages.txt`：MobileComm 一篇 7 頁的文章，PgDn 翻到底）：
 * 每一頁都認得出來、進度對、網址的 OSC 8 超連結不會變成亂碼，接起來是完整的一篇、頁跟頁之間不重複也不漏。
 */
class ArticleReplayTest {
    private val pages: List<Page> by lazy { Fixtures.replay("article-pages").map(ScreenParser::parse) }

    @Test
    fun everyPageIsRecognizedWithItsProgress() {
        val views = pages.map { it as Page.ArticleView }
        assertEquals((1..7).toList(), views.map { it.progress.page })
        assertEquals(listOf(7), views.map { it.progress.pages }.distinct())
        assertEquals(listOf(1 to 22, 22 to 44, 44 to 66, 66 to 88, 88 to 110, 110 to 132, 116 to 138), views.map { it.progress.firstLine to it.progress.lastLine })
        assertEquals(listOf(false, false, false, false, false, false, true), views.map { it.progress.isLast })
    }

    @Test
    fun hyperlinksDoNotLeakIntoTheScreen() {
        // 以前 OSC 8 的「8;;網址」被當字印出來，折到下一列，之後每頁都對不齊
        for (page in pages) assertFalse(page.screen.toString(), page.screen.contains("8;;"))
        val second = pages[1] as Page.ArticleView
        assertTrue(second.body.any { it.startsWith("受害者網址：https://community.samsung.com/") })
    }

    @Test
    fun pagesJoinIntoTheWholeArticle() {
        val article = pages.fold(ArticleText.EMPTY) { text, page -> text + (page as Page.ArticleView) }
        assertTrue(article.complete)
        assertEquals(138, article.lastLine)
        // 138 行，加上標頭下面那條分隔線
        assertEquals(139, article.lines.size)
        assertTrue(article.lines[0], article.lines[0].startsWith(" 作者  chanifa"))
        assertTrue(article.lines[3].all { it == '─' })
        assertTrue(article.lines.last(), article.lines.last().startsWith("推 yahappy4u"))
        // 頁跟頁重疊的那一行只出現一次
        for (overlap in listOf("https://i.mopix.cc/r1WtVa.jpg", "我的證券app也很綠")) {
            assertEquals(overlap, 1, article.lines.count { it.startsWith(overlap) })
        }
        // 推文都在
        assertEquals(article.lines.count { it.startsWith("推 ") || it.startsWith("噓 ") || it.startsWith("→ ") }, article.lines.filter { it.contains(Regex("""\d\d/\d\d \d\d:\d\d$""")) }.size)
    }

    @Test
    fun pushesBecomeStructuredComments() {
        val article = pages.fold(ArticleText.EMPTY) { text, page -> text + (page as Page.ArticleView) }
        val items = article.items()
        val comments = items.filterIsInstance<ArticleItem.Comment>().map { it.push }
        // 推文列（有時間的那些）全部認出來；被切成多列的接回去之後數量只會少不會多
        val pushLines = article.lines.count { it.contains(Regex("""\d\d/\d\d \d\d:\d\d$""")) }
        assertTrue("$pushLines 列推文，認出 ${comments.size} 則", comments.size in 1..pushLines)
        assertTrue(comments.all { it.user.isNotEmpty() && it.time.matches(Regex("""\d\d/\d\d \d\d:\d\d""")) })
        assertEquals("yahappy4u", comments.last().user)
        // 標頭認得出來、不當本文；分隔線也不是；本文從第一個空白列起，順序不變
        assertEquals(ArticleHeader("chanifa", "股師", "MobileComm", "[討論] s24u 綠線案例", "Fri Nov  6 09:19:28 2026"), article.header)
        assertEquals(article.lines[4], (items[0] as ArticleItem.Line).text)
        assertTrue(items.any { it is ArticleItem.Signature && it.text.startsWith("※ 發信站") })
        assertTrue(items.filterIsInstance<ArticleItem.Line>().none { it.text.contains(Regex("""\d\d/\d\d \d\d:\d\d$""")) })
    }

    @Test
    fun colorsSurviveIntoTheArticle() {
        val first = pages[0] as Page.ArticleView
        // 真的 PTT 畫面：標頭「作者」那列是彩色的（pmore 的標頭上色），每一段都落在那列的字裡
        assertEquals(first.body.size, first.colors.size)
        assertTrue(first.colors[0].isNotEmpty())
        for ((row, spans) in first.colors.withIndex()) {
            for (span in spans) assertTrue("第 $row 列 $span 超出「${first.body[row]}」", span.start < span.end && span.end <= first.body[row].length)
        }
        val article = pages.fold(ArticleText.EMPTY) { text, page -> text + (page as Page.ArticleView) }
        assertEquals(article.lines.size, article.colors.size)
        assertTrue(article.colors[0].isNotEmpty())
        // 推文列在 PTT 上是彩色的（這篇的本文沒有顏色）
        assertTrue(article.colors.drop(4).any { it.isNotEmpty() })
        // 切成段落之後本文的列帶著自己那列的顏色（標頭四列跳過，對得上位置）；推文不帶（App 用自己的樣式畫）
        val items = article.items()
        assertEquals(article.colors[4], (items[0] as ArticleItem.Line).colors)
    }

    @Test
    fun copiedTextHasNoTrailingBackgroundSpaces() {
        val article = pages.fold(ArticleText.EMPTY) { text, page -> text + (page as Page.ArticleView) }
        // 標頭在 PTT 上是藍底一路畫到右邊：上色要留著那些空白，複製出去不要
        assertTrue(article.lines[1].length > article.lines[1].trimEnd().length)
        val copied = article.plainText().lines()
        assertEquals(article.lines.size, copied.size)
        assertTrue(copied.none { it != it.trimEnd() })
        assertTrue(copied[1], copied[1].startsWith(" 標題  [討論]"))
    }

    @Test
    fun jumpingToTheLastPageLeavesAGapThatFillsInFromEitherSide() {
        val views = pages.map { it as Page.ArticleView }
        val whole = views.fold(ArticleText.EMPTY) { text, page -> text + page }
        // 「到底」：第一頁接著直接是最後一頁（116～138），中間 23～115 缺著
        var text = ArticleText.EMPTY + views[0] + views[6]
        assertEquals(listOf(23..115), text.gaps)
        assertEquals(22, text.lastLine)
        assertEquals(138, text.end)
        assertFalse(text.complete)
        val rows = text.rows()
        assertEquals(ArticleItem.Gap(23, 115), rows.map { it.item }.single { it is ArticleItem.Gap })
        // 缺的那段後面的推文數不出樓層
        val comments = rows.map { it.item }.filterIsInstance<ArticleItem.Comment>()
        assertTrue(comments.isNotEmpty())
        assertTrue(comments.all { it.floor == 0 })
        // key 不重複，前面插進新讀的行時不變
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        val lastKey = rows.last().key
        // 從下面往上補（跳到 94 那一行的那頁：88～110）、再從上面往下補，接成整篇，跟一頁一頁翻的一樣
        text = text + views[4]
        assertEquals(listOf(23..87, 111..115), text.gaps)
        assertEquals(lastKey, text.rows().last().key)
        text = text + views[1] + views[2] + views[3] + views[5]
        assertTrue(text.complete)
        assertEquals(whole.lines, text.lines)
        assertEquals(whole.items(), text.items())
        assertTrue(text.items().filterIsInstance<ArticleItem.Comment>().all { it.floor > 0 })
    }

    @Test
    fun rereadingPagesReplacesThemAndTheEndMovesWhenTheArticleGrows() {
        val views = pages.map { it as Page.ArticleView }
        val whole = views.fold(ArticleText.EMPTY) { text, page -> text + page }
        // 同一頁再讀一次（檢查新推文重讀第一頁）：不會多出重複的行
        assertEquals(whole.lines, (whole + views[0]).lines)
        // 文章變長：讀到比結尾還後面的行，結尾作廢；最後一頁又出現了才知道新的結尾
        val shortened = views.take(6).fold(ArticleText.EMPTY) { text, page -> text + page }
        assertEquals(null, shortened.end)
        assertEquals(132, shortened.lastLine)
        assertFalse(shortened.complete)
        assertEquals(whole.lines, (shortened + views[6]).lines)
    }

    @Test
    fun footerIsDoneWhenItReachesTheRightEnd() {
        val first = pages[0] as Page.ArticleView
        assertTrue(first.footerDone)
        // 進度列只畫到行號（後面還在路上）：還不算畫完
        val half = first.screen.toString().lines().let { lines -> lines.dropLast(1) + lines.last().substringBefore("(y)") }
        val partial = ScreenParser.parse(com.twtrubiks.quietptt.core.terminal.Screen.of(half.joinToString("\n"))) as Page.ArticleView
        assertFalse(partial.footerDone)
    }
}
