package com.twtrubiks.quietptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 乾淨守門（manifest 這一半；依賴樹那一半是 `checkCleanDependencies`）：
 * 權限只有 INTERNET，沒有 service、receiver、provider，沒有 largeHeap，allowBackup 關著。
 * 要加任何一項都先改這裡、寫清楚為什麼。
 */
class CleanManifestTest {
    // Gradle 跑單元測試時工作目錄是 app/
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun onlyInternetPermission() {
        val permissions = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(listOf("android.permission.INTERNET"), permissions)
    }

    @Test
    fun noBackgroundComponents() {
        for (tag in listOf("<service", "<receiver", "<provider")) {
            assertFalse("manifest 裡不該有 $tag", manifest.contains(tag))
        }
    }

    @Test
    fun noLargeHeapAndNoBackup() {
        assertFalse(manifest.contains("largeHeap"))
        assertTrue(manifest.contains("""android:allowBackup="false""""))
    }
}
