package com.twtrubiks.quietptt

import com.twtrubiks.quietptt.core.ptt.ArticleItem
import com.twtrubiks.quietptt.core.ptt.Push
import com.twtrubiks.quietptt.core.ptt.PushType
import org.junit.Assert.assertEquals
import org.junit.Test

/** 文章頁的排版：推文的樓層標示照 BePTT「12樓/推5」。 */
class ArticleLayoutTest {
    @Test
    fun floorLabelLikeBePtt() {
        assertEquals("12樓/推5", floorLabel(ArticleItem.Comment(Push(PushType.PUSH, "a", "", "07/18 09:54"), floor = 12, nth = 5)))
        assertEquals("3樓/→1", floorLabel(ArticleItem.Comment(Push(PushType.ARROW, "a", "", "07/18 09:54"), floor = 3, nth = 1)))
        assertEquals("7樓/噓2", floorLabel(ArticleItem.Comment(Push(PushType.BOO, "a", "", "07/18"), floor = 7, nth = 2)))
    }
}
