package com.twtrubiks.quietptt.core.record

import com.twtrubiks.quietptt.core.ptt.Page
import com.twtrubiks.quietptt.core.ptt.ScreenParser
import com.twtrubiks.quietptt.core.terminal.Terminal

/**
 * 從一段錄影（[Recording]）量「送鍵之後 PTT 的回應分幾段來、段跟段隔多久」，用來看 `Session.QUIET_MS`（畫面多久沒變就當它畫完）該是多少。
 * `tools/capture` 用：`--e2e` 走完印一份、`--timing 錄影檔` 分析錄好的。只算時間，不連 PTT。
 *
 * 一次「回應」是一次送鍵之後、下一次送鍵之前收到的所有段。段跟段的間隔比安靜時間長，`Session` 就可能在後面那段到之前把畫面當成畫完。
 * 會不會真的出錯要看那時候的畫面：把 bytes 重播進 [Terminal]，每一段之後用 [ScreenParser] 認一次——
 * 已經認得出是最後那種頁面、內容卻跟最後不一樣（[Gap.partial]）才是「畫到一半就被當成畫完」；
 * 還認不出來的（過場畫面、PTT 自己在等，例如登入）`Session` 本來就會繼續等，不算。
 * 這只是近似：`Session` 每一步等的條件比「是哪種頁面」嚴（例如文章頁看進度列），實際會出錯的不會比這裡算的多。
 */
object Timing {
    /** 回應裡相鄰兩段的間隔：[ms] 毫秒；[page] 是前一段到了之後認成哪種頁面。 */
    class Gap(val ms: Long, val page: String, val partial: Boolean)

    /**
     * 一次送鍵（[label]：錄影裡那次送鍵的說明）的回應：[firstMs] 是送出到第一段到的毫秒，[chunks] 段，最後認成 [page]。
     * 沒有回應的（PTT 一個字都不回）不在裡面。
     */
    class Response(val label: String, val firstMs: Long, val chunks: Int, val gaps: List<Gap>, val page: String)

    /** [recording] 裡每一次有回應的送鍵。第一次送鍵之前收到的（剛連上的歡迎畫面）不算。 */
    fun responses(recording: Recording): List<Response> {
        val terminal = Terminal()
        val found = ArrayList<Response>()
        var sent: Recording.Sent? = null
        val arrivals = ArrayList<Long>()
        val screens = ArrayList<Pair<String, List<String>>>()

        fun flush() {
            val key = sent ?: return
            if (arrivals.isEmpty()) return
            val (lastPage, lastLines) = screens.last()
            val gaps = (0 until arrivals.size - 1).map { i ->
                val (page, lines) = screens[i]
                Gap(arrivals[i + 1] - arrivals[i], page, partial = page != UNKNOWN && page == lastPage && lines != lastLines)
            }
            found += Response(key.label, arrivals.first() - key.ms, arrivals.size, gaps, lastPage)
        }

        for (event in recording.events) {
            when (event) {
                is Recording.Sent -> {
                    flush()
                    sent = event
                    arrivals.clear()
                    screens.clear()
                }
                is Recording.Received -> {
                    terminal.feed(event.bytes)
                    if (sent != null) {
                        val screen = terminal.snapshot()
                        arrivals += event.ms
                        screens += kind(ScreenParser.parse(screen)) to screen.lines
                    }
                }
                is Recording.Note -> Unit
            }
        }
        flush()
        return found
    }

    /**
     * 給人看的報告。[quietMs] 是現在的安靜時間，[candidates] 是想比較的幾個值。
     * 只有數字、送鍵的說明與頁面的種類，沒有畫面上的字。
     */
    fun report(recording: Recording, quietMs: Long, candidates: List<Long> = CANDIDATES): String = buildString {
        val sentCount = recording.events.count { it is Recording.Sent }
        val responses = responses(recording)
        appendLine("時間量測：送鍵 $sentCount 次，有回應的 ${responses.size} 次（現在畫面 $quietMs ms 沒變就當它畫完）")
        if (responses.isEmpty()) return@buildString

        val first = responses.map { it.firstMs }.sorted()
        appendLine("送鍵到第一段：中位數 ${percentile(first, 50)} ms，90% 在 ${percentile(first, 90)} ms 內，最慢 ${first.last()} ms")

        val one = responses.count { it.chunks == 1 }
        val two = responses.count { it.chunks == 2 }
        appendLine("一次回應分幾段：1 段 $one 次、2 段 $two 次、3 段以上 ${responses.size - one - two} 次（最多 ${responses.maxOf { it.chunks }} 段）")

        val gaps = responses.flatMap { r -> r.gaps.map { r to it } }
        if (gaps.isEmpty()) {
            appendLine("每次回應都是一段到齊，沒有段跟段的間隔可以量")
            return@buildString
        }
        val sorted = gaps.map { it.second.ms }.sorted()
        appendLine(
            "段跟段的間隔（${sorted.size} 個）：中位數 ${percentile(sorted, 50)} ms，90% 在 ${percentile(sorted, 90)} ms 內，" +
                "99% 在 ${percentile(sorted, 99)} ms 內，最長 ${sorted.last()} ms",
        )

        appendLine("安靜時間設成下面的值時，比它長的間隔有幾個（「畫到一半」：那時候已經認得出是最後那種頁面、內容還沒到齊，會被當成畫完）：")
        for (candidate in (candidates + quietMs).distinct().sorted()) {
            val longer = gaps.filter { it.second.ms >= candidate }
            val partial = longer.filter { it.second.partial }
            val mark = if (candidate == quietMs) "（現在）" else ""
            appendLine(
                "  %4d ms%s：%d 個（%.1f%%），畫到一半 %d 個、在 %d 次回應裡".format(
                    candidate, mark, longer.size, longer.size * 100.0 / gaps.size, partial.size, partial.map { it.first }.distinct().size,
                ),
            )
        }

        appendLine("最長的間隔（前一段到了之後認成的頁面 → 最後的頁面）：")
        for ((response, gap) in gaps.sortedByDescending { it.second.ms }.take(LONGEST)) {
            val mark = if (gap.partial) "　← 畫到一半" else ""
            appendLine("  %5d ms　%s　%s → %s%s".format(gap.ms, response.label, gap.page, response.page, mark))
        }
    }.trimEnd()

    /** [sorted]（由小到大）裡第 [percent] 百分位的值。 */
    private fun percentile(sorted: List<Long>, percent: Int): Long =
        sorted[((sorted.size * percent + 99) / 100 - 1).coerceIn(0, sorted.size - 1)]

    private fun kind(page: Page): String = page::class.simpleName ?: UNKNOWN

    private const val UNKNOWN = "Unknown"

    /** 報告最後列幾個最長的間隔。 */
    private const val LONGEST = 15

    /** 預設比較的安靜時間（毫秒）。 */
    val CANDIDATES = listOf(30L, 50L, 80L, 100L, 150L)
}
