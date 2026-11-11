package com.twtrubiks.quietptt

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import kotlinx.coroutines.launch

/** 首頁的四個分頁（照 BePTT 首頁的順序：我的最愛、分組討論、即時熱門、私人信件）。 */
enum class HomeTab(val label: String) { FAVORITES("我的最愛"), CLASSES("分組討論"), HOT("即時熱門"), MAIL("私人信件") }

/**
 * 登入後的首頁：上面一排分頁（[HomeTab]），點分頁或在下面左右滑都能切（[HorizontalPager]）。
 * 只有**停下來的那頁**會叫 Session 走過去（切到哪頁 PTT 就走到哪裡）：滑到一半、點分頁時動畫經過的頁只畫上次列的，不按鍵。
 * 每頁列過的東西留著（pager 收掉的頁也存著 saved state），切回來先列上次的、走到了再換新的。
 * 分頁選到哪個留在 pager 的 saved state（轉螢幕、從看板回來都還在那頁）。右上角「搜尋」進搜尋看板頁、「終端」、「設定」。
 * 標題是登入的帳號（[AccountTitle]，分頁列已經寫了是哪一頁），點了跳出帳號選單：查詢我的資料（[UserInfoDialog] 查自己）、
 * 切換帳號（[onSwitchAccount]：登出、登入頁直接到帳號列表）、登出（[onLogout]）。
 *
 * - 我的最愛：[FavoritesTab]（[Session.allFavorites]）
 * - 分組討論：[ClassesTab]（[Session.showClasses]，最後一項「即時熱門看板」不列，它有自己的分頁），點一個分類進 [ClassBoardsScreen]
 * - 即時熱門：[HotTab]（[Session.hotBoards]：【分類看板】最後一項進去，人氣排名），長按一列加入我的最愛
 * - 私人信件：[MailTab]（[Session.showMails]，最新的在上），點一封進 [MailScreen]；右下角「寫信」進 [WriteMailScreen]。
 *   PTT 說有新信（[Session.newMail]）時分頁名稱右上角一個藍點（[MailTabLabel]）
 *
 * 「你有新信件」的「看信」（[openMailTab]）：切到私人信件分頁，切好叫 [onMailTabOpened]；切好之前每一頁都不叫 Session 走過去
 * （從看板回來時 pager 停在上次那頁，不先走到那頁再走到信箱）。停下來的是哪一頁告訴 [onTabSettled]（停在私人信件就不跳提醒）。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    // 分頁底線跟著手指（滑過一半就換）；叫 Session 走過去只看 settledPage
    val shown = pager.currentPage
    var accountMenu by remember { mutableStateOf(false) }
    var queryingSelf by remember { mutableStateOf(false) }
    if (accountMenu) {
        ActionSheet(
            account,
            listOf(
                MenuItem("查詢我的資料", "query_self", onClick = { queryingSelf = true }),
                MenuItem("切換帳號", "switch_account", onClick = onSwitchAccount),
                MenuItem("登出", "account_logout", onClick = onLogout),
            ),
            onDismiss = { accountMenu = false },
        )
    }
    if (queryingSelf) UserInfoDialog(session, account, onDismiss = { queryingSelf = false })
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { AccountTitle(account, onClick = { accountMenu = true }) },
            actions = {
                TextIconButton("搜尋", onClick = onSearchBoards, modifier = Modifier.testTag("board_search_button"), icon = Icons.Filled.Search)
                TerminalButton(onTerminal)
                TextIconButton("設定", onClick = onSettings, modifier = Modifier.testTag("settings_button"), icon = Icons.Filled.Settings)
            },
        )
        PrimaryTabRow(selectedTabIndex = shown) {
            HomeTab.entries.forEachIndexed { i, t ->
                Tab(
                    selected = i == shown,
                    onClick = { scope.launch { pager.animateScrollToPage(i) } },
                    text = { if (t == HomeTab.MAIL) MailTabLabel(t.label, newMail == true) else Text(t.label) },
                    modifier = Modifier.testTag("tab_${t.name.lowercase()}"),
                )
            }
        }
        HorizontalPager(
            state = pager,
            key = { HomeTab.entries[it].name },
            modifier = Modifier.fillMaxSize().testTag("home_pager"),
        ) { i ->
            // 滑動中、動畫經過的頁不按鍵（不然 PTT 會被拉去旁邊那頁，放手彈回來時兩邊對不上）
            val active = i == pager.settledPage && !openMailTab
            when (HomeTab.entries[i]) {
                HomeTab.FAVORITES -> FavoritesTab(session, account, active, onOpenBoard, onTerminal)
                HomeTab.CLASSES -> ClassesTab(session, active, onOpenClass, onTerminal)
                HomeTab.HOT -> HotTab(session, active, onOpenBoard, onTerminal)
                HomeTab.MAIL -> MailTab(session, active, onOpenMail, onWriteMail, onTerminal)
            }
        }
    }
}

/** 首頁的標題：代號第一個字的圓形頭像、代號、「▾」，整列點了開帳號選單。代號太長（最多 12 個字）加「…」。 */
@Composable
private fun AccountTitle(account: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(MaterialTheme.shapes.medium).clickable(onClickLabel = "帳號選單", onClick = onClick)
            .padding(4.dp).testTag("account_button"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(account.take(1).uppercase(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(account, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false))
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
    }
}

/**
 * 我的最愛。一進來就叫 [Session.allFavorites]（從看板回來會按左鍵退出來）：超過一頁的話 PTT 那邊一頁一頁翻完，列出整份。
 * 列出來的存在 rememberSaveable 裡（跟著 [account]）：從看板回來先列上次的，翻完再換成最新的；更新失敗時上次的照樣列著，最上面一列說原因與「重試」。
 * 長按一列：移到最上面／往上一格／往下一格／移到最下面（[Session.moveFavorite]，新位置是鄰居的序號）、從我的最愛移除
 * （先問一次，[Session.removeFavorite]）；做完用 PTT 重讀的整份換掉列表，失敗 toast 說原因。
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
            listOf(
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

/**
 * 「私人信件」分頁的名稱；有新信時右上角一個藍點（[UnreadBlue]），TalkBack 唸「有新信件」。
 * 點疊在字的右上角、不佔寬度（Material 的 [BadgedBox]）：分頁平分螢幕寬，點放在字後面會把「私人信件」擠成兩行。
 */
@Composable
private fun MailTabLabel(label: String, newMail: Boolean) {
    BadgedBox(
        badge = {
            if (newMail) Badge(containerColor = UnreadBlue, modifier = Modifier.semantics { contentDescription = "有新信件" })
        },
    ) {
        Text(label)
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
