package com.twtrubiks.quietptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 發文的草稿（[PostDrafts]、[worthKeeping]、[PostDraft.preview]）：一個看板一份、重開還在、刪掉、什麼時候才問。SharedPreferences 用記憶體裡的假的。 */
class PostDraftsTest {
    @Test
    fun oneDraftPerBoardAndBoardNamesIgnoreCase() {
        val prefs = MemoryPrefs()
        val drafts = PostDrafts(prefs)
        drafts.save("Gossiping", PostDraft("標題", "內文\n第二行"))
        assertEquals(PostDraft("標題", "內文\n第二行"), PostDrafts(prefs).get("gossiping"))
        assertNull(drafts.get("Test"))
    }

    @Test
    fun savingAgainReplacesAndRemoveDeletes() {
        val drafts = PostDrafts(MemoryPrefs())
        drafts.save("Test", PostDraft("a", "b"))
        drafts.save("Test", PostDraft("", "c"))
        assertEquals(PostDraft("", "c"), drafts.get("Test"))
        drafts.remove("Test")
        assertNull(drafts.get("Test"))
    }

    @Test
    fun asksOnlyWhenSomethingWasTyped() {
        assertFalse(worthKeeping("", "", ""))
        assertFalse(worthKeeping(" ", "簽名", "簽名"))
        assertTrue(worthKeeping("標題", "", ""))
        assertTrue(worthKeeping("", "簽名\n多打的", "簽名"))
        assertTrue(worthKeeping("", "內文", ""))
    }

    @Test
    fun previewIsOneShortLine() {
        assertEquals("標題", PostDraft("標題", "內文").preview)
        assertEquals("第一行 第二行", PostDraft("", "第一行\n第二行").preview)
        assertEquals("a".repeat(20) + "…", PostDraft("", "a".repeat(30)).preview)
    }
}
