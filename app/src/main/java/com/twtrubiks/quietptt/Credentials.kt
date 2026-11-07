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
 * 記住的帳號與密碼。密碼用 AndroidKeyStore 裡的 AES-256-GCM 金鑰加密後存進 SharedPreferences `account`，
 * 金鑰離不開這台手機，備份、換機都解不開（manifest 也不讓這份 prefs 被備份）。
 * 帳號本身是公開的，明碼存。
 */
class Credentials(private val prefs: SharedPreferences) {
    fun load(): Account? {
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val encrypted = prefs.getString(KEY_PASSWORD, null)
        // 解不開（金鑰被系統清掉、換過手機）就當作沒記密碼
        return Account(name, encrypted?.let { runCatching { decrypt(it) }.getOrNull() })
    }

    /** [password] 是 null 表示只記帳號。 */
    fun save(name: String, password: String?) {
        prefs.edit {
            putString(KEY_NAME, name)
            if (password == null) remove(KEY_PASSWORD) else putString(KEY_PASSWORD, encrypt(password))
        }
    }

    fun clear() = prefs.edit { clear() }

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

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES))
        return String(cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "quietptt-account"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_NAME = "name"
        const val KEY_PASSWORD = "password"
    }
}
