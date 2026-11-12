package com.twtrubiks.quietptt

import android.content.SharedPreferences
import androidx.core.content.edit

/** 發文頁存下來的草稿：標題與內文（分類、簽名檔不存）。 */
data class PostDraft(val title: String, val body: String) {
    /** 「恢復內容」對話框裡給人認的開頭：有標題用標題，沒有用內文；一行，最多 [PREVIEW_CHARS] 個字。 */
    val preview: String
        get() {
            val text = title.ifBlank { body }.trim().replace(Regex("\\s+"), " ")
            return if (text.length > PREVIEW_CHARS) text.take(PREVIEW_CHARS) + "…" else text
        }

    companion object {
        const val PREVIEW_CHARS = 20
    }
}

/**
 * 發文的草稿，一個看板一份（板名不分大小寫）：離開發文頁時選「保留草稿」才存，下次進同一個板的發文頁問要不要恢復；
 * 發出去了、選「捨棄草稿」或「刪除暫存」就刪掉。只有發文有，回文、寫信沒有。
 *
 * 存在 SharedPreferences `drafts`：一個看板兩個鍵（`<板名小寫>.title`、`<板名小寫>.body`）。
 * 不備份、不隨換機傳輸（`data_extraction_rules.xml`）。
 */
class PostDrafts(private val prefs: SharedPreferences) {
    fun get(board: String): PostDraft? {
        val body = prefs.getString(bodyKey(board), null) ?: return null
        return PostDraft(prefs.getString(titleKey(board), null).orEmpty(), body)
    }

    fun save(board: String, draft: PostDraft) = prefs.edit {
        putString(titleKey(board), draft.title)
        putString(bodyKey(board), draft.body)
    }

    fun remove(board: String) = prefs.edit {
        remove(titleKey(board))
        remove(bodyKey(board))
    }

    private fun titleKey(board: String) = "${board.lowercase()}.title"
    private fun bodyKey(board: String) = "${board.lowercase()}.body"
}

/**
 * 發文頁打的東西值不值得問「要保留這份草稿嗎？」：有標題，或內文不是空的、也不是原樣的預設內文（[template]）。
 */
fun worthKeeping(title: String, body: String, template: String): Boolean =
    title.isNotBlank() || (body.isNotBlank() && body != template)
