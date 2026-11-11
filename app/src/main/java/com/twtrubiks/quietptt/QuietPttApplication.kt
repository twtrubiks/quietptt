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

/**
 * 啟動時什麼都不做：不碰網路、不初始化任何 SDK。只掛上「退到背景就斷線」的觀察者。
 * （「自動登入」是畫面到了前景才由那個觀察者發起，不在這裡。）
 */
class QuietPttApplication : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.connection.watchForeground()
    }
}

val Context.container: AppContainer get() = (applicationContext as QuietPttApplication).container

/** 整個 App 共用的東西：設定、帳密、搜尋紀錄、連線。 */
class AppContainer(context: Context) {
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 深色模式。 */
    val theme = EnumSetting(settings, "theme", ThemeMode.entries, ThemeMode.SYSTEM)

    /** 文章與信的字級（文章頁「⋮」或設定裡改），預設標準（跟著系統的字體大小）。 */
    val articleTextSize = EnumSetting(settings, "article_text_size", ArticleTextSize.entries, ArticleTextSize.NORMAL)

    /** 連線方式，預設 SSH；切換後下次連線才生效。 */
    val transport = EnumSetting(settings, "transport", TransportKind.entries, TransportKind.SSH)

    /** 滑動退出（回上一頁）的方向，預設由左至右（跟 BePTT 一樣）。 */
    val swipeBack = EnumSetting(settings, "swipe_back", SwipeBackMode.entries, SwipeBackMode.LEFT_TO_RIGHT)

    /** 記住帳號與密碼（預設開）。關掉時只留帳號、不留密碼。 */
    val rememberPassword = BoolSetting(settings, "remember_password", true)

    /** 自動登入（預設開，跟 BePTT 一樣）：打開 App 時用上次登入的那組帳密直接登入。要記得密碼才有用。 */
    val autoLogin = BoolSetting(settings, "auto_login", true)

    /**
     * 重複登入時刪除其他連線（預設開）：PTT 問「您想刪除其他重複登入的連線嗎」時答 y。關掉答 n，別的地方的連線不會被踢，
     * 但其他連線已經有 3 條時登不進去（登入頁給「刪除其他連線再登入」）。
     */
    val kickOtherLogins = BoolSetting(settings, "kick_other_logins", true)

    /** 帳號與加密過的密碼，另一份 SharedPreferences（不備份、不隨換機傳輸）。 */
    val credentials = Credentials(context.getSharedPreferences("account", Context.MODE_PRIVATE))

    /** 看板裡搜尋的紀錄（標題、作者、推文數各一份），另一份 SharedPreferences（不備份、不隨換機傳輸）。第一次開搜尋或設定才讀。 */
    val searchHistory by lazy { SearchHistory(context.getSharedPreferences("search_history", Context.MODE_PRIVATE)) }

    /** 看板上次列過的文章，只在記憶體（再進同一個板先列這份，見 [BoardScreen]）。第一次進板才建。 */
    val boardLists by lazy { BoardLists() }

    /** WebSocket 連線、文章裡「開啟圖片」用；`pingInterval` 保持預設 0，不送 ping。選 WebSocket 或第一次開圖時才建。 */
    val http: OkHttpClient by lazy { OkHttpClient() }

    /** 收資料、解析畫面都在 Default，不在主執行緒。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val connection = Connection(
        scope,
        credentials,
        autoLogin = { autoLogin.value },
        kickOtherLogins = { kickOtherLogins.value },
    ) { newTransport() }

    fun newTransport(): Transport = when (transport.value) {
        TransportKind.SSH -> SshTransport()
        TransportKind.WEBSOCKET -> WebSocketTransport(http)
    }
}
