package com.twtrubiks.quietptt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.twtrubiks.quietptt.core.ptt.ArticleRef
import com.twtrubiks.quietptt.core.ptt.ArticleSearch
import com.twtrubiks.quietptt.core.ptt.ArticleSummary
import com.twtrubiks.quietptt.core.ptt.MailSummary
import com.twtrubiks.quietptt.core.ptt.Page
import com.twtrubiks.quietptt.core.ptt.ScreenParser
import com.twtrubiks.quietptt.core.ptt.SearchKind
import com.twtrubiks.quietptt.core.ptt.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 登入後的畫面。首頁是我的最愛，搜尋看板、看板、搜尋結果、文章、發文、回文、編輯、讀信、寫信從它往裡走；終端畫面是認不出來時的退路。設定不在 stack 裡，是蓋在上面的一頁。 */
sealed interface Route {
    val key: String

    data object Favorites : Route {
        override val key = "favorites"
    }

    data class Board(val name: String) : Route {
        override val key get() = "board-$name"
    }

    /** 在 [board] 裡搜尋的結果。 */
    data class Search(val board: String, val search: ArticleSearch) : Route {
        override val key get() = "search-$board-${search.kind}-${search.query}"
    }

    /** [board] 裡的 [article]（序號或置底文）。從搜尋結果點進來的帶著 [search]（序號是結果裡的第幾筆）。 */
    /** 文章。[preview] 是列表上那一列（標題、作者、日期）：文章還沒讀到之前先拿它畫標題卡，不算在 [key] 裡。 */
    data class Article(val board: String, val article: ArticleRef, val search: ArticleSearch? = null, val preview: ArticleSummary? = null) : Route {
        override val key get() = "article-$board-${article.saved()}-${search?.kind}-${search?.query}"
    }

    /** 在 [board] 發文。 */
    data class Post(val board: String) : Route {
        override val key get() = "post-$board"
    }

    /**
     * 回 [board] 的第 [index] 篇到看板（[search] 有給的話是那次搜尋結果裡的第幾筆）。[title]、[author] 是原文的（標頭的，預填「Re: 標題」用）；
     * [original] 是列表上那一列（送出前對一次，看板變了就不回），從看板點進來才有。
     */
    data class ReplyPost(
        val board: String,
        val index: Int,
        val search: ArticleSearch?,
        val title: String,
        val author: String,
        val original: ArticleSummary? = null,
    ) : Route {
        override val key get() = "reply-post-$board-$index-${search?.kind}-${search?.query}"
    }

    /** 編輯自己在 [board] 的第 [index] 篇（[search] 有給的話是那次搜尋結果裡的第幾筆）。 */
    data class Edit(val board: String, val index: Int, val search: ArticleSearch? = null) : Route {
        override val key get() = "edit-$board-$index-${search?.kind}-${search?.query}"
    }

    /** 分組討論區裡的一個分類：[path] 是從【分類看板】一層一層點進來的序號，[title] 是分類名稱。 */
    data class ClassBoards(val path: List<Int>, val title: String) : Route {
        override val key get() = "class-${path.joinToString("/")}"
    }

    /** 信箱裡的第 [index] 封；[preview] 是列表上那一列（先畫標題卡用），不算在 [key] 裡。 */
    data class Mail(val index: Int, val preview: MailSummary? = null) : Route {
        override val key get() = "mail-$index"
    }

    /**
     * 寫信：[reply] 是要回的那一封（信箱列表上那一列，序號是 [MailSummary.index]）；null 是寫新的一封。
     * 寫新的一封時 [to]、[title] 是預填的收件人與標題（文章頁的「寄信給作者」），都可以改。
     */
    data class WriteMail(val reply: MailSummary? = null, val to: String? = null, val title: String? = null) : Route {
        override val key get() = "write-mail-" + (reply?.index?.toString() ?: to?.let { "to-$it" } ?: "new")
    }

    /** 搜尋看板（從我的最愛進來）。 */
    data object BoardSearch : Route {
        override val key = "board-search"
    }

    data object Terminal : Route {
        override val key = "terminal"
    }
}

/** 轉螢幕、切到背景再回來時，走到哪一頁要留著。搜尋的關鍵字放最後一欄（裡面可能有「|」）。 */
private val StackSaver = listSaver<List<Route>, String>(
    save = { stack ->
        stack.map {
            when (it) {
                Route.Favorites -> "favorites"
                is Route.Board -> "board|${it.name}"
                is Route.Search -> "search|${it.board}|${it.search.kind.name}|${it.search.query}"
                // 預覽接在最後面、用 tab 隔開（終端上的字不會有 tab）；前面那段跟沒有預覽時一模一樣
                is Route.Article -> "article|${it.board}|${it.article.saved()}" +
                    (it.search?.let { s -> "|${s.kind.name}|${s.query}" } ?: "") +
                    (it.preview?.let { p -> "\t${p.title}\t${p.author}\t${p.date}" } ?: "")
                is Route.Post -> "post|${it.board}"
                // 跟文章一樣：搜尋放最後一欄；原文的標題、作者，再來是列表那一列，用 tab 隔開
                is Route.ReplyPost -> "reply-post|${it.board}|${it.index}" +
                    (it.search?.let { s -> "|${s.kind.name}|${s.query}" } ?: "") +
                    "\t${it.title}\t${it.author}" +
                    (it.original?.let { p -> "\t${p.title}\t${p.author}\t${p.date}" } ?: "")
                is Route.Edit -> "edit|${it.board}|${it.index}" + (it.search?.let { s -> "|${s.kind.name}|${s.query}" } ?: "")
                // 分類名稱放最後一欄（裡面可能有「|」）
                is Route.ClassBoards -> "class|${it.path.joinToString("/")}|${it.title}"
                is Route.Mail -> "mail|${it.index}" + (it.preview?.let { m -> "\t${m.state}\t${m.date}\t${m.author}\t${m.title}" } ?: "")
                // 回信：那一封接在 tab 後面；寄信給作者：「|收件人」，預填的標題接在 tab 後面
                is Route.WriteMail -> "write-mail" +
                    (it.reply?.let { m -> "\t${m.index}\t${m.state}\t${m.date}\t${m.author}\t${m.title}" } ?: "") +
                    (it.to?.let { to -> "|$to\t${it.title ?: ""}" } ?: "")
                Route.BoardSearch -> "board-search"
                Route.Terminal -> "terminal"
            }
        }
    },
    restore = { saved ->
        saved.map { line ->
            val tabbed = line.split("\t")
            val main = tabbed[0]
            // 關鍵字是最後一欄：文章（與回文、編輯）帶搜尋是 5 欄、搜尋結果是 4 欄，不多切
            val parts = main.split(
                "|",
                limit = if (main.startsWith("article") || main.startsWith("reply-post") || main.startsWith("edit")) 5 else if (main.startsWith("class")) 3 else 4,
            )
            when (parts[0]) {
                "board" -> Route.Board(parts[1])
                "search" -> Route.Search(parts[1], ArticleSearch(SearchKind.valueOf(parts[2]), parts[3]))
                "article" -> Route.Article(
                    parts[1],
                    articleRef(parts[2]),
                    if (parts.size > 4) ArticleSearch(SearchKind.valueOf(parts[3]), parts[4]) else null,
                    if (tabbed.size == 4) ArticleSummary(null, null, null, date = tabbed[3], author = tabbed[2], title = tabbed[1]) else null,
                )
                "post" -> Route.Post(parts[1])
                "reply-post" -> Route.ReplyPost(
                    parts[1],
                    parts[2].toInt(),
                    if (parts.size > 4) ArticleSearch(SearchKind.valueOf(parts[3]), parts[4]) else null,
                    title = tabbed.getOrElse(1) { "" },
                    author = tabbed.getOrElse(2) { "" },
                    original = if (tabbed.size == 6) {
                        ArticleSummary(parts[2].toInt(), null, null, date = tabbed[5], author = tabbed[4], title = tabbed[3])
                    } else {
                        null
                    },
                )
                "edit" -> Route.Edit(
                    parts[1],
                    parts[2].toInt(),
                    if (parts.size > 4) ArticleSearch(SearchKind.valueOf(parts[3]), parts[4]) else null,
                )
                "class" -> Route.ClassBoards(parts[1].split("/").map(String::toInt), parts[2])
                "mail" -> Route.Mail(
                    parts[1].toInt(),
                    if (tabbed.size == 5) MailSummary(parts[1].toInt(), tabbed[1].firstOrNull() ?: ' ', tabbed[2], tabbed[3], tabbed[4]) else null,
                )
                "write-mail" -> if (parts.size > 1) {
                    Route.WriteMail(to = parts[1], title = tabbed.getOrNull(1)?.ifEmpty { null })
                } else {
                    Route.WriteMail(
                        if (tabbed.size == 6) MailSummary(tabbed[1].toInt(), tabbed[2].firstOrNull() ?: ' ', tabbed[3], tabbed[4], tabbed[5]) else null,
                    )
                }
                "board-search" -> Route.BoardSearch
                "terminal" -> Route.Terminal
                else -> Route.Favorites
            }
        }
    },
)

/** 存進 saved state 的寫法：序號是數字，置底文是 `p` 加倒數第幾筆。 */
private fun ArticleRef.saved(): String = when (this) {
    is ArticleRef.Numbered -> index.toString()
    is ArticleRef.Pinned -> "p$fromBottom"
}

private fun articleRef(saved: String): ArticleRef =
    if (saved.startsWith("p")) ArticleRef.Pinned(saved.drop(1).toInt()) else ArticleRef.Numbered(saved.toInt())

/**
 * 整個 App。導覽是 sealed interface 的 [Route] ＋ rememberSaveable 的 stack，不另外引入 Navigation 套件。
 * 沒登入時只有登入頁（與設定）；登入後照 stack 顯示。
 *
 * 往回走只動 stack，不直接送按鍵：回到的那一頁進來時會叫 Session 從現在的畫面走過去（看板按左鍵退回列表之類的），
 * 從終端畫面回來時也一樣對得齊。系統的返回鍵：設定頁先關設定，其他頁 pop，到我的最愛（或登入頁）才交給系統離開。
 * 橫著滑（[SwipeBackBox]，方向照設定）也是 pop。
 *
 * 新信：PTT 的標題列從沒寫變成寫了「你有新信件」（[Session.newMail]，同一批信只提醒一次：[Connection.mailAlert]），
 * 最下面跳一個 Snackbar，「看信」回首頁、切到私人信件分頁（[openMail]）。已經在看信（私人信件分頁、讀信、寫信）就不跳；
 * 正在發文、回文、編輯時不給「看信」（會丟掉打到一半的字）。PTT 不寫了（信讀完）就收掉。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun QuietPttApp() {
    val container = LocalContext.current.container
    val state by container.connection.state.collectAsState()
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf<Route>(Route.Favorites)) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // 首頁帳號選單的「切換帳號」：登出之後登入頁直接開帳號列表；下一次登入好了（換了連線）就清掉
    var switching by rememberSaveable { mutableStateOf(false) }
    // stack 是哪一條連線的。手動重新登入（新的 Session）就從我的最愛開始；轉螢幕是同一條連線，stack 留著；
    // 背景斷線後的自動重登（Online.resumed）stack 也留著：每一頁進來會叫 Session 從現在的畫面走過去，所以回得到剛剛的看板或文章
    var stackOwner by rememberSaveable { mutableIntStateOf(0) }
    val holder = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    // 「看信」：首頁切到私人信件分頁，切好（HomeScreen 叫 onMailTabOpened）就清掉
    var openMailTab by rememberSaveable { mutableStateOf(false) }
    // 首頁停在哪個分頁（停在私人信件就不跳「你有新信件」）；HomeScreen 每次停下來都會再告訴一次，不用存
    var homeTab by remember { mutableStateOf(HomeTab.FAVORITES) }

    fun push(route: Route) {
        stack = stack + route
    }
    fun pop() {
        val top = stack.last()
        stack = stack.dropLast(1).ifEmpty { listOf(Route.Favorites) }
        holder.removeState(top.key)
    }
    /** 最上面那頁換成 [route]（在搜尋結果裡再搜一次：不疊一層）。 */
    fun replace(route: Route) {
        val top = stack.last()
        stack = stack.dropLast(1) + route
        holder.removeState(top.key)
    }
    /**
     * 文章頁的「尋找同作者文章」：文章這層換成搜尋結果 [route]。文章底下已經是搜尋結果的話一起換掉
     * （跟在結果頁再搜一次一樣不疊），返回鍵回看板。
     */
    fun searchFromArticle(route: Route.Search) {
        val removed = stack.takeLast(if (stack.getOrNull(stack.size - 2) is Route.Search) 2 else 1)
        stack = stack.dropLast(removed.size) + route
        removed.forEach { holder.removeState(it.key) }
    }
    /** 「你有新信件」的「看信」：回首頁（stack 只留首頁），切到私人信件分頁。 */
    fun openMail() {
        stack.drop(1).forEach { holder.removeState(it.key) }
        stack = listOf(Route.Favorites)
        openMailTab = true
    }

    BackHandler(enabled = !settingsOpen && state is ConnectionState.Online && stack.size > 1) { pop() }
    // 後宣告的先處理：設定頁開著時，返回鍵先關設定
    BackHandler(enabled = settingsOpen) { settingsOpen = false }

    QuietPttTheme(container.theme.value) {
        Surface(
            Modifier
                .fillMaxSize()
                // 讓 UiAutomator 用 By.res(...) 找得到 testTag
                .semantics { testTagsAsResourceId = true },
        ) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                when {
                    settingsOpen -> SettingsScreen(
                        account = (state as? ConnectionState.Online)?.account,
                        onBack = { settingsOpen = false },
                        onLogout = {
                            container.connection.logout()
                            settingsOpen = false
                        },
                    )
                    state is ConnectionState.Online -> {
                        val online = state as ConnectionState.Online
                        val session = online.session
                        val owner = System.identityHashCode(session)
                        val keepStack = online.resumed
                        if (stackOwner != owner) {
                            SideEffect {
                                stackOwner = owner
                                // 登入好了（新的連線）才清：按了「切換帳號」那一下，state 可能晚一格才變成 Offline
                                switching = false
                                if (!keepStack) stack = listOf(Route.Favorites)
                            }
                        }
                        val page by remember(session) { session.screens.map(ScreenParser::parse).flowOn(Dispatchers.Default) }
                            .collectAsState(initial = session.page)
                        // 記了兩組以上帳號，發文、回文、寫信、推文才標出用哪個帳號（只有一組的不多那一行）
                        val signedAs = remember(session) { online.account.takeIf { container.credentials.count() > 1 } }
                        // 手動換了連線的那一次組合就直接畫我的最愛，不讓舊 stack 的頁面先去按鍵；自動重登的留在原頁
                        val route = if (stackOwner == owner || keepStack) stack.last() else Route.Favorites
                        val shownRoute by rememberUpdatedState(route)
                        // collectLatest：PTT 不寫了（信讀完）就取消正在顯示的 Snackbar
                        LaunchedEffect(session) {
                            session.newMail.collectLatest { newMail ->
                                if (newMail == null || !container.connection.mailAlert(online.account, newMail)) return@collectLatest
                                val at = shownRoute
                                if (at is Route.Mail || at is Route.WriteMail || (at == Route.Favorites && homeTab == HomeTab.MAIL)) return@collectLatest
                                val editing = at is Route.Post || at is Route.ReplyPost || at is Route.Edit
                                val result = snackbar.showSnackbar(
                                    "你有新信件",
                                    actionLabel = if (editing) null else "看信",
                                    withDismissAction = true,
                                    duration = SnackbarDuration.Long,
                                )
                                if (result == SnackbarResult.ActionPerformed) openMail()
                            }
                        }
                        // 橫著滑回上一頁（BePTT 的滑動退出）：首頁沒得退；終端畫面自己要橫向捲，不包
                        SwipeBackBox(
                            mode = container.swipeBack.value,
                            enabled = stack.size > 1 && route != Route.Terminal,
                            onBack = ::pop,
                        ) {
                            holder.SaveableStateProvider(route.key) {
                                OnlineScreens(
                                    session = session,
                                    account = online.account,
                                    signedAs = signedAs,
                                    page = page,
                                    route = route,
                                    onPush = ::push,
                                    onReplace = ::replace,
                                    onSearchFromArticle = ::searchFromArticle,
                                    onPop = ::pop,
                                    onSettings = { settingsOpen = true },
                                    onSwitchAccount = {
                                        switching = true
                                        container.connection.logout()
                                    },
                                    onLogout = container.connection::logout,
                                    openMailTab = openMailTab,
                                    onMailTabOpened = { openMailTab = false },
                                    onTabSettled = { homeTab = it },
                                )
                            }
                        }
                    }
                    else -> {
                        // 解密只在登入頁出現時做一次，不是每次重組都做；忘掉一組之後重讀
                        var savedAccounts by remember { mutableStateOf(container.credentials.loadAll()) }
                        LoginScreen(
                            state = state,
                            savedAccounts = savedAccounts,
                            lastLogin = container.credentials::lastLogin,
                            onForget = { name ->
                                container.credentials.remove(name)
                                savedAccounts = container.credentials.loadAll()
                            },
                            rememberPassword = container.rememberPassword.value,
                            onRememberChange = container.rememberPassword::set,
                            onLogin = { account, password ->
                                container.scope.launch {
                                    container.connection.login(account, password, remember = container.rememberPassword.value)
                                }
                            },
                            onKickOthers = container.connection::retryKickingOthers,
                            onSettings = { settingsOpen = true },
                            startAtList = switching,
                        )
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun OnlineScreens(
    session: Session,
    account: String,
    signedAs: String?,
    page: Page,
    route: Route,
    onPush: (Route) -> Unit,
    onReplace: (Route) -> Unit,
    onSearchFromArticle: (Route.Search) -> Unit,
    onPop: () -> Unit,
    onSettings: () -> Unit,
    onSwitchAccount: () -> Unit,
    onLogout: () -> Unit,
    openMailTab: Boolean,
    onMailTabOpened: () -> Unit,
    onTabSettled: (HomeTab) -> Unit,
) {
    when (route) {
        Route.Favorites -> HomeScreen(
            session = session,
            account = account,
            onOpenBoard = { onPush(Route.Board(it)) },
            onOpenClass = { path, title -> onPush(Route.ClassBoards(path, title)) },
            onOpenMail = { onPush(Route.Mail(it.index, preview = it)) },
            onWriteMail = { onPush(Route.WriteMail()) },
            onSearchBoards = { onPush(Route.BoardSearch) },
            onTerminal = { onPush(Route.Terminal) },
            onSettings = onSettings,
            onSwitchAccount = onSwitchAccount,
            onLogout = onLogout,
            openMailTab = openMailTab,
            onMailTabOpened = onMailTabOpened,
            onTabSettled = onTabSettled,
        )
        is Route.ClassBoards -> ClassBoardsScreen(
            session = session,
            path = route.path,
            title = route.title,
            onOpenBoard = { onPush(Route.Board(it)) },
            onOpenClass = { path, title -> onPush(Route.ClassBoards(path, title)) },
            onTerminal = { onPush(Route.Terminal) },
            onBack = onPop,
        )
        is Route.Mail -> MailScreen(
            session = session,
            index = route.index,
            preview = route.preview,
            onReply = { route.preview?.let { onPush(Route.WriteMail(reply = it)) } },
            onTerminal = { onPush(Route.Terminal) },
            onBack = onPop,
        )
        is Route.WriteMail -> WriteMailScreen(
            session = session,
            reply = route.reply,
            initialTo = route.to,
            initialTitle = route.title,
            signedAs = signedAs,
            onDone = onPop,
            onTerminal = { onPush(Route.Terminal) },
            onBack = onPop,
        )
        Route.BoardSearch -> BoardSearchScreen(
            session = session,
            onOpenBoard = { onPush(Route.Board(it)) },
            onTerminal = { onPush(Route.Terminal) },
            onBack = onPop,
        )
        is Route.Board -> BoardScreen(
            session = session,
            page = page,
            board = route.name,
            account = account,
            onOpenArticle = { ref, row -> onPush(Route.Article(route.name, ref, preview = row)) },
            onEdit = { onPush(Route.Edit(route.name, it)) },
            onSearch = { onPush(Route.Search(route.name, it)) },
            onPost = { onPush(Route.Post(route.name)) },
            onTerminal = { onPush(Route.Terminal) },
            onBack = onPop,
        )
        is Route.Search -> BoardScreen(
            session = session,
            page = page,
            board = route.board,
            search = route.search,
            account = account,
            onOpenArticle = { ref, row -> onPush(Route.Article(route.board, ref, route.search, preview = row)) },
            onEdit = { onPush(Route.Edit(route.board, it, route.search)) },
            onSearch = { onReplace(Route.Search(route.board, it)) },
            onTerminal = { onPush(Route.Terminal) },
            onBack = onPop,
        )
        is Route.Article -> ArticleScreen(
            session = session,
            board = route.board,
            article = route.article,
            search = route.search,
            preview = route.preview,
            account = account,
            onEdit = { (route.article as? ArticleRef.Numbered)?.let { onPush(Route.Edit(route.board, it.index, route.search)) } },
            onReply = { title, author ->
                (route.article as? ArticleRef.Numbered)?.let {
                    onPush(Route.ReplyPost(route.board, it.index, route.search, title, author, original = route.preview))
                }
            },
            onSearch = { onSearchFromArticle(Route.Search(route.board, it)) },
            onMail = { to, title -> onPush(Route.WriteMail(to = to, title = title)) },
            onTerminal = { onPush(Route.Terminal) },
            onBack = onPop,
            signedAs = signedAs,
        )
        is Route.ReplyPost -> ReplyPostScreen(
            session = session,
            board = route.board,
            index = route.index,
            search = route.search,
            title = route.title,
            author = route.author,
            original = route.original,
            onDone = onPop,
            onTerminal = { onPush(Route.Terminal) },
            onBack = onPop,
            signedAs = signedAs,
        )
        is Route.Edit -> EditArticleScreen(
            session = session,
            board = route.board,
            index = route.index,
            search = route.search,
            onDone = onPop,
            onTerminal = { onPush(Route.Terminal) },
            onBack = onPop,
        )
        is Route.Post -> PostScreen(
            session = session,
            board = route.board,
            onDone = onPop,
            onTerminal = { onPush(Route.Terminal) },
            onBack = onPop,
            signedAs = signedAs,
        )
        Route.Terminal -> TerminalScreen(session = session, onBack = onPop)
    }
}
