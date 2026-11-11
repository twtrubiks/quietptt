package com.twtrubiks.quietptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** 文章裡的圖：哪些檔頭可能會動（交給 ImageDecoder）、多大的圖要縮小解。真的解碼要在手機上測（DeviceTest）。 */
class ImagesTest {
    @Test
    fun gifAndAnimatedWebpMayBeAnimated() {
        assertTrue(mayBeAnimated("GIF89a".toByteArray() + ByteArray(20)))
        assertTrue(mayBeAnimated("GIF87a".toByteArray() + ByteArray(20)))
        assertTrue(mayBeAnimated(ANIMATED_WEBP))
    }

    @Test
    fun otherPicturesAreDecodedAsStill() {
        assertFalse(mayBeAnimated(STILL_WEBP))
        assertFalse(mayBeAnimated(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))) // JPEG
        assertFalse(mayBeAnimated(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))) // PNG
        assertFalse(mayBeAnimated("<html>".toByteArray()))
        assertFalse(mayBeAnimated(ByteArray(0)))
        // VP8X 但沒開動畫旗標（例如有透明度的一般 WebP）
        val alphaOnly = ANIMATED_WEBP.copyOf().also { it[20] = 0x10 }
        assertFalse(mayBeAnimated(alphaOnly))
        // 檔案只抓到一半
        assertFalse(mayBeAnimated(ANIMATED_WEBP.copyOf(20)))
    }

    @Test
    fun longSideIsScaledDownToAtMost2048() {
        assertEquals(1, sampleSize(2048, 2048))
        assertEquals(1, sampleSize(1, 1))
        assertEquals(2, sampleSize(2049, 10))
        assertEquals(2, sampleSize(10, 4096))
        assertEquals(4, sampleSize(4100, 8))
    }

    private companion object {
        // Pillow 做的 4×4 兩格動態 WebP（紅 100 ms、藍 5 秒）與一張一般的 WebP
        val ANIMATED_WEBP: ByteArray = Base64.getDecoder().decode(
            "UklGRoQAAABXRUJQVlA4WAoAAAACAAAAAwAAAwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAAMAAAMAAGQAAAJWUDhMDwAAAC8DwAAABxD9j/4HIqL/" +
                "AQBBTk1GKAAAAAAAAAAAAAMAAAMAAIgTAABWUDhMDwAAAC8DwAAABxDR//4HIqL/AQA=",
        )
        val STILL_WEBP: ByteArray = "RIFF\u001c\u0000\u0000\u0000WEBPVP8L\u000f\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    }
}
