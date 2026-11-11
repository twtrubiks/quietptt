package com.twtrubiks.quietptt

import android.content.SharedPreferences

/** 記憶體裡的假 SharedPreferences，只做 [Credentials]、[SearchHistory] 用得到的：字串、Long、contains、edit（後寫的算、clear 先做）。 */
internal class MemoryPrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()

    override fun getAll(): Map<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?): String? = map[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = throw UnsupportedOperationException()
    override fun getInt(key: String, defValue: Int): Int = throw UnsupportedOperationException()
    override fun getLong(key: String, defValue: Long): Long = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = throw UnsupportedOperationException()
    override fun getBoolean(key: String, defValue: Boolean): Boolean = throw UnsupportedOperationException()
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private val removed = Any()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { changes[key] = value ?: removed }
        override fun putStringSet(key: String, values: Set<String>?) = throw UnsupportedOperationException()
        override fun putInt(key: String, value: Int) = throw UnsupportedOperationException()
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = throw UnsupportedOperationException()
        override fun putBoolean(key: String, value: Boolean) = throw UnsupportedOperationException()
        override fun remove(key: String) = apply { changes[key] = removed }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) map.clear()
            for ((key, value) in changes) if (value === removed) map.remove(key) else map[key] = value
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
