package com.twtrubiks.quietptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 推文列的長度檢查：照 pttbbs `comment` 一行放得下的 byte 數，有 IP 的板少 15。 */
class PushTextTest {
    @Test
    fun emptyAndTooLongAreRefused() {
        assertEquals("請輸入內容", pushProblem("  ", showsIp = false))
        assertNull(pushProblem("推", showsIp = false))
        assertNull(pushProblem("一".repeat(25), showsIp = false)) // 50 bytes
        assertEquals(true, pushProblem("一".repeat(26), showsIp = false)?.startsWith("太長了"))
        assertNull(pushProblem("a".repeat(35), showsIp = true))
        assertEquals(true, pushProblem("a".repeat(36), showsIp = true)?.startsWith("太長了"))
    }
}
