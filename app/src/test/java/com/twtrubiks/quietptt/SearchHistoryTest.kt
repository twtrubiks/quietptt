package com.twtrubiks.quietptt

import com.twtrubiks.quietptt.core.ptt.ArticleSearch
import com.twtrubiks.quietptt.core.ptt.SearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 看板裡搜尋的紀錄（[SearchHistory]、[withSearch]）：順序、重複、上限、每種各一份、重開還在、刪與清。SharedPreferences 用記憶體裡的假的。 */
class SearchHistoryTest {
    @Test
    fun newestFirstAndTheSameWordOnlyOnce() {
        assertEquals(listOf("心得", "問題"), withSearch(listOf("問題"), "心得"))
        assertEquals(listOf("問題", "心得"), withSearch(listOf("心得", "問題"), "問題"))
    }

    @Test
    fun theSameWordInAnotherCaseReplacesTheOldOneWithTheNewSpelling() {
        assertEquals(listOf("IPhone", "android"), withSearch(listOf("android", "iphone"), "IPhone"))
    }

    @Test
    fun keepsAtMostMaxAndDropsTheOldest() {
        val full = (1..SearchHistory.MAX).map { "k$it" }
        val after = withSearch(full, "new")
        assertEquals(SearchHistory.MAX, after.size)
        assertEquals("new", after.first())
        assertFalse("k${SearchHistory.MAX}" in after)
    }

    @Test
    fun blankIsNotRecordedAndNewlinesBecomeSpaces() {
        assertEquals(listOf("a"), withSearch(listOf("a"), "   "))
        assertEquals(listOf("a b", "a"), withSearch(listOf("a"), " a\nb "))
    }

    @Test
    fun eachKindHasItsOwnListThatSurvivesARestart() {
        val prefs = MemoryPrefs()
        SearchHistory(prefs).apply {
            add(ArticleSearch(SearchKind.TITLE, "問題"))
            add(ArticleSearch(SearchKind.AUTHOR, "abc"))
            add(ArticleSearch(SearchKind.PUSH_COUNT, "-10"))
            add(ArticleSearch(SearchKind.TITLE, "心得"))
        }
        val again = SearchHistory(prefs)
        assertEquals(listOf("心得", "問題"), again.of(SearchKind.TITLE))
        assertEquals(listOf("abc"), again.of(SearchKind.AUTHOR))
        assertEquals(listOf("-10"), again.of(SearchKind.PUSH_COUNT))
    }

    @Test
    fun removeOneClearOneKindAndClearAll() {
        val prefs = MemoryPrefs()
        val history = SearchHistory(prefs)
        history.add(ArticleSearch(SearchKind.TITLE, "問題"))
        history.add(ArticleSearch(SearchKind.TITLE, "心得"))
        history.add(ArticleSearch(SearchKind.AUTHOR, "abc"))

        history.remove(SearchKind.TITLE, "問題")
        assertEquals(listOf("心得"), SearchHistory(prefs).of(SearchKind.TITLE))

        history.clear(SearchKind.TITLE)
        assertEquals(emptyList<String>(), SearchHistory(prefs).of(SearchKind.TITLE))
        assertEquals(listOf("abc"), SearchHistory(prefs).of(SearchKind.AUTHOR))
        assertFalse(history.isEmpty)

        history.clearAll()
        assertTrue(history.isEmpty)
        assertTrue(SearchHistory(prefs).isEmpty)
        assertTrue(prefs.all.isEmpty())
    }
}
