package com.twtrubiks.quietptt.core.ptt

import com.twtrubiks.quietptt.core.terminal.Big5Uao
import com.twtrubiks.quietptt.core.terminal.Keys
import com.twtrubiks.quietptt.core.terminal.Screen
import com.twtrubiks.quietptt.core.terminal.Terminal
import com.twtrubiks.quietptt.core.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** 登入的結果。 */
sealed interface LoginResult {
    data object Success : LoginResult

    /** 密碼不對或無此帳號。 */
    data class BadCredentials(val message: String) : LoginResult

    /** 其他：系統過載、登入太頻繁、等不到主選單。[screen] 給終端畫面顯示。 */
    data class Failed(val message: String, val screen: Screen) : LoginResult
}

/**
 * 一條連線上的操作流程：送按鍵、等預期的畫面出現。
 *
 * 一個 [Session] 對應一條 [Transport]，連線關了就丟掉、重開一個。
 * 收資料的 coroutine 跑在 [scope]（App 用 `Dispatchers.Default`，不在主執行緒解析），
 * 每收到一段就更新 [screens]，[page] 是最新畫面辨識的結果。
 */
class Session(
    private val transport: Transport,
    private val scope: CoroutineScope,
) {
    private val terminal = Terminal()
    private val _screens = MutableStateFlow(terminal.snapshot())
    private var reader: Job? = null

    /** 最新的畫面；畫面每變一次就發一次。 */
    val screens: StateFlow<Screen> get() = _screens

    val page: Page get() = ScreenParser.parse(_screens.value)

    /** 連線已經結束（對方關掉、網路斷了、[close]）。 */
    val closed = MutableStateFlow(false)

    suspend fun connect() {
        transport.connect()
        reader = scope.launch {
            try {
                while (true) {
                    val bytes = transport.read() ?: break
                    terminal.feed(bytes)
                    _screens.value = terminal.snapshot()
                }
            } finally {
                closed.value = true
            }
        }
    }

    /** 送一段文字（會轉成 Big5-UAO）或按鍵（[Keys]）。 */
    suspend fun send(text: String) = transport.write(Big5Uao.encode(text))

    /**
     * 等到畫面符合 [predicate]，而且之後 [quietMs] 內沒有再變（畫面是一段一段送來的，先等它畫完）。
     * 超過 [timeoutMs] 丟 `TimeoutCancellationException`。
     */
    suspend fun awaitPage(timeoutMs: Long = DEFAULT_TIMEOUT_MS, quietMs: Long = QUIET_MS, predicate: (Page) -> Boolean): Page =
        withTimeout(timeoutMs) { awaitPageLoop(quietMs, predicate) }

    private suspend fun awaitPageLoop(quietMs: Long, predicate: (Page) -> Boolean): Page {
        while (true) {
            val current = _screens.value
            val page = ScreenParser.parse(current)
            if (predicate(page)) {
                val changed = withTimeoutOrNull(quietMs) { _screens.first { it !== current } }
                if (changed == null) return page
            } else {
                _screens.first { it !== current }
            }
        }
    }

    /** 等畫面安靜下來（[quietMs] 內沒變），回傳當時的頁面。不管是哪一頁。 */
    suspend fun awaitQuiet(timeoutMs: Long = DEFAULT_TIMEOUT_MS, quietMs: Long = QUIET_MS): Page =
        awaitPage(timeoutMs, quietMs) { true }

    /**
     * 登入：輸入代號、密碼，然後把中間會跳出來的問題都答掉（重複登入不刪、錯誤嘗試紀錄刪掉、任意鍵），直到主選單。
     * 認不出的畫面會等它變，超過 [timeoutMs] 回 [LoginResult.Failed] 並附上畫面。
     */
    suspend fun login(account: String, password: String, timeoutMs: Long = LOGIN_TIMEOUT_MS): LoginResult =
        withTimeoutOrNull(timeoutMs) { loginFlow(account, password) } ?: LoginResult.Failed("登入等太久", _screens.value)

    private suspend fun loginFlow(account: String, password: String): LoginResult {
        awaitPage { it is Page.Login }
        send(account + Keys.ENTER)
        awaitPage { it is Page.PasswordPrompt || it is Page.Login }
        if (page is Page.Login) return LoginResult.Failed("代號沒有被接受", _screens.value)
        send(password + Keys.ENTER)
        while (true) {
            when (val p = awaitQuiet()) {
                is Page.MainMenu -> return LoginResult.Success
                is Page.Login -> return LoginResult.BadCredentials(p.error ?: "密碼不對或無此帳號")
                is Page.DuplicateLoginPrompt -> send("n" + Keys.ENTER)
                is Page.DeleteFailedLoginsPrompt -> send("y" + Keys.ENTER)
                is Page.PressAnyKey -> send(Keys.ENTER)
                is Page.PasswordPrompt -> Unit // 還在等伺服器
                else -> send(Keys.ENTER) // 歡迎畫面、公告、動態看板：按一下看會不會往下走
            }
        }
    }

    /** 從主選單進我的最愛（`F` 然後 Enter），等列表畫出來。 */
    suspend fun openFavorites(): Page.Favorites {
        send("f" + Keys.ENTER)
        return awaitPage { it is Page.Favorites } as Page.Favorites
    }

    /** 從任何列表畫面進看板（`s` 搜尋板名），等文章列表畫出來。 */
    suspend fun openBoard(name: String): Page.ArticleList {
        send("s$name" + Keys.ENTER)
        val page = awaitQuiet()
        // 進板前可能先跳板規／公告（請按任意鍵繼續）
        if (page is Page.PressAnyKey) send(Keys.ENTER)
        return awaitPage { it is Page.ArticleList } as Page.ArticleList
    }

    /**
     * 讀整篇文章：在文章列表上游標所在的那篇按 Enter，一頁一頁往下翻（PAGE_DOWN），
     * 照每頁的進度（第幾～幾行）拼成完整內文，翻到最後一頁為止，最後按左鍵回列表。
     * TODO：還沒實作，等有真的 fixture 再寫（拼接要處理相鄰兩頁重疊的那一行）。
     */
    suspend fun readArticle(board: String): Article {
        TODO("等 tools/capture 抓到文章頁的 fixture 再實作")
    }

    fun close() {
        reader?.cancel()
        transport.close()
        closed.value = true
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val LOGIN_TIMEOUT_MS = 45_000L

        /** 畫面幾毫秒沒變就當它畫完了。 */
        const val QUIET_MS = 150L
    }
}
