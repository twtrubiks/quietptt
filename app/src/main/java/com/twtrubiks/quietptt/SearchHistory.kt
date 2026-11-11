package com.twtrubiks.quietptt

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.content.edit
import com.twtrubiks.quietptt.core.ptt.ArticleSearch
import com.twtrubiks.quietptt.core.ptt.SearchKind

/**
 * 看板裡搜尋的紀錄（照 BePTT 的「搜尋紀錄」）：標題、作者、推文數各一份，不分看板。只記從搜尋對話框送出的。
 * 最近搜的排最前面；同一個字（不分大小寫：PTT 搜標題、作者都不分）只留一筆，換成這次打的寫法；每種最多 [MAX] 筆，多的丟掉最舊的。
 *
 * 存在 SharedPreferences `search_history`：一種一個鍵（[SearchKind] 的名字），`\n` 隔開（輸入框是單行，[withSearch] 也把換行換成空白）。
 * 不備份、不隨換機傳輸（`data_extraction_rules.xml`）。[of] 讀的是 Compose 狀態，對話框、設定頁直接畫。
 */
class SearchHistory(private val prefs: SharedPreferences) {
    private val lists = mutableStateMapOf<SearchKind, List<String>>().apply {
        for (kind in SearchKind.entries) put(kind, prefs.getString(kind.name, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty())
    }

    /** [kind] 的紀錄，最近的在前面。 */
    fun of(kind: SearchKind): List<String> = lists[kind].orEmpty()

    /** 三種都沒有紀錄。 */
    val isEmpty: Boolean get() = SearchKind.entries.all { of(it).isEmpty() }

    /** 記下剛送出的搜尋。 */
    fun add(search: ArticleSearch) = store(search.kind, withSearch(of(search.kind), search.query))

    /** 刪掉一筆（紀錄旁的「×」）。 */
    fun remove(kind: SearchKind, query: String) = store(kind, of(kind) - query)

    /** 清掉一種（搜尋對話框的「清除」）。 */
    fun clear(kind: SearchKind) = store(kind, emptyList())

    /** 全部清掉（設定的「清除搜尋紀錄」）。 */
    fun clearAll() {
        for (kind in SearchKind.entries) lists[kind] = emptyList()
        prefs.edit { clear() }
    }

    private fun store(kind: SearchKind, list: List<String>) {
        lists[kind] = list
        prefs.edit { if (list.isEmpty()) remove(kind.name) else putString(kind.name, list.joinToString(SEPARATOR)) }
    }

    companion object {
        /** 每種最多記幾筆。 */
        const val MAX = 20
        private const val SEPARATOR = "\n"
    }
}

/** [history] 加上剛搜的 [query]：排到最前面、拿掉同一個字（不分大小寫）的舊的那筆、最多 [max] 筆。空白的不記。 */
fun withSearch(history: List<String>, query: String, max: Int = SearchHistory.MAX): List<String> {
    val q = query.replace('\n', ' ').trim()
    if (q.isEmpty()) return history
    return (listOf(q) + history.filterNot { it.equals(q, ignoreCase = true) }).take(max)
}
