package com.twtrubiks.quietptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 寫信頁：收件人與標題的檢查、回信預設的標題、確認對話框的內容、哪些信回得了。 */
class MailFormTest {
    @Test
    fun recipientMustBeAPttId() {
        assertEquals("請輸入收件人的代號", recipientProblem(" "))
        assertNull(recipientProblem("someone"))
        assertNull(recipientProblem("Ab"))
        assertNull(recipientProblem("abcdefghijk1"))
        assertTrue(recipientProblem("a")!!.startsWith("代號是"))
        assertTrue(recipientProblem("1abc")!!.startsWith("代號是"))
        assertTrue(recipientProblem("abcdefghijklm")!!.startsWith("代號是"))
        assertTrue(recipientProblem("some_one")!!.startsWith("代號是"))
        assertTrue(recipientProblem("小明")!!.startsWith("代號是"))
    }

    @Test
    fun titleFitsTheSubjectBox() {
        assertEquals("請輸入標題", mailTitleProblem("  "))
        assertNull(mailTitleProblem("你好"))
        // 29 個中文字 58 byte 可以，30 個 60 byte 太長
        assertNull(mailTitleProblem("一".repeat(29)))
        assertTrue(mailTitleProblem("一".repeat(30))!!.startsWith("標題太長"))
    }

    @Test
    fun replyTitleIsReOnTheSubject() {
        assertEquals("Re: 第一封信的標題", replyTitle("◇ 第一封信的標題"))
        // 列表上回信是「R: 」、轉寄是「轉 」（pttbbs `maildoent`）；一層層的 Re: 都拿掉，只留一個
        assertEquals("Re: 回你的信", replyTitle("R: 回你的信"))
        assertEquals("Re: 回你的信", replyTitle("Re: RE: 回你的信"))
        assertEquals("Re: [問題] 轉寄的標題", replyTitle("轉 [問題] 轉寄的標題"))
        assertEquals("Re: [問題] 轉寄的標題", replyTitle("Fw: [問題] 轉寄的標題"))
    }

    @Test
    fun summaryShowsWhoAndTheFirstSixtyCharacters() {
        val summary = mailSummary("someone", "你好", "a".repeat(70))
        assertTrue(summary.startsWith("收件人：someone\n標題：你好\n內文：" + "a".repeat(60) + "…"))
        assertTrue(mailSummary("someone", "你好", "").contains("（空白）"))
    }

    @Test
    fun systemMailsAndOwnBackupsCannotBeReplied() {
        assertTrue(canReply("someone"))
        assertTrue(canReply("SYSOP"))
        assertFalse(canReply("[備.忘.錄]"))
        assertFalse(canReply("-"))
        assertFalse(canReply(""))
    }
}
