package com.twtrubiks.quietptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 記好幾組帳號（[Credentials]）的存法，JVM 上跑：SharedPreferences 用記憶體裡的假的、加密換成看得出來的假盒子
 * （真的 AndroidKeyStore 在模擬器上測：`DeviceTest`）。
 */
class CredentialsTest {
    private val prefs = MemoryPrefs()
    private var now = 1_000L
    private val credentials = Credentials(prefs, FakeBox) { now }

    /** 舊版只有一組：`name` 與 `password` 兩個鍵。 */
    private fun oldVersionSaved(name: String, password: String) {
        prefs.edit().putString("name", name).putString("password", FakeBox.seal(password)).commit()
    }

    @Test
    fun passwordsAreStoredSealed() {
        credentials.save("someone", "secret")
        assertEquals(Account("someone", "secret"), credentials.load())
        assertFalse(prefs.all.values.any { it == "secret" })
    }

    @Test
    fun theOldSingleAccountKeepsItsPasswordWhenASecondAccountIsSaved() {
        oldVersionSaved("first", "one")
        assertEquals(listOf(Account("first", "one")), credentials.loadAll())
        // 更新之後第一次就用另一個帳號登入：第一組的密碼不能不見
        credentials.save("second", "two")
        assertEquals(listOf(Account("second", "two"), Account("first", "one")), credentials.loadAll())
        assertNull(prefs.getString("password", null))
    }

    @Test
    fun forgettingAnotherAccountKeepsTheOldSingleAccountPassword() {
        oldVersionSaved("first", "one")
        credentials.remove("nobody")
        assertEquals(listOf(Account("first", "one")), credentials.loadAll())
    }

    @Test
    fun savingTheOldSingleAccountAgainWithoutPasswordDropsIt() {
        oldVersionSaved("first", "one")
        credentials.save("first", null)
        assertEquals(listOf(Account("first", null)), credentials.loadAll())
    }

    @Test
    fun theSameAccountInAnotherCaseReplacesItInPlace() {
        credentials.save("abc", "one")
        credentials.save("other", "two")
        credentials.save("ABC", "three")
        assertEquals(listOf(Account("ABC", "three"), Account("other", "two")), credentials.loadAll())
        assertNull(prefs.getString("password:abc", null))
        credentials.save("other", "two")
        // 順序照第一次記住的，大小寫換過的那組留在原來的位置
        assertEquals(listOf(Account("other", "two"), Account("ABC", "three")), credentials.loadAll())
    }

    @Test
    fun countsAccountsIncludingTheOldSingleOneAndCaseChanges() {
        assertEquals(0, credentials.count())
        oldVersionSaved("first", "one")
        assertEquals(1, credentials.count())
        credentials.save("FIRST", null)
        assertEquals(1, credentials.count())
        credentials.save("second", "two")
        assertEquals(2, credentials.count())
        credentials.remove("second")
        assertEquals(1, credentials.count())
    }

    @Test
    fun removingTheLastLoginFallsBackToTheNextOne() {
        credentials.save("first", "one")
        credentials.save("second", null)
        credentials.remove("second")
        assertEquals(Account("first", "one"), credentials.load())
        credentials.remove("first")
        assertNull(credentials.load())
        assertEquals(emptyList<Account>(), credentials.loadAll())
    }

    @Test
    fun theLastLoginTimeIsKeptPerAccountAndOrdersTheOthers() {
        credentials.save("a", "1")
        now = 2_000
        credentials.save("b", "2")
        now = 3_000
        credentials.save("c", null)
        now = 4_000
        credentials.save("a", "1")
        // 最後一次登入的排第一，其他照上次登入時間、新的在前
        assertEquals(listOf("a", "c", "b"), credentials.loadAll().map { it.name })
        assertEquals(4_000L, credentials.lastLogin("a"))
        assertEquals(2_000L, credentials.lastLogin("b"))
        // 關掉記住密碼只丟密碼，時間留著；忘掉一組連時間一起丟
        credentials.forgetPasswords()
        assertEquals(3_000L, credentials.lastLogin("c"))
        credentials.remove("c")
        assertNull(credentials.lastLogin("c"))
    }

    @Test
    fun theOldVersionHasNoLastLoginTime() {
        oldVersionSaved("first", "one")
        assertNull(credentials.lastLogin("first"))
        credentials.save("ABC", "two")
        credentials.save("abc", "three")
        // 換了大小寫：舊寫法的時間不留
        assertNull(credentials.lastLogin("ABC"))
        assertEquals(1_000L, credentials.lastLogin("abc"))
        assertEquals(listOf("abc", "first"), credentials.loadAll().map { it.name })
    }

    private object FakeBox : PasswordBox {
        override fun seal(text: String) = "sealed:" + text.reversed()
        override fun open(encoded: String) = encoded.removePrefix("sealed:").reversed()
    }
}
