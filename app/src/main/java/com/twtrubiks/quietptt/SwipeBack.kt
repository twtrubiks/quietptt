package com.twtrubiks.quietptt

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 滑動退出（回上一頁）的方向；BePTT 的「滑動退出手勢方向」，預設跟它一樣由左至右。 */
enum class SwipeBackMode(val label: String) {
    LEFT_TO_RIGHT("由左至右"),
    RIGHT_TO_LEFT("由右至左"),
    OFF("關閉"),
}

/** 要滑過畫面寬度的幾成才退（BePTT「滑動關閉比例」預設 30）。 */
const val SWIPE_BACK_FRACTION = 0.3f

/** 甩得多快就退，不管距離（每秒幾 dp）。 */
const val SWIPE_BACK_FLING_DP_PER_SECOND = 1000f

/**
 * 放手時要不要退：往退出的方向拖了 [dragged] px（反方向是負的）、畫面寬 [width] px、放手時的速度 [velocity] px/s。
 * 拖過寬度的 [SWIPE_BACK_FRACTION]，或往那個方向甩得比 [flingVelocity] 快（而且真的有往那個方向拖）就退。
 */
fun swipeGoesBack(dragged: Float, width: Float, velocity: Float, mode: SwipeBackMode, flingVelocity: Float): Boolean {
    val sign = when (mode) {
        SwipeBackMode.LEFT_TO_RIGHT -> 1f
        SwipeBackMode.RIGHT_TO_LEFT -> -1f
        SwipeBackMode.OFF -> return false
    }
    if (width <= 0f) return false
    val distance = dragged * sign
    if (distance <= 0f) return false
    return distance >= width * SWIPE_BACK_FRACTION || velocity * sign >= flingVelocity
}

/**
 * 包住一頁：照 [mode] 的方向橫著拖，頁面跟著手指移，放手時 [swipeGoesBack] 說要退就叫 [onBack]，不然彈回去。
 * 只收橫向的拖（列表上下捲不受影響）；終端畫面自己要橫向捲，不包。
 */
@Composable
fun SwipeBackBox(mode: SwipeBackMode, enabled: Boolean, onBack: () -> Unit, content: @Composable () -> Unit) {
    var drag by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    val fling = with(LocalDensity.current) { SWIPE_BACK_FLING_DP_PER_SECOND.dp.toPx() }
    val active = enabled && mode != SwipeBackMode.OFF
    val sign = if (mode == SwipeBackMode.RIGHT_TO_LEFT) -1f else 1f
    val state = rememberDraggableState { delta ->
        // 只往退出的方向移；反方向拖不動
        drag = ((drag + delta) * sign).coerceAtLeast(0f) * sign
    }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { width = it.width }
            .draggable(
                state = state,
                orientation = Orientation.Horizontal,
                enabled = active,
                onDragStopped = { velocity ->
                    if (swipeGoesBack(drag, width.toFloat(), velocity, mode, fling)) {
                        drag = 0f
                        onBack()
                    } else {
                        animate(drag, 0f) { value, _ -> drag = value }
                    }
                },
            )
            .offset { IntOffset(drag.roundToInt(), 0) },
    ) {
        content()
    }
}
