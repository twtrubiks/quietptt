package com.twtrubiks.quietptt

import android.app.Application
import android.content.Context
import com.twtrubiks.quietptt.core.transport.SshTransport
import com.twtrubiks.quietptt.core.transport.Transport
import com.twtrubiks.quietptt.core.transport.WebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** 啟動時什麼都不做：不碰網路、不初始化任何 SDK。只掛上「退到背景就斷線」的觀察者。 */
class QuietPttApplication : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.connection.watchForeground()
    }
}

val Context.container: AppContainer get() = (applicationContext as QuietPttApplication).container

/** 整個 App 共用的東西：設定、帳密、連線。 */
class AppContainer(context: Context) {
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 深色模式。 */
    val theme = EnumSetting(settings, "theme", ThemeMode.entries, ThemeMode.SYSTEM)

    /** 連線方式，預設 SSH；切換後下次連線才生效。 */
    val transport = EnumSetting(settings, "transport", TransportKind.entries, TransportKind.SSH)

    /** 記住帳號與密碼（預設開）。關掉時只留帳號、不留密碼。 */
    val rememberPassword = BoolSetting(settings, "remember_password", true)

    /** 帳號與加密過的密碼，另一份 SharedPreferences（不備份、不隨換機傳輸）。 */
    val credentials = Credentials(context.getSharedPreferences("account", Context.MODE_PRIVATE))

    /** WebSocket 連線用；`pingInterval` 保持預設 0，不送 ping。只在選 WebSocket 時才建。 */
    val http: OkHttpClient by lazy { OkHttpClient() }

    /** 收資料、解析畫面都在 Default，不在主執行緒。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val connection = Connection(scope, credentials) { newTransport() }

    fun newTransport(): Transport = when (transport.value) {
        TransportKind.SSH -> SshTransport()
        TransportKind.WEBSOCKET -> WebSocketTransport(http)
    }
}
