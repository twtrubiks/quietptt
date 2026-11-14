package com.twtrubiks.quietptt

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 連線畫面要畫的：哪個帳號、到了哪一步（連上 PTT 中／登入中）。 */
internal data class AutoLoginProgress(val account: String, val loggingIn: Boolean)

/** 自動登入（打開 App、背景斷線後回到前景）進行中就是連線畫面要畫的東西；在登入頁按的、其他狀態是 null。 */
internal fun ConnectionState.autoLoginProgress(): AutoLoginProgress? = when (this) {
    is ConnectionState.Connecting -> if (automatic) AutoLoginProgress(account, loggingIn = false) else null
    is ConnectionState.LoggingIn -> if (automatic) AutoLoginProgress(account, loggingIn = true) else null
    else -> null
}

/**
 * 連線畫面圖示裡的「>_」：用小方塊拼的，每一格什麼時候亮。座標是 App 圖示的 108×108（`ic_launcher_foreground.xml`），
 * 跟圖示原本的提示符號（x 38～66、y 46～63）差不多同一塊地方：
 * ```
 * ■■
 *  ■■
 *   ■■
 *  ■■
 * ■■   ■■■■■
 * ```
 * 先是「>」由上往下一格一格亮，再是游標「_」由左往右，像打字；拼好之後游標閃（亮 [BLINK_MS]、暗 [BLINK_MS]）。
 */
internal object PromptBlocks {
    /** 一格的大小；方塊是 [BLOCK]，格子之間留一點縫，看得出是一顆一顆的。 */
    const val CELL = 3f
    const val BLOCK = 2.5f
    const val LEFT = 37f
    const val TOP = 47f

    /** （欄, 列），亮的順序。 */
    val cells: List<Pair<Int, Int>> = listOf(
        0 to 0, 1 to 0,
        1 to 1, 2 to 1,
        2 to 2, 3 to 2,
        1 to 3, 2 to 3,
        0 to 4, 1 to 4,
    ) + (5..9).map { it to 4 }

    /** [cells] 裡游標從第幾格開始。 */
    const val CURSOR_FROM = 10

    /** 下一格晚多久亮、每一格淡入多久。 */
    const val STEP_MS = 50L
    const val FADE_MS = 120L

    /** 全部亮好的時間：之後只剩游標閃。 */
    val ASSEMBLED_MS = (cells.size - 1) * STEP_MS + FADE_MS

    /** 游標亮、暗各多久（跟一般終端差不多）。 */
    const val BLINK_MS = 530L

    /** 第 [index] 格在開始後 [elapsed] 毫秒的不透明度。 */
    fun alpha(index: Int, elapsed: Long): Float {
        val start = index * STEP_MS
        return when {
            elapsed < start -> 0f
            elapsed < start + FADE_MS -> (elapsed - start).toFloat() / FADE_MS
            index >= CURSOR_FROM && elapsed >= ASSEMBLED_MS -> if ((elapsed - ASSEMBLED_MS) / BLINK_MS % 2 == 0L) 1f else 0f
            else -> 1f
        }
    }
}

// 跟 App 圖示（res/values/colors.xml）同一組顏色：接在系統啟動畫面後面，看起來是同一個圖示
private val IconBackground = Color(0xFF1F3A5F)
private val IconTerminal = Color(0xFF0E1A2B)
private val IconPrompt = Color(0xFF7FD1AE)

/** 圖示（含圓底）畫多大：跟 Android 12 起系統啟動畫面「有圓底的圖示」一樣，240dp 的圖、圓底直徑 160dp（圖示座標的 72）。 */
private val ICON_SIZE = 240.dp

/**
 * 自動登入（打開 App、背景斷線後回到前景）時蓋在最上面的連線畫面：中間是 App 圖示，裡面的「>_」一格一格拼出來（[PromptBlocks]），
 * 拼好之後游標閃；下面是帳號與「連線中…／登入中…」。登好了（或失敗回登入頁）由 [QuietPttApp] 淡出拿掉。
 *
 * 圖示的大小、位置跟 Android 12 起的系統啟動畫面一樣（`values-v31/themes.xml`：圓底、還沒有提示符號的空終端，放在整個視窗正中間），
 * 底色也跟啟動畫面一樣（`window_light`／`window_dark`）：打開 App 時從啟動畫面接過來，就像終端醒過來。
 * 所以這一頁用整個視窗置中（[QuietPttApp] 把它放在 safeDrawingPadding 外面）。
 *
 * 省電：拼的那不到一秒每一格重畫（只重畫圖示，不重組），拼好之後只有游標每 [PromptBlocks.BLINK_MS] 換一次；拿掉之後什麼都不畫。
 * 系統設定把動畫關掉（動畫時間 0）就直接畫拼好的、游標不閃。蓋在上面時底下的登入頁按不到（[Surface] 吃掉點擊）。
 */
@Composable
internal fun ConnectingScreen(progress: AutoLoginProgress, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    var elapsed by remember { mutableLongStateOf(if (still) PromptBlocks.ASSEMBLED_MS else 0L) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (elapsed < PromptBlocks.ASSEMBLED_MS) {
            elapsed = withFrameMillis { it } - start
        }
        while (true) {
            delay(PromptBlocks.BLINK_MS)
            elapsed += PromptBlocks.BLINK_MS
        }
    }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val background = colorResource(if (dark) R.color.window_dark else R.color.window_light)
    Surface(modifier.fillMaxSize().testTag("connecting_screen"), color = background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // elapsed 只在畫的時候讀：動畫只重畫這一塊，不重組
            Canvas(Modifier.align(Alignment.Center).size(ICON_SIZE)) { drawIcon(elapsed) }
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = maxHeight / 2 + ICON_SIZE / 3 + 20.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    progress.account,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("connecting_account"),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (progress.loggingIn) "登入中…" else "連線中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("connecting_status"),
                )
            }
        }
    }
}

/** 圓底、深色的終端視窗（`ic_launcher_foreground.xml` 的第一個 path）、裡面拼到一半的「>_」。 */
private fun DrawScope.drawIcon(elapsed: Long) {
    val unit = size.width / 108f
    drawCircle(IconBackground, radius = 36f * unit, center = Offset(54f * unit, 54f * unit))
    drawRoundRect(
        IconTerminal,
        topLeft = Offset(28f * unit, 34f * unit),
        size = Size(52f * unit, 40f * unit),
        cornerRadius = CornerRadius(4f * unit),
    )
    val block = Size(PromptBlocks.BLOCK * unit, PromptBlocks.BLOCK * unit)
    val inset = (PromptBlocks.CELL - PromptBlocks.BLOCK) / 2
    PromptBlocks.cells.forEachIndexed { index, (column, row) ->
        val alpha = PromptBlocks.alpha(index, elapsed)
        if (alpha > 0f) {
            drawRect(
                IconPrompt,
                topLeft = Offset(
                    (PromptBlocks.LEFT + column * PromptBlocks.CELL + inset) * unit,
                    (PromptBlocks.TOP + row * PromptBlocks.CELL + inset) * unit,
                ),
                size = block,
                alpha = alpha,
            )
        }
    }
}
