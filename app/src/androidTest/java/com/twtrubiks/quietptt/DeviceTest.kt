package com.twtrubiks.quietptt

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** 只有真機才測得到的事：AndroidKeyStore 的 AES-GCM 加解密帳密（JVM 沒有 AndroidKeyStore）。`record.sh` 在錄影前先跑、不錄影。 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("device-test-account", Context.MODE_PRIVATE)

    @After
    fun cleanUp() {
        prefs.edit().clear().commit()
    }

    @Test
    fun passwordSurvivesRoundTripAndIsNotStoredInPlainText() {
        val credentials = Credentials(prefs)
        credentials.save("someone", "p@ss 中文")

        assertEquals(Account("someone", "p@ss 中文"), Credentials(prefs).load())
        val stored = prefs.getString("password", null)!!
        assertEquals(false, stored.contains("p@ss"))
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
}
