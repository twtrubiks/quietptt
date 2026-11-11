package com.twtrubiks.quietptt.core.ptt

import org.junit.Assert.assertEquals
import org.junit.Test

/** 往下捲載入的那頁併進列表：照序號排、重複的用新的、置底文在最後。 */
class ArticleMergeTest {
    private fun row(index: Int?, push: Int? = null, title: String = "第 $index 篇") = ArticleSummary(index, null, push, "11/07", "someone", title)

    @Test
    fun olderPageGoesInFrontAndPinnedStayLast() {
        val listed = listOf(row(21), row(22), row(null, title = "置底"))
        val merged = listed.mergedWith(listOf(row(19), row(20), row(21)))
        assertEquals(listOf(19, 20, 21, 22, null), merged.map { it.index })
        assertEquals("置底", merged.last().title)
    }

    @Test
    fun theNewerCopyOfTheSameArticleWins() {
        val merged = listOf(row(5, push = 1)).mergedWith(listOf(row(5, push = 7)))
        assertEquals(listOf(7), merged.map { it.pushCount })
    }

    @Test
    fun pinnedComeFromTheNewerPageWhenItHasThem() {
        val merged = listOf(row(1), row(null, title = "舊的置底")).mergedWith(listOf(row(2), row(null, title = "新的置底")))
        assertEquals(listOf("第 1 篇", "第 2 篇", "新的置底"), merged.map { it.title })
    }

    @Test
    fun aPageFarAwayDoesNotConnect() {
        // 真機：搜尋結果按左鍵退出來，終端停在結果裡選的那篇所在的頁（153768～153787），App 列的是 153871 起：併進來中間會缺一段
        val listed = (153871..153910).map { row(it) } + row(null)
        assertEquals(false, listed.connectsWith((153768..153787).map { row(it) }))
        // 重疊、剛好接在前面或後面（新文章）都接得上
        assertEquals(true, listed.connectsWith((153860..153880).map { row(it) }))
        assertEquals(true, listed.connectsWith((153851..153870).map { row(it) }))
        assertEquals(true, listed.connectsWith((153895..153915).map { row(it) } + row(null)))
        // 還沒列任何一般文章、或那頁只有置底文
        assertEquals(true, listOf(row(null)).connectsWith((1..20).map { row(it) }))
        assertEquals(true, listed.connectsWith(listOf(row(null))))
    }
}
