package com.twtrubiks.quietptt.core.ptt

import com.twtrubiks.quietptt.core.terminal.Big5Uao
import com.twtrubiks.quietptt.core.terminal.Keys
import com.twtrubiks.quietptt.core.terminal.Screen
import com.twtrubiks.quietptt.core.terminal.Terminal
import com.twtrubiks.quietptt.core.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.math.abs

/** 登入的結果。 */
sealed interface LoginResult {
    data object Success : LoginResult

    /** 密碼不對或無此帳號。 */
    data class BadCredentials(val message: String) : LoginResult

    /**
     * 重複登入答了「不刪除」，PTT 把連線關掉：這個帳號在別的地方已經有 3 條連線（pttbbs `multi_user_check` 答 n 時
     * 其他連線還有 3 條就 `abort_bbs`，不說原因）。要登入得答「刪除」。[screen] 是斷線前的畫面。
     */
    data class TooManyLogins(val screen: Screen) : LoginResult

    /** 其他：系統過載、登入太頻繁、等不到主選單、連線被關掉。[screen] 給終端畫面顯示。 */
    data class Failed(val message: String, val screen: Screen) : LoginResult
}

/** 連線已經結束（對方關掉、網路斷了、[Session.close]），不會再有畫面。 */
class SessionClosedException(cause: Throwable? = null) : IOException("連線已經中斷", cause)

/** 等不到預期的畫面：逾時、畫面認不出來、按了沒反應。[screen] 是當時的畫面，給終端畫面或錯誤訊息用。 */
class UnexpectedScreenException(message: String, val screen: Screen) : Exception(message)

/**
 * 發文已經答了「s」存檔之後才出錯（認不出後面的畫面、連線斷了…）：文章**可能已經發出去了**。
 * App 不能直接給「重試」（會發兩篇），要請使用者回看板確認。
 */
class PostUnconfirmedException(cause: Throwable) :
    Exception("文章可能已經發出去了，請回看板確認（${cause.message ?: cause::class.simpleName}）", cause)

/**
 * 寄信、回信已經答了「s」存檔，還沒看到「已順利寄出」就出錯（認不出後面的畫面、連線斷了…）：信**可能已經寄出去了**。
 * App 不能直接給「重試」（會寄兩封）。
 */
class MailUnconfirmedException(cause: Throwable) :
    Exception("信可能已經寄出去了，不要直接再寄一次（${cause.message ?: cause::class.simpleName}）", cause)

/**
 * 一條連線上的操作流程：送按鍵、等預期的畫面出現。
 *
 * 一個 [Session] 對應一條 [Transport]，連線關了就丟掉、重開一個。
 * 收資料、解析畫面、等畫面都在 [dispatcher]（預設 `Dispatchers.Default`），從主執行緒呼叫也不會在主執行緒解析。
 * 每收到一段就更新 [screens]，[page] 是最新畫面辨識的結果。
 *
 * 等畫面的規則：送出按鍵之後，先等畫面**變了**（不拿按之前的畫面當答案），再等它 [QUIET_MS] 內不再變（畫面是一段一段送來的）。
 * 進我的最愛、進看板、開文章這些「走到某一頁」的操作一次只跑一個（[navigation]），而且從現在的畫面出發，
 * 已經在那一頁就不按鍵，所以重進同一頁、轉螢幕都不會多按。
 */
class Session(
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val terminal = Terminal()
    private val _screens = MutableStateFlow(terminal.snapshot())
    private val _closed = MutableStateFlow(false)
    private val _newMail = MutableStateFlow<Boolean?>(null)
    private val navigation = Mutex()
    private var reader: Job? = null

    /** 上一次等到的畫面（已經確定畫完了）；畫面還是它的話，[awaitPage] 不用再等一次安靜。 */
    @Volatile private var settled: Screen? = null

    /** 終端現在停著的搜尋結果是哪一次搜的（只有這裡會按鍵搜尋）；在整個列表上就是 null。 */
    private var currentSearch: ArticleSearch? = null

    /** 最新的畫面；畫面每變一次就發一次（每次都是新的物件，`!==` 就是變了）。 */
    val screens: StateFlow<Screen> get() = _screens

    val page: Page get() = ScreenParser.parse(_screens.value)

    /** 連線已經結束（對方關掉、網路斷了、[close]）。 */
    val closed: StateFlow<Boolean> get() = _closed

    /**
     * PTT 說有沒有新信：標題列中間那格的「你有新信件」（[ScreenParser.newMail]）。連上之後還沒等到有標題列的畫面是 null。
     * 不另外按鍵：每次等到畫面（[awaitPage]）順便看第 0 列，文章內文、提示這些沒有標題列的畫面不動它。
     * 信寄來時 PTT 不會主動送畫面（`sendalert` 只打開一個旗標），要等下一次重畫標題列，所以 App 也是換頁時才知道。
     * PTT 登入時看最新 5 封有沒有沒讀的（`load_mailalert`），離開信箱時再看一次（`setmailalert`）：都讀過了才不寫。
     */
    val newMail: StateFlow<Boolean?> get() = _newMail

    suspend fun connect() {
        transport.connect()
        reader = scope.launch(dispatcher) {
            try {
                while (true) {
                    val bytes = transport.read() ?: break
                    terminal.feed(bytes)
                    _screens.value = terminal.snapshot()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 網路斷了：跟對方關掉一樣處理。不往外丟，App 的 scope 沒有例外處理器，丟出去會閃退
            } finally {
                _closed.value = true
                // 換一個新的快照，讓正在等畫面的人醒來、發現連線關了
                _screens.value = terminal.snapshot()
            }
        }
    }

    /** 送一段文字（會轉成 Big5-UAO）或按鍵（[Keys]）。連線已經關了丟 [SessionClosedException]。 */
    suspend fun send(text: String) {
        if (_closed.value) throw SessionClosedException()
        try {
            transport.write(Big5Uao.encode(text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SessionClosedException(e)
        }
    }

    /**
     * 等到畫面符合 [predicate]，而且之後 [quietMs] 內沒有再變。
     * 有給 [after] 的話，先等畫面從 [after] 變成別的再開始看（送出按鍵之後用，免得拿按之前的畫面當答案）。
     * 超過 [timeoutMs] 丟 [UnexpectedScreenException]；連線關了丟 [SessionClosedException]。
     */
    suspend fun awaitPage(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        quietMs: Long = QUIET_MS,
        after: Screen? = null,
        predicate: (Page) -> Boolean,
    ): Page = awaitPageWith(timeoutMs, { quietMs }, after, predicate)

    /**
     * 同 [awaitPage]，安靜要等多久看認出來的是哪一頁（[quietFor] 回 0 就不等）。
     * 沒給 [after]、畫面也還是上一次等到的那個（[settled]）：它已經畫完了，符合就直接回傳，不再等一次安靜。
     */
    private suspend fun awaitPageWith(
        timeoutMs: Long,
        quietFor: (Page) -> Long,
        after: Screen?,
        predicate: (Page) -> Boolean,
    ): Page = withContext(dispatcher) {
        withTimeoutOrNull(timeoutMs) {
            if (after != null) {
                nextScreen(after)
            } else {
                val now = _screens.value
                if (now === settled && !_closed.value) ScreenParser.parse(now).let { if (predicate(it)) return@withTimeoutOrNull it }
            }
            awaitPageLoop(quietFor, predicate)
        } ?: throw UnexpectedScreenException("等了 ${timeoutMs / 1000} 秒還沒等到預期的畫面", _screens.value)
    }

    /** 等畫面安靜下來（[quietMs] 內沒變），回傳當時的頁面。不管是哪一頁。 */
    suspend fun awaitQuiet(timeoutMs: Long = DEFAULT_TIMEOUT_MS, quietMs: Long = QUIET_MS, after: Screen? = null): Page =
        awaitPage(timeoutMs, quietMs, after) { true }

    private suspend fun awaitPageLoop(quietFor: (Page) -> Long, predicate: (Page) -> Boolean): Page {
        while (true) {
            val current = _screens.value
            if (_closed.value) throw SessionClosedException()
            val page = ScreenParser.parse(current)
            if (predicate(page)) {
                val quietMs = quietFor(page)
                if (quietMs <= 0 || withTimeoutOrNull(quietMs) { nextScreen(current) } == null) {
                    settled = current
                    ScreenParser.newMail(page)?.let { _newMail.value = it }
                    return page
                }
            } else {
                nextScreen(current)
            }
        }
    }

    /** 等畫面從 [from] 變成別的（連線關掉時也會變一次）。 */
    private suspend fun nextScreen(from: Screen): Screen = _screens.first { it !== from }

    /**
     * 登入：輸入代號、密碼，然後把中間會跳出來的問題都答掉（重複登入照 [kickOthers]、錯誤嘗試紀錄刪掉、任意鍵），直到主選單。
     * 重複登入（「您想刪除其他重複登入的連線嗎」）：[kickOthers] 答 y、不然答 n。答 n 之後 PTT 把連線關掉是 [LoginResult.TooManyLogins]。
     * 不會丟例外：等太久、連線被關掉、畫面不對都回 [LoginResult.Failed] 並附上畫面。
     */
    suspend fun login(
        account: String,
        password: String,
        kickOthers: Boolean = true,
        timeoutMs: Long = LOGIN_TIMEOUT_MS,
    ): LoginResult =
        withContext(dispatcher) {
            try {
                withTimeoutOrNull(timeoutMs) { loginFlow(account, password, kickOthers) }
                    ?: LoginResult.Failed("登入等太久", _screens.value)
            } catch (e: SessionClosedException) {
                LoginResult.Failed("PTT 把連線關掉了", _screens.value)
            } catch (e: UnexpectedScreenException) {
                LoginResult.Failed(e.message ?: "登入的畫面跟預期的不一樣", e.screen)
            }
        }

    private suspend fun loginFlow(account: String, password: String, kickOthers: Boolean): LoginResult {
        val accountPrompt = awaitPage { it is Page.Login }
        send(account + Keys.ENTER)
        // 代號送出之後，同一個畫面在代號下面多出「請輸入您的密碼」；在那之前最下面的提示還是代號那列（多了回顯）
        val passwordPrompt = awaitPage(after = accountPrompt.screen) { it is Page.PasswordPrompt }
        send(password + Keys.ENTER)
        var answered = passwordPrompt.screen
        var duplicateAnswered = false
        try {
            while (true) {
                val page = awaitQuiet(after = answered)
                answered = page.screen
                when (page) {
                    is Page.MainMenu -> return LoginResult.Success
                    is Page.Login -> return LoginResult.BadCredentials(page.error ?: BAD_CREDENTIALS)
                    is Page.PasswordPrompt -> Unit // 伺服器還在處理密碼，等下一次畫面變化
                    // 照 [kickOthers] 答 y／n（pttbbs `multi_user_check`）。只答一次：答完先睡 0～5 秒才往下走，
                    // 這段時間畫面只多了回顯的 y／n、還是這個提示，再答一次的「y Enter」會被下一個畫面吃掉
                    is Page.DuplicateLoginPrompt -> if (!duplicateAnswered) {
                        send((if (kickOthers) "y" else "n") + Keys.ENTER)
                        duplicateAnswered = true
                    }
                    is Page.DeleteFailedLoginsPrompt -> send("y" + Keys.ENTER)
                    is Page.PressAnyKey -> send(Keys.ENTER)
                    else -> {
                        // 認不出來的畫面（歡迎畫面、公告、「正在更新…」）：多等一下，還停在這裡才按 Enter 看會不會往下走。
                        // 太早按的話這個 Enter 會被下一個畫面吃掉，例如主選單，就會跑進某個選單裡
                        if (withTimeoutOrNull(NUDGE_MS) { nextScreen(page.screen) } == null) send(Keys.ENTER)
                    }
                }
            }
        } catch (e: SessionClosedException) {
            // 答了 n 之後被關掉：其他連線已經有 3 條，PTT 不給登入（不說原因、直接斷線）
            if (duplicateAnswered && !kickOthers) return LoginResult.TooManyLogins(_screens.value)
            throw e
        }
    }

    /** 走到我的最愛：主選單按 `f` Enter；在看板或文章裡就按左鍵一層一層退出來。已經在我的最愛就什麼都不按。 */
    suspend fun showFavorites(): Page.Favorites = navigate { goToFavorites() }

    /**
     * 整份我的最愛（App 的首頁用這個）：走到我的最愛，超過一頁的話一頁一頁翻完，照序號回傳全部的看板（含目錄）。
     * 只有一頁（最後一列是空的，而且是第 1 頁）就不按鍵。不然先按 Home 回到第 1 頁（從看板退回來時游標停在剛剛那個板，可能在後面幾頁），
     * PgDn 翻到最後一頁，再按 Home 回第 1 頁，終端畫面也從第一個開始。
     * 翻頁照 pttbbs `board_cmd_pgdn`／`psb.c`：游標往下一頁；最後一頁再按，游標先移到最後一筆（同一頁），在最後一筆上再按才繞回第 1 頁。
     */
    suspend fun allFavorites(): List<Board> = navigate { readFavorites(goToFavorites()) }

    /** 從我的最愛的某一頁開始把整份讀完（[allFavorites] 的本體，刪除、移動之後也用它重讀）。 */
    private suspend fun readFavorites(start: Page.Favorites): List<Board> {
        var page = start
        // 空的、或第一列讀不出序號（按了 c 切成顯示文章數）：分不出第幾頁，只給這一頁（Home 在第 1 頁按畫面不會變，會等到逾時）
        val first = page.firstNumber() ?: return page.boards
        if (first == 1 && !page.full()) return page.boards
        if (first != 1) page = press(Keys.HOME, page) { it is Page.Favorites } as Page.Favorites
        val (boards, end) = readBoardPages(page, MAX_FAVORITE_PAGES)
        if ((end.firstNumber() ?: 1) != 1) press(Keys.HOME, end) { it is Page.Favorites }
        return boards
    }

    /**
     * 把 [board]（看板或目錄，用它在我的最愛裡的序號）從我的最愛移除（pttbbs `fav_cmd_toggle_or_del`）：
     * 走到我的最愛、打序號 Enter 把游標移過去、按 `d`，最下面那列問「你確定刪除嗎? [N/y]」（`vans`），答 `y` Enter。
     * PTT 重畫列表、游標留在同一個位置（最後一筆被刪就往上一格），沒有成功訊息。回傳刪完重讀的整份我的最愛。
     * 列表上還看得到同名的那筆就丟例外。PTT 要等登出（或按 `w`）才寫進檔案，App 不用管。
     */
    suspend fun removeFavorite(board: Board): List<Board> = navigate {
        val list = favoriteCursorAt(board)
        val confirm = press("d", list) { it is Page.ConfirmPrompt && it.text.startsWith("你確定刪除嗎") }
        val after = press("y" + Keys.ENTER, confirm) { it is Page.Favorites } as Page.Favorites
        if (after.boards.any { it.index == board.index && it.name == board.name }) {
            throw UnexpectedScreenException("PTT 沒有把 ${board.name} 從我的最愛移除", after.screen)
        }
        readFavorites(after)
    }

    /**
     * 把我的最愛裡的 [board] 移到第 [to] 個位置（1 起，序號算法跟列表一樣：分隔線也佔一個；pttbbs `fav_cmd_move`／`imovefav`）：
     * 游標移過去、按大寫 `M`（小寫 m 是刪除！），第 22 列問「請輸入新次序:」，打數字 Enter。
     * 超出範圍 PTT 說「輸入範圍有誤!」（任意鍵），按掉、丟例外。回傳移完重讀的整份我的最愛。
     */
    suspend fun moveFavorite(board: Board, to: Int): List<Board> = navigate {
        require(to >= 1) { "新位置要從 1 起" }
        val list = favoriteCursorAt(board)
        if (board.index == to) return@navigate readFavorites(list)
        val prompt = press("M", list) { it is Page.MoveFavoritePrompt }
        val after = press(to.toString() + Keys.ENTER, prompt) { it is Page.Favorites || it is Page.PressAnyKey }
        if (after is Page.PressAnyKey) {
            val message = anyKeyMessage(after.screen)
            press(Keys.ENTER, after) { it is Page.Favorites }
            throw UnexpectedScreenException(message.ifEmpty { "PTT 不讓移動" }, after.screen)
        }
        readFavorites(after as Page.Favorites)
    }

    /**
     * 把我的最愛裡的看板 [board] 整個板設成已讀（[read]）或未讀（pttbbs `board_cmd_mark_read`）：游標移過去，按小寫 `v` 已讀、大寫 `V` 未讀。
     * PTT 把這個板的閱讀紀錄整份換掉（`brc_toggle_all_read`：已讀是現在以前的文章都算讀過、未讀是全部算沒讀過，文章列表的「+」跟著變），
     * 重畫列表、板名前的「ˇ」拿掉或加上，沒有確認、沒有訊息。PTT 上已經是那個樣子就不按鍵（畫面不會變，會等到逾時）。
     * 回傳重讀的整份我的最愛。
     */
    suspend fun markFavoriteRead(board: Board, read: Boolean): List<Board> = navigate {
        require(!board.isFolder) { "目錄沒有已讀、未讀" }
        val index = board.index
        val list = favoriteCursorAt(board)
        if (list.boards.any { it.index == index && it.unread != read }) return@navigate readFavorites(list)
        val after = press(if (read) "v" else "V", list) { page ->
            page is Page.Favorites && page.boards.any { it.index == index && it.unread != read }
        } as Page.Favorites
        readFavorites(after)
    }

    /**
     * 走到我的最愛、把游標停在 [board] 的序號上（打序號 Enter，pttbbs `psb_cmd_num`）；已經在上面就不按鍵。
     * 停好之後確認那一列還是 [board]：App 列的可能是舊的（還沒更新完、在終端或別的地方改過），序號對到的是別的板，
     * 照按就刪錯、搬錯，而且事後看不出來。對不上就丟例外，什麼都不按。
     */
    private suspend fun favoriteCursorAt(board: Board): Page.Favorites {
        val index = board.index
        val start = goToFavorites()
        val list = if (start.cursor == index) start else press(index.toString() + Keys.ENTER, start) { it is Page.Favorites && it.cursor == index } as Page.Favorites
        val here = list.boards.firstOrNull { it.index == index }
        if (here == null || here.name != board.name || here.isFolder != board.isFolder) {
            throw UnexpectedScreenException(
                "我的最愛跟 App 列的不一樣了（第 $index 個現在是 ${here?.name ?: "分隔線或空的"}，不是 ${board.name}），先重新整理再試",
                list.screen,
            )
        }
        return list
    }

    /**
     * 分組討論區的根目錄（主選單 `C`，【分類看板】）：每個分類一項，超過一頁翻完（pttbbs PgDn 到最後一筆再按會繞回第一筆）。
     * 線上 14 項、一頁放得下，最後一項是「--> 即時熱門看板 <--」（[ClassEntry.isHot]）（真機）。
     */
    suspend fun showClasses(): List<ClassEntry> = navigate {
        var page = goToClassRoot()
        if ((page.entries.firstOrNull()?.index ?: 1) != 1) page = press(Keys.HOME, page) { it is Page.ClassRoot } as Page.ClassRoot
        val found = LinkedHashMap<Int, ClassEntry>()
        for (i in 1..MAX_CLASS_PAGES) {
            page.entries.forEach { found.putIfAbsent(it.index, it) }
            val last = page.entries.lastOrNull()?.index ?: break
            if (page.screen.lines[page.screen.rows - 2].isBlank() || i == MAX_CLASS_PAGES) break
            val next = press(Keys.PAGE_DOWN, page) { it is Page.ClassRoot } as Page.ClassRoot
            if ((next.entries.firstOrNull()?.index ?: 0) <= last) break
            page = next
        }
        if ((page.entries.firstOrNull()?.index ?: 1) != 1) press(Keys.HOME, page) { it is Page.ClassRoot }
        found.values.sortedBy { it.index }
    }

    /**
     * 分類 [path] 裡的看板（含子分類「Σ」；即時熱門看板也是走這裡）。[path] 是從【分類看板】一層一層點進去的序號：
     * 回到根目錄，每一層打序號 Enter 把游標移過去（已經在就不動）、再按 Enter 進去（分類也可能有進板畫面，任意鍵按掉），
     * 最後那層的列表超過一頁翻完。版面跟我的最愛一樣（[Page.Favorites.inClass]）。只有一個看板的分類 PTT 直接進那個板（回那一個）。
     * 空的分類（「--- 本目錄目前尚無看板 ---」）回空的。做完留在那個列表（App 點看板時 [showBoard] 從這裡用 `s` 換過去）。
     */
    suspend fun showClass(path: List<Int>): List<Board> = navigate {
        require(path.isNotEmpty())
        var page: Page = goToClassRoot()
        for (index in path) {
            val cursor = when (val p = page) {
                is Page.ClassRoot -> p.cursor
                is Page.Favorites -> p.cursor
                else -> throw UnexpectedScreenException("分類 $path 走到一半畫面跟預期的不一樣", p.screen)
            }
            if (cursor != index) {
                page = press(index.toString() + Keys.ENTER, page) {
                    (it is Page.ClassRoot && it.cursor == index) || (it is Page.Favorites && it.cursor == index)
                }
            }
            page = press(Keys.ENTER, page) { (it is Page.Favorites && it.inClass) || it is Page.PressAnyKey || it is Page.ArticleList }
            if (page is Page.PressAnyKey) page = press(Keys.ENTER, page) { (it is Page.Favorites && it.inClass) || it is Page.ArticleList }
            // 只有一個看板的分類 PTT 直接進那個板（BePTT 也處理這個）：就回那一個
            if (page is Page.ArticleList) return@navigate listOf(Board(index = 1, name = page.board, category = "", title = ""))
        }
        readBoardPages(page as Page.Favorites, MAX_FAVORITE_PAGES).first
    }

    /**
     * 即時熱門看板：【分類看板】裡敘述有「即時熱門看板」的那一項（真機是最後一項、第 14 項，不是第 1 項），進去列出來（人氣排名）。
     * 找不到那一項就丟例外。
     */
    suspend fun hotBoards(): List<Board> {
        val hot = showClasses().firstOrNull { it.isHot }
            ?: throw UnexpectedScreenException("分類看板裡找不到「即時熱門看板」", _screens.value)
        return showClass(listOf(hot.index))
    }

    /**
     * 信箱裡的信（主選單 `M` Enter → `R` Enter，【郵件選單】）：從最新的那頁（[goToNewestMail]）往前翻 [MAX_MAIL_PAGES] 頁，序號由小到大回傳；
     * 沒有信（pttbbs `m_read` 說「您沒有來信」，不開列表）回空的。做完留在信箱列表。
     * 「您沒有來信」照原始碼寫的，真機沒遇過。
     */
    suspend fun showMails(): List<MailSummary> = navigate {
        var page = goToNewestMail(goToMailList() ?: return@navigate emptyList())
        val found = LinkedHashMap<Int, MailSummary>()
        for (i in 1..MAX_MAIL_PAGES) {
            page.mails.forEach { found.putIfAbsent(it.index, it) }
            val first = page.mails.firstOrNull()?.index ?: break
            if (first <= 1 || i == MAX_MAIL_PAGES) break
            val prev = press(Keys.PAGE_UP, page) { it is Page.MailList } as Page.MailList
            if ((prev.mails.firstOrNull()?.index ?: first) >= first) break
            page = prev
        }
        found.values.sortedBy { it.index }
    }

    /**
     * 從信箱列表 [list] 走到最後一頁（最新的信）。跟看板的 [goToNewest] 一樣：游標已經在最後一封時按 End，PTT 一個字都不回（會等到逾時）；
     * 進信箱時游標就停在最新那封，信滿一頁（20 封）以上時一進來就是這樣。所以：
     * - 最下面那列是空的：最後一封就在這頁，不按
     * - 游標在這頁最後一列（可能已經是最後一封）：先上移一列（畫面一定變），再 End（從上一列移過去，畫面也一定變）
     * - 其他：End（後面還有，畫面一定變）
     * 找不到游標就不動（不知道會不會變）。
     */
    private suspend fun goToNewestMail(list: Page.MailList): Page.MailList {
        val cursor = list.cursor
        if (list.screen.lines[list.screen.rows - 2].isBlank() || cursor == null) return list
        val from = if (cursor == list.mails.lastOrNull()?.index && cursor > 1) press(Keys.UP, list) { it is Page.MailList } else list
        return press(Keys.END, from) { it is Page.MailList } as Page.MailList
    }

    /**
     * 讀第 [index] 封信、整封接起來（跟 [wholeArticle] 一樣一頁一頁 PgDn，讀完按左鍵退回信箱列表；信是同一個閱讀器 `more`）。
     * PTT 讀過就把「+」拿掉（變已讀）。
     */
    suspend fun wholeMail(index: Int): ArticleText = navigate {
        var list = goToMailList() ?: throw UnexpectedScreenException("信箱是空的", _screens.value)
        if (list.cursor != index) {
            list = press(index.toString() + Keys.ENTER, list) { it is Page.MailList && it.cursor == index } as Page.MailList
        }
        var opened = press(Keys.ENTER, list) { it is Page.ArticleView || it is Page.PressAnyKey }
        if (opened is Page.PressAnyKey) opened = press(Keys.ENTER, opened) { it is Page.ArticleView }
        var page = opened as Page.ArticleView
        if (page.progress.firstLine > 1) page = pressPage(Keys.HOME, page) { it.progress.firstLine <= 1 }
        var text = ArticleText.EMPTY + page
        var pages = 1
        while (!text.complete && pages < MAX_ARTICLE_PAGES) {
            val from = page
            page = pressPage(Keys.PAGE_DOWN, from) { it.progress.firstLine > from.progress.firstLine }
            text += page
            pages++
        }
        press(Keys.LEFT, page) { it is Page.MailList }
        text
    }

    /**
     * 寄一封站內信（pttbbs `m_send` → `mail_ui_send` → `do_innersend` → `vedit2`），回傳 PTT 上的代號（大小寫照 PTT）。
     *
     * 先查一次收信人（[queryUser] 那幾步）：PTT 的代號補完（`usercomplete`）在打的代號不存在、但只有一個代號以它開頭時會換成那一個，
     * 不先查的話打錯一個字可能寄給別人。查不到、或查到的代號跟打的不一樣（不分大小寫）就不寄。
     * 接著主選單 `M` Enter 進【私人信件區】，上下鍵把游標移到「站內寄信」那項 Enter（真機的選單有兩項是 S 開頭：Send 與 Savemail，不按快捷鍵）→
     * 第 1 列「請輸入使用者代號:」打代號 Enter →（今日免費額度用完：「確定繼續？[y/N]」答 n，不代使用者花 Ptt 幣，不寄）→
     * 第 2 列「主題：」打標題 Enter → 編輯器打內文 → [finishMail]（存檔、簽名檔、自存底稿、任意鍵）→ 回到【私人信件區】。
     * 寄不出去（「使用者 [x] 無法收信」「信箱已滿，無法收信」「對方拒收」…）把 PTT 的話丟出來。
     */
    suspend fun sendMail(mail: NewMail): String = navigate {
        val to = clean(mail.to).trim()
        val user = lookUpUser(to) ?: throw UnexpectedScreenException("PTT 上沒有 $to 這個帳號，沒有寄", _screens.value)
        if (!user.id.equals(to, ignoreCase = true)) {
            throw UnexpectedScreenException("PTT 上沒有 $to 這個帳號（PTT 會把它補成 ${user.id}），沒有寄", _screens.value)
        }
        val back: (Page) -> Boolean = { it is Page.MailMenu && !it.noMail }
        val form = pressMenuItem(goToMailMenu(), "私人信件區", "站內寄信") { it.isMailStep(Page.MailForm.Step.RECIPIENT) }
        val page = press(user.id + Keys.ENTER, form) {
            it.isMailStep(Page.MailForm.Step.SUBJECT) || it.isMailStep(Page.MailForm.Step.QUOTA) || it is Page.PressAnyKey || back(it)
        }
        when {
            page is Page.PressAnyKey -> failMail(page, user.id, back)
            page is Page.MailForm && page.step == Page.MailForm.Step.QUOTA -> declineQuota(page, back)
            back(page) -> throw UnexpectedScreenException("PTT 沒有收下收信人 ${user.id}，沒有寄", page.screen)
        }
        val editor = press(clean(mail.title).trim() + Keys.ENTER, page) { it is Page.Editor || it is Page.PressAnyKey }
        if (editor is Page.PressAnyKey) failMail(editor, user.id, back)
        finishMail(editor as Page.Editor, cleanBody(mail.body), mail.signature, mail.backup, back)
        user.id
    }

    /**
     * 回信箱裡第 [index] 封信（pttbbs 信箱列表的 `y` → `multi_reply` → `mail_reply` → `mail_ui_send`）。
     * 列表上游標移到那封（打序號 Enter）；[original] 有給的話先對那一列的寄件人與標題，對不上（信箱變了）就不回，免得回錯封 → 按 `y` →
     * 群組信件「請問要回信給原發信者(r)還是全部的人(a)」答 r → 寄件人的帳號被重新註冊過（「確定仍然要寄信給對方嗎[y/N]」）答 n、不寄 →
     * 「採用原標題[Y/n]?」：[MailReply.title] 是 null 答 y，不然答 n、「新標題：」Ctrl-Y 清掉預填的再打 →
     * （今日免費額度用完答 n、不寄）→「請問要引用原文嗎」答 [MailReply.quote] → 編輯器打內文 → [finishMail] → 回到信箱列表。
     * 有引言時游標在第一行、引言上面（`vedit2`：不管有沒有引用，游標都從 (0,0) 開始）：內文打完空一行再接引言。
     * 系統信、匿名信 PTT 說「無法回信。」；`y` 沒反應（沒有權限）也丟例外。
     */
    suspend fun replyMail(index: Int, original: MailSummary?, reply: MailReply): Unit = navigate {
        var list = goToMailList() ?: throw UnexpectedScreenException("信箱是空的", _screens.value)
        if (list.cursor != index) {
            list = press(index.toString() + Keys.ENTER, list) { it is Page.MailList && it.cursor == index } as Page.MailList
        }
        val row = list.mails.firstOrNull { it.index == index }
        if (original != null && (row == null || !row.author.equals(original.author, ignoreCase = true) || row.title != original.title)) {
            throw UnexpectedScreenException("信箱變了：第 $index 封不是要回的那封，回信箱重新整理再回", list.screen)
        }
        val back: (Page) -> Boolean = { it is Page.MailList }
        var page = pressOrNothing("y", list) { it is Page.MailForm || it is Page.PressAnyKey || it is Page.Editor }
            ?: throw UnexpectedScreenException("PTT 沒有反應：不能回這封信", list.screen)
        repeat(MAX_STEPS + 2) {
            page = when (val p = page) {
                is Page.Editor -> {
                    val body = cleanBody(reply.body)
                    finishMail(p, if (reply.quote && body.isNotEmpty()) body + Keys.ENTER + Keys.ENTER else body, reply.signature, reply.backup, back)
                    return@navigate
                }
                // 「已找不到此使用者。」印在第 2 列，任意鍵的訊息只是「放棄寄信。」
                is Page.PressAnyKey -> if (p.screen.lines.any { it.contains("已找不到此使用者") }) {
                    dismissMessages(p, back)
                    throw UnexpectedScreenException("PTT 上已經找不到寄件人的帳號，沒有寄", p.screen)
                } else {
                    failMail(p, null, back)
                }
                is Page.MailForm -> when (p.step) {
                    Page.MailForm.Step.GROUP_REPLY -> press("r" + Keys.ENTER, p) { !it.isMailStep(Page.MailForm.Step.GROUP_REPLY) }
                    Page.MailForm.Step.REREGISTERED -> {
                        dismissMessages(press("n" + Keys.ENTER, p) { !it.isMailStep(Page.MailForm.Step.REREGISTERED) }, back)
                        throw UnexpectedScreenException("寄件人的帳號被重新註冊過，收信的可能已經不是當初寄信的人：沒有寄", p.screen)
                    }
                    Page.MailForm.Step.KEEP_TITLE ->
                        press((if (reply.title == null) "y" else "n") + Keys.ENTER, p) { !it.isMailStep(Page.MailForm.Step.KEEP_TITLE) }
                    Page.MailForm.Step.NEW_TITLE ->
                        press(Keys.ctrl('Y') + clean(reply.title.orEmpty()).trim() + Keys.ENTER, p) { !it.isMailStep(Page.MailForm.Step.NEW_TITLE) }
                    Page.MailForm.Step.QUOTA -> declineQuota(p, back)
                    Page.MailForm.Step.QUOTE ->
                        press((if (reply.quote) "y" else "n") + Keys.ENTER, p) { it is Page.Editor || it is Page.PressAnyKey }
                    // 正在印收信人、標題（或認錯了）：等下一個畫面
                    else -> settleFrom(p)
                }
                else -> settleFrom(p)
            }
        }
        throw UnexpectedScreenException("按了 ${MAX_STEPS + 2} 次還沒進編輯器", page.screen)
    }

    private fun Page.isMailStep(step: Page.MailForm.Step): Boolean = this is Page.MailForm && this.step == step

    /**
     * 編輯器裡把信打完、寄出去（`vedit2` → `write_file` → `hold_mail`）：確定是插入模式、不自動縮排，打 [body]（已經換成按鍵）→
     * Ctrl-X 等「確定要儲存檔案嗎？」答 s → 之後的提示照問題答：簽名檔答 [signature]、「已順利寄出，是否自存底稿」答 [backup]（y／n）、
     * 任意鍵按 Enter（訊息收起來），直到回到 [done] 那一頁。
     * 存檔前的內容檢查不給過時 PTT 把人丟回編輯器：Ctrl-X、a 放棄。
     * 回到 [done] 之前沒看到「已順利寄出」就是沒寄出去，把第一個訊息丟出來（「使用者 [x] 信箱已滿，無法收信」「取消。」…）。
     * 答了 s 之後、看到「已順利寄出」之前出別的錯丟 [MailUnconfirmedException]；看到之後才出錯算寄出去了（PTT 停在任意鍵，下一次走頁面會按掉）。
     */
    private suspend fun finishMail(editor: Page.Editor, body: String, signature: Char, backup: Boolean, done: (Page) -> Boolean) {
        var page: Page = typeBody(editorReady(editor), body)
        page = press(Keys.ctrl('X'), page) { it is Page.SavePrompt }
        val messages = ArrayList<String>()
        var sent = false
        try {
            page = press("s" + Keys.ENTER, page) { it !is Page.SavePrompt }
            var steps = 0
            while (!done(page)) {
                if (++steps > MAX_STEPS * 2) throw UnexpectedScreenException("存檔之後按了 ${MAX_STEPS * 2} 次還沒回到信箱", page.screen)
                page = when (val p = page) {
                    is Page.SignaturePrompt -> press(signature + Keys.ENTER, p) { it !is Page.SignaturePrompt }
                    is Page.BackupPrompt -> {
                        sent = true
                        press((if (backup) "y" else "n") + Keys.ENTER, p) { it !is Page.BackupPrompt }
                    }
                    is Page.PressAnyKey -> {
                        messages += noticeAboveAnyKey(p.screen)
                        press(Keys.ENTER, p) { true }
                    }
                    is Page.Editor -> {
                        val prompt = press(Keys.ctrl('X'), p) { it is Page.SavePrompt }
                        press("a" + Keys.ENTER, prompt) { it !is Page.SavePrompt }
                    }
                    else -> settleFrom(p)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (sent) return
            throw MailUnconfirmedException(e)
        }
        if (!sent) {
            val reason = messages.map { mailNotice(it, null) }.firstOrNull { it.isNotEmpty() }
            throw UnexpectedScreenException(reason ?: "信沒有寄出去", page.screen)
        }
    }

    /** 寄信途中 PTT 用任意鍵的訊息擋下來：一路按掉（後面常接著一個「取消。」），回到 [done] 那一頁，把第一個訊息丟出來。[id] 是打過的收信人。 */
    private suspend fun failMail(page: Page.PressAnyKey, id: String?, done: (Page) -> Boolean): Nothing {
        val reason = dismissMessages(page, done).map { mailNotice(it, id) }.firstOrNull { it.isNotEmpty() }
        throw UnexpectedScreenException(reason ?: "PTT 沒有讓這封信寄出去", page.screen)
    }

    /** 今日免費寄信額度用完了（「…確定繼續？[y/N]」）：答 n（不代使用者花 Ptt 幣），按掉後面的訊息回到 [done]，把原因丟出來。 */
    private suspend fun declineQuota(page: Page.MailForm, done: (Page) -> Boolean): Nothing {
        val text = page.screen.lines[page.screen.rows - 2].trim().substringBefore("，確定")
        dismissMessages(press("n" + Keys.ENTER, page) { !it.isMailStep(Page.MailForm.Step.QUOTA) }, done)
        throw UnexpectedScreenException("$text：App 不替你花 Ptt 幣，沒有寄", page.screen)
    }

    /**
     * 寄信畫面上的訊息拿掉前面的「請輸入使用者代號: 代號」：`m_send` 的錯誤（「這裡沒有這個人啦！」「使用者 [x] 無法收信」）
     * 直接印在代號後面，同一列（照原始碼推的）。
     */
    private fun mailNotice(message: String, id: String?): String {
        if (!message.startsWith("請輸入使用者代號")) return message.trim()
        val rest = message.removePrefix("請輸入使用者代號").trimStart(':', '：', ' ')
        return (if (id != null && rest.startsWith(id, ignoreCase = true)) rest.drop(id.length) else rest).trim()
    }

    /** 一路按掉任意鍵的訊息（收起來回傳，[noticeAboveAnyKey]）直到畫面是 [done]；過場畫面等它變。 */
    private suspend fun dismissMessages(from: Page, done: (Page) -> Boolean): List<String> {
        val messages = ArrayList<String>()
        var page = from
        repeat(MAX_STEPS) {
            if (done(page)) return messages
            page = when (val p = page) {
                is Page.PressAnyKey -> {
                    messages += noticeAboveAnyKey(p.screen)
                    press(Keys.ENTER, p) { true }
                }
                else -> settleFrom(p)
            }
        }
        if (done(page)) return messages
        throw UnexpectedScreenException("按了 $MAX_STEPS 次還沒回到信箱", page.screen)
    }

    /** 過場的畫面（還在畫、或在等 PTT 做事）：等它變了再等安靜；[NUDGE_MS] 的兩倍都沒變就丟例外。 */
    private suspend fun settleFrom(page: Page): Page {
        if (withTimeoutOrNull(NUDGE_MS * 2) { nextScreen(page.screen) } == null) {
            throw UnexpectedScreenException("停在認不出來的畫面", page.screen)
        }
        return awaitQuiet()
    }

    /** 走到【私人信件區】（主選單 `M` Enter）：信箱列表、看板、文章這些按左鍵退出來；「您沒有來信」的訊息按掉。 */
    private suspend fun goToMailMenu(): Page.MailMenu {
        var page = awaitQuiet()
        repeat(MAX_STEPS) {
            page = when (val p = page) {
                is Page.MailMenu -> if (p.noMail) press(Keys.ENTER, p) { it is Page.MailMenu && !it.noMail } else return p
                is Page.MainMenu -> press("M" + Keys.ENTER, p) { it is Page.MailMenu }
                is Page.BoardNamePrompt -> press(Keys.BACKSPACE.repeat(p.typed.length) + Keys.ENTER, p) { it !is Page.BoardNamePrompt }
                is Page.BoardKeywordPrompt -> press(Keys.ENTER, p) { it !is Page.BoardKeywordPrompt }
                is Page.PressAnyKey -> press(Keys.ENTER, p) { it !is Page.Unknown }
                is Page.Favorites, is Page.ArticleList, is Page.ArticleView, is Page.ClassRoot, is Page.MailList, is Page.TalkMenu ->
                    press(Keys.LEFT, p) { it !is Page.Unknown }
                else -> throw UnexpectedScreenException("認不出現在的畫面，走不到私人信件區", p.screen)
            }
        }
        throw UnexpectedScreenException("按了 $MAX_STEPS 次還沒到私人信件區", page.screen)
    }

    /** 走到【分類看板】：主選單按 `C` Enter；在分類的列表、看板、文章、信箱裡按左鍵一層一層退出來。 */
    private suspend fun goToClassRoot(): Page.ClassRoot {
        var page = awaitQuiet()
        repeat(MAX_STEPS) {
            page = when (val p = page) {
                is Page.ClassRoot -> return p
                is Page.MainMenu -> press("C" + Keys.ENTER, p) { it is Page.ClassRoot }
                is Page.BoardNamePrompt -> press(Keys.BACKSPACE.repeat(p.typed.length) + Keys.ENTER, p) { it !is Page.BoardNamePrompt }
                is Page.BoardKeywordPrompt -> press(Keys.ENTER, p) { it !is Page.BoardKeywordPrompt }
                is Page.PressAnyKey -> press(Keys.ENTER, p) { it !is Page.Unknown }
                is Page.Favorites, is Page.ArticleList, is Page.ArticleView, is Page.MailMenu, is Page.MailList, is Page.TalkMenu ->
                    press(Keys.LEFT, p) { it !is Page.Unknown }
                else -> throw UnexpectedScreenException("認不出現在的畫面，走不到分類看板", p.screen)
            }
        }
        throw UnexpectedScreenException("按了 $MAX_STEPS 次還沒到分類看板", page.screen)
    }

    /**
     * 走到信箱的列表：主選單按 `M` Enter 進私人信件區、再 `R` Enter；其他畫面左鍵退出來。
     * 沒有信回 null、停在私人信件區：「 ◆ 您沒有來信 … [按任意鍵繼續]」是 `vmsg`，PTT 停在那裡等一個鍵，
     * 不按掉的話下一個鍵會被它吃掉（照原始碼寫的）：按 Enter 等選單的最下面那列畫回來。
     */
    private suspend fun goToMailList(): Page.MailList? {
        var page = awaitQuiet()
        repeat(MAX_STEPS) {
            page = when (val p = page) {
                is Page.MailList -> return p
                is Page.MailMenu -> {
                    val next = press("R" + Keys.ENTER, p) { it is Page.MailList || (it is Page.MailMenu && it.noMail) }
                    if (next is Page.MailMenu) {
                        press(Keys.ENTER, next) { it is Page.MailMenu && !it.noMail }
                        return null
                    }
                    next
                }
                is Page.MainMenu -> press("M" + Keys.ENTER, p) { it is Page.MailMenu }
                is Page.BoardNamePrompt -> press(Keys.BACKSPACE.repeat(p.typed.length) + Keys.ENTER, p) { it !is Page.BoardNamePrompt }
                is Page.BoardKeywordPrompt -> press(Keys.ENTER, p) { it !is Page.BoardKeywordPrompt }
                is Page.PressAnyKey -> press(Keys.ENTER, p) { it !is Page.Unknown }
                is Page.Favorites, is Page.ArticleList, is Page.ArticleView, is Page.ClassRoot, is Page.TalkMenu -> press(Keys.LEFT, p) { it !is Page.Unknown }
                else -> throw UnexpectedScreenException("認不出現在的畫面，走不到信箱", p.screen)
            }
        }
        throw UnexpectedScreenException("按了 $MAX_STEPS 次還沒到信箱", page.screen)
    }

    /**
     * 走到 [name] 的文章列表：在文章裡先按左鍵退回列表；不在這個板就把進板的鍵一次送出去（[goToBoard]：我的最愛這頁看得到那個板用列表的 `s`、
     * 在別的看板用看板裡的 `s`、其他用主選單的 `s`），進板一律按 End 到最後面。停在搜尋結果（[Page.ArticleList.filtered]）的話按左鍵退回整個列表。
     * [newest]：確定走到列表最後面（最新的文章與置底文，[goToNewest]）。App 第一次列這個板（列表還是空的、或先列的是上次記下來的）時要：
     * 同一條連線第二次進同一個板，PTT 停在上次游標的位置，不是最新的那頁（進板畫面比一頁長時，進板送的 End 會被它吃掉）。
     * 從文章退回來不用（列出來的都還在）。
     */
    suspend fun showBoard(name: String, newest: Boolean = false): Page.ArticleList = navigate {
        val list = goToList(name, search = null)!!
        if (newest) goToNewest(list) else list
    }

    /**
     * 在 [board] 裡搜尋文章（pttbbs `read.c` 的 `select_read`）：先走到整個列表（已經在別的搜尋結果裡就按左鍵退出來），
     * 按 [SearchKind.key] 等最下面一列出現提示（[SearchKind.prompt]），再打關鍵字 Enter。
     * 有結果就是「系列文章」的列表（序號是結果裡的第幾筆、停在最後一頁，也就是最新的）；找不到時 PTT 說「找不到符合條件的文章。」，
     * 按掉它、回傳 null。同一次搜尋再叫一次（轉螢幕、從文章退回來）不會重搜。
     */
    suspend fun searchArticles(board: String, search: ArticleSearch): Page.ArticleList? = navigate { goToList(board, search) }

    /**
     * 打開 [board] 裡的 [article]，停在第一頁：先走到文章列表，一般文章打序號 Enter 跳過去（「跳至第幾項」），等游標移過去再按 Enter；
     * 置底文按 End 到最後一筆、往上數 [ArticleRef.Pinned.fromBottom] 筆再按 Enter。
     * 現在已經在文章頁就用它（重進同一頁、轉螢幕不會再走一次）；不在第一頁的話按 Home 回到開頭（真機確認 Home 回到「01~22」）。
     * 有 [search] 的話 [article] 是搜尋結果裡的第幾筆：先確定終端還停在那個搜尋結果（不是就重搜一次）再跳。
     */
    suspend fun openArticle(board: String, article: ArticleRef, search: ArticleSearch? = null): Page.ArticleView = navigate {
        val page = goToArticle(board, article, search)
        if (page.progress.firstLine <= 1) page
        else pressPage(Keys.HOME, page) { it.progress.firstLine <= 1 }
    }

    /**
     * 文章往下翻一頁（PgDn），回傳翻過去的那頁；用 [ArticleText.plus] 接起來。已經是最後一頁就不按鍵、回傳現在這頁。
     * 現在不在文章頁（例如切到終端畫面亂按過）就先打開 [article]（從第一頁開始，所以呼叫的人要從頭接）。
     */
    suspend fun nextArticlePage(board: String, article: ArticleRef, search: ArticleSearch? = null): Page.ArticleView = navigate {
        val page = goToArticle(board, article, search)
        if (page.progress.isLast) page
        else pressPage(Keys.PAGE_DOWN, page) { it.progress.firstLine > page.progress.firstLine }
    }

    /**
     * [article] 裡有第 [line] 行的那一頁（App 往下捲接下一頁、捲到中間缺的那段）：現在那頁就有的話不按鍵；
     * 是下一頁的（從現在這頁最後一行之後的 22 行內）按 PgDn（真機確認的翻法）；其他的用 pmore 的「跳至此行」：`:` 行號 Enter
     * （pttbbs `pmore_cmd_goto_line`，那一行排在第一列；BePTT 讀文章也這樣跳），第 1 行按 Home。
     * 最後幾頁對齊文章結尾，所以回傳的那頁可能從更前面開始；[line] 超過文章結尾的話回傳最後一頁。
     * 不在文章裡（終端畫面亂按過、查詢使用者回來）先打開 [article]（停在第一頁），再跳過去。
     */
    suspend fun articlePage(board: String, article: ArticleRef, search: ArticleSearch? = null, line: Int): Page.ArticleView = navigate {
        val page = goToArticle(board, article, search)
        when {
            page.covers(line) -> page
            line <= 1 -> pressPage(Keys.HOME, page) { it.progress.firstLine <= 1 }
            line > page.progress.lastLine && line <= page.progress.lastLine + PAGE_LINES -> pressPage(Keys.PAGE_DOWN, page) { it.covers(line) }
            else -> pressPage(":" + line + Keys.ENTER, page) { it.covers(line) }
        }
    }

    /**
     * [article] 的最後一頁（App 的「到底」：不用一頁一頁翻過去）：End（pmore `pmore_cmd_end`，照原始碼寫的）。
     * 已經在最後一頁就不按鍵。
     */
    suspend fun articleEnd(board: String, article: ArticleRef, search: ArticleSearch? = null): Page.ArticleView = navigate {
        val page = goToArticle(board, article, search)
        if (page.progress.isLast) page else pressPage(Keys.END, page) { it.progress.isLast }
    }

    /** 這頁有第 [line] 行（最後一頁的話，[line] 在結尾之後也算）。 */
    private fun Page.ArticleView.covers(line: Int): Boolean =
        progress.firstLine <= line && (line <= progress.lastLine || progress.isLast)

    /**
     * 整篇 [article]（看板長按的「複製全文」）：打開、從第一頁一頁一頁翻到最後接成整篇，再按左鍵退回列表。
     * **整個在一次 [navigate] 裡做完**，中間不讓別的導覽插進來：分成 [openArticle]、[nextArticlePage] 一次一次叫的話，
     * App 在複製途中點了另一篇，那篇的 [openArticle] 會排在兩頁中間，[goToArticle] 看到終端上開著文章就當成是它（文章頁認不出是哪一篇），
     * 結果打開的是正在複製的這篇。現在別的導覽等這裡退回列表才開始。
     * 翻了 [MAX_ARTICLE_PAGES] 頁還沒到底就停，回傳的 [ArticleText.complete] 是 false。
     */
    suspend fun wholeArticle(board: String, article: ArticleRef, search: ArticleSearch? = null): ArticleText = navigate {
        var page = goToArticle(board, article, search)
        if (page.progress.firstLine > 1) page = pressPage(Keys.HOME, page) { it.progress.firstLine <= 1 }
        var text = ArticleText.EMPTY + page
        var pages = 1
        while (!text.complete && pages < MAX_ARTICLE_PAGES) {
            val from = page
            page = pressPage(Keys.PAGE_DOWN, from) { it.progress.firstLine > from.progress.firstLine }
            text += page
            pages++
        }
        press(Keys.LEFT, page) { it is Page.ArticleList }
        text
    }

    /**
     * [article] 的文章資訊（看板長按的「查詢文章資訊」，BePTT 也有）：走到列表（在文章裡先按左鍵退回來），游標移到那篇
     * （跟 [goToArticle] 一樣：一般文章打序號 Enter、已經在那篇上就不打；置底文 End、往上數），按 `Q`（pttbbs `bbs.c` `view_postinfo`），
     * 移游標跟 `Q` 一次送出。等框畫完（最下面出現任意鍵）讀出來（[ScreenParser.articleInfo]），再按 Enter 回到列表
     * （不能按 `Q` 按掉：框裡再按一次 `Q` 是切換列表顯示文章價格）。有 [search] 的話是搜尋結果裡的第幾筆，回到的也是搜尋結果。
     * 被刪的文章不會叫到這裡（列表上被刪的那列不能長按）。
     */
    suspend fun articleInfo(board: String, article: ArticleRef, search: ArticleSearch? = null): ArticleInfo = navigate {
        var now = awaitQuiet()
        if (now is Page.ArticleView) now = press(Keys.LEFT, now) { it is Page.ArticleList }
        val list = goToList(board, search, from = now) ?: throw UnexpectedScreenException(NO_RESULTS, _screens.value)
        val move = when (article) {
            is ArticleRef.Numbered -> if (list.cursor != article.index) article.index.toString() + Keys.ENTER else ""
            is ArticleRef.Pinned -> Keys.END + Keys.UP.repeat(article.fromBottom)
        }
        val box = press(move + "Q", list) { it is Page.PressAnyKey && ScreenParser.articleInfo(it.screen) != null }
        val info = ScreenParser.articleInfo(box.screen)!!
        press(Keys.ENTER, box) { it is Page.ArticleList }
        info
    }

    /**
     * 重新讀一次 [article]（App 的「檢查新推文」）：現在在文章裡就先按左鍵退回列表再打開，回傳第一頁。
     * 不能只按 Home：pttbbs 的閱讀器（`more`）開文章時讀一次檔，在裡面翻來翻去不會再讀，新的推文要退出來重進才看得到。
     */
    suspend fun reloadArticle(board: String, article: ArticleRef, search: ArticleSearch? = null): Page.ArticleView = navigate {
        (awaitQuiet() as? Page.ArticleView)?.let { press(Keys.LEFT, it) { p -> p is Page.ArticleList } }
        goToArticle(board, article, search)
    }

    /**
     * 重新讀一次 [board] 的列表（App 的「重整」，BePTT 看板頁下面那顆）：現在停在這個板就退出去再進一次，
     * 再走到列表最後面（[goToNewest]），回傳最新的那一頁。退出重進是為了讓 pttbbs 重讀目錄（新文章、推文數）、畫面也一定會變；
     * 重進停在上次游標的位置（真機），所以還要走到最後面。退出、重進一次送出去：左鍵退出看板、再退到主選單，用主選單的 `s` 進來
     * （[enterBoard] 接的 End 走到最後面；剛剛就在這個板，不會有進板畫面）。在文章裡先退回列表。
     * 有 [search] 是重搜一次：先退出系列文章模式（跟換條件一樣）再搜；找不到回 null。
     */
    suspend fun reloadBoard(board: String, search: ArticleSearch? = null): Page.ArticleList? = navigate {
        var page = awaitQuiet()
        if (page is Page.ArticleView) page = press(Keys.LEFT, page) { it !is Page.Unknown }
        if (page is Page.ArticleList && page.isBoard(board)) {
            if (page.filtered) page = press(Keys.LEFT, page) { it is Page.ArticleList && it.isBoard(board) && !it.filtered }
            currentSearch = null
            if (search == null) page = enterBoard(Keys.LEFT + selectFromMainMenu(board), page)
        }
        val list = goToList(board, search, from = page)
        if (search == null && list != null) goToNewest(list) else list
    }

    /**
     * 從 [list] 走到列表最後面（最新的文章與置底文）。pttbbs 每個列表記得游標上次停在哪（`getkeep`），同一條連線再進同一個板
     * 會回到那裡（真機：在 #153870 退出重進，列的是 153860～153879，最新的是 153882），不是第一次進板時的最新那頁。
     * End 把游標移到最後一筆，但游標已經在最後一筆時 PTT 一個字都不回（會等到逾時），所以：
     * - 看得到置底文、或最下面那列是空的：列表的尾巴就在這頁，不按
     * - 游標在這頁最後一列（可能已經是最後一筆）：先上移一列（畫面一定變），再 End（從上一列移過去，畫面也一定變）
     * - 其他：End（後面還有，畫面一定變）
     * 找不到游標就不動（不知道會不會變）。
     */
    private suspend fun goToNewest(list: Page.ArticleList): Page.ArticleList {
        val tailShown = list.articles.any { it.index == null } || list.screen.lines[list.screen.rows - 2].isBlank()
        val cursor = list.cursor
        if (tailShown || cursor == null) return list
        val from = if (cursor == list.articles.lastOrNull()?.index && cursor > 1) press(Keys.UP, list) { it is Page.ArticleList } else list
        return press(Keys.END, from) { it is Page.ArticleList } as Page.ArticleList
    }

    /**
     * 走到 [article]：已經在文章頁就是它（認不出是哪一篇，看板頁、文章頁一次只開一篇）；不然走到列表，
     * 一般文章打序號 Enter（「跳至第幾項」）再 Enter 開文章，置底文 End、往上數、Enter，**一次送出去**：
     * PTT 照順序吃先打的字，中間的列表不會畫出來（pfterm 有先打的字就不更新畫面，BePTT 也是一次送整串）。
     * 游標已經在那篇上就不打序號（PTT 對跳到原地一個字都不回，真機）。後面加 Ctrl-L，看到進度列畫完就是整頁到了（[pressPage]）。
     */
    private suspend fun goToArticle(board: String, article: ArticleRef, search: ArticleSearch?): Page.ArticleView {
        val now = awaitQuiet()
        if (now is Page.ArticleView) return now
        // 剛剛等過畫面安靜了，進板不用再等一次（省 QUIET_MS）
        val list = goToList(board, search, from = now) ?: throw UnexpectedScreenException(NO_RESULTS, _screens.value)
        val keys = when (article) {
            is ArticleRef.Numbered -> (if (list.cursor != article.index) article.index.toString() + Keys.ENTER else "") + Keys.ENTER
            is ArticleRef.Pinned -> Keys.END + Keys.UP.repeat(article.fromBottom) + Keys.ENTER
        }
        send(keys + Keys.CTRL_L)
        var page = awaitPageWith(DEFAULT_TIMEOUT_MS, ::pageQuiet, list.screen) { it is Page.ArticleView || it is Page.PressAnyKey }
        if (page is Page.PressAnyKey) page = pressPage(Keys.ENTER, page) { true }
        return page as Page.ArticleView
    }

    /**
     * [board] 裡序號比 [before] 小的文章（App 往下捲到底載入更舊的）：回傳畫面上看到的那些，至少大半頁（到第 1 篇為止）。
     *
     * 不靠 PgUp 從終端現在停的那頁往前翻：從文章退回來時終端停在那篇所在的頁，不一定是 App 列出來最舊的那頁。
     * 所以打 `before - 1` Enter 跳到那篇（跳至第幾項），列表會換到有那篇的頁；那篇如果在頁首，只多了一兩篇，再按 PgUp 往前一頁。
     * 第 1 篇已經在畫面上就不按 PgUp（翻到最前面再往前可能會繞到最後一頁）。
     * 有 [search] 的話是搜尋結果裡更前面的幾筆（序號是結果裡的第幾筆，跳序號、PgUp 在結果裡一樣能用）。
     */
    suspend fun olderArticles(board: String, before: Int, search: ArticleSearch? = null): List<ArticleSummary> = navigate {
        val found = sortedMapOf<Int, ArticleSummary>()
        fun collect(list: Page.ArticleList) = list.articles.forEach { a -> a.index?.takeIf { it < before }?.let { found[it] = a } }
        fun Page.isListOf(predicate: (List<ArticleSummary>) -> Boolean) =
            this is Page.ArticleList && isBoard(board) && filtered == (search != null) && predicate(articles)

        var list = goToList(board, search) ?: throw UnexpectedScreenException(NO_RESULTS, _screens.value)
        collect(list)
        if (found.isEmpty() && before > 1) {
            val target = before - 1
            list = press(target.toString() + Keys.ENTER, list) { p -> p.isListOf { it.any { a -> a.index == target } } } as Page.ArticleList
            collect(list)
        }
        val oldest = if (found.isEmpty()) null else found.firstKey()
        if (oldest != null && oldest > 1 && found.size < list.articles.size / 2) {
            list = press(Keys.PAGE_UP, list) { p -> p.isListOf { it.any { a -> (a.index ?: Int.MAX_VALUE) < oldest } } } as Page.ArticleList
            collect(list)
        }
        found.values.toList()
    }

    /**
     * 發文表單上的選項（App 的發文頁一進來叫）：走到 [board] 的列表按 Ctrl-P，等「種類：」那列（[Page.PostForm]）讀出分類，
     * 然後 Enter（不選）、Enter（空標題）取消掉（pttbbs `do_general`：標題空的就不發）。BePTT 也是這樣拿分類的。
     * 不能發文（「無法發文: …」「未達看板發文限制: …」的訊息）就按掉它、把原因丟出來。
     */
    suspend fun postOptions(board: String): PostOptions = navigate {
        val form = openPostForm(board)
        val titlePrompt = press(Keys.ENTER, form) { it is Page.PostForm && it.titleAsked }
        press(Keys.ENTER, titlePrompt) { it is Page.ArticleList }
        PostOptions(form.categories)
    }

    /**
     * 在 [board] 發一篇文章（pttbbs `do_general` → `vedit` → `write_file`）：
     * 列表按 Ctrl-P → 種類打分類的號碼（不選就只按 Enter）→ 標題 Enter → 進編輯器（確定是插入模式、關掉自動縮排）→
     * 板主設的發文範本清掉（[clearTemplate]）→ 打內文 →
     * Ctrl-X 等「確定要儲存檔案嗎？」答 s → 之後的提示照問題答：簽名檔答 [NewPost.signature]、匿名板的名稱答 r（用真名）、「確定[y/N]」答 y、
     * 任意鍵按 Enter，直到回到文章列表。不能發文時的訊息按掉、原因丟出來。
     */
    suspend fun post(board: String, post: NewPost): Page.ArticleList = navigate {
        val form = openPostForm(board)
        val category = post.category?.takeIf { it in form.categories.indices }?.let { (it + 1).toString() } ?: ""
        val titlePrompt = press(category + Keys.ENTER, form) { it is Page.PostForm && it.titleAsked }
        var page = press(clean(post.title).trim() + Keys.ENTER, titlePrompt) { it is Page.Editor || it is Page.PressAnyKey }
        if (page is Page.PressAnyKey) failPost(page)
        page = typeBody(clearTemplate(editorReady(page as Page.Editor)), cleanBody(withFooter(post.body, post.footer)))
        page = press(Keys.ctrl('X'), page) { it is Page.SavePrompt }
        // 答了 s 之後 PTT 就存了：後面出錯也可能已經發出去，丟 PostUnconfirmedException，App 不給直接重試
        try {
            page = press("s" + Keys.ENTER, page) { it !is Page.SavePrompt }
            afterSave(page, ArrayList(), signature = post.signature)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PostUnconfirmedException(e)
        }
    }

    /**
     * 回一篇文章到看板（pttbbs 列表的 `y` → `do_reply` → `do_generalboardreply` → `do_post_article`）。
     * 列表上游標移到序號 [index] 的那篇（[search] 有給的話是那次搜尋的結果裡的第幾筆）；[original] 有給的話先對那一列的作者與標題，
     * 對不上（看板變了）就不回，免得回錯篇 → 按 `y` →「▲ 回應至 (F)看板 …」答 f（只回到看板，不寄給作者）→
     * 「採用原標題[Y/n]?」：[PostReply.title] 是 null 答 y，不然答 n、「新標題：」Ctrl-Y 清掉預填的再打 →
     * 「請問要引用原文嗎」答 [PostReply.quote] → 編輯器打內文 → Ctrl-X、s → 簽名檔這些照 [post] 答完，回到列表。
     * 有引言時游標在第一行、引言上面（`vedit2`：不管有沒有引用，游標都從 (0,0) 開始）：內文打完空一行再接引言。
     *
     * 擋下來的情況：PTT 對 `y` 沒反應（不能在這個板發文，`CheckPostPerm`）、「無法回應至看板」（答 q）、「本板不開放回覆文章，要改回信給作者嗎」
     * （答 n）、已結案的文章（`vmsg` 按掉就是不回）、「無法發文」「未達看板發文限制」的訊息。
     * 存檔時「引言比例超過 80%」（`check_quote`）：還沒存，答 e 回編輯器再放棄（Ctrl-X、a），不替使用者強制寫入。
     * 答了 s 之後才出錯丟 [PostUnconfirmedException]（可能已經發出去了）。
     */
    suspend fun replyPost(
        board: String,
        index: Int,
        original: ArticleSummary?,
        reply: PostReply,
        search: ArticleSearch? = null,
    ): Page.ArticleList = navigate {
        val list = cursorOn(board, index, search)
        val row = list.articles.firstOrNull { it.index == index }
        if (row == null || (original != null && (!row.author.equals(original.author, ignoreCase = true) || row.title != original.title))) {
            throw UnexpectedScreenException("看板變了：第 $index 篇不是要回的那篇，回看板重新整理再回", list.screen)
        }
        if (row.deleted) throw UnexpectedScreenException("這篇文章已經被刪了", list.screen)
        val back: (Page) -> Boolean = { it is Page.ArticleList }
        var page = pressOrNothing("y", list) { it is Page.ReplyPrompt || it is Page.PressAnyKey }
            ?: throw UnexpectedScreenException("PTT 沒有反應：你在這個板不能發文（或這篇不能回）", list.screen)
        repeat(MAX_STEPS + 2) {
            page = when (val p = page) {
                is Page.ReplyPrompt -> when {
                    p.noReply -> {
                        dismissMessages(press("n" + Keys.ENTER, p) { it !is Page.ReplyPrompt }, back)
                        throw UnexpectedScreenException("這個板不開放回文", p.screen)
                    }
                    p.toBoard -> press("f" + Keys.ENTER, p) { it.isReplyForm() || it is Page.PressAnyKey || it is Page.Editor }
                    else -> {
                        dismissMessages(press("q" + Keys.ENTER, p) { it !is Page.ReplyPrompt }, back)
                        throw UnexpectedScreenException("PTT 說無法回應至看板（你在這個板不能發文）", p.screen)
                    }
                }
                is Page.PressAnyKey -> {
                    val reason = dismissMessages(p, back).firstOrNull { it.isNotEmpty() }
                    throw UnexpectedScreenException(
                        when {
                            reason == null -> "PTT 沒有讓你回文"
                            // `vmsg("此篇文章已結案, 是否真的要回應?(y/N)")`：按 Enter 就是不回
                            reason.contains("是否真的要回應") -> "這篇文章已經結案了：App 不回，真的要回請到終端畫面"
                            else -> reason
                        },
                        p.screen,
                    )
                }
                is Page.ReplyForm -> when (p.step) {
                    Page.ReplyForm.Step.KEEP_TITLE ->
                        press((if (reply.title == null) "y" else "n") + Keys.ENTER, p) { !it.isReplyStep(Page.ReplyForm.Step.KEEP_TITLE) }
                    Page.ReplyForm.Step.NEW_TITLE ->
                        press(Keys.ctrl('Y') + clean(reply.title.orEmpty()).trim() + Keys.ENTER, p) { !it.isReplyStep(Page.ReplyForm.Step.NEW_TITLE) }
                    Page.ReplyForm.Step.QUOTE ->
                        press((if (reply.quote) "y" else "n") + Keys.ENTER, p) { it is Page.Editor || it is Page.PressAnyKey }
                    // 正在印板規、標題：等下一個畫面
                    Page.ReplyForm.Step.OTHER -> settleFrom(p)
                }
                is Page.Editor -> return@navigate finishReply(p, reply)
                // 標題空的、標題不合（`content_check`）PTT 直接回列表
                is Page.ArticleList -> throw UnexpectedScreenException("PTT 沒有讓你回文（回到列表了）", p.screen)
                else -> settleFrom(p)
            }
        }
        throw UnexpectedScreenException("按了 ${MAX_STEPS + 2} 次還沒進編輯器", page.screen)
    }

    private fun Page.isReplyForm(): Boolean = this is Page.ReplyForm && step != Page.ReplyForm.Step.OTHER

    private fun Page.isReplyStep(step: Page.ReplyForm.Step): Boolean = this is Page.ReplyForm && this.step == step

    /**
     * 回文的編輯器裡打內文、存檔（[replyPost] 的後半）：插入模式、不自動縮排，打 [PostReply.body]（有引言的話後面空一行）→
     * [PostReply.footer]（沒引言就接在內文後面；有引言就 Ctrl-T 到檔案最後一行、Ctrl-E 到行尾，Enter 一下再打）→ Ctrl-X、s →
     * 引言太多（[Page.QuoteRatioPrompt]）就 e 回編輯器、放棄、把原因丟出來 → 其他的提示照 [afterSave] 答完，回到列表。
     */
    private suspend fun finishReply(editor: Page.Editor, reply: PostReply): Page.ArticleList {
        var page: Page = if (reply.quote) {
            val body = cleanBody(reply.body)
            val typed = typeBody(editorReady(editor), if (body.isNotEmpty()) body + Keys.ENTER + Keys.ENTER else body)
            val footer = cleanBody(reply.footer)
            // 引言在游標下面：到檔案最後一行的行尾（Ctrl-T、Ctrl-E）。引言每一列都帶換行（`do_quote`），
            // 最後一行本來就是空的，Enter 一下就空出一行
            if (footer.isEmpty()) typed else typeBody(typed, Keys.ctrl('T') + Keys.ctrl('E') + Keys.ENTER + footer)
        } else {
            typeBody(editorReady(editor), cleanBody(withFooter(reply.body, reply.footer)))
        }
        page = press(Keys.ctrl('X'), page) { it is Page.SavePrompt }
        // 答了 s 之後 PTT 就可能存了：後面出錯丟 PostUnconfirmedException，App 不給直接重試
        try {
            page = press("s" + Keys.ENTER, page) { it !is Page.SavePrompt }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PostUnconfirmedException(e)
        }
        if (page is Page.QuoteRatioPrompt) {
            // 還沒存（`check_quote` 在寫檔之前）：回編輯器、放棄，PTT 不會留下文章
            val back = press("e" + Keys.ENTER, page) { it is Page.Editor } as Page.Editor
            abortEditor(back)
            throw UnexpectedScreenException("引言太多：PTT 規定引言不能超過八成，沒有發出去。多寫一點，或不要引用原文", page.screen)
        }
        try {
            return afterSave(page, ArrayList(), signature = reply.signature)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PostUnconfirmedException(e)
        }
    }


    /**
     * 把 [board] 加進我的最愛（pttbbs `choose_board_add_favorite`）：走到我的最愛按 `a`，
     * 等「請輸入欲加入的看板名稱」，打板名 Enter。已經在裡面（第一頁看得到）就不按鍵，回 false；加進去之後回 true。
     * 板名要完全對得上（`CompleteBoard` 只收存在的板名，對不上就什麼都不加）：加完列表上看不到那個板就丟例外。
     * 「你的最愛太多了啦 真花心」這種訊息按掉、原因丟出來。
     */
    suspend fun addFavorite(board: String): Boolean = navigate {
        val favorites = goToFavorites()
        if (favorites.boards.any { it.name.equals(board, ignoreCase = true) }) return@navigate false
        val prompt = press("a", favorites) { it is Page.BoardNamePrompt && it.adding }
        val page = press(board + Keys.ENTER, prompt) { it is Page.Favorites || it is Page.PressAnyKey }
        if (page is Page.PressAnyKey) {
            val message = anyKeyMessage(page.screen)
            press(Keys.ENTER, page) { it is Page.Favorites }
            throw UnexpectedScreenException(message.ifEmpty { "PTT 不讓加進我的最愛" }, page.screen)
        }
        val list = page as Page.Favorites
        if (list.boards.none { it.name.equals(board, ignoreCase = true) }) {
            throw UnexpectedScreenException("PTT 沒有把 $board 加進我的最愛（可能沒有這個看板）", list.screen)
        }
        true
    }

    /**
     * 找看板（App 的「搜尋看板」，照 BePTT：英文找板名、中文找標題）。
     * - [query] 是板名的字（英數、底線、減號）：在我的最愛按 `s`，打這幾個字再按空白鍵，PTT 列出所有這樣開頭的板名
     *   （pttbbs `CompleteBoard`，**真機確認**）。一頁列不完時 PTT 最後一格印「...」、再按空白鍵也不翻頁（真機），
     *   結果的 [BoardSearchResult.truncated] 為 true，請使用者多打幾個字；最下面若出現「按空白鍵可列出更多項目」才按空白鍵翻，最多 [MAX_COMPLETION_PAGES] 頁。
     *   列完把打的字用 Backspace 刪掉再 Enter（空的就是取消），回到我的最愛。結果只有板名，沒有分類與標題。
     * - 其他字：按 `y` 列出全部看板，`/` 打關鍵字 Enter，PTT 只留標題有這個關鍵字的板（`board_cmd_search_keyword`），
     *   一頁 20 個，最多翻 [MAX_BOARD_PAGES] 頁；找不到時 PTT 說「沒有任何看板標題有此關鍵字」，按掉。
     *   最後按左鍵取消關鍵字（`board_cmd_quit`：有關鍵字時第一下只清掉關鍵字）、再按 `y` 回到我的最愛。
     * PTT 打字的那一下沒反應（沒有板名以這個字開頭時 PTT 不收那個字）就當成沒有結果。
     */
    suspend fun searchBoards(query: String): BoardSearchResult = navigate {
        val q = query.trim()
        if (q.isEmpty()) return@navigate BoardSearchResult(emptyList())
        if (BOARD_NAME_CHARS.matches(q)) completeBoardNames(q) else BoardSearchResult(boardsByKeyword(q))
    }

    private suspend fun completeBoardNames(prefix: String): BoardSearchResult {
        val favorites = goToFavorites()
        val prompt = press("s", favorites) { it is Page.BoardNamePrompt && !it.adding } as Page.BoardNamePrompt
        val names = LinkedHashSet<String>()
        var truncated = false
        var page: Page.BoardNamePrompt = prompt
        try {
            page = press(prefix + " ", prompt) { it is Page.BoardNamePrompt && it.candidates != null } as Page.BoardNamePrompt
            repeat(MAX_COMPLETION_PAGES) {
                names += page.candidates.orEmpty()
                truncated = page.truncated
                if (!page.more) return@repeat
                page = press(" ", page) { it is Page.BoardNamePrompt && it.candidates != null } as Page.BoardNamePrompt
            }
        } catch (e: UnexpectedScreenException) {
            // 打的字 PTT 不收（沒有板名這樣開頭）、或清單翻不動：下面照樣取消，回空的
            page = (awaitQuiet() as? Page.BoardNamePrompt) ?: throw e
        }
        // 把打的字刪掉（可能被自動補成更長的共同前綴）再 Enter：空的就是取消，回到列表
        press(Keys.BACKSPACE.repeat(page.typed.length) + Keys.ENTER, page) { it is Page.Favorites }
        val boards = names.filter { it.startsWith(prefix, ignoreCase = true) }.map { Board(index = 0, name = it, category = "", title = "") }
        return BoardSearchResult(boards, truncated)
    }

    private suspend fun boardsByKeyword(keyword: String): List<Board> {
        var favorites = goToFavorites()
        val all = press("y", favorites) { it is Page.Favorites && it.allBoards } as Page.Favorites
        val prompt = press("/", all) { it is Page.BoardKeywordPrompt }
        var page = press(clean(keyword) + Keys.ENTER, prompt) { (it is Page.Favorites && it.allBoards) || it is Page.PressAnyKey }
        if (page is Page.PressAnyKey) {
            // 「沒有任何看板標題有此關鍵字」（`board_reload_and_check_empty`，按掉就清掉關鍵字、列回全部）
            val message = anyKeyMessage(page.screen)
            val back = press(Keys.ENTER, page) { it is Page.Favorites }
            press("y", back) { it is Page.Favorites && !it.allBoards }
            if (message.contains("沒有任何看板")) return emptyList()
            throw UnexpectedScreenException(message.ifEmpty { "找看板的畫面跟預期的不一樣" }, page.screen)
        }
        val (boards, list) = readBoardPages(page as Page.Favorites, MAX_BOARD_PAGES)
        // 左鍵清掉關鍵字（列回全部看板）、y 回到我的最愛
        favorites = press(Keys.LEFT, list) { it is Page.Favorites && it.allBoards } as Page.Favorites
        press("y", favorites) { it is Page.Favorites && !it.allBoards }
        return boards.filter { !it.isFolder }.distinctBy { it.name }
    }

    /**
     * 從 [first]（看板列表的某一頁）按 PgDn 一頁一頁讀到最後一頁，最多 [maxPages] 頁。回傳讀到的看板（照序號、不重複）與最後停在的畫面。
     * 這一頁沒填滿（最後一列是空的）就是最後一頁，不按；填滿的話按了之後第一個序號沒有變大
     * （最後一頁：游標移到最後一筆、或繞回第 1 頁，pttbbs `board_cmd_pgdn`）也是最後一頁。
     */
    private suspend fun readBoardPages(first: Page.Favorites, maxPages: Int): Pair<List<Board>, Page.Favorites> {
        val found = LinkedHashMap<Int, Board>()
        var page = first
        var end = first
        for (i in 1..maxPages) {
            page.boards.forEach { found.putIfAbsent(it.index, it) }
            val last = page.boards.lastOrNull()?.index ?: break
            if (!page.full() || i == maxPages) break
            val next = press(Keys.PAGE_DOWN, page) { it is Page.Favorites } as Page.Favorites
            end = next
            if ((next.boards.firstOrNull()?.index ?: 0) <= last) break
            page = next
        }
        return found.values.sortedBy { it.index } to end
    }

    /** 看板列表這一頁第一列的序號（分隔線也有序號，`brdlist_separator`）；讀不出來是 null。 */
    private fun Page.Favorites.firstNumber(): Int? = screen.text(BOARD_LIST_FIRST_ROW, 0, 7).filter(Char::isDigit).toIntOrNull()

    /** 看板列表這一頁填滿了（最後一列不是空的）：後面可能還有。不能數看板：分隔線不算看板。 */
    private fun Page.Favorites.full(): Boolean = screen.lines[screen.rows - 2].isNotBlank()

    /**
     * 推文（pttbbs `comment`）：在 [board] 的 [article] 裡按 `X`，
     * 「您覺得這篇文章 1.值得推薦 2.給它噓聲 3.只加→註解」答 [type] 的號碼（作者本人、或 90 秒內推過的 PTT 不問，直接用 →），
     * 等「推 代號:」打 [text] Enter，「確定[y/N]:」答 y。推完 PTT 回到文章或列表；要看到新推文請再 [reloadArticle]。
     * 不給推（「未達看板發文限制」「本板禁止快速連續推文，請再等 N 秒」…）的訊息按掉、原因丟出來。
     * [text] 太長 PTT 只收前面一段（`maxlength`：78 扣掉標示、日期、時間、代號，有 IP 的板再扣 15），呼叫的人先算好。
     */
    suspend fun push(board: String, article: ArticleRef, type: PushType, text: String, search: ArticleSearch? = null): Unit = navigate {
        val view = goToArticle(board, article, search)
        var page = press("X", view) { it is Page.PushPrompt || it is Page.CommentPrompt || it is Page.PressAnyKey }
        if (page is Page.PressAnyKey) failWith(page, "PTT 不讓推文")
        if (page is Page.PushPrompt) {
            page = press((type.ordinal + 1).toString(), page) { it is Page.CommentPrompt || it is Page.PressAnyKey }
            if (page is Page.PressAnyKey) failWith(page, "PTT 不讓推文")
        }
        page = press(clean(text).trim() + Keys.ENTER, page) { it is Page.ConfirmPrompt || it is Page.PressAnyKey }
        if (page is Page.PressAnyKey) failWith(page, "PTT 不讓推文")
        page = press("y" + Keys.ENTER, page) { it !is Page.ConfirmPrompt }
        if (page is Page.PressAnyKey) failWith(page, "推文沒有送出")
        if (page !is Page.ArticleView && page !is Page.ArticleList) {
            throw UnexpectedScreenException("推文之後停在認不出來的畫面", page.screen)
        }
    }

    /**
     * 刪掉自己的文章（pttbbs `del_post`）：列表上游標移到序號 [index] 的那篇按 `d`，
     * 等第 1 列「請確定刪除(Y/N)?[N]」答 y，列表上那篇的作者變成「-」、標題「(本文已被刪除) [代號]」。
     * 真機答 y 之後列表上面還疊了一個「請按任意鍵繼續」的訊息（列表沒清掉）：按掉它，看那篇是不是真的刪了，刪了就算成功。
     * 不是自己的文章、或這個板不給刪時 PTT 一個字都不回（`DONOTHING`）：等 [SHORT_TIMEOUT_MS] 沒反應就丟例外。
     * [search] 有給的話 [index] 是那次搜尋結果裡的第幾筆（[cursorInBoard]）。
     */
    suspend fun deleteArticle(board: String, index: Int, search: ArticleSearch? = null): Page.ArticleList = navigate {
        val list = cursorInBoard(board, index, search)
        val at = list.cursor ?: index
        val prompt = pressOrNothing("d", list) { it is Page.DeletePrompt || it is Page.PressAnyKey }
            ?: throw UnexpectedScreenException("PTT 沒有反應：只能刪自己的文章，有些板也不給自刪", list.screen)
        if (prompt is Page.PressAnyKey) failWith(prompt, "PTT 不讓刪")
        val after = press("y" + Keys.ENTER, prompt) { it is Page.ArticleList || it is Page.PressAnyKey }
        if (after !is Page.PressAnyKey) return@navigate after as Page.ArticleList
        val message = noticeAboveAnyKey(after.screen)
        val back = press(Keys.ENTER, after) { it is Page.ArticleList } as Page.ArticleList
        if (back.articles.firstOrNull { it.index == at }?.deleted != true) {
            throw UnexpectedScreenException(message.ifEmpty { "文章沒有刪掉" }, after.screen)
        }
        back
    }

    /**
     * 疊在列表上面的「請按任意鍵繼續」（`pressanykey`，列表上半沒清掉）上的訊息：最下面那列上面、最後一列有字的。
     * 真機（刪文）：第 13 列起清掉、第 14 列「正在刪除文章: [測試] …」。（[anyKeyMessage] 拿第 1 列起第一段字，這種畫面拿到的會是列表。）
     */
    private fun noticeAboveAnyKey(screen: Screen): String {
        val bottom = screen.lines.last().trim()
        if (bottom.startsWith("◆")) return anyKeyMessage(screen)
        return screen.lines.dropLast(1).lastOrNull { it.isNotBlank() }?.trim() ?: anyKeyMessage(screen)
    }

    /**
     * 改自己文章的標題（pttbbs `edit_title`）：游標移到那篇按 `T`，第 22 列「標題：舊標題」，
     * Ctrl-Y 把舊的清掉、打 [title] Enter，「確定(Y/N)?[n]」答 y。
     * 要扣稿費的（「修改此標題需扣除全部稿費」）答 n、原因丟出來，不代使用者花錢。不給改的 PTT 沒反應或說「無法修改標題: …」。
     * [search] 有給的話 [index] 是那次搜尋結果裡的第幾筆（[cursorInBoard]）。
     */
    suspend fun editTitle(board: String, index: Int, title: String, search: ArticleSearch? = null): Page.ArticleList = navigate {
        val list = cursorInBoard(board, index, search)
        val prompt = pressOrNothing("T", list) { it is Page.TitlePrompt || it is Page.PressAnyKey }
            ?: throw UnexpectedScreenException("PTT 沒有反應：只能改自己文章的標題", list.screen)
        if (prompt is Page.PressAnyKey) failWith(prompt, "PTT 不讓改標題")
        val confirm = press(Keys.ctrl('Y') + clean(title).trim() + Keys.ENTER, prompt) {
            it is Page.ConfirmPrompt || it is Page.ArticleList || it is Page.PressAnyKey
        }
        when (confirm) {
            // 標題沒變：PTT 不問，直接重畫
            is Page.ArticleList -> return@navigate confirm
            is Page.PressAnyKey -> failWith(confirm, "PTT 不讓改標題")
            is Page.ConfirmPrompt -> if (confirm.text.contains("稿費")) {
                press("n" + Keys.ENTER, confirm) { it !is Page.ConfirmPrompt }
                throw UnexpectedScreenException(confirm.text.substringBefore("，確定").ifEmpty { "改這個標題要扣稿費" }, confirm.screen)
            }
            else -> Unit
        }
        val after = press("y" + Keys.ENTER, confirm) { it is Page.ArticleList || it is Page.PressAnyKey }
        if (after is Page.PressAnyKey) failWith(after, "標題沒有改成")
        after as Page.ArticleList
    }

    /**
     * 改自己文章的內文（pttbbs `edit_post`→`vedit2`）。
     * [oldBody] 是文章現在的本文（[ArticleText.editableBody]：標頭與分隔線之後、「--」簽名檔之前那些列，App 整篇讀完才有），
     * [newBody] 是改好的。做法：列表上游標移到那篇按 `E` 進編輯器（檔案是標頭三列、空一列、本文、「--」、「※ 發信站」…），
     * 在前 23 列裡找到 [oldBody] 第一列是檔案的第幾行（找不到就當第 5 行），游標往下移到那裡，Ctrl-Y 一行一行刪掉舊本文，
     * 確認游標那一列已經是「--」（或「※ 發信站」）才打新本文（每列 Enter），然後 Ctrl-X、s 存檔。
     * 哪一步對不上就 Ctrl-X、a 放棄（PTT 不會動到原文），把原因丟出來。
     * 原文裡的顏色會不見（App 看到的是純文字）；一列超過編輯器的寬度會被折成兩列。本文沒改（[newBody] 跟 [oldBody] 一樣）就不碰它，顏色留著。
     *
     * [replies] 是要插在推文下面的回應（[insertReplies]）：本文弄完之後才插，只插入、不刪任何一列。
     * [search] 有給的話 [index] 是那次搜尋結果裡的第幾筆（[cursorInBoard]）。
     */
    suspend fun editArticle(
        board: String,
        index: Int,
        oldBody: List<String>,
        newBody: String,
        replies: List<PushReply> = emptyList(),
        search: ArticleSearch? = null,
    ): Page.ArticleList = navigate {
        val list = cursorInBoard(board, index, search)
        var editor = pressOrNothing("E", list) { it is Page.Editor || it is Page.PressAnyKey }
            ?: throw UnexpectedScreenException("PTT 沒有反應：只能編輯自己的文章", list.screen)
        if (editor is Page.PressAnyKey) failWith(editor, "PTT 不讓編輯")
        editor = editorReady(editor as Page.Editor)
        if (newBody.lines() != oldBody) editor = replaceBody(editor, oldBody, newBody)
        if (replies.isNotEmpty()) editor = insertReplies(editor, replies)
        var page: Page = press(Keys.ctrl('X'), editor) { it is Page.SavePrompt }
        page = press("s" + Keys.ENTER, page) { it !is Page.SavePrompt }
        val messages = ArrayList<String>()
        page = afterSave(page, messages)
        messages.firstOrNull { it.contains("無法寫入") || it.contains("修改過") }?.let {
            throw UnexpectedScreenException(it, page.screen)
        }
        page
    }

    /** 編輯器裡（游標在第 1 行）把本文 [oldBody] 換成 [newBody]；做完游標停在本文後面那列（「--」）。對不上就放棄、丟例外。 */
    private suspend fun replaceBody(from: Page.Editor, oldBody: List<String>, newBody: String): Page.Editor {
        var editor = from
        val rows = editor.screen.lines.dropLast(1).map { it.trimEnd() }
        val first = oldBody.firstOrNull()?.trimEnd()
        // 本文從檔案第幾行開始：畫面上（檔案前 23 行）跟本文第一列一樣的那列；本文是空的或找不到就看第 4 行是不是空的（標頭後空一行）
        val bodyRow = rows.withIndex().firstOrNull { (row, text) -> row >= HEADER_LINES && first != null && text == first }?.index
            ?: if (rows.getOrNull(HEADER_LINES).isNullOrBlank()) HEADER_LINES + 1 else HEADER_LINES
        if (bodyRow > 0) {
            editor = press(Keys.DOWN.repeat(bodyRow), editor) { it is Page.Editor && it.line == bodyRow + 1 } as Page.Editor
        }
        if (oldBody.isNotEmpty()) {
            editor = press(Keys.ctrl('Y').repeat(oldBody.size), editor) {
                it is Page.Editor && it.line == bodyRow + 1 && it.screen.lines[bodyRow].trimEnd() != rows[bodyRow]
            } as Page.Editor
        }
        // 刪完游標那一列該是本文後面的那列（「--」或「※ 發信站」；沒有簽名檔的話後面全空）：不是的話放棄，不存
        val footer = editor.screen.lines[bodyRow].trimEnd()
        val nothingLeft = editor.screen.lines.drop(bodyRow).dropLast(1).all { it.isBlank() }
        if (!footer.startsWith("--") && !footer.startsWith("※") && !nothingLeft) {
            abortEditor(editor)
            throw UnexpectedScreenException("編輯器裡的內容跟 App 看到的對不上，沒有存檔（游標停在「${footer.take(20)}」）", editor.screen)
        }
        val body = cleanBody(newBody)
        if (body.isNotEmpty()) editor = typeBody(editor, body + Keys.ENTER)
        return editor
    }

    /**
     * 編輯器裡把 [replies] 一則一則插在它的推文下面（pttbbs `edit.c`）。只插入、不刪。
     * 1. Ctrl-T 到檔尾（`edit_cmd_eof`），狀態列的「行」就是檔案總共幾行
     * 2. 由下往上做（先插下面的，上面的行號不會被推走）。每一則：Esc-L（`prompt_goto_line`，第 22 列「跳至第幾行:」）跳到
     *    「總行數－[PushReply.fromEnd]」，游標在行首、那一行在畫面中間（`goto_line`→`edit_window_adjust_middle`）
     * 3. 那則推文該在游標那一列；進編輯器之前有人又推了文、或檔尾多一列空的，它會差幾列：在整個畫面找長得一樣的列
     *    （[editorRowShows]），挑離游標最近的。找不到就放棄
     * 4. 游標移到它的下一行行首（看狀態列的「行:欄」確認），打回應、每列 Enter：回應插在推文與下一列之間。
     *    推文是檔案最後一行（下面沒有行）就到行尾（Ctrl-E）先 Enter 再打
     * 哪一步對不上就 Ctrl-X、a 放棄（PTT 不動原文），把原因丟出來。
     */
    private suspend fun insertReplies(from: Page.Editor, replies: List<PushReply>): Page.Editor {
        var editor = from
        try {
            editor = press(Keys.ctrl('T'), editor) { it is Page.Editor && it.line > from.line } as Page.Editor
            val total = editor.line
            for (reply in replies.sortedBy { it.fromEnd }) {
                val text = cleanBody(reply.text)
                if (text.isBlank()) continue
                val guess = (total - reply.fromEnd).coerceIn(1, total)
                val prompt = press(Keys.ESC + "L", editor) { page -> page.screen.lines.any { it.contains(GOTO_LINE_PROMPT) } }
                editor = press("$guess" + Keys.ENTER, prompt) { page ->
                    page is Page.Editor && page.line == guess && page.screen.lines.none { it.contains(GOTO_LINE_PROMPT) }
                } as Page.Editor
                val cursor = editor.screen.cursorRow
                val row = editor.screen.lines.indices.toList().dropLast(1)
                    .filter { editorRowShows(editor.screen.lines[it], reply.push) }
                    .minByOrNull { abs(it - cursor) }
                    ?: throw UnexpectedScreenException("在編輯器裡找不到「${reply.push.trim().take(20)}」這則推文，沒有存檔", editor.screen)
                val pushLine = guess + row - cursor
                if (pushLine >= total) {
                    // 下面沒有行了：到那一行的行尾，先換行再打
                    val at = editor
                    if (pushLine != at.line) {
                        val move = if (pushLine > at.line) Keys.DOWN.repeat(pushLine - at.line) else Keys.UP.repeat(at.line - pushLine)
                        editor = press(move, at) { it is Page.Editor && it.line == pushLine } as Page.Editor
                    }
                    editor = typeBody(editor, Keys.ctrl('E') + Keys.ENTER + text)
                } else {
                    val at = editor
                    val target = pushLine + 1
                    if (target != at.line) {
                        val move = if (target > at.line) Keys.DOWN.repeat(target - at.line) else Keys.UP.repeat(at.line - target)
                        editor = press(move, at) { it is Page.Editor && it.line == target && it.column == 1 } as Page.Editor
                    }
                    editor = typeBody(editor, text + Keys.ENTER)
                }
            }
            return editor
        } catch (e: UnexpectedScreenException) {
            // 還在編輯器裡：放棄，不存（放棄不了就留給終端畫面）
            runCatching { (awaitQuiet() as? Page.Editor)?.let { abortEditor(it) } }
            throw e
        }
    }

    /** 編輯器開起來是插入模式、不自動縮排（pttbbs 預設）；不是的話切過去（Ctrl-O 插入／取代、Esc-I 自動縮排），不然打出來的字會歪。 */
    private suspend fun editorReady(editor: Page.Editor): Page.Editor {
        var page = editor
        if (!page.insertMode) page = press(Keys.ctrl('O'), page) { it is Page.Editor && it.insertMode } as Page.Editor
        if (page.indentMode) page = press(Keys.ESC + "I", page) { it is Page.Editor && !it.indentMode } as Page.Editor
        return page
    }

    /**
     * 發新文章時編輯器裡已經有東西：那是板主設的發文範本（pttbbs `do_general` 把那個分類的 `postsample` 複製進來），清掉它，
     * 不然會跟著內文一起貼出去。游標在第 1 行，Ctrl-Y（`delete_line`）一次送 [TEMPLATE_ROWS] 個：刪到只剩一行時是把那行清空，多按沒事。
     * 畫面上（最下面的狀態列以外）還有字就再送一輪；[TEMPLATE_ROUNDS] 輪還清不完就 Ctrl-X、a 放棄，丟例外。
     * 只看畫面有沒有字：範本尾巴的空行超出這幾輪刪的範圍會留著。
     * 空的編輯器不是整片空白：檔案結尾之後的每一列畫一個「~」（pttbbs `edit.c` 的 `display_buffer`），那些列也算空的（[editorIsEmpty]）。
     */
    private suspend fun clearTemplate(from: Page.Editor): Page.Editor {
        var editor = from
        repeat(TEMPLATE_ROUNDS) {
            if (editorIsEmpty(editor)) return editor
            send(Keys.ctrl('Y').repeat(TEMPLATE_ROWS))
            val page = awaitQuiet(quietMs = BODY_QUIET_MS, after = editor.screen)
            editor = page as? Page.Editor ?: throw UnexpectedScreenException("清發文範本的時候離開了編輯器", page.screen)
        }
        if (editorIsEmpty(editor)) return editor
        val screen = editor.screen
        runCatching { abortEditor(editor) }.onFailure { if (it is CancellationException) throw it }
        throw UnexpectedScreenException("這個板的發文範本清不掉，沒有發文", screen)
    }

    /** 編輯器裡沒有內容：狀態列以外的每一列不是空白就是只有一個「~」（檔案結尾之後的列）。 */
    private fun editorIsEmpty(editor: Page.Editor): Boolean =
        editor.screen.lines.dropLast(1).all { it.isBlank() || it.trimEnd() == "~" }

    /**
     * 在編輯器裡一次送出 [text]（空的就不送），等編輯器把字畫完：內文長的話回應會分好幾段來，安靜 [BODY_QUIET_MS] 才算畫完。
     * 畫完不是編輯器就丟例外。
     */
    private suspend fun typeBody(editor: Page.Editor, text: String): Page.Editor {
        if (text.isEmpty()) return editor
        send(text)
        val page = awaitQuiet(quietMs = BODY_QUIET_MS, after = editor.screen)
        if (page !is Page.Editor) throw UnexpectedScreenException("打內文的時候離開了編輯器", page.screen)
        return page
    }

    /** 多行的內文換成編輯器的按鍵：每一列拿掉控制字元（[clean]），列之間按 Enter。 */
    private fun cleanBody(body: String): String = body.lines().joinToString(Keys.ENTER) { clean(it) }

    /** 內文後面空一行接 [footer]（空的就不加；內文是空的就只有 [footer]）。 */
    private fun withFooter(body: String, footer: String): String = when {
        footer.isEmpty() -> body
        body.isBlank() -> footer
        else -> body.trimEnd() + "\n\n" + footer
    }

    /** 編輯器裡放棄：Ctrl-X、a（「文章 沒有 存入」），等回到列表。 */
    private suspend fun abortEditor(editor: Page.Editor) {
        val prompt = press(Keys.ctrl('X'), editor) { it is Page.SavePrompt }
        val page = press("a" + Keys.ENTER, prompt) { it !is Page.SavePrompt }
        afterSave(page, ArrayList())
    }

    /**
     * 存檔（或放棄）之後把提示答完、回到文章列表：簽名檔答 [signature]、匿名板的名稱答 r、「確定[y/N]」答 y、任意鍵按 Enter
     * （訊息收進 [messages]）；過場畫面等它變。
     */
    private suspend fun afterSave(from: Page, messages: MutableList<String>, signature: Char = '0'): Page.ArticleList {
        var page = from
        repeat(MAX_STEPS) {
            page = when (val p = page) {
                is Page.ArticleList -> return p
                is Page.SignaturePrompt -> press(signature + Keys.ENTER, p) { it !is Page.SignaturePrompt }
                is Page.AnonymousPrompt -> press("r" + Keys.ENTER, p) { it !is Page.AnonymousPrompt }
                is Page.ConfirmPrompt -> press("y" + Keys.ENTER, p) { it !is Page.ConfirmPrompt }
                is Page.PressAnyKey -> {
                    messages += anyKeyMessage(p.screen)
                    press(Keys.ENTER, p) { it !is Page.PressAnyKey }
                }
                // 過場（「文章 沒有 存入」之類的訊息、pttbbs 存檔前 sleep 一秒、「正在檢查檔案是否被修改過」）：等它變，不亂按
                else -> if (withTimeoutOrNull(NUDGE_MS * 2) { nextScreen(p.screen) } == null) {
                    throw UnexpectedScreenException("存檔之後停在認不出來的畫面", p.screen)
                } else {
                    awaitQuiet()
                }
            }
        }
        throw UnexpectedScreenException("存檔之後按了 $MAX_STEPS 次還沒回到文章列表", page.screen)
    }

    /**
     * 走到 [board] 的整個列表（[search] 有給的話是那次搜尋的結果）、游標移到序號 [index] 的那篇（打序號 Enter；已經在那篇上就不動）。
     * 在文章裡先退回列表。
     */
    private suspend fun cursorOn(board: String, index: Int, search: ArticleSearch? = null): Page.ArticleList {
        (awaitQuiet() as? Page.ArticleView)?.let { press(Keys.LEFT, it) { p -> p is Page.ArticleList } }
        val list = goToList(board, search) ?: throw UnexpectedScreenException(NO_RESULTS, _screens.value)
        if (list.cursor == index) return list
        return press(index.toString() + Keys.ENTER, list) { it is Page.ArticleList && it.cursor == index } as Page.ArticleList
    }

    /**
     * 走到 [board] 的**整個列表**、游標移到要改／刪的那篇。[search] 是 null 的話 [index] 就是看板的序號（[cursorOn]）。
     * 有 [search] 的話 [index] 是搜尋結果裡的第幾筆，而 pttbbs 在搜尋模式不給編輯、改標題、刪文（`edit_post`、`edit_title`、`del_post`
     * 都擋 `MODE_SELECT`），所以：在結果裡游標移到那筆按 `Q` 讀文章代碼（AID）、Enter 關掉，左鍵退出搜尋，
     * 在整個列表按 `#`（pttbbs `read.c` `select_by_aid`，最下面一列「搜尋文章代碼(AID): #」）打代碼 Enter，游標就跳到那篇。
     * 找不到（第 22 列「找不到這個文章代碼(AID)…」加任意鍵）就按掉、丟例外。
     */
    private suspend fun cursorInBoard(board: String, index: Int, search: ArticleSearch?): Page.ArticleList {
        if (search == null) return cursorOn(board, index)
        val results = cursorOn(board, index, search)
        val box = press("Q", results) { it is Page.PressAnyKey && ScreenParser.articleInfo(it.screen) != null }
        val aid = ScreenParser.articleInfo(box.screen)?.aid
        press(Keys.ENTER, box) { it is Page.ArticleList }
        if (aid == null) throw UnexpectedScreenException("讀不到這篇的文章代碼", box.screen)
        val list = goToList(board, null) ?: throw UnexpectedScreenException("回不到 $board 的文章列表", _screens.value)
        val prompt = press("#", list) { it.screen.lines.last().contains(AID_PROMPT) }
        val found = press(aid + Keys.ENTER, prompt) {
            (it is Page.ArticleList && it.isBoard(board) && !it.filtered && !it.screen.lines.last().contains(AID_PROMPT)) ||
                it is Page.PressAnyKey
        }
        if (found is Page.PressAnyKey) {
            press(Keys.ENTER, found) { it is Page.ArticleList }
            throw UnexpectedScreenException("在看板裡找不到這篇，可能已經被刪了", found.screen)
        }
        found as Page.ArticleList
        if (found.cursor == null) throw UnexpectedScreenException("在看板裡找不到這篇", found.screen)
        return found
    }

    /** 送出 [keys]，等畫面變成 [expect] 的樣子；[SHORT_TIMEOUT_MS] 內畫面一點都沒變就回 null（PTT 對不給做的事常常一個字都不回）。 */
    private suspend fun pressOrNothing(keys: String, from: Page, expect: (Page) -> Boolean): Page? {
        send(keys)
        if (withTimeoutOrNull(SHORT_TIMEOUT_MS) { nextScreen(from.screen) } == null) return null
        return awaitPage(predicate = expect)
    }

    /** 「[按任意鍵繼續]」的訊息擋下來：按掉它，把訊息（沒有就 [fallback]）丟出來。 */
    private suspend fun failWith(page: Page.PressAnyKey, fallback: String): Nothing {
        val message = anyKeyMessage(page.screen)
        press(Keys.ENTER, page) { it !is Page.PressAnyKey }
        throw UnexpectedScreenException(message.ifEmpty { fallback }, page.screen)
    }

    /** 走到 [board] 的整個列表按 Ctrl-P，回傳發文的表單；PTT 不給發（任意鍵的訊息）就按掉、把原因丟出來。 */
    private suspend fun openPostForm(board: String): Page.PostForm {
        val list = goToList(board, search = null)!!
        val page = press(Keys.ctrl('P'), list) { it is Page.PostForm || it is Page.PressAnyKey }
        if (page is Page.PressAnyKey) failPost(page)
        return page as Page.PostForm
    }

    /** 發文途中 PTT 用「[按任意鍵繼續]」的訊息擋下來（無法發文、未達發文限制、標題不合）：按掉它，把訊息丟出來。 */
    private suspend fun failPost(page: Page.PressAnyKey): Nothing = failWith(page, "PTT 不讓發文")

    /** 拿掉送給 PTT 會出事的控制字元（Esc 會被當成按鍵、Ctrl 字母是編輯器的指令）；只留 Tab。 */
    private fun clean(text: String): String = text.filterNot { it < ' ' && it != '\t' }

    private suspend fun <T> navigate(block: suspend () -> T): T = withContext(dispatcher) { navigation.withLock { block() } }

    private suspend fun goToFavorites(): Page.Favorites {
        var page = awaitQuiet()
        repeat(MAX_STEPS) {
            page = when (val p = page) {
                // 分類（或即時熱門看板）裡的看板列表：左鍵退回【分類看板】，下一輪再退到主選單。
                // 要先看這個：分類的列表第 1 列也是「[y]只列最愛」（[Page.Favorites.allBoards]），而分類裡按 y 什麼都不做
                // （pttbbs `fav_cmd_yank` 的 `IN_CLASS()`，真機），按了就等到逾時
                // 我的最愛按了 y 列出全部看板（在終端畫面按的，或找看板用 `/` 留下的）：再按一次 y 回到我的最愛
                is Page.Favorites -> when {
                    p.inClass -> press(Keys.LEFT, p) { it !is Page.Unknown }
                    p.allBoards -> press("y", p) { it is Page.Favorites && !it.allBoards }
                    else -> return p
                }
                is Page.MainMenu -> press("f" + Keys.ENTER, p) { it is Page.Favorites && !it.inClass }
                // 找看板打到一半（搜尋被中途取消）：把字刪掉、Enter 取消
                is Page.BoardNamePrompt -> press(Keys.BACKSPACE.repeat(p.typed.length) + Keys.ENTER, p) { it !is Page.BoardNamePrompt }
                is Page.BoardKeywordPrompt -> press(Keys.ENTER, p) { it !is Page.BoardKeywordPrompt }
                // 移動位置問到一半：空的 Enter，PTT 說「輸入範圍有誤!」（任意鍵），下一輪按掉
                is Page.MoveFavoritePrompt -> press(Keys.ENTER, p) { it !is Page.MoveFavoritePrompt }
                // 刪除問到一半（「你確定刪除嗎? [N/y]」）：答 n，什麼都不刪。其他的確認不知道答了會怎樣，照樣交給使用者
                is Page.ConfirmPrompt -> if (p.text.startsWith("你確定刪除嗎")) {
                    press("n" + Keys.ENTER, p) { it !is Page.ConfirmPrompt }
                } else {
                    throw UnexpectedScreenException("認不出現在的畫面，走不到我的最愛", p.screen)
                }
                // 停在搜尋結果的話左鍵先退回整個列表，再按一次才離開看板：迴圈會多走一輪
                is Page.ArticleList, is Page.ArticleView -> press(Keys.LEFT, p) { it !is Page.Unknown }
                is Page.PressAnyKey -> press(Keys.ENTER, p) { it !is Page.Unknown }
                // 分組討論區、信箱：左鍵一層一層退回主選單
                is Page.ClassRoot, is Page.MailMenu, is Page.MailList, is Page.TalkMenu -> press(Keys.LEFT, p) { it !is Page.Unknown }
                // 認不出來的畫面不知道按了會怎樣：交給使用者在終端畫面處理
                else -> throw UnexpectedScreenException("認不出現在的畫面，走不到我的最愛", p.screen)
            }
        }
        throw UnexpectedScreenException("按了 $MAX_STEPS 次還沒到我的最愛", page.screen)
    }

    /**
     * 走到 [board] 的列表：[search] 是 null 要的是整個列表，不然是那次搜尋的結果。
     * 停在搜尋結果而那不是要的（要整個列表，或是別的搜尋）就按左鍵退出來（pttbbs `read_cmd_quit`：第一下只離開搜尋）；
     * 要搜尋而現在是整個列表就搜一次。找不到回 null（這時終端已經按掉訊息、回到整個列表）。
     *
     * 哪個搜尋還留在終端上只記在 [currentSearch]（畫面上看不出搜的是什麼）：看到整個列表就清掉。
     * 使用者在終端畫面自己搜了別的東西，這裡會以為還是原來那次；下一次走到整個列表就對回來了。
     */
    private suspend fun goToList(board: String, search: ArticleSearch?, from: Page? = null): Page.ArticleList? {
        var list = goToBoard(board, from)
        if (list.filtered && (search == null || search != currentSearch)) {
            list = press(Keys.LEFT, list) { it is Page.ArticleList && it.isBoard(board) && !it.filtered } as Page.ArticleList
        }
        if (!list.filtered) currentSearch = null
        if (search == null || list.filtered) return list
        return runSearch(board, search, list)
    }

    /** 在整個列表 [from] 上搜一次：按鍵、等提示、打關鍵字 Enter。回傳結果；找不到按掉訊息、回 null；別的訊息（搜尋服務異常之類）丟出來。 */
    private suspend fun runSearch(board: String, search: ArticleSearch, from: Page.ArticleList): Page.ArticleList? {
        val prompt = press(search.kind.key, from) { it.screen.lines.last().contains(search.kind.prompt) }
        val result = press(search.query + Keys.ENTER, prompt) {
            (it is Page.ArticleList && it.isBoard(board) && it.filtered) || it is Page.PressAnyKey
        }
        if (result is Page.PressAnyKey) {
            // 「 ◆ 找不到符合條件的文章。 [按任意鍵繼續]」（`vshowmsg`，真機確認）：按掉它就回到整個列表
            val message = anyKeyMessage(result.screen)
            press(Keys.ENTER, result) { it is Page.ArticleList && it.isBoard(board) && !it.filtered }
            if (message.contains(NO_RESULTS)) return null
            throw UnexpectedScreenException(message.ifEmpty { "搜尋的畫面跟預期的不一樣" }, result.screen)
        }
        currentSearch = search
        return result as Page.ArticleList
    }

    /**
     * 走到 [name] 的文章列表。[from] 是呼叫的人剛剛等到的安靜畫面（有的話就從它出發，不再等一次）。
     *
     * 進板的鍵**一次送出去**（[enterBoard]，BePTT 也是一次送整串）：PTT 照順序吃先打的字（pttbbs 只有搜太頻繁的 `wait_penalty`
     * 會清掉先打的字），中間的畫面不畫，一次來回就到，不用每一步都等畫面、等安靜。照現在停的地方挑一種送法：
     * - 別的看板（文章列表）：看板裡的 `s`（「選擇看板」）板名 Enter，直接換過去
     * - 看板列表（我的最愛、分類、即時熱門）的這頁上有那個板：`s`（「搜尋全站看板」）板名 Enter 把游標移過去，再 Enter 進去
     *   （之後左鍵退回這個列表）。pttbbs `board_cmd_search_global`：板在列表裡（整份，不只這頁）只移游標，**不在列表裡就直接進板**，
     *   這時多的那個 Enter 會在板裡開文章，所以只有這頁看得到那個板才這樣送
     * - 其他（這頁看不到那個板的看板列表、【分類看板】、主選單）：左鍵退回主選單，用主選單的 `s`（`menu_cmd_select_board` →
     *   `do_select`：找全站、直接進去，之後左鍵退回主選單）。左鍵一律送 [MAX_LIST_DEPTH] 個：主選單上多的左鍵只把游標移到
     *   「(G)oodbye」（`menu_cmd_left` 的 `default_exit`），不會問要不要離開（BePTT 從哪裡都先送十幾個左鍵）
     *
     * 進不去的板（打錯、隱板、沒有權限）pttbbs 什麼都不說、留在原地（`do_select`、`select_board_to_read`）：
     * 送過之後又回到主選單或原來的看板就丟 [UnexpectedScreenException]，不再試。
     * 進板畫面比一頁長、文章頁、信箱、聊天區照舊一步一步走。
     */
    private suspend fun goToBoard(name: String, from: Page? = null): Page.ArticleList {
        var page = from ?: awaitQuiet()
        var tries = 0
        var message = ""
        repeat(MAX_STEPS) {
            page = when (val p = page) {
                is Page.ArticleList -> when {
                    p.isBoard(name) -> return p
                    tries > 0 -> cannotEnter(name, message, p)
                    else -> enterBoard("s" + name + Keys.ENTER, p).also { tries++ }
                }
                is Page.Favorites -> when {
                    tries >= MAX_ENTER_TRIES -> cannotEnter(name, message, p)
                    tries == 0 && p.boards.any { !it.isFolder && it.name.equals(name, ignoreCase = true) } ->
                        enterBoard("s" + name + Keys.ENTER + Keys.ENTER, p).also { tries++ }
                    else -> enterBoard(selectFromMainMenu(name), p).also { tries++ }
                }
                is Page.ClassRoot ->
                    if (tries >= MAX_ENTER_TRIES) cannotEnter(name, message, p) else enterBoard(selectFromMainMenu(name), p).also { tries++ }
                is Page.MainMenu ->
                    if (tries > 0) cannotEnter(name, message, p) else enterBoard("s" + name + Keys.ENTER, p).also { tries++ }
                is Page.ArticleView -> press(Keys.LEFT, p) { it !is Page.Unknown }
                // 比一頁長的進板畫面（兩個 End 都被它吃掉）、進板時的訊息（pttbbs `say_enter_failed`：「您沒有進入此看板的權限！」）
                is Page.PressAnyKey -> {
                    if (p.screen.lines.last().trim().startsWith("◆")) message = anyKeyMessage(p.screen)
                    press(Keys.ENTER, p) { it !is Page.Unknown }
                }
                // 信箱、休閒聊天區：左鍵退回主選單，下一輪用主選單的 s
                is Page.MailMenu, is Page.MailList, is Page.TalkMenu -> press(Keys.LEFT, p) { it !is Page.Unknown }
                else -> throw UnexpectedScreenException("認不出現在的畫面，進不了 $name", p.screen)
            }
        }
        throw UnexpectedScreenException("按了 $MAX_STEPS 次還沒進到 $name", page.screen)
    }

    /** 退回主選單（左鍵 [MAX_LIST_DEPTH] 個，多的只移游標）再用主選單的 `s` 找 [name]、Enter 進去。後面接的 End 與 Ctrl-L 由 [enterBoard] 加。 */
    private fun selectFromMainMenu(name: String): String = Keys.LEFT.repeat(MAX_LIST_DEPTH) + "s" + name + Keys.ENTER

    /**
     * 送出進板的 [keys]，後面接 End 兩下與 Ctrl-L，一次送出去，等畫面變了、認得出來。
     * 換到別的板時 PTT 先秀進板畫面（pttbbs `Read()`：`more` 印一頁、`pressanykey`）：第一個 End 按掉它、第二個到列表最後面
     * （最新的文章與置底文）；沒有進板畫面（沒設、或剛剛就在這個板）的話第一個就到最後面，第二個什麼都不做。
     * 結尾的 Ctrl-L 整頁重畫（不算一個鍵，不會被進板畫面吃掉），PTT 一定會回東西：游標已經在最後一筆時 End 一個字都不回。
     * 進板畫面比一頁長的話兩個 End 都被它吃掉（翻到最後、再按掉任意鍵），列表停在 PTT 記得的位置，要最新的那頁時 [goToNewest] 再走過去。
     * 不收找看板的輸入框（[Page.BoardNamePrompt]）：那是還沒吃完先打的字的中間畫面。
     */
    private suspend fun enterBoard(keys: String, from: Page): Page {
        send(keys + Keys.END + Keys.END + Keys.CTRL_L)
        return awaitPage(after = from.screen) { it !is Page.Unknown && it !is Page.BoardNamePrompt }
    }

    /** 送過進板的鍵又回到原地（主選單、原來的看板、看板列表）：PTT 不說原因（[message] 是途中看到的訊息，沒有就是空的），丟出來。 */
    private fun cannotEnter(name: String, message: String, page: Page): Nothing =
        throw UnexpectedScreenException("進不了 $name：" + message.ifEmpty { "找不到這個看板，或沒有權限進去" }, page.screen)

    /**
     * 「按任意鍵繼續」畫面上的訊息：最下面一列「 ◆ 訊息 [按任意鍵繼續]」（pttbbs `vshowmsg`，真機確認）就是那句；
     * 不然是整頁的提示（例如搜太頻繁的「使用限制」，`alert_screen`，照原始碼寫的）：第 2 列起第一段不空的字，最下面「請按任意鍵繼續」。
     * 拿原因給人看，不然只看得到「請按任意鍵繼續」。
     */
    private fun anyKeyMessage(screen: Screen): String {
        val bottom = screen.lines.last().trim()
        return if (bottom.startsWith("◆")) {
            bottom.removePrefix("◆").substringBefore("[按任意鍵繼續]").trim()
        } else {
            screen.lines.drop(1).dropLast(1).map { it.trim() }.dropWhile { it.isEmpty() }.takeWhile { it.isNotEmpty() }.joinToString(" ")
        }
    }

    /** 送出 [keys]，等畫面從 [from] 變了、而且符合 [expect]。 */
    private suspend fun press(keys: String, from: Page, expect: (Page) -> Boolean): Page {
        send(keys)
        return awaitPage(after = from.screen, predicate = expect)
    }

    /**
     * 文章（或信）裡按 [keys]，後面加 Ctrl-L（[Keys.CTRL_L]）：PTT 清掉畫面、由上往下整頁重畫，進度列最後才畫
     * （pttbbs `system_key_hook` → `redrawwin`、pfterm `doupdate` 一列一列由上往下），所以進度列畫到「離開」（[Page.ArticleView.footerDone]）
     * 就是整頁都到了，不用再等安靜 [QUIET_MS]（BePTT 也是看進度列完整就往下）。沒畫到「離開」的照舊等安靜。
     */
    private suspend fun pressPage(keys: String, from: Page, expect: (Page.ArticleView) -> Boolean): Page.ArticleView {
        send(keys + Keys.CTRL_L)
        return awaitPageWith(DEFAULT_TIMEOUT_MS, ::pageQuiet, from.screen) { it is Page.ArticleView && expect(it) } as Page.ArticleView
    }

    private fun pageQuiet(page: Page): Long = if (page is Page.ArticleView && page.footerDone) 0L else QUIET_MS

    /**
     * 查詢使用者 [id]（App 點推文的「查詢這個使用者」、看板長按的「查詢作者資料」，BePTT 也有）：走到主選單，`t` Enter 進【休閒聊天區】，
     * 游標移到「查詢網友」那列 Enter（「請輸入使用者代號: 」，pttbbs `t_query`），打代號 Enter；
     * pttbbs `my_query` 從第 1 列起印「《ＩＤ暱稱》…」那幾列、下面是名片檔，最下面「請按任意鍵繼續」。讀完按 Enter、左鍵退回主選單。
     * 沒有這個人回 null（`my_query` 什麼都不印，回到選單）。
     * 在【休閒聊天區】用上下鍵移游標、不按「Q」：pttbbs 新版的選單把 q、Q 當成「回到上一層」（`menu_nav_cmds`）。
     */
    suspend fun queryUser(id: String): UserInfo? = navigate { lookUpUser(id) }

    /** [queryUser] 的那幾步（不另外拿 [navigation] 的鎖：[sendMail] 寄信前也要查一次）。做完停在主選單。 */
    private suspend fun lookUpUser(id: String): UserInfo? {
        val menu = goToMainMenu()
        val talk = press("t" + Keys.ENTER, menu, ::isTalkMenu)
        val prompt = pressMenuItem(talk, "休閒聊天區", "查詢網友") { p -> p.screen.lines.any { it.contains("請輸入使用者代號") } }
        val result = press(clean(id) + Keys.ENTER, prompt) { p ->
            isTalkMenu(p) || p is Page.PressAnyKey || p.screen.lines.any { it.contains("《ＩＤ暱稱》") }
        }
        val info = if (result.screen.lines.any { it.contains("《ＩＤ暱稱》") }) {
            // 印完才等任意鍵：等最下面那列出現提示，資料才齊（名片檔在後面）
            val done = if (result is Page.PressAnyKey) result else awaitPage(after = result.screen) { it is Page.PressAnyKey }
            parseUserInfo(done.screen)
        } else {
            null
        }
        var page = awaitQuiet()
        if (!isTalkMenu(page)) page = press(Keys.ENTER, page, ::isTalkMenu)
        press(Keys.LEFT, page) { it is Page.MainMenu }
        return info
    }

    /**
     * 選單（【休閒聊天區】、【私人信件區】）裡用上下鍵把游標（「>」或「●」）移到有 [item] 的那一項、Enter，等畫面變成 [expect]。
     * 不按快捷鍵：站方的選單可能改（新版 q 是回上一層；私人信件區有兩項是 S 開頭）。[menuName] 只用在找不到時的訊息。
     */
    private suspend fun pressMenuItem(menu: Page, menuName: String, item: String, expect: (Page) -> Boolean): Page {
        val lines = menu.screen.lines
        val target = lines.indexOfFirst { it.contains(item) }
        val cursor = lines.indexOfFirst { MENU_CURSOR.containsMatchIn(it) }
        if (target < 0 || cursor < 0) throw UnexpectedScreenException("${menuName}裡找不到「$item」", menu.screen)
        val move = if (target >= cursor) Keys.DOWN.repeat(target - cursor) else Keys.UP.repeat(cursor - target)
        return press(move + Keys.ENTER, menu, expect)
    }

    /** 【休閒聊天區】：標題認得出來，或畫面上有「(Q)uery … 查詢網友」那一項（標題的字跟原始碼推的不一樣時也走得下去）。 */
    private fun isTalkMenu(page: Page): Boolean =
        page is Page.TalkMenu || page.screen.lines.any { it.contains("uery") && it.contains("查詢網友") }

    /** `my_query` 的畫面：第 1 列起每個「《標籤》值」照抄，「《ＩＤ暱稱》id (暱稱)」拆開；最後一個有《》的列之後、最下面那列之前不空的是名片檔。 */
    private fun parseUserInfo(screen: Screen): UserInfo? {
        val rows = screen.lines.drop(1).dropLast(1)
        val fields = ArrayList<Pair<String, String>>()
        var lastField = -1
        rows.forEachIndexed { index, line ->
            val found = USER_FIELD.findAll(line).map { it.groupValues[1].trim() to it.groupValues[2].trim() }.toList()
            if (found.isNotEmpty()) {
                fields += found
                lastField = index
            }
        }
        val idField = fields.firstOrNull { it.first == "ＩＤ暱稱" } ?: return null
        val idMatch = USER_ID.matchEntire(idField.second)
        val id = idMatch?.groupValues?.get(1) ?: idField.second.substringBefore(' ')
        val nickname = idMatch?.groupValues?.get(2)?.ifBlank { null }
        val plan = rows.drop(lastField + 1).map { it.trimEnd() }.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        return UserInfo(id, nickname, fields.filter { it !== idField }, plan)
    }

    /** 走到主選單：看板、文章、我的最愛、分類、信箱、休閒聊天區按左鍵一層一層退出來。 */
    private suspend fun goToMainMenu(): Page.MainMenu {
        var page = awaitQuiet()
        repeat(MAX_STEPS + 2) {
            page = when (val p = page) {
                is Page.MainMenu -> return p
                is Page.BoardNamePrompt -> press(Keys.BACKSPACE.repeat(p.typed.length) + Keys.ENTER, p) { it !is Page.BoardNamePrompt }
                is Page.BoardKeywordPrompt -> press(Keys.ENTER, p) { it !is Page.BoardKeywordPrompt }
                is Page.MoveFavoritePrompt -> press(Keys.ENTER, p) { it !is Page.MoveFavoritePrompt }
                is Page.PressAnyKey -> press(Keys.ENTER, p) { it !is Page.Unknown }
                is Page.Favorites, is Page.ArticleList, is Page.ArticleView, is Page.ClassRoot, is Page.MailMenu, is Page.MailList, is Page.TalkMenu ->
                    press(Keys.LEFT, p) { it !is Page.Unknown }
                else -> throw UnexpectedScreenException("認不出現在的畫面，走不到主選單", p.screen)
            }
        }
        throw UnexpectedScreenException("按了 ${MAX_STEPS + 2} 次還沒到主選單", page.screen)
    }

    fun close() {
        reader?.cancel()
        transport.close()
        _closed.value = true
        _screens.value = terminal.snapshot()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val LOGIN_TIMEOUT_MS = 45_000L

        /** 畫面幾毫秒沒變就當它畫完了。 */
        const val QUIET_MS = 150L

        /** 發文打內文時等編輯器畫完：內文長的話回應分好幾段來，安靜久一點才算。 */
        const val BODY_QUIET_MS = 600L

        /** 清發文範本：一輪送幾個 Ctrl-Y（編輯器一頁 23 列）、最多幾輪。 */
        const val TEMPLATE_ROWS = 23
        const val TEMPLATE_ROUNDS = 20

        /** 登入途中停在認不出來的畫面多久，才按 Enter 推它一下。 */
        const val NUDGE_MS = 1_500L

        /** 走到某一頁最多按幾次鍵（每次都要看到畫面變了、認得出來）。 */
        const val MAX_STEPS = 6

        /**
         * 看板列表最多疊幾層（主選單 →【分類看板】→ 分類 → 子分類…）：從看板列表退回主選單一律送這麼多個左鍵，
         * 多的在主選單上只移游標（pttbbs `menu_cmd_left`）。
         */
        const val MAX_LIST_DEPTH = 4

        /** 進一個板最多一次送出幾次（[goToBoard]）：在列表裡進不去再從主選單試一次。 */
        const val MAX_ENTER_TRIES = 2

        /** 刪文、改標題、編輯這些 PTT 不給做時一個字都不回的操作：等多久沒反應就算了。 */
        const val SHORT_TIMEOUT_MS = 4_000L

        /** 找板名的候選清單最多翻幾頁（一頁一百多個）。 */
        const val MAX_COMPLETION_PAGES = 4

        /** 中文關鍵字找看板最多翻幾頁（一頁 [BOARDS_PER_PAGE] 個）。 */
        const val MAX_BOARD_PAGES = 5
        /** 我的最愛最多翻幾頁（一頁 [BOARDS_PER_PAGE] 個）。 */
        const val MAX_FAVORITE_PAGES = 30
        /** 【分類看板】根目錄一頁 16 項（第 7 列起），線上三十幾項 */
        const val MAX_CLASS_PAGES = 4
        /** 信箱從最新的往前最多讀 10 頁（200 封） */
        const val MAX_MAIL_PAGES = 10
        /** 看板列表的第一列（上面是標題列、說明列、欄名列，pttbbs `choose_board` 的 `header_lines = 3`）。 */
        const val BOARD_LIST_FIRST_ROW = 3
        const val BOARDS_PER_PAGE = 20

        /** 「複製全文」最多翻幾頁（一頁 22 行，500 頁是一萬多行，幾千則推文的爆文也夠）。 */
        const val MAX_ARTICLE_PAGES = 500

        /** 文章一頁（第 2 頁起）有幾行：23 列全是文章（「第 22~44 行」）；PgDn 從一頁的最後一行接下去。 */
        const val PAGE_LINES = 22

        /** 選單的游標：「> (C)lass」或「●(C)lass」（主選單真機是「>」）。 */
        private val MENU_CURSOR = Regex("""^\s*(>|●)\s*\(""")

        /** `my_query` 的一個欄位「《標籤》值」，值到下一個「《」或列尾。 */
        private val USER_FIELD = Regex("""《([^》]+)》([^《]*)""")

        /** 「《ＩＤ暱稱》id (暱稱)」的值。 */
        private val USER_ID = Regex("""(\S+)\s*\((.*)\)\s*""")

        /** 文章檔案開頭的標頭：作者、標題、時間三行（`write_header`）。 */
        const val HEADER_LINES = 3

        /** 編輯器 Esc-L 的提示（pttbbs `prompt_goto_line`）。 */
        const val GOTO_LINE_PROMPT = "跳至第幾行"

        /** 整個列表按 `#` 之後最下面一列的提示（pttbbs `select_by_aid`：「搜尋文章代碼(AID): #」）。 */
        const val AID_PROMPT = "搜尋文章代碼"

        /** 板名只會是這些字（pttbbs `is_validboardname`）：這樣的查詢用板名補完找，其他的用中文關鍵字找。 */
        val BOARD_NAME_CHARS = Regex("""[A-Za-z0-9_\-]+""")

        const val BAD_CREDENTIALS = "密碼不對或無此帳號"

        /** pttbbs `select_read` 找不到時的訊息。 */
        const val NO_RESULTS = "找不到符合條件的文章"
    }
}
