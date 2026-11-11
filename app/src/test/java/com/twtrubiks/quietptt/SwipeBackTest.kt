package com.twtrubiks.quietptt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 滑動回上一頁：放手時照距離（畫面寬度三成）或速度決定；方向照設定。 */
class SwipeBackTest {
    private val width = 1000f
    private val fling = 2000f

    @Test
    fun farEnoughInTheConfiguredDirectionGoesBack() {
        assertTrue(swipeGoesBack(dragged = 300f, width, velocity = 0f, SwipeBackMode.LEFT_TO_RIGHT, fling))
        assertFalse(swipeGoesBack(dragged = 299f, width, velocity = 0f, SwipeBackMode.LEFT_TO_RIGHT, fling))
        assertTrue(swipeGoesBack(dragged = -300f, width, velocity = 0f, SwipeBackMode.RIGHT_TO_LEFT, fling))
        // 方向反了不算
        assertFalse(swipeGoesBack(dragged = -900f, width, velocity = 0f, SwipeBackMode.LEFT_TO_RIGHT, fling))
        assertFalse(swipeGoesBack(dragged = 900f, width, velocity = 0f, SwipeBackMode.RIGHT_TO_LEFT, fling))
    }

    @Test
    fun aFastFlingGoesBackEvenIfShort() {
        assertTrue(swipeGoesBack(dragged = 50f, width, velocity = 2000f, SwipeBackMode.LEFT_TO_RIGHT, fling))
        assertFalse(swipeGoesBack(dragged = 50f, width, velocity = 1999f, SwipeBackMode.LEFT_TO_RIGHT, fling))
        assertTrue(swipeGoesBack(dragged = -50f, width, velocity = -2000f, SwipeBackMode.RIGHT_TO_LEFT, fling))
        // 甩得快但沒往那個方向拖（例如手指先往回）不算
        assertFalse(swipeGoesBack(dragged = 0f, width, velocity = 5000f, SwipeBackMode.LEFT_TO_RIGHT, fling))
        assertFalse(swipeGoesBack(dragged = 50f, width, velocity = -5000f, SwipeBackMode.LEFT_TO_RIGHT, fling))
    }

    @Test
    fun offNeverGoesBack() {
        assertFalse(swipeGoesBack(dragged = 999f, width, velocity = 9999f, SwipeBackMode.OFF, fling))
        assertFalse(swipeGoesBack(dragged = 999f, width = 0f, velocity = 0f, SwipeBackMode.LEFT_TO_RIGHT, fling))
    }
}
