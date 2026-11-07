package com.twtrubiks.quietptt.core.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Big5UaoTest {
    @Test
    fun decodesStandardBig5() {
        // 中 = A4A4、文 = A4E5
        assertEquals("中文", Big5Uao.decode(byteArrayOf(0xA4.toByte(), 0xA4.toByte(), 0xA4.toByte(), 0xE5.toByte())))
    }

    @Test
    fun decodesUaoExtension() {
        // 日文平假名「あ」在 UAO 是 C6E8（標準 Big5 沒有）
        assertEquals("あ", Big5Uao.decode(byteArrayOf(0xC6.toByte(), 0xE8.toByte())))
    }

    @Test
    fun asciiPassesThroughAndBrokenBytesBecomeReplacement() {
        assertEquals("ab�", Big5Uao.decode(byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 0xA4.toByte())))
    }

    @Test
    fun encodeIsTheInverseOfDecode() {
        val bytes = Big5Uao.encode("推 中文 ok")
        assertArrayEquals(
            byteArrayOf(0xB1.toByte(), 0xC0.toByte(), ' '.code.toByte(), 0xA4.toByte(), 0xA4.toByte(), 0xA4.toByte(), 0xE5.toByte(), ' '.code.toByte(), 'o'.code.toByte(), 'k'.code.toByte()),
            bytes,
        )
        assertEquals("推 中文 ok", Big5Uao.decode(bytes))
    }

    @Test
    fun unknownCharactersAreEncodedAsQuestionMark() {
        // emoji 是兩個 char（surrogate pair），各自換成一個 ?
        assertArrayEquals("??".toByteArray(Charsets.US_ASCII), Big5Uao.encode("\uD83D\uDE00"))
    }
}
