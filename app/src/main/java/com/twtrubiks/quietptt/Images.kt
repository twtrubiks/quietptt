package com.twtrubiks.quietptt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * 文章頁裡打開過的圖片：哪些網址按了「開啟圖片」（[opened]）、抓回來解好的圖（[loaded]）、點了暫停的動圖（[paused]）。
 * 放在文章頁那一層，列捲出畫面再捲回來不用重抓；離開文章就丟掉。
 */
class OpenedImages {
    val opened: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val loaded: SnapshotStateMap<String, LoadedImage> = mutableStateMapOf()
    val paused: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    /** 每張動圖現在有幾個地方在顯示（同一個網址在文章裡出現兩次就有兩個，共用一個 Drawable）。只在主執行緒碰。 */
    private val showing = mutableMapOf<String, Int>()

    /** 動圖進畫面（[delta] = 1）或捲出畫面（-1）。 */
    fun shown(url: String, delta: Int) {
        showing[url] = (showing[url] ?: 0) + delta
        update(url)
    }

    /** 點了動圖：在播就暫停（停在那一格），暫停中就再播；播完了（檔案裡寫只播幾次的）再播一次。再播都是從頭（系統的 `start()` 停了再開一律從頭）。 */
    fun tap(url: String) {
        val animation = (loaded[url] as? LoadedImage.Animated)?.drawable ?: return
        when {
            paused[url] == true -> paused.remove(url)
            animation.isRunning -> paused[url] = true
        }
        update(url)
    }

    /** 動圖在畫面上、又沒被點暫停才播；捲出畫面就停（停了就不再解下一格），捲回來從頭播。 */
    private fun update(url: String) {
        val animation = (loaded[url] as? LoadedImage.Animated)?.drawable ?: return
        if ((showing[url] ?: 0) > 0 && paused[url] != true) animation.start() else animation.stop()
    }
}

/** 抓回來解好的一張圖。 */
sealed interface LoadedImage {
    /** 不會動的圖（JPEG、PNG、只有一格的 GIF……）。 */
    class Still(val bitmap: Bitmap) : LoadedImage

    /** 會動的圖（GIF、動態 WebP）：系統邊播邊解，只留目前那幾格。 */
    class Animated(val drawable: AnimatedImageDrawable) : LoadedImage
}

/**
 * 內嵌在文章裡的一張圖（BePTT 按「開啟圖片」之後在原列展開的 `article_content_image`）：
 * 載入中轉圈、失敗顯示原因「（點擊重試）」、成功就整個寬度顯示。只在按了按鈕之後才抓（不預載），用 App 共用的 OkHttpClient。
 * 動圖（GIF、動態 WebP）打開就播，點一下暫停（中間一個播放鍵）、再點一下從頭播。
 */
@Composable
fun InlineImage(url: String, images: OpenedImages) {
    val context = LocalContext.current
    val http = context.container.http
    val image = images.loaded[url]
    var error by remember(url) { mutableStateOf<String?>(null) }
    var attempt by remember(url) { mutableStateOf(0) }
    LaunchedEffect(url, attempt) {
        if (images.loaded[url] != null) return@LaunchedEffect
        error = null
        try {
            images.loaded[url] = loadImage(http, url, context.cacheDir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = imageError(e)
        }
    }
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    when {
        image is LoadedImage.Still -> Image(
            image.bitmap.asImageBitmap(),
            contentDescription = url,
            contentScale = ContentScale.FillWidth,
            modifier = modifier.testTag("inline_image"),
        )
        image is LoadedImage.Animated -> AnimatedImage(url, image.drawable, images, modifier)
        error != null -> Text(
            "$error（點擊重試）",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().clickable { attempt++ }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("image_error"),
        )
        else -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("正在載入圖片…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 一張動圖：進畫面就播、捲出畫面就停（[OpenedImages.shown]），點一下暫停／從頭播，暫停時中間一個播放鍵。 */
@Composable
private fun AnimatedImage(url: String, drawable: AnimatedImageDrawable, images: OpenedImages, modifier: Modifier) {
    DisposableEffect(url, drawable) {
        images.shown(url, 1)
        onDispose { images.shown(url, -1) }
    }
    val paused = images.paused[url] == true
    val painter = remember(drawable) { DrawablePainter(drawable) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            painter,
            contentDescription = url,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (paused) "播放" else "暫停") { images.tap(url) }
                .testTag("inline_image"),
        )
        if (paused) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "已暫停",
                tint = Color.White,
                modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(8.dp).testTag("image_paused"),
            )
        }
    }
}

/**
 * 把 Drawable 畫成 Compose 的 [Painter]（Compose 的 `Image` 不會自己播 Drawable）。
 * 硬體加速的畫布上，[AnimatedImageDrawable] 的每一格由系統的 RenderThread 自己換，不用主執行緒重畫；
 * 畫在軟體畫布上時它才用 `invalidateSelf`／`scheduleSelf` 叫下一格，這裡接成重畫。
 */
private class DrawablePainter(private val drawable: Drawable) : Painter(), RememberObserver {
    private var invalidations by mutableIntStateOf(0)
    private val handler = Handler(Looper.getMainLooper())
    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            invalidations++
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            handler.postAtTime(what, `when`)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            handler.removeCallbacks(what)
        }
    }

    override val intrinsicSize: Size
        get() = Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())

    override fun DrawScope.onDraw() {
        invalidations // 讀一下：invalidateSelf 時重畫
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }

    override fun onRemembered() {
        drawable.callback = callback
    }

    override fun onForgotten() {
        if (drawable.callback === callback) drawable.callback = null
        handler.removeCallbacksAndMessages(null)
    }

    override fun onAbandoned() = onForgotten()
}

/** 失敗的原因，用 BePTT 那幾句：逾時、不存在（404）、限制流量（429）、格式不支援（解不開）、其他。 */
fun imageError(e: Exception): String = when {
    e is SocketTimeoutException -> "圖片載入逾時"
    e is ImageException -> e.message ?: "圖片載入失敗"
    e is IOException -> "圖片載入失敗"
    else -> "圖片格式不支援或檔案不完整"
}

class ImageException(message: String) : IOException(message)

/**
 * 抓 [url] 的圖、解好（[decodeImage]）：超過 [MAX_BYTES] 不抓（文章裡的圖不該吃掉太多記憶體）。在 IO 執行緒做。
 * [cacheDir]：Android 9、10 解動圖要先寫成暫存檔（[decodeAnimation]）。
 */
suspend fun loadImage(http: OkHttpClient, url: String, cacheDir: File): LoadedImage = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) QuietPTT").build()
    val bytes = http.newCall(request).execute().use { response ->
        when (response.code) {
            404, 410 -> throw ImageException("圖片不存在或網址已失效")
            429 -> throw ImageException("圖片伺服器目前限制流量")
        }
        if (!response.isSuccessful) throw ImageException("圖片載入失敗（HTTP ${response.code}）")
        val body = response.body
        if (body.contentLength() > MAX_BYTES) throw ImageException("圖片太大")
        body.byteStream().use { it.readUpTo(MAX_BYTES + 1) }
    }
    if (bytes.size > MAX_BYTES) throw ImageException("圖片太大")
    decodeImage(bytes, cacheDir)
}

/**
 * 解 [bytes]：GIF 與動態 WebP（[mayBeAnimated]）交給 `ImageDecoder`，會動就是 [LoadedImage.Animated]；
 * 其他的、只有一格的、或 `ImageDecoder` 解不開的（例如檔案不完整），照一般的圖解第一格（`BitmapFactory`）。
 * 長邊超過 [MAX_SIDE] 像素的縮小解碼（[sampleSize]）。
 */
fun decodeImage(bytes: ByteArray, cacheDir: File): LoadedImage {
    if (mayBeAnimated(bytes)) {
        try {
            return decodeAnimation(bytes, cacheDir)
        } catch (e: Exception) {
            // 動圖解不開：退回只顯示第一格
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ImageException("圖片格式不支援或檔案不完整")
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw ImageException("圖片格式不支援或檔案不完整")
    return LoadedImage.Still(bitmap)
}

/**
 * 用 `ImageDecoder` 解：會動的得到 `AnimatedImageDrawable`（系統邊播邊解，不會每一格都解開放著），只有一格的得到一般的 Bitmap。
 * Android 9、10 從記憶體（ByteBuffer）解動圖有問題（issuetracker 139371066，Coil 也這樣繞）：先寫成 cache 裡的暫存檔再解。
 * 解好馬上刪：動圖的 Drawable 自己開著那個檔，刪掉之後還讀得到，Drawable 不要了才真的放掉空間；不用另外記得清。
 */
private fun decodeAnimation(bytes: ByteArray, cacheDir: File): LoadedImage {
    if (Build.VERSION.SDK_INT >= 30) return decodeAnimation(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
    val dir = File(cacheDir, "images").apply { mkdirs() }
    val file = File.createTempFile("image", null, dir)
    try {
        file.writeBytes(bytes)
        return decodeAnimation(ImageDecoder.createSource(file))
    } finally {
        file.delete()
    }
}

private fun decodeAnimation(source: ImageDecoder.Source): LoadedImage {
    val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
        decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // 只有一格時跟 BitmapFactory 解出來的一樣
    }
    return when (drawable) {
        is AnimatedImageDrawable -> LoadedImage.Animated(drawable)
        is BitmapDrawable -> LoadedImage.Still(drawable.bitmap)
        else -> throw ImageException("圖片格式不支援或檔案不完整")
    }
}

/**
 * 看檔頭可能會動的：GIF（`GIF8`），或 WebP（`RIFF`、第 8 個 byte 起 `WEBP`）用延伸格式 `VP8X`、而且動畫旗標（第 20 個 byte 的 0x02）有開。
 * GIF 要解了才知道幾格，一律試；一般的 WebP 照舊走 `BitmapFactory`。
 */
fun mayBeAnimated(bytes: ByteArray): Boolean {
    fun at(offset: Int, text: String) = bytes.size >= offset + text.length && text.indices.all { bytes[offset + it] == text[it].code.toByte() }
    if (at(0, "GIF8")) return true
    return at(0, "RIFF") && at(8, "WEBP") && at(12, "VP8X") && bytes.size > 20 && bytes[20].toInt() and 0x02 != 0
}

/** 長邊縮到 [MAX_SIDE] 像素以內要隔幾個像素取一個（2 的次方，`inSampleSize` 與 `setTargetSampleSize` 用）。 */
fun sampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (width / sample > MAX_SIDE || height / sample > MAX_SIDE) sample *= 2
    return sample
}

/** 最多讀 [limit] 個 byte（`readNBytes` 要 API 33）。 */
private fun java.io.InputStream.readUpTo(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

private const val MAX_BYTES = 20 * 1024 * 1024
private const val MAX_SIDE = 2048
