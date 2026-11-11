package com.twtrubiks.quietptt

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.twtrubiks.quietptt.core.ptt.LoginResult
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.terminal.Screen
import com.twtrubiks.quietptt.core.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ConnectionState {
    /**
     * [reason] 是上一次斷線或登入失敗的原因，登入頁顯示用；一開始是 null。
     * [screen] 是登入失敗當時的終端畫面（過載、等太久這種），登入頁可以打開來看。
     * [badPassword]：PTT 說密碼不對或沒這個帳號（登入頁從帳號卡片登入失敗時，換到打密碼的那層）。
     * [tooManyLogins]：重複登入答了「不刪除」、其他連線已經有 3 條，PTT 不給登入（登入頁給「刪除其他連線再登入」，[Connection.retryKickingOthers]）。
     */
    data class Offline(
        val reason: String?,
        val screen: Screen? = null,
        val badPassword: Boolean = false,
        val tooManyLogins: Boolean = false,
    ) : ConnectionState

    data object Connecting : ConnectionState

    data object LoggingIn : ConnectionState

    /**
     * 登入了；[account] 是登入的代號（判斷文章是不是自己的）。
     * [resumed]：這條連線是退到背景斷線之後自動重新登入的——頁面的 stack 留著，回到剛剛的看板或文章；手動登入的從我的最愛開始。
     */
    data class Online(val session: Session, val account: String, val resumed: Boolean = false) : ConnectionState
}

/**
 * App 層的連線管理：一次只有一條 [Session]，記錄狀態給畫面看。
 *
 * 省電的規則都在這裡：
 * - 只在前景連線。整個 App 退到背景超過 [BACKGROUND_GRACE_MS] 就斷線（沒有前景服務、wakelock、keep-alive）
 * - 在背景期間連線先被系統或 PTT 關掉（關螢幕進 Doze 常常幾分鐘內就死），也算「因為退到背景才斷的」；
 *   回到前景之後 [RESUME_GRACE_MS] 內才發現死掉的（TCP 半開，第一次送鍵才知道）一樣算
 * - 回到前景時，如果是因為退到背景才斷的、而且記得密碼，自動重新登入（[ConnectionState.Online.resumed]，頁面留在原地）；
 *   其他原因斷的回登入頁
 * - 打開 App（這個 process 第一次到前景）時，[autoLogin] 開著又記得上次那組的密碼，就直接登入（[loginOnLaunch]，BePTT 的「自動登入主帳號」）
 * - 新信不另外查：PTT 的標題列寫了「你有新信件」（[Session.newMail]）才提醒，同一批信只提醒一次（[mailAlert]）
 *
 * 這裡的函式在 `AppContainer.scope` 裡跑，那個 scope 沒有例外處理器：例外丟出去 App 就閃退，所以一律接住、變成 [ConnectionState.Offline]。
 */
class Connection(
    private val scope: CoroutineScope,
    private val credentials: Credentials,
    /** 設定裡的「自動登入」（打開 App 時用上次登入的帳號登入）。 */
    private val autoLogin: () -> Boolean,
    /** 設定裡的「重複登入時刪除其他連線」（PTT 問的時候答 y；關掉答 n）。 */
    private val kickOtherLogins: () -> Boolean,
    private val newTransport: () -> Transport,
) {
    /**
     * 一次登入要用的東西：「刪除其他連線再登入」照原樣再登一次（自動重登的話頁面一樣留在原地）。
     * 不用 data class：toString 會把密碼印出來。
     */
    private class Attempt(val account: String, val password: String, val remember: Boolean, val resumed: Boolean)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Offline(null))
    val state: StateFlow<ConnectionState> get() = _state

    // 主執行緒（登出、登入）與 scope（連線斷掉的通知）一起讀寫
    @Volatile private var session: Session? = null
    private var backgroundTimer: Job? = null
    // 下面三個由主執行緒（前景／背景的通知）與 scope（Dispatchers.Default，連線斷掉的通知）一起讀寫。
    // 「背景斷的、要自動重登」用 compareAndSet 搶：剛回前景的那一刻連線也剛好斷掉時，兩邊都會叫 resumeIfDropped，只能登入一次
    private val droppedInBackground = AtomicBoolean(false)
    @Volatile private var inBackground = false
    /** 回到前景的時刻（`SystemClock.elapsedRealtime`）；0 是還沒退過背景。 */
    @Volatile private var resumedAt = 0L
    /** 這個 process 到過前景了沒：打開 App 的自動登入只在第一次做（主執行緒讀寫）。 */
    private var launched = false
    // 一次只登入一條：打開 App 的自動登入跑到一半又按了登入，第二個等第一個做完、先把它斷掉再登，不會在 PTT 上留兩條連線
    private val loginLock = Mutex()
    /**
     * 因為其他連線太多被擋下的那次登入（[ConnectionState.Offline.tooManyLogins]），給 [retryKickingOthers] 用；只在記憶體。
     * 下一次登入、登出就清掉。主執行緒（按鈕、登出）與 scope（登入）一起讀寫。
     */
    @Volatile private var blocked: Attempt? = null
    /** 已經跳過「你有新信件」的帳號（[mailAlert]）；只在記憶體，主執行緒讀寫。 */
    private val mailAlerted = mutableSetOf<String>()

    /**
     * 連線並登入；成功就把帳密照 [remember] 存起來（false 只留帳號）。
     * [resumed]：是背景斷線之後的自動重登（[ConnectionState.Online.resumed]）。
     * [kickOthers]：重複登入時刪除其他連線；null 是照設定（[kickOtherLogins]），「刪除其他連線再登入」給 true。
     */
    suspend fun login(
        account: String,
        password: String,
        remember: Boolean,
        resumed: Boolean = false,
        kickOthers: Boolean? = null,
    ): LoginResult = loginLock.withLock {
        loginLocked(Attempt(account, password, remember, resumed), kickOthers ?: kickOtherLogins())
    }

    /**
     * 登入頁的「刪除其他連線再登入」：上一次因為其他連線已經有 3 條被擋下（[ConnectionState.Offline.tooManyLogins]），
     * 這次重複登入答 y、其他照舊（同一組帳密、自動重登的話頁面留在原地）。只用這一次，設定不變。
     */
    fun retryKickingOthers() {
        val attempt = blocked ?: return
        blocked = null
        scope.launch { login(attempt.account, attempt.password, attempt.remember, attempt.resumed, kickOthers = true) }
    }

    /**
     * 「你有新信件」要不要跳提醒：[Session.newMail] 每變一次（不是 null）叫一次。PTT 從沒寫變成寫了才回 true；
     * 同一個帳號跳過之後，要等 PTT 不寫了（信都讀過）再寫，才會再跳。
     * 跨連線記著：背景斷線後自動重登、手動重新登入，還是同一批沒讀的信就不再跳；App 重開（換一個 process）第一次登入會跳。
     */
    fun mailAlert(account: String, newMail: Boolean): Boolean =
        if (newMail) mailAlerted.add(account) else { mailAlerted.remove(account); false }

    private suspend fun loginLocked(attempt: Attempt, kickOthers: Boolean): LoginResult {
        val account = attempt.account
        val password = attempt.password
        val remember = attempt.remember
        val resumed = attempt.resumed
        blocked = null
        disconnect(null)
        val session = Session(newTransport(), scope)
        _state.value = ConnectionState.Connecting
        try {
            session.connect()
        } catch (e: CancellationException) {
            session.close()
            _state.value = ConnectionState.Offline(null)
            throw e
        } catch (e: Exception) {
            session.close()
            val reason = "連不上 PTT：${e.message ?: e::class.simpleName}"
            _state.value = ConnectionState.Offline(reason)
            return LoginResult.Failed(reason, session.screens.value)
        }
        _state.value = ConnectionState.LoggingIn
        // Session.login 自己不丟例外（逾時、斷線都變成 Failed），這裡只防萬一
        val result = try {
            session.login(account, password, kickOthers = kickOthers)
        } catch (e: CancellationException) {
            session.close()
            _state.value = ConnectionState.Offline(null)
            throw e
        } catch (e: Exception) {
            LoginResult.Failed("登入失敗：${e.message ?: e::class.simpleName}", session.screens.value)
        }
        when (result) {
            is LoginResult.Success -> {
                this.session = session
                credentials.save(account, if (remember) password else null)
                _state.value = ConnectionState.Online(session, account, resumed)
                watchSession(session)
            }
            is LoginResult.BadCredentials -> {
                session.close()
                _state.value = ConnectionState.Offline(result.message, badPassword = true)
            }
            is LoginResult.TooManyLogins -> {
                session.close()
                blocked = attempt
                _state.value = ConnectionState.Offline(TOO_MANY_LOGINS, result.screen, tooManyLogins = true)
            }
            is LoginResult.Failed -> {
                session.close()
                _state.value = ConnectionState.Offline(result.message, result.screen)
            }
        }
        return result
    }

    /**
     * 連線被對方關掉、網路斷了：回到登入頁。在背景期間死的、或剛回到前景 [RESUME_GRACE_MS] 內死的，
     * 當成背景斷線：前景時（或馬上）自動重登、頁面留在原地。
     */
    private fun watchSession(session: Session) {
        scope.launch {
            session.closed.first { it }
            if (this@Connection.session === session) {
                this@Connection.session = null
                if (_state.value is ConnectionState.Online) {
                    val justResumed = resumedAt != 0L && SystemClock.elapsedRealtime() - resumedAt < RESUME_GRACE_MS
                    if (inBackground || justResumed) {
                        droppedInBackground.set(true)
                        _state.value = ConnectionState.Offline("在背景時連線中斷")
                        if (!inBackground) resumeIfDropped()
                    } else {
                        _state.value = ConnectionState.Offline("連線中斷", session.screens.value)
                    }
                }
            }
        }
    }

    /** 因為退到背景才斷的、而且記得密碼：自動重新登入（頁面留在原地）。 */
    private fun resumeIfDropped() {
        if (!droppedInBackground.compareAndSet(true, false)) return
        val saved = credentials.load()
        if (saved?.password != null) scope.launch { login(saved.name, saved.password, remember = true, resumed = true) }
    }

    /**
     * 打開 App（這個 process 第一次到前景）：設定的「自動登入」開著、又記得上次登入那組的密碼，就直接登入，不停在登入頁等人按。
     * 一個 process 只做一次：登出之後留在登入頁（要換帳號），不會又自己登進去；失敗也只試這一次，原因照常顯示在登入頁。
     * 帶 [ConnectionState.Online.resumed]：系統在背景把 process 收掉、使用者回來時 Activity 帶著原本的頁面 stack，直接回到剛剛那頁；
     * 從桌面打開的 stack 本來就只有我的最愛。
     */
    private fun loginOnLaunch() {
        if (!autoLogin()) return
        val saved = credentials.load() ?: return
        val password = saved.password ?: return
        scope.launch { login(saved.name, password, remember = true, resumed = true) }
    }

    /**
     * 自己斷線（登入前先斷舊的、退到背景太久）：**先**把 [session] 清掉、狀態改成 Offline，再關連線。
     * 順序反過來的話，關連線的當下 [watchSession] 在另一條執行緒醒來，看到 [session] 還是它、狀態還是 Online，
     * 會當成「連線被對方斷了」；剛回前景 15 秒內還會當成背景斷線、馬上自動重登（以前登出完立刻又登進去，就是這樣）。
     */
    fun disconnect(reason: String?) {
        val old = session
        session = null
        _state.value = ConnectionState.Offline(reason)
        old?.close()
    }

    /**
     * 使用者按了「登出」：斷線，而且這次不自動重登（背景斷線留下的「回前景要重登」也取消），停在登入頁讓人挑帳號或打新的。
     */
    fun logout() {
        blocked = null
        droppedInBackground.set(false)
        backgroundTimer?.cancel()
        disconnect(null)
    }

    /** 在 `Application.onCreate` 掛上：整個 App（不是單一 Activity）的前景／背景。 */
    fun watchForeground() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    inBackground = true
                    backgroundTimer?.cancel()
                    backgroundTimer = scope.launch {
                        delay(BACKGROUND_GRACE_MS)
                        if (session != null) {
                            droppedInBackground.set(true)
                            disconnect("退到背景超過 ${BACKGROUND_GRACE_MS / 60_000} 分鐘，已斷線")
                        }
                    }
                }

                override fun onStart(owner: LifecycleOwner) {
                    backgroundTimer?.cancel()
                    if (inBackground) resumedAt = SystemClock.elapsedRealtime()
                    inBackground = false
                    if (!launched) {
                        launched = true
                        loginOnLaunch()
                    } else {
                        resumeIfDropped()
                    }
                }
            },
        )
    }

    companion object {
        /** 退到背景多久才主動斷線：2 分鐘。螢幕亮著切去別的 App 看一下再回來，多半在這之內，不用重登 */
        const val BACKGROUND_GRACE_MS = 120_000L

        /** 回到前景之後這段時間內連線死掉，當成是在背景時就死了（TCP 半開要送鍵才發現） */
        const val RESUME_GRACE_MS = 15_000L

        const val TOO_MANY_LOGINS = "這個帳號在別的地方已經有 3 條連線，不刪除其他連線登不進去"
    }
}
