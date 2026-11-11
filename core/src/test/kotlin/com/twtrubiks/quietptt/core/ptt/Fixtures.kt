package com.twtrubiks.quietptt.core.ptt

import com.twtrubiks.quietptt.core.record.Recording
import com.twtrubiks.quietptt.core.terminal.Screen
import com.twtrubiks.quietptt.core.terminal.Terminal

/** 測試用的畫面：`core/src/test/resources/screens/<name>.txt`，24 列文字（tools/capture 存的）。 */
object Fixtures {
    fun text(name: String): String =
        checkNotNull(Fixtures::class.java.getResourceAsStream("/screens/$name.txt")) { "沒有 fixture：$name" }
            .bufferedReader().use { it.readText() }

    fun screen(name: String): Screen = Screen.of(text(name))

    /**
     * 重播 `core/src/test/resources/recordings/<name>.txt`：每次送出按鍵之前的畫面（上一個回應畫完了），加上最後的畫面。
     * 第一個送出之前的畫面（錄影開始時還沒畫東西）不算。
     */
    fun replay(name: String): List<Screen> {
        val text = checkNotNull(Fixtures::class.java.getResourceAsStream("/recordings/$name.txt")) { "沒有錄影：$name" }
            .bufferedReader().use { it.readText() }
        val terminal = Terminal()
        val shots = ArrayList<Screen>()
        var sent = 0
        for (event in Recording.parse(text).events) {
            when (event) {
                is Recording.Received -> terminal.feed(event.bytes)
                is Recording.Sent -> if (sent++ > 0) shots += terminal.snapshot()
                is Recording.Note -> Unit
            }
        }
        shots += terminal.snapshot()
        return shots
    }

    /**
     * 照 pttbbs `readdoent` 的欄位排一列文章（見 `ScreenParser.articles`）：游標 1 欄、序號 %6d（置底是「  ★ 」）、空白、
     * 標記 1 欄、推文數 2 欄、日期 %-6.5s、作者 13 欄、「□ 標題」。推文數跟日期中間沒有空格。
     */
    fun articleRow(
        index: Int?,
        push: String = "  ",
        mark: Char = ' ',
        date: String = "11/07",
        author: String = "someone",
        title: String = "標題",
        kind: String = "□",
        cursor: String = " ",
    ): String {
        val number = if (index == null) "   ★ " else "%6d".format(index)
        // 爆 是全形，自己就佔 2 欄
        val pushField = if (push.any { Screen.isWide(it) }) push else push.padStart(2)
        return cursor + number + " " + mark + pushField + "%-6.5s".format(date) + author.padEnd(13) + kind + " " + title
    }
}
