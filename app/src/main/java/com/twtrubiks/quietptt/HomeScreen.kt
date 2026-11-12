package com.twtrubiks.quietptt

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twtrubiks.quietptt.core.ptt.ArticleSummary
import com.twtrubiks.quietptt.core.ptt.ClassEntry
import com.twtrubiks.quietptt.core.ptt.Board
import com.twtrubiks.quietptt.core.ptt.MailSummary
import com.twtrubiks.quietptt.core.ptt.Popularity
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.ptt.UserInfo
import kotlinx.coroutines.launch

/**
 * 首頁的五個分頁：[label] 是標題列寫的全名，[short] 是最下面那排寫的兩個字，[icon] 是那排的圖示。
 * 前四個照 BePTT 首頁的順序，最後一個「我」照 PttX。
 */
enum class HomeTab(val label: String, val short: String, val icon: ImageVector) {
    FAVORITES("我的最愛", "最愛", Icons.Filled.Favorite),
    CLASSES("分組討論", "分類", Icons.Filled.Menu),
    HOT("即時熱門", "熱門", Icons.Filled.Star),
    MAIL("私人信件", "信件", Icons.Filled.Email),
    ME("我", "我", Icons.Filled.Person),
}

/**
 * 登入後的首頁：最下面一排分頁（[HomeTab]，圖示與兩個字），點分頁或在中間左右滑都能切（[HorizontalPager]）。
 * 只有**停下來的那頁**會叫 Session 走過去（切到哪頁 PTT 就走到哪裡）：滑到一半、點分頁時動畫經過的頁只畫上次列的，不按鍵。
 * 每頁列過的東西留著（pager 收掉的頁也存著 saved state），切回來先列上次的、走到了再換新的。
 * 分頁選到哪個留在 pager 的 saved state（轉螢幕、從看板回來都還在那頁）。
 * 標題是現在那頁的名字（「我」那頁是登入的帳號），右上角只有「搜尋」進搜尋看板頁；終端、設定、切換帳號、登出都在「我」那頁。
 *
 * - 我的最愛：[FavoritesTab]（[Session.allFavorites]）
 * - 分組討論：[ClassesTab]（[Session.showClasses]，最後一項「即時熱門看板」不列，它有自己的分頁），點一個分類進 [ClassBoardsScreen]
 * - 即時熱門：[HotTab]（[Session.hotBoards]：【分類看板】最後一項進去，人氣排名），長按一列加入我的最愛
 * - 私人信件：[MailTab]（[Session.showMails]，最新的在上），點一封進 [MailScreen]；右下角「寫信」進 [WriteMailScreen]。
 *   PTT 說有新信（[Session.newMail]）時分頁圖示右上角一個藍點（[MailTabIcon]）
 * - 我：[MeTab]（[Session.queryUser] 查自己）
 *
 * 「你有新信件」的「看信」（[openMailTab]）：切到私人信件分頁，切好叫 [onMailTabOpened]；切好之前每一頁都不叫 Session 走過去
 * （從看板回來時 pager 停在上次那頁，不先走到那頁再走到信箱）。停下來的是哪一頁告訴 [onTabSettled]（停在私人信件就不跳提醒）。
 */
@Composable
fun HomeScreen(
    session: Session,
    account: String,
    onOpenBoard: (String) -> Unit,
    onOpenClass: (path: List<Int>, title: String) -> Unit,
    onOpenMail: (MailSummary) -> Unit,
    onWriteMail: () -> Unit,
    onSearchBoards: () -> Unit,
    onTerminal: () -> Unit,
    onSettings: () -> Unit,
    onSwitchAccount: () -> Unit,
    onLogout: () -> Unit,
    openMailTab: Boolean = false,
    onMailTabOpened: () -> Unit = {},
    onTabSettled: (HomeTab) -> Unit = {},
) {
    val pager = rememberPagerState { HomeTab.entries.size }
    val scope = rememberCoroutineScope()
    val newMail by session.newMail.collectAsState()
    LaunchedEffect(openMailTab) {
        if (openMailTab) {
            pager.scrollToPage(HomeTab.MAIL.ordinal)
            onMailTabOpened()
        }
    }
    LaunchedEffect(pager.settledPage) { onTabSettled(HomeTab.entries[pager.settledPage]) }
    // 選到哪個分頁跟著手指（滑過一半就換）；叫 Session 走過去只看 settledPage
    val shown = pager.currentPage
    val shownTab = HomeTab.entries[shown]
    Column(Modifier.fillMaxSize()) {
        AppBar(if (shownTab == HomeTab.ME) account else shownTab.label) {
            TextIconButton("搜尋", onClick = onSearchBoards, modifier = Modifier.testTag("board_search_button"), icon = Icons.Filled.Search)
        }
        HorizontalPager(
            state = pager,
            key = { HomeTab.entries[it].name },
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("home_pager"),
        ) { i ->
            // 滑動中、動畫經過的頁不按鍵（不然 PTT 會被拉去旁邊那頁，放手彈回來時兩邊對不上）
            val active = i == pager.settledPage && !openMailTab
            when (HomeTab.entries[i]) {
                HomeTab.FAVORITES -> FavoritesTab(session, account, active, onOpenBoard, onTerminal)
                HomeTab.CLASSES -> ClassesTab(session, active, onOpenClass, onTerminal)
                HomeTab.HOT -> HotTab(session, active, onOpenBoard, onTerminal)
                HomeTab.MAIL -> MailTab(session, active, onOpenMail, onWriteMail, onTerminal)
                HomeTab.ME -> MeTab(session, account, active, onSettings, onTerminal, onSwitchAccount, onLogout)
            }
        }
        NavigationBar {
            HomeTab.entries.forEachIndexed { i, t ->
                NavigationBarItem(
                    selected = i == shown,
                    onClick = { scope.launch { pager.animateScrollToPage(i) } },
                    icon = { if (t == HomeTab.MAIL) MailTabIcon(t.icon, newMail == true) else Icon(t.icon, contentDescription = null) },
                    label = { Text(t.short) },
                    modifier = Modifier.testTag("tab_${t.name.lowercase()}"),
                )
            }
        }
    }
}

/**
 * 「我」：上面是代號（第一個字的圓形頭像）與 PTT 給的資料，下面兩欄的格子：設定、終端畫面、切換帳號（[onSwitchAccount]：登出、登入頁直接到帳號列表）、登出。
 * 資料是 [Session.queryUser] 查自己（PTT 會走到主選單）：第一次停在這頁時查一次，查到的留著（存在 saved state，跟著 [account]），
 * 之後停在這頁不再查；代號右邊的「重新整理」再查一次。查的時候轉圈，查不到說原因與「重試」；格子不用等 PTT，一直都在。
 */
@Composable
private fun MeTab(
    session: Session,
    account: String,
    active: Boolean,
    onSettings: () -> Unit,
    onTerminal: () -> Unit,
    onSwitchAccount: () -> Unit,
    onLogout: () -> Unit,
) {
    var info by rememberSaveable(account, stateSaver = UserInfoSaver) { mutableStateOf<UserInfo?>(null) }
    val navigation = rememberNavigation(session, "me", enabled = active && info == null) {
        info = queryUser(account) ?: throw IllegalStateException("PTT 查不到 $account 的資料")
    }
    val found = info
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("me_tab")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text(account.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(account, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("me_account"))
                found?.nickname?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (found != null) {
                TextIconButton(
                    "重新整理",
                    onClick = {
                        info = null
                        navigation.retry()
                    },
                    modifier = Modifier.testTag("me_refresh"),
                    icon = Icons.Filled.Refresh,
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val error = navigation.error
            when {
                found != null -> for ((label, value) in found.fields) {
                    Row {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(88.dp),
                        )
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                error != null -> {
                    Text("查不到我的資料：${error.lineSequence().first()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = navigation::retry) { Text("重試") }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在查詢我的資料…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MeTile("設定", Icons.Filled.Settings, "settings_button", onSettings, Modifier.weight(1f))
                MeTile("終端畫面", AppIcons.Terminal, "terminal_button", onTerminal, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MeTile("切換帳號", Icons.Filled.Refresh, "switch_account", onSwitchAccount, Modifier.weight(1f))
                MeTile("登出", Icons.Filled.ExitToApp, "account_logout", onLogout, Modifier.weight(1f))
            }
        }
    }
}

/** 「我」那頁的一格：圖示與字，整格可以點。 */
@Composable
private fun MeTile(label: String, icon: ImageVector, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
    }
}

/**
 * 我的最愛。一進來就叫 [Session.allFavorites]（從看板回來會按左鍵退出來）：超過一頁的話 PTT 那邊一頁一頁翻完，列出整份。
 * 列出來的存在 rememberSaveable 裡（跟著 [account]）：從看板回來先列上次的，翻完再換成最新的；更新失敗時上次的照樣列著，最上面一列說原因與「重試」。
 * 長按一列：移到最上面／往上一格／往下一格／移到最下面（[Session.moveFavorite]，新位置是鄰居的序號）、從我的最愛移除
 * （先問一次，[Session.removeFavorite]）；看板還有「設為已讀」（有新文章的）或「設為未讀」（[Session.markFavoriteRead]，整個板）；
 * 做完用 PTT 重讀的整份換掉列表，失敗 toast 說原因。
 * 目錄（資料夾）不做：列成「目錄」、點不進去。
 */
@Composable
private fun FavoritesTab(session: Session, account: String, active: Boolean, onOpenBoard: (String) -> Unit, onTerminal: () -> Unit) {
    var boards by rememberSaveable(account, stateSaver = BoardsSaver) { mutableStateOf<List<Board>?>(null) }
    val navigation = rememberNavigation(session, "favorites", enabled = active) { boards = allFavorites() }
    val container = LocalContext.current.container
    val context = LocalContext.current
    val editing = remember(session, account) { Action() }
    var removing by remember { mutableStateOf<Board?>(null) }
    LaunchedEffect(editing.error) {
        editing.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    fun edit(block: suspend Session.() -> List<Board>) = editing.launch(container) { boards = session.block() }
    removing?.let { board ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("從我的最愛移除") },
            text = { Text("要把 ${board.name} 從我的最愛移除嗎？PTT 會問「你確定刪除嗎」，這裡替你答「是」。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = null
                        edit { removeFavorite(board) }
                    },
                    modifier = Modifier.testTag("remove_favorite_confirm"),
                ) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }
    BoardList(
        boards = boards,
        navigation = navigation,
        loadingText = "正在打開我的最愛…",
        emptyText = "認得是我的最愛，但讀不出看板（可能是空的，或格式跟預期的不一樣）",
        listTag = "favorites_list",
        onOpenBoard = onOpenBoard,
        onOpenFolder = null,
        onTerminal = onTerminal,
        actions = { board ->
            val list = boards.orEmpty()
            val at = list.indexOf(board)
            val busy = editing.active
            val first = list.firstOrNull()
            val last = list.lastOrNull()
            val mark = when {
                board.isFolder -> null
                board.unread -> MenuItem("設為已讀", "fav_mark_read", enabled = !busy) { edit { markFavoriteRead(board, read = true) } }
                else -> MenuItem("設為未讀", "fav_mark_unread", enabled = !busy) { edit { markFavoriteRead(board, read = false) } }
            }
            listOfNotNull(
                mark,
                MenuItem("移到最上面", "fav_top", enabled = !busy && first != null && first != board) { edit { moveFavorite(board, first!!.index) } },
                MenuItem("往上一格", "fav_up", enabled = !busy && at > 0) { edit { moveFavorite(board, list[at - 1].index) } },
                MenuItem("往下一格", "fav_down", enabled = !busy && at in 0 until list.lastIndex) { edit { moveFavorite(board, list[at + 1].index) } },
                MenuItem("移到最下面", "fav_bottom", enabled = !busy && last != null && last != board) { edit { moveFavorite(board, last!!.index) } },
                MenuItem("從我的最愛移除", "fav_remove", enabled = !busy) { removing = board },
            )
        },
    )
}

/**
 * 即時熱門看板（[Session.hotBoards]）：列表跟我的最愛一樣，點了進板。
 * 長按一列「加入我的最愛」（照 BePTT 的「正在加入我的最愛」「已將 X 加入我的最愛」）：[Session.addFavorite] 走到我的最愛按 `a` 打板名，
 * 做完 PTT 停在我的最愛，不走回熱門列表（列表照樣列著；點看板時 [Session.showBoard] 從那裡走過去，切回這頁會再走一次）。
 * 在 App 的 scope 裡跑，中途切走也會做完；toast 說加好了、本來就有、或失敗的原因。
 */
@Composable
private fun HotTab(session: Session, active: Boolean, onOpenBoard: (String) -> Unit, onTerminal: () -> Unit) {
    var boards by rememberSaveable(stateSaver = BoardsSaver) { mutableStateOf<List<Board>?>(null) }
    val navigation = rememberNavigation(session, "hot", enabled = active) { boards = hotBoards() }
    val context = LocalContext.current
    val adding = remember(session) { Action() }
    LaunchedEffect(adding.error) { adding.error?.let { context.toast("加入我的最愛失敗：$it") } }
    fun addFavorite(board: String) = adding.launch(context.container) {
        val added = session.addFavorite(board)
        context.toast(if (added) "已將 $board 加入我的最愛" else "$board 已經在我的最愛裡了")
    }
    BoardList(
        boards = boards,
        navigation = navigation,
        loadingText = "正在打開即時熱門看板…",
        emptyText = "認得是看板列表，但讀不出看板",
        listTag = "hot_list",
        onOpenBoard = onOpenBoard,
        onOpenFolder = null,
        onTerminal = onTerminal,
        actions = { board ->
            listOf(
                MenuItem(
                    if (adding.active) "正在加入我的最愛…" else "加入我的最愛",
                    "add_favorite",
                    enabled = !adding.active && !board.isFolder,
                ) { addFavorite(board.name) },
            )
        },
    )
}

/** 分組討論區的根目錄（[Session.showClasses]）：每個分類一列（名稱、說明），點了進那個分類的看板列表。「即時熱門看板」不列（有自己的分頁）。 */
@Composable
private fun ClassesTab(session: Session, active: Boolean, onOpenClass: (List<Int>, String) -> Unit, onTerminal: () -> Unit) {
    var entries by rememberSaveable(stateSaver = ClassesSaver) { mutableStateOf<List<ClassEntry>?>(null) }
    val navigation = rememberNavigation(session, "classes", enabled = active) { entries = showClasses() }
    val shown = entries
    when {
        shown == null -> WaitingForPage("正在打開分組討論區…", onTerminal, navigation.error, navigation::retry)
        shown.isEmpty() -> WaitingForPage("", onTerminal, error = "認得是分類看板，但讀不出分類", onRetry = navigation::retry)
        else -> LazyColumn(Modifier.fillMaxSize().testTag("classes_list")) {
            navigation.error?.let { error -> item(key = "error") { RefreshFailed(error, onRetry = navigation::retry) } }
            items(shown.filterNot { it.isHot }, key = { it.index }) { entry ->
                ListItem(
                    headlineContent = { NameWithUnreadDot(entry.name, entry.unread) },
                    supportingContent = { if (entry.description.isNotEmpty()) Text(entry.description) },
                    modifier = Modifier.clickable { onOpenClass(listOf(entry.index), entry.name) }.testTag("class_row"),
                )
            }
        }
    }
}

/**
 * 信箱（[Session.showMails]）：最新的在上，一列跟文章列表一樣（[ArticleRow]：未讀「+」當標記），點了讀信。
 * 沒有信顯示「您沒有來信」（PTT 說的）。右下角「寫信」（BePTT、PttX 也是放在信箱）：信箱是空的也有。不刪信。
 */
@Composable
private fun MailTab(session: Session, active: Boolean, onOpenMail: (MailSummary) -> Unit, onWriteMail: () -> Unit, onTerminal: () -> Unit) {
    var mails by rememberSaveable(stateSaver = MailsSaver) { mutableStateOf<List<MailSummary>?>(null) }
    val navigation = rememberNavigation(session, "mail", enabled = active) { mails = showMails() }
    val shown = mails
    Box(Modifier.fillMaxSize()) {
        when {
            shown == null -> WaitingForPage("正在打開信箱…", onTerminal, navigation.error, navigation::retry)
            shown.isEmpty() -> WaitingForPage("", onTerminal, error = "您沒有來信", onRetry = navigation::retry)
            // 最下面留空：最後一封不被「寫信」鈕蓋住
            else -> LazyColumn(Modifier.fillMaxSize().testTag("mail_list"), contentPadding = PaddingValues(bottom = 88.dp)) {
                navigation.error?.let { error -> item(key = "error") { RefreshFailed(error, onRetry = navigation::retry) } }
                items(shown.sortedByDescending { it.index }, key = { it.index }) { mail ->
                    ArticleRow(mail.asArticle(), onClick = { onOpenMail(mail) })
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onWriteMail,
            icon = { Icon(Icons.Filled.Create, contentDescription = null) },
            text = { Text("寫信") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("write_mail_button"),
        )
    }
}

/** 一封信畫成文章列表的一列：未讀「+」放標記那格（顯示成一樣的色條），沒有推文數。 */
fun MailSummary.asArticle(): ArticleSummary =
    ArticleSummary(index = index, mark = state.takeIf { it != ' ' }, pushCount = null, date = date, author = author, title = title)

/**
 * 看板列表（我的最愛、即時熱門、分類裡的看板共用）：[boards] 還沒讀到顯示 [loadingText]，走不到顯示原因與「切到終端畫面」；
 * 認得是列表但一列都讀不出來顯示 [emptyText]。有上次列的就先列著，更新失敗時最上面一列說原因與「重試」。
 * 有新文章的板（PTT 板名前的「ˇ」，[Board.unread]）板名後面一個藍點（[NameWithUnreadDot]），跟分組討論的分類一樣。
 * 一列是板名、下面分類與標題（跟搜尋看板的結果一樣），右邊是人氣（[PopularityText]，照 BePTT 的看板列表）；目錄右邊寫「目錄」。
 * 目錄（[Board.isFolder]）點了叫 [onOpenFolder]（null 就不能點）。[actions] 給了就能長按一列跳選單（我的最愛的移動、刪除；即時熱門的加入我的最愛）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BoardList(
    boards: List<Board>?,
    navigation: Navigation,
    loadingText: String,
    emptyText: String,
    listTag: String,
    onOpenBoard: (String) -> Unit,
    onOpenFolder: ((Board) -> Unit)?,
    onTerminal: () -> Unit,
    actions: ((Board) -> List<MenuItem>)? = null,
) {
    when {
        boards == null -> WaitingForPage(loadingText, onTerminal, navigation.error, navigation::retry)
        boards.isEmpty() -> WaitingForPage("", onTerminal, error = emptyText, onRetry = navigation::retry)
        else -> LazyColumn(Modifier.fillMaxSize().testTag(listTag)) {
            navigation.error?.let { error -> item(key = "error") { RefreshFailed(error, onRetry = navigation::retry) } }
            items(boards, key = { it.index }) { board ->
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    ListItem(
                        headlineContent = { NameWithUnreadDot(board.name, board.unread) },
                        supportingContent = { Text(listOf(board.category, board.title).filter { it.isNotBlank() }.joinToString("  ")) },
                        trailingContent = {
                            when {
                                board.isFolder -> Text("目錄")
                                else -> board.popularity?.let { PopularityText(it) }
                            }
                        },
                        modifier = Modifier
                            .combinedClickable(
                                enabled = !board.isFolder || onOpenFolder != null || actions != null,
                                onClick = { if (board.isFolder) onOpenFolder?.invoke(board) else onOpenBoard(board.name) },
                                // 長按的震動 combinedClickable 自己會做，不用再震
                                onLongClick = if (actions == null) null else ({ menuOpen = true }),
                            )
                            .testTag("board_row"),
                    )
                    // 長按從下面升起的面板，第一行是板名
                    if (actions != null && menuOpen) ActionSheet(board.name, actions(board), onDismiss = { menuOpen = false })
                }
            }
        }
    }
}

/**
 * 看板的人氣（[Popularity]，PTT 看板列表最右邊那欄）：照 PTT 的字（「爆」「HOT」、人數）與顏色。
 * 「爆」是幾千人只有顏色分得出來（1000 人以上白、2000 紅、5000 藍…），顏色跟文章裡的 ANSI 色同一組（[ansiColor]），預設的白字跟著主題的文字色。
 */
@Composable
private fun PopularityText(popularity: Popularity) {
    Text(
        popularity.text,
        color = ansiColor(popularity.fg, popularity.bold) ?: Color.Unspecified,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { contentDescription = "人氣 ${popularity.text}" }.testTag("board_popularity"),
    )
}

/**
 * 看板、分類的名稱；有新文章（PTT 的「ˇ」）後面一個藍點，跟文章列表「+」未讀的色條同一個藍（[UnreadBlue]），
 * TalkBack 唸「有新文章」。名稱太長就換行，藍點不會被擠出去。
 */
@Composable
private fun NameWithUnreadDot(name: String, unread: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.weight(1f, fill = false))
        if (unread) {
            Box(
                Modifier.padding(start = 8.dp).size(8.dp).background(UnreadBlue, CircleShape)
                    .semantics { contentDescription = "有新文章" },
            )
        }
    }
}

/** 「私人信件」分頁的圖示；有新信時右上角一個藍點（[UnreadBlue]），TalkBack 唸「有新信件」。 */
@Composable
private fun MailTabIcon(icon: ImageVector, newMail: Boolean) {
    BadgedBox(
        badge = {
            if (newMail) Badge(containerColor = UnreadBlue, modifier = Modifier.semantics { contentDescription = "有新信件" })
        },
    ) {
        Icon(icon, contentDescription = null)
    }
}

/** 列著上次的，但這次更新失敗：原因（第一行）與「重試」。 */
@Composable
fun RefreshFailed(error: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("favorites_refresh_failed"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "更新失敗：${error.lineSequence().first()}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text("重試") }
    }
}

/** 存進 saved state：一個看板一行，欄位用 Tab 隔開（板名、分類、標題、人氣都不會有 Tab）；沒有人氣的三格是空的。 */
fun encodeBoards(boards: List<Board>): ArrayList<String> =
    boards.mapTo(ArrayList()) { b ->
        val p = b.popularity
        listOf(
            b.index.toString(), b.name, b.category, if (b.unread) "1" else "", if (b.isFolder) "1" else "",
            p?.text.orEmpty(), p?.fg?.toString().orEmpty(), if (p?.bold == true) "1" else "", b.title,
        ).joinToString("\t")
    }

fun decodeBoards(saved: List<String>): List<Board> = saved.map { line ->
    val f = line.split("\t", limit = 9)
    Board(
        index = f[0].toInt(), name = f[1], category = f[2], title = f[8], unread = f[3] == "1", isFolder = f[4] == "1",
        popularity = f[5].takeIf { it.isNotEmpty() }?.let { Popularity(it, fg = f[6].toInt(), bold = f[7] == "1") },
    )
}

val BoardsSaver = Saver<List<Board>?, ArrayList<String>>(
    save = { it?.let(::encodeBoards) },
    restore = { decodeBoards(it) },
)

private val ClassesSaver = Saver<List<ClassEntry>?, ArrayList<String>>(
    save = { list -> list?.mapTo(ArrayList()) { "${it.index}\t${if (it.unread) "1" else ""}\t${it.title}" } },
    restore = { saved ->
        saved.map { line ->
            val f = line.split("\t", limit = 3)
            ClassEntry(f[0].toInt(), f[2], unread = f[1] == "1")
        }
    },
)

private val MailsSaver = Saver<List<MailSummary>?, ArrayList<String>>(
    save = { list -> list?.mapTo(ArrayList()) { "${it.index}\t${it.state}\t${it.date}\t${it.author}\t${it.title}" } },
    restore = { saved ->
        saved.map { line ->
            val f = line.split("\t", limit = 5)
            MailSummary(f[0].toInt(), f[1].firstOrNull() ?: ' ', f[2], f[3], f[4])
        }
    },
)

/** [UserInfo] 存進 saved state：代號、暱稱、幾個欄位，接著每個欄位的標籤與值，最後是名片檔的每一列。 */
private val UserInfoSaver = Saver<UserInfo?, ArrayList<String>>(
    save = { info ->
        info?.let {
            ArrayList<String>().apply {
                add(it.id)
                add(it.nickname.orEmpty())
                add(it.fields.size.toString())
                for ((label, value) in it.fields) {
                    add(label)
                    add(value)
                }
                addAll(it.plan)
            }
        }
    },
    restore = { saved ->
        val count = saved[2].toInt()
        UserInfo(
            id = saved[0],
            nickname = saved[1].takeIf { it.isNotEmpty() },
            fields = (0 until count).map { saved[3 + it * 2] to saved[4 + it * 2] },
            plan = saved.drop(3 + count * 2),
        )
    },
)
