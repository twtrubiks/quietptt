package com.twtrubiks.quietptt

import com.twtrubiks.quietptt.core.ptt.ArticleSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 看板上次列過的文章（再進同一個板先列這份）：記多少、跟著帳號、板名不分大小寫、最久沒用的先丟。 */
class BoardListsTest {
    private fun row(index: Int?, title: String = "標題") = ArticleSummary(index, null, null, "11/07", "someone", title)

    @Test
    fun keepsTheNewestArticlesAndEveryPinnedOne() {
        val lists = BoardLists()
        val numbered = (1..150).map { row(it) }
        val pinned = listOf(row(null, "[公告] 板規"), row(null, "[置底] 閒聊"))
        lists.put("me", "MobileComm", numbered + pinned)
        val kept = lists.get("me", "MobileComm")!!
        // PTT 的順序：序號由小到大、置底文在最後
        assertEquals((51..150).toList() + listOf(null, null), kept.map { it.index })
        assertEquals(pinned, kept.takeLast(2))
    }

    @Test
    fun boardNamesIgnoreCaseButAccountsAreSeparate() {
        val lists = BoardLists()
        lists.put("me", "MobileComm", listOf(row(1)))
        assertEquals(listOf(1), lists.get("ME", "mobilecomm")?.map { it.index })
        // 未讀的「+」每個帳號不一樣：別的帳號拿不到
        assertNull(lists.get("other", "MobileComm"))
    }

    @Test
    fun anEmptyListIsNotKept() {
        val lists = BoardLists()
        lists.put("me", "MobileComm", listOf(row(1)))
        lists.put("me", "MobileComm", emptyList())
        assertEquals(listOf(1), lists.get("me", "MobileComm")?.map { it.index })
        lists.put("me", "Stock", emptyList())
        assertNull(lists.get("me", "Stock"))
    }

    @Test
    fun theLeastRecentlyUsedBoardGoesFirst() {
        val lists = BoardLists()
        repeat(BoardLists.MAX_BOARDS) { lists.put("me", "Board$it", listOf(row(1))) }
        // 讀過 Board0：它變成最近用的，滿了先丟的是 Board1
        lists.get("me", "Board0")
        lists.put("me", "OneMore", listOf(row(1)))
        assertEquals(listOf(1), lists.get("me", "Board0")?.map { it.index })
        assertNull(lists.get("me", "Board1"))
        assertEquals(listOf(1), lists.get("me", "OneMore")?.map { it.index })
    }
}
