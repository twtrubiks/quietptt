package com.twtrubiks.quietptt

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

enum class ThemeMode(val label: String) {
    SYSTEM("跟系統"),
    LIGHT("淺色"),
    DARK("深色"),
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
