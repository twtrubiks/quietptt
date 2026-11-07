package com.twtrubiks.quietptt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 錄影用的展示流程，CI 只錄這個 class，`scripts/ci/record.sh` 會在執行期間錄下整個螢幕。
 * 不連 PTT（模擬器連不連得到 ptt.cc 不一定，也不該把真的帳號放進 CI）：只走登入頁與設定頁。
 * 全長控制在 150 秒內。
 */
@RunWith(AndroidJUnit4::class)
class DemoRecordingTest : UiTest() {
    @Test
    fun demo() {
        container.credentials.clear()
        launchApp()
        waitFor(By.text("登入"), timeout = 30_000)
        pause(2000)
        screenshot("01-login")

        // 設定頁：連線方式預設 SSH，切成 WebSocket 再切回來
        find(res("settings_button")).click()
        waitFor(By.text("連線方式"))
        pause(1500)
        screenshot("02-settings")
        find(res("transport_websocket")).click()
        pause(1000)
        assertEquals(TransportKind.WEBSOCKET, container.transport.value)
        find(res("transport_ssh")).click()
        pause(1000)
        assertEquals(TransportKind.SSH, container.transport.value)
        device.pressBack()
        waitFor(By.text("登入"))
        pause(1500)
    }
}
