package com.twtrubiks.quietptt.core.ptt

import com.twtrubiks.quietptt.core.terminal.Screen

/** 測試用的畫面：`core/src/test/resources/screens/<name>.txt`，24 列文字（tools/capture 存的）。 */
object Fixtures {
    fun screen(name: String): Screen {
        val text = checkNotNull(Fixtures::class.java.getResourceAsStream("/screens/$name.txt")) { "沒有 fixture：$name" }
            .bufferedReader().use { it.readText() }
        return Screen.of(text)
    }
}
