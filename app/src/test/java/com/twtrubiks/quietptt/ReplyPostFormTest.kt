package com.twtrubiks.quietptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回文頁：預填的標題、改過的標題與內文的檢查。 */
class ReplyPostFormTest {
    @Test
    fun defaultTitleIsReOnTheSubject() {
        // 標頭的標題（pttbbs `do_reply_title` 用 `subject()` 去掉一層層的 Re:、Fw:）
        assertEquals("Re: [問題] 合約到期", replyTitle("[問題] 合約到期"))
        assertEquals("Re: [問題] 合約到期", replyTitle("Re: [問題] 合約到期"))
        assertEquals("Re: [問題] 合約到期", replyTitle("Re: Re: [問題] 合約到期"))
        assertEquals("Re: [新聞] 轉來的", replyTitle("Fw: [新聞] 轉來的"))
        // 標頭認不出來時用列表那一列：回文是「R: 」
        assertEquals("Re: [問題] 合約到期", replyTitle("R: [問題] 合約到期"))
    }

    @Test
    fun changedTitleFitsTheNewTitleBox() {
        assertEquals("請輸入標題", replyTitleProblem("  "))
        assertNull(replyTitleProblem("Re: 改個標題"))
        // 「Re: 」4 個 byte 加 29 個中文字 58 byte 是 62，可以；30 個是 64，太長
        assertNull(replyTitleProblem("Re: " + "一".repeat(29)))
        assertTrue(replyTitleProblem("Re: " + "一".repeat(30))!!.startsWith("標題太長"))
    }

    @Test
    fun bodyIsRequired() {
        assertEquals("請輸入內文", replyBodyProblem(" \n "))
        assertNull(replyBodyProblem("推一個"))
    }
}
