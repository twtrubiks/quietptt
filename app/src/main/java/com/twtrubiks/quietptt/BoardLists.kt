package com.twtrubiks.quietptt

import com.twtrubiks.quietptt.core.ptt.ArticleSummary

/**
 * 看板上次列過的文章（[BoardScreen]：再進同一個板先列這份，PTT 走到最新那頁再併進來或換掉）。
 * **只在記憶體**，不存檔：App 的 process 結束就沒了。跟著帳號（未讀的「+」每個帳號不一樣）；
 * 最多記 [MAX_BOARDS] 個板（最久沒用的先丟），每個板留最新的 [MAX_ARTICLES] 篇一般文章與全部置底文。
 * 搜尋結果不記（序號是結果裡的第幾筆，下次搜的不一定一樣）。主執行緒與背景都可能叫，所以加鎖。
 */
class BoardLists {
    private val lists = object : LinkedHashMap<String, List<ArticleSummary>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ArticleSummary>>): Boolean = size > MAX_BOARDS
    }

    /** [account] 上次列過的 [board]（板名不分大小寫）；沒列過回 null。 */
    @Synchronized
    fun get(account: String, board: String): List<ArticleSummary>? = lists[key(account, board)]

    /** 記下 [articles]（PTT 的順序：序號由小到大、置底文在最後，跟 `mergedWith` 一樣）。空的不記。 */
    @Synchronized
    fun put(account: String, board: String, articles: List<ArticleSummary>) {
        val numbered = articles.filter { it.index != null }.takeLast(MAX_ARTICLES)
        val pinned = articles.filter { it.index == null }
        if (numbered.isEmpty() && pinned.isEmpty()) return
        lists[key(account, board)] = numbered + pinned
    }

    private fun key(account: String, board: String) = account.lowercase() + "\n" + board.lowercase()

    companion object {
        const val MAX_BOARDS = 30
        const val MAX_ARTICLES = 100
    }
}
