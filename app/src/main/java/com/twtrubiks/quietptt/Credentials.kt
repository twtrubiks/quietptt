package com.twtrubiks.quietptt

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Account(val name: String, val password: String?)

/**
 * 記住的帳號與密碼，可以記好幾組（登入頁挑一個、或打新的）。密碼用 AndroidKeyStore 裡的 AES-256-GCM 金鑰加密後存進
 * SharedPreferences `account`，金鑰離不開這台手機，備份、換機都解不開（manifest 也不讓這份 prefs 被備份）。帳號本身是公開的，明碼存。
 *
 * 存法：`name` 是最後一次登入的帳號（[load]）、`names` 是所有記住的帳號（照第一次記住的順序，`\n` 隔開）、
 * 每個帳號的密碼在 `password:<帳號>`、上次登入的時間在 `lastLogin:<帳號>`（登入頁的「上次登入時間」，照 PttX）。舊版只有一組（`name`＋`password`），讀的時候當成那一組；第一次存或忘掉任何一組時
 * 先把它搬到那個帳號自己的鍵（不然記第二組時第一組的密碼會不見）。PTT 的帳號不分大小寫：換了大小寫再存，換掉同一組，不多一組。
 *
 * [box] 是加解密（預設 AndroidKeyStore；JVM 測試換成假的），[clock] 是現在幾點（毫秒）。
 */
class Credentials(
    private val prefs: SharedPreferences,
    private val box: PasswordBox = KeyStoreBox,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 最後一次登入的那組；沒記過就 null。 */
    fun load(): Account? {
        val name = prefs.getString(KEY_NAME, null) ?: return null
        return Account(name, passwordOf(name))
    }

    /** 所有記住的帳號：最後一次登入的排最前面，其他照上次登入時間、新的在前（舊版沒記時間的排後面，照第一次記住的順序）。 */
    fun loadAll(): List<Account> {
        val current = prefs.getString(KEY_NAME, null)
        val others = (names() - setOfNotNull(current)).sortedByDescending { lastLogin(it) ?: Long.MIN_VALUE }
        return (listOfNotNull(current) + others).map { Account(it, passwordOf(it)) }
    }

    /** 記了幾組帳號（不解密碼）：記了兩組以上，寫東西的地方才標出用哪個帳號。 */
    fun count(): Int = names().size

    /** [name] 上次登入成功的時間（毫秒）；這版之前登入的沒記，null。 */
    fun lastLogin(name: String): Long? = lastLoginKey(name).takeIf(prefs::contains)?.let { prefs.getLong(it, 0) }

    /**
     * 登入成功：記住（或更新）[name] 這組、把它設成最後一次登入的、記下現在的時間。[password] 是 null 表示只記帳號。
     * 加密失敗（KeyStore 出問題）就當作沒記密碼，不報錯。
     */
    fun save(name: String, password: String?) {
        val encrypted = password?.let { runCatching { box.seal(it) }.getOrNull() }
        val names = names()
        // 同一個帳號（不分大小寫）留原來的位置、換成這次打的寫法
        val updated = names.map { if (it.equals(name, ignoreCase = true)) name else it }.distinct().let { if (name in it) it else it + name }
        prefs.edit {
            migrateLegacy()
            for (old in names) if (old != name && old.equals(name, ignoreCase = true)) {
                remove(passwordKey(old))
                remove(lastLoginKey(old))
            }
            putString(KEY_NAME, name)
            putLong(lastLoginKey(name), clock())
            putString(KEY_NAMES, updated.joinToString(NAMES_SEPARATOR))
            // 寫在 migrateLegacy 後面：同一個鍵，後寫的算
            if (encrypted == null) remove(passwordKey(name)) else putString(passwordKey(name), encrypted)
        }
    }

    /** 忘掉 [name] 這組（帳號與密碼都丟）。它是最後一次登入的話，最後一次登入換成剩下的第一個。 */
    fun remove(name: String) {
        val rest = names() - name
        prefs.edit {
            migrateLegacy()
            remove(passwordKey(name))
            remove(lastLoginKey(name))
            if (rest.isEmpty()) remove(KEY_NAMES) else putString(KEY_NAMES, rest.joinToString(NAMES_SEPARATOR))
            if (prefs.getString(KEY_NAME, null) == name) {
                if (rest.isEmpty()) remove(KEY_NAME) else putString(KEY_NAME, rest.first())
            }
        }
    }

    /** 只把所有密碼丟掉，帳號留著（設定裡關掉「記住帳號與密碼」）。 */
    fun forgetPasswords() = prefs.edit {
        remove(KEY_LEGACY_PASSWORD)
        for (name in names()) remove(passwordKey(name))
    }

    fun clear() = prefs.edit { clear() }

    private fun names(): List<String> {
        val stored = prefs.getString(KEY_NAMES, null)
        if (stored != null) return stored.split(NAMES_SEPARATOR).filter { it.isNotEmpty() }
        return listOfNotNull(prefs.getString(KEY_NAME, null))
    }

    /** 解不開（金鑰被系統清掉、換過手機）就當作沒記密碼。 */
    private fun passwordOf(name: String): String? {
        val encrypted = prefs.getString(passwordKey(name), null)
            ?: prefs.getString(KEY_LEGACY_PASSWORD, null)?.takeIf { prefs.getString(KEY_NAME, null) == name && prefs.getString(KEY_NAMES, null) == null }
            ?: return null
        return runCatching { box.open(encrypted) }.getOrNull()
    }

    private fun passwordKey(name: String) = "$KEY_PASSWORD_PREFIX$name"

    private fun lastLoginKey(name: String) = "$KEY_LAST_LOGIN_PREFIX$name"

    /** 舊版的單組（`name`＋`password`，還沒有 `names`）：密碼搬到那個帳號自己的鍵、補上 `names`。 */
    private fun SharedPreferences.Editor.migrateLegacy() {
        val legacy = prefs.getString(KEY_LEGACY_PASSWORD, null)
        remove(KEY_LEGACY_PASSWORD)
        val owner = prefs.getString(KEY_NAME, null) ?: return
        if (prefs.getString(KEY_NAMES, null) != null) return
        putString(KEY_NAMES, owner)
        if (legacy != null && !prefs.contains(passwordKey(owner))) putString(passwordKey(owner), legacy)
    }

    private companion object {
        const val KEY_NAME = "name"
        const val KEY_NAMES = "names"
        const val NAMES_SEPARATOR = "\n"
        const val KEY_PASSWORD_PREFIX = "password:"
        const val KEY_LAST_LOGIN_PREFIX = "lastLogin:"
        const val KEY_LEGACY_PASSWORD = "password"
    }
}

/** 密碼怎麼加解密：[seal] 加密成可以存進 SharedPreferences 的字串，[open] 解回來（解不開丟例外）。 */
interface PasswordBox {
    fun seal(text: String): String
    fun open(encoded: String): String
}

/** AndroidKeyStore 裡的 AES-256-GCM 金鑰（第一次用才產生）；存的是 IV 加密文的 Base64。 */
object KeyStoreBox : PasswordBox {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    override fun open(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES))
        return String(cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES), Charsets.UTF_8)
    }

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "quietptt-account"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
}
