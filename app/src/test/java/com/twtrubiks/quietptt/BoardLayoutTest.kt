package com.twtrubiks.quietptt

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** 看板列表的排版：標題裡的分類拿出來、推文數照 PTT 的顏色。 */
class BoardLayoutTest {
    @Test
    fun categoryComesOutOfTheTitle() {
        assertEquals("問卦" to "有沒有安靜的八卦", titleParts("[問卦] 有沒有安靜的八卦"))
        assertEquals("問卦" to "R: 有沒有安靜的八卦", titleParts("R: [問卦] 有沒有安靜的八卦"))
        assertEquals("討論" to "s24u 綠線案例 [圖多]", titleParts("[討論] s24u 綠線案例 [圖多]"))
        assertEquals(null to "沒有分類的標題", titleParts("沒有分類的標題"))
        // 超過 4 個字的不算分類
        assertEquals(null to "[這不是分類啦] 標題", titleParts("[這不是分類啦] 標題"))
        assertEquals(null to "(本文已被刪除) [someone]", titleParts("(本文已被刪除) [someone]"))
    }

    @Test
    fun pushCountColorsFollowPtt() {
        assertEquals(Color(0xFFBE0200), pushColor(100, dark = false))
        assertEquals(Color(0xFFFF8800), pushColor(10, dark = false))
        assertEquals(Color(0xFF00BE02), pushColor(9, dark = false))
        assertEquals(Color(0xFF444444), pushColor(-10, dark = false))
        assertEquals(Color(0xFF66FF66), pushColor(1, dark = true))
    }
}
