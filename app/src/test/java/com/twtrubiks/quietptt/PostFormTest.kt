package com.twtrubiks.quietptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 發文頁：標題的檢查（含分類最多 48 個 byte）、簽名檔的字、確認對話框的內容。 */
class PostFormTest {
    @Test
    fun titleMustFitWithItsCategory() {
        assertEquals("請輸入標題", titleProblem("  ", null))
        assertNull(titleProblem("有沒有安靜的八卦", "問卦"))
        // 20 個中文字 40 byte 加「[問卦] 」7 byte = 47
        assertNull(titleProblem("一".repeat(20), "問卦"))
        assertTrue(titleProblem("一".repeat(21), "問卦")!!.startsWith("標題太長"))
        assertNull(titleProblem("一".repeat(24), null))
    }

    @Test
    fun signatureLabels() {
        assertEquals("不使用簽名檔", signatureLabel("0"))
        assertEquals("簽名檔 3", signatureLabel("3"))
        assertEquals("隨機簽名檔", signatureLabel("x"))
        assertEquals(11, SIGNATURES.size)
    }

    @Test
    fun summaryShowsTheFullTitleAndTheFirstSixtyCharacters() {
        val summary = postSummary("問卦", "安靜", "a".repeat(70))
        assertTrue(summary.startsWith("標題：[問卦] 安靜\n內文：" + "a".repeat(60) + "…"))
        assertTrue(postSummary(null, "安靜", "").contains("（空白）"))
    }
}
