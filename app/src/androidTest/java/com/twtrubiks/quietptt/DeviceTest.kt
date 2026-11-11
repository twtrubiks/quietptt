package com.twtrubiks.quietptt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 只有真機才測得到的事（`record.sh` 在錄影前先跑、不錄影）：
 * AndroidKeyStore 的 AES-GCM 加解密帳密（JVM 沒有 AndroidKeyStore）、文章裡的圖用 `ImageDecoder` 解動圖（JVM 沒有）。
 * CI 的模擬器是 Android 9，解動圖走的是寫暫存檔那條路。
 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.getSharedPreferences("device-test-account", Context.MODE_PRIVATE)
    private val cacheDir = File(context.cacheDir, "device-test")

    @After
    fun cleanUp() {
        prefs.edit().clear().commit()
        cacheDir.deleteRecursively()
    }

    @Test
    fun passwordSurvivesRoundTripAndIsNotStoredInPlainText() {
        val credentials = Credentials(prefs)
        credentials.save("someone", "p@ss 中文")

        assertEquals(Account("someone", "p@ss 中文"), Credentials(prefs).load())
        val stored = prefs.getString("password:someone", null)!!
        assertEquals(false, stored.contains("p@ss"))
    }

    @Test
    fun severalAccountsAreKeptAndTheLastLoginComesFirst() {
        val credentials = Credentials(prefs)
        credentials.save("first", "one")
        credentials.save("second", null)
        assertEquals(listOf(Account("second", null), Account("first", "one")), credentials.loadAll())
        credentials.save("first", "one")
        assertEquals(listOf(Account("first", "one"), Account("second", null)), credentials.loadAll())
        credentials.forgetPasswords()
        assertEquals(listOf(Account("first", null), Account("second", null)), credentials.loadAll())
        credentials.remove("first")
        assertEquals(Account("second", null), credentials.load())
        credentials.remove("second")
        assertNull(credentials.load())
        assertEquals(emptyList<Account>(), credentials.loadAll())
    }

    @Test
    fun oldSingleAccountFormatIsStillReadable() {
        val old = Credentials(prefs)
        old.save("someone", "secret")
        // 舊版只有 name 與 password 兩個鍵
        prefs.edit().remove("names").putString("password", prefs.getString("password:someone", null)).remove("password:someone").commit()
        assertEquals(listOf(Account("someone", "secret")), Credentials(prefs).loadAll())
    }

    @Test
    fun rememberingOnlyTheAccountDropsThePassword() {
        val credentials = Credentials(prefs)
        credentials.save("someone", "secret")
        credentials.save("someone", null)
        assertEquals(Account("someone", null), credentials.load())
        credentials.clear()
        assertNull(credentials.load())
    }

    @Test
    fun twoFrameGifIsAnAnimationThatMoves() {
        val image = decodeImage(gif(TWO_FRAMES), cacheDir)
        val drawable = (image as LoadedImage.Animated).drawable
        // Android 9、10 寫的暫存檔解好就刪了
        assertEquals(emptyList<String>(), File(cacheDir, "images").list()?.toList().orEmpty())
        // 第一格紅（100 ms）、第二格藍（5 秒）：開始播、隔半秒再畫就是藍的（暫存檔刪了還讀得到後面的格）
        instrumentation.runOnMainSync { drawable.start() }
        try {
            assertEquals(Color.RED, draw(drawable))
            SystemClock.sleep(500)
            assertEquals(Color.BLUE, draw(drawable))
        } finally {
            instrumentation.runOnMainSync { drawable.stop() }
        }
    }

    @Test
    fun animatedWebpIsAnAnimation() {
        assertTrue(decodeImage(gif(ANIMATED_WEBP), cacheDir) is LoadedImage.Animated)
    }

    @Test
    fun hugeAnimationIsScaledDown() {
        val drawable = (decodeImage(gif(WIDE), cacheDir) as LoadedImage.Animated).drawable // 4100×8
        assertTrue("寬 ${drawable.intrinsicWidth}", drawable.intrinsicWidth in 1..2048)
    }

    @Test
    fun singleFrameGifAndPngAreStillPictures() {
        val gif = (decodeImage(gif(ONE_FRAME), cacheDir) as LoadedImage.Still).bitmap
        assertEquals(3, gif.width)
        assertEquals(Color.GREEN, gif.getPixel(1, 1))

        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(5, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val still = (decodeImage(png, cacheDir) as LoadedImage.Still).bitmap
        assertEquals(5, still.width)
        assertEquals(4, still.height)
    }

    @Test
    fun notAPictureIsRefused() {
        for (bytes in listOf("<html></html>".toByteArray(), "GIF89a 不是圖".toByteArray())) {
            try {
                decodeImage(bytes, cacheDir)
                fail("應該解不開")
            } catch (e: ImageException) {
                assertEquals("圖片格式不支援或檔案不完整", e.message)
            }
        }
        assertEquals(emptyList<String>(), File(cacheDir, "images").list()?.toList().orEmpty())
    }

    /** 用軟體畫布畫一次，回傳中間那一點的顏色。 */
    private fun draw(drawable: Drawable): Int {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync {
            drawable.setBounds(0, 0, 4, 4)
            drawable.draw(Canvas(bitmap))
        }
        return bitmap.getPixel(2, 2)
    }

    private fun gif(base64: String): ByteArray = Base64.decode(base64, Base64.DEFAULT)

    private companion object {
        // Pillow 做的小圖。TWO_FRAMES：4×4，紅 100 ms、藍 5 秒，無限循環
        const val TWO_FRAMES =
            "R0lGODlhBAAEAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAABAAEAAAICQABCBxIsCCAgAAh+QQA9AEAACwAAAAABAAEAIEAAP8AAAAAAAAAAAAICQABCBxIsCCAgAA7"

        // 4100×8，兩格
        const val WIDE =
            "R0lGODlhBBAIAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAABBAIAAAI/wABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgz" +
                "atzIsaPHjyBDihxJsqTJkyhTqlzJsqXLlzBjypxJs6bNmzhz6tzJs6fPn0CDCh1KtKjRo0iTKl3KtKnTp1CjSp1KtarVq1izat3KtavXr2DDih1LtqzZs2jTql3L" +
                "tq3bt3Djyp1Lt67du3jz6t3Lt6/fv4ADCx5MuLDhw4gTK17MuLHjx5AjS55MubLly5gza97MubPnz6BDix5NurTp06hTq17NurXr17Bjy55Nu7bt27hz697Nu7fv" +
                "38CDCx9OvLjx48iTK1/OvLnz59CjS59Ovbr169iza9/Ovbv37+DDiyQfT768+fPo06tfz769+/fw48ufT7++/fv48+vfz7+///+gBQQAIfkEAAoAAAAsAAAAAAQQ" +
                "CACBAAD/AAAAAAAAAAAACP8AAQgcSLCgwYMIEypcyLChw4cQI0qcSLGixYsYM2rcyLGjx48gQ4ocSbKkyZMoU6pcybKly5cwY8qcSbOmzZs4c+rcybOnz59Agwod" +
                "SrSo0aNIkypdyrSp06dQo0qdSrWq1atYs2rdyrWr169gw4odS7as2bNo06pdy7at27dw48qdS7eu3bt48+rdy7ev37+AAwseTLiw4cOIEytezLix48eQI0ueTLmy" +
                "5cuYM2vezLmz58+gQ4seTbq06dOoU6tezbq169ewY8ueTbu27du4c+vezbu379/AgwsfTry48ePIkytfzry58+fQo0ufTr269evYs2vfzr279+/gw4skH0++vPnz" +
                "6NOrX8++vfv38OPLn0+/vv37+PPr38+/v///oAUEADs="

        // 3×2 綠色，只有一格
        const val ONE_FRAME = "R0lGODdhAwACAIEAAAD/AAAAAAAAAAAAACwAAAAAAwACAAAIBgABCBwYEAA7"

        // 4×4 兩格的動態 WebP
        const val ANIMATED_WEBP =
            "UklGRoQAAABXRUJQVlA4WAoAAAACAAAAAwAAAwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAAMAAAMAAGQAAAJWUDhMDwAAAC8DwAAABxD9j/4HIqL/" +
                "AQBBTk1GKAAAAAAAAAAAAAMAAAMAAIgTAABWUDhMDwAAAC8DwAAABxDR//4HIqL/AQA="
    }
}
