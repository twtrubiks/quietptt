package com.twtrubiks.quietptt

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** 發文、回文時接在內文最後面的那兩列（設定「文末加上 Sent from QuietPTT」開著才加）。不寫手機型號。 */
const val SENT_FROM = "-----\nSent from QuietPTT"

/** 原始碼（設定頁「關於」）。 */
const val GITHUB_URL = "https://github.com/twtrubiks/quietptt"

enum class ThemeMode(val label: String) {
    SYSTEM("跟系統"),
    LIGHT("淺色"),
    DARK("深色"),
}

/** 文章與信的字級（[ArticleTextScale]）：系統的字體大小再乘 [scale]。 */
enum class ArticleTextSize(val label: String, val scale: Float) {
    SMALL("小", 0.85f),
    NORMAL("標準", 1f),
    LARGE("大", 1.15f),
    LARGER("更大", 1.3f),
    LARGEST("最大", 1.5f),
}

enum class TransportKind(val label: String) {
    SSH("SSH（ptt.cc:22）"),
    WEBSOCKET("WebSocket（ws.ptt.cc）"),
}

/**
 * 使用者的設定，存在 SharedPreferences `settings`（存名字，不存序號）。
 * [value] 是 Compose 的狀態，畫面直接讀。
 */
class EnumSetting<E : Enum<E>>(private val prefs: SharedPreferences, private val key: String, entries: List<E>, default: E) {
    var value by mutableStateOf(prefs.getString(key, null)?.let { saved -> entries.firstOrNull { it.name == saved } } ?: default)
        private set

    fun set(value: E) {
        this.value = value
        prefs.edit { putString(key, value.name) }
    }
}

class BoolSetting(private val prefs: SharedPreferences, private val key: String, default: Boolean) {
    var value by mutableStateOf(prefs.getBoolean(key, default))
        private set

    fun set(value: Boolean) {
        this.value = value
        prefs.edit { putBoolean(key, value) }
    }
}
