package com.twtrubiks.quietptt

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.twtrubiks.quietptt.core.ptt.LoginResult
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    /** [reason] 是上一次斷線或登入失敗的原因，登入頁顯示用；一開始是 null。 */
    data class Offline(val reason: String?) : ConnectionState

    data object Connecting : ConnectionState

    data object LoggingIn : ConnectionState

    data class Online(val session: Session) : ConnectionState
}

/**
 * App 層的連線管理：一次只有一條 [Session]，記錄狀態給畫面看。
 *
 * 省電的規則都在這裡：
 * - 只在前景連線。整個 App 退到背景超過 [BACKGROUND_GRACE_MS] 就斷線（沒有前景服務、wakelock、keep-alive）
 * - 回到前景時，如果是因為退到背景才斷的、而且記得密碼，自動重新登入；其他原因斷的回登入頁
 */
class Connection(
    private val scope: CoroutineScope,
    private val credentials: Credentials,
    private val newTransport: () -> Transport,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Offline(null))
    val state: StateFlow<ConnectionState> get() = _state

    private var session: Session? = null
    private var backgroundTimer: Job? = null
    private var droppedInBackground = false

    /** 連線並登入；成功就把帳密照 [remember] 存起來（false 只留帳號）。 */
    suspend fun login(account: String, password: String, remember: Boolean): LoginResult {
        disconnect(null)
        val session = Session(newTransport(), scope)
        _state.value = ConnectionState.Connecting
        try {
            session.connect()
        } catch (e: Exception) {
            session.close()
            val reason = "連不上 PTT：${e.message ?: e::class.simpleName}"
            _state.value = ConnectionState.Offline(reason)
            return LoginResult.Failed(reason, session.screens.value)
        }
        _state.value = ConnectionState.LoggingIn
        val result = session.login(account, password)
        if (result is LoginResult.Success) {
            this.session = session
            credentials.save(account, if (remember) password else null)
            _state.value = ConnectionState.Online(session)
            watchSession(session)
        } else {
            session.close()
            _state.value = ConnectionState.Offline(
                when (result) {
                    is LoginResult.BadCredentials -> result.message
                    is LoginResult.Failed -> result.message
                    LoginResult.Success -> null
                },
            )
        }
        return result
    }

    /** 連線被對方關掉、網路斷了：回到登入頁。 */
    private fun watchSession(session: Session) {
        scope.launch {
            session.closed.collect { closed ->
                if (closed && this@Connection.session === session) {
                    this@Connection.session = null
                    if (_state.value is ConnectionState.Online) _state.value = ConnectionState.Offline("連線中斷")
                }
            }
        }
    }

    fun disconnect(reason: String?) {
        session?.close()
        session = null
        _state.value = ConnectionState.Offline(reason)
    }

    /** 在 `Application.onCreate` 掛上：整個 App（不是單一 Activity）的前景／背景。 */
    fun watchForeground() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    backgroundTimer?.cancel()
                    backgroundTimer = scope.launch {
                        delay(BACKGROUND_GRACE_MS)
                        if (session != null) {
                            droppedInBackground = true
                            disconnect("退到背景超過 ${BACKGROUND_GRACE_MS / 1000} 秒，已斷線")
                        }
                    }
                }

                override fun onStart(owner: LifecycleOwner) {
                    backgroundTimer?.cancel()
                    if (!droppedInBackground) return
                    droppedInBackground = false
                    val saved = credentials.load()
                    if (saved?.password != null) scope.launch { login(saved.name, saved.password, remember = true) }
                }
            },
        )
    }

    companion object {
        const val BACKGROUND_GRACE_MS = 30_000L
    }
}
