package com.twtrubiks.quietptt

import android.content.ClipData
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.twtrubiks.quietptt.core.ptt.ArticleRef
import com.twtrubiks.quietptt.core.ptt.ArticleSearch
import com.twtrubiks.quietptt.core.ptt.ArticleSummary
import com.twtrubiks.quietptt.core.ptt.Page
import com.twtrubiks.quietptt.core.ptt.SearchKind
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.ptt.connectsWith
import com.twtrubiks.quietptt.core.ptt.mergedWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 看板的文章列表，或是看板裡搜尋的結果（有 [search]）。
 * 一進來叫 [Session.showBoard]（從文章回來會按左鍵退回列表）或 [Session.searchArticles]，認出 [Page.ArticleList] 後列出文章。
 * 第一次列（列表是空的）叫 `showBoard(newest = true)`：同一條連線再進同一個板，PTT 停在上次游標的位置，要走到最新那頁。
 *
 * **新的在上、舊的在下**（跟 PTT 終端相反，排法見 [boardRows]），一進來停在最上面（最新的）。
 * 置底文直接列在最上面（照 PTT 的順序、標「置底」，跟 BePTT 一樣），不另外收起來。
 * 列出來的是進板那一頁加上往下捲載入的（[Session.olderArticles]），存在 rememberSaveable 裡，開文章再回來還在、捲動位置也還在。
 * 離開之後也記在記憶體（[BoardLists]，不存檔）：再從我的最愛之類的地方點同一個板，先列上次的、不用等 PTT，
 * 走到最新那頁（`newest = true`）之後接得上就併、接不上就整份換成最新那頁。
 * 捲到最下面自動載入更舊的一頁，最下面那列也可以點；載入失敗那列顯示原因與重試。
 * 在最上面往下拉是重整（[PullToRefreshBox]，BePTT 的 `refresh_layout`）：跟工具列的「重整」做一樣的事（[refresh]）。
 *
 * 每一列照 BePTT 的文章列表（[ArticleRow]）：左邊一條色條（照標記：未讀、有新推文、已標記…）、分類與推文數一欄（推文數照 PTT 的顏色：
 * 爆紅、兩位數黃、個位數綠、噓灰）、標題（「[問卦]」拿出來當分類）、下面作者與日期。
 *
 * 最下面一條工具列（[BoardBottomBar]，照 BePTT 看板頁下面那排）：「搜尋」開 [SearchDialog]（標題／作者／推文數加一個輸入框，
 * 跟 BePTT 一樣），結果是另一頁（[onSearch]）；「重整」叫 [Session.reloadBoard] 退出重進拿最新的一頁（接得上就併進來、接不上就整份換掉，回到最上面）；
 * 「發文」（只在整個列表上，搜尋結果沒有）進發文頁（[onPost]）。
 * 右上角「⋮」裡有「加入我的最愛」（[Session.addFavorite]，加完回到這個板）。
 * 標題列右邊是板裡現在有多少人（[OnlineCount]，PTT 列表上的「人氣:179」，[Page.ArticleList.online]），照 BePTT 看板頁右上角那個；
 * PTT 每次畫這個板的列表（進板、從文章退回來、重整、載入更舊的）就跟著更新，不另外按鍵。搜尋結果頁不放。
 *
 * 長按一列跳出選單（[rowActions]）：每篇都有「查詢作者資料」（[UserInfoDialog]，終端會走到主選單，之後的動作自己走回這個板）、
 * 「查詢文章資訊」（[ArticleInfoDialog]，列表上按 `Q`）、「尋找同作者文章」「尋找同標題文章」（直接搜，跟 [onSearch] 一樣換到結果頁）、
 * 「複製全文」（把整篇讀完放進剪貼簿，不用進文章）；自己的文章（[ownArticle]）
 * 多「編輯文章」（[onEdit]）、「修改標題」（[Session.editTitle]）、「刪除文章」（[Session.deleteArticle]，先問一次），
 * 跟文章頁「⋮」裡的一樣，不用先進文章。改完、刪完 PTT 重畫的列表會併進來（標題、作者「-」跟著變）。
 * 搜尋結果的序號是結果裡的第幾筆，終端畫面上看到的列表要是搜尋結果（[Page.ArticleList.filtered]）才併進來，
 * 整個列表那一頁則只併整個列表。兩種都要等 Session 走完（[Navigation.done]）才併：不然會把上一次搜尋的結果、
 * 或再進板時 PTT 停的舊位置併進來（舊的那頁接不上最新那頁，併了之後最新的反而進不來，見 connectsWith）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
    session: Session,
    page: Page,
    board: String,
    search: ArticleSearch? = null,
    account: String = "",
    onOpenArticle: (ArticleRef, ArticleSummary) -> Unit,
    onEdit: (Int) -> Unit = {},
    onSearch: (ArticleSearch) -> Unit,
    onPost: (() -> Unit)? = null,
    onTerminal: () -> Unit,
    onBack: () -> Unit,
) {
    // 再進同一個板先列上次的（只在記憶體，[BoardLists]）；PTT 走到最新那頁之後併進來或整份換掉（[fromCache] 那段）
    val boardLists = LocalContext.current.container.boardLists
    val cached = remember(account, board, search) { if (search == null) boardLists.get(account, board) else null }
    var fromCache by rememberSaveable(board, search) { mutableStateOf(cached != null) }
    var noResults by rememberSaveable(board, search) { mutableStateOf(false) }
    var articles by rememberSaveable(board, search, stateSaver = ArticlesSaver) { mutableStateOf(cached ?: emptyList()) }
    val navigation = rememberNavigation(session, board to search) {
        noResults = if (search == null) {
            // 第一次列（列表還是空的，或先列的是上次記下來的）要走到最新那頁：同一條連線再進同一個板，PTT 停在上次游標的位置。從文章退回來不用
            showBoard(board, newest = articles.isEmpty() || fromCache)
            false
        } else {
            searchArticles(board, search) == null
        }
    }
    // 等 Session 走完（[Navigation.done]）才併：走到一半的畫面可能是上一次搜尋的結果，或再進板時 PTT 停的舊位置（接不上最新那頁，併了最新的就進不來）
    val live = (page as? Page.ArticleList)?.takeIf {
        it.isBoard(board) && it.filtered == (search != null) && navigation.done
    }
    // 板裡現在有多少人：終端上是這個板的列表就拿它的「人氣:」（還沒看到時是 null，不顯示）
    var online by rememberSaveable(board) { mutableStateOf<Int?>(null) }
    val onlineNow = (page as? Page.ArticleList)?.takeIf { it.isBoard(board) }?.online
    LaunchedEffect(onlineNow) { if (onlineNow != null) online = onlineNow }
    val listState = rememberLazyListState()
    // 底部工具列跟著捲動收放（BePTT：往下捲超過 5 px 滑下去藏起來，往上捲超過 5 px 滑回來）；回到最上面一定露出來
    var barShown by remember { mutableStateOf(true) }
    val barScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -BAR_SCROLL_THRESHOLD) barShown = false
                if (available.y > BAR_SCROLL_THRESHOLD) barShown = true
                return Offset.Zero
            }
        }
    }
    val listAtTop by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }
    // 終端上現在那一頁（進板、從文章退回來、載入更舊的之後）併進來：推文數會更新，新文章也會出現。
    // 本來停在最上面的話留在最上面：新文章排在最上面，不然 LazyColumn 會照 key 留在原本那篇、新的被擠到畫面外。
    // 跟列出來的接不上的那頁不併（搜尋結果退出來時終端停在很久以前的那頁，併進來中間會缺一段，見 connectsWith）
    // 先列的是上次記下來的（[fromCache]）：PTT 走到的最新那頁接得上就併，接不上（離開太久，新文章超過一頁）就整份換掉，不然最新的永遠進不來
    LaunchedEffect(live) {
        if (live == null) return@LaunchedEffect
        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        val connects = articles.connectsWith(live.articles)
        if (connects || fromCache) {
            articles = if (connects) articles.mergedWith(live.articles) else live.articles
            fromCache = false
            if (atTop) listState.scrollToItem(0)
        }
    }
    // 記下列過的，下次進這個板先列（搜尋結果不記；先列的上次那份還沒換成新的也不記）
    LaunchedEffect(articles, fromCache) {
        if (search == null && !fromCache && articles.isNotEmpty()) boardLists.put(account, board, articles)
    }

    var loadingOlder by remember { mutableStateOf(false) }
    var olderError by remember { mutableStateOf<String?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val favorite = remember(session, board) { Action() }
    LaunchedEffect(favorite.error) { favorite.error?.let { context.toast("加入我的最愛失敗：$it") } }
    /** 「加入我的最愛」（BePTT 看板選單裡那項）：走到我的最愛按 a 加進去，再回到這個板。 */
    fun addFavorite() = favorite.launch(context.container) {
        val added = session.addFavorite(board)
        session.showBoard(board)
        context.toast(if (added) "已將 $board 加入我的最愛" else "$board 已經在我的最愛裡了")
    }

    fun loadOlder() {
        // 每次都從 articles 現在的值算（這個函式會被捲動的 LaunchedEffect 留著用）
        val oldest = articles.firstNotNullOfOrNull { it.index }
        if (loadingOlder || oldest == null || oldest <= 1) return
        loadingOlder = true
        olderError = null
        scope.launch {
            try {
                // 接在最下面：上面的列不動，捲動位置不用調
                articles = articles.mergedWith(session.olderArticles(board, before = oldest, search = search))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                olderError = e.message ?: e::class.simpleName
            } finally {
                loadingOlder = false
            }
        }
    }

    var refreshing by remember { mutableStateOf(false) }
    /**
     * 「重整」（BePTT 看板頁下面那顆）：退出重進拿最新的一頁。新文章不多（接得上）就併進來，推文數也跟著更新；
     * 離開太久、新文章超過一頁（接不上）就整份換成最新那頁，往下捲再載更早的。做完回到最上面（最新的）。搜尋結果是重搜一次。
     */
    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                val fresh = session.reloadBoard(board, search)
                if (fresh == null) {
                    noResults = true
                    articles = emptyList()
                } else {
                    noResults = false
                    articles = if (articles.connectsWith(fresh.articles)) articles.mergedWith(fresh.articles) else fresh.articles
                    fromCache = false
                    olderError = null
                    listState.scrollToItem(0)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context.toast("重新整理失敗：${e.message ?: e::class.simpleName}")
            } finally {
                refreshing = false
            }
        }
    }

    // 長按一列的動作：都在 App 的 scope 裡跑（離開這一頁也要把 PTT 的提示答完、退回列表）
    val clipboard = LocalClipboard.current
    val copying = remember(session, board) { Action() }
    val editing = remember(session, board) { Action() }
    val deleting = remember(session, board) { Action() }
    var titleFor by remember { mutableStateOf<ArticleSummary?>(null) } // 正在改標題的那篇（對話框開著）
    var deleteFor by remember { mutableStateOf<ArticleSummary?>(null) } // 正在問要不要刪的那篇
    var authorFor by remember { mutableStateOf<String?>(null) } // 正在查的作者（[UserInfoDialog] 開著）
    var infoFor by remember { mutableStateOf<BoardRow.Article?>(null) } // 正在看文章資訊的那篇（[ArticleInfoDialog] 開著）
    LaunchedEffect(copying.error) { copying.error?.let { context.toast("複製全文失敗：$it") } }
    LaunchedEffect(editing.error) { editing.error?.let { context.toast("修改標題失敗：$it") } }
    LaunchedEffect(deleting.error) { deleting.error?.let { context.toast("刪除失敗：$it") } }

    /**
     * 「複製全文」：打開那篇、一頁一頁翻到底接成整篇（跟文章頁一樣的字），退回列表，放進剪貼簿。
     * [Session.wholeArticle] 一次做完：複製途中點了別篇，那篇等這裡退回列表才開（不會開成正在複製的這篇）。
     */
    fun copyArticle(ref: ArticleRef) = copying.launch(context.container) {
        val text = session.wholeArticle(board, ref, search)
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("PTT 文章", text.plainText())))
        when {
            !text.complete -> context.toast("文章太長，只複製了前 ${text.lastLine} 行")
            // Android 13 起系統自己會提示複製了
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> context.toast("已複製全文")
        }
    }

    fun changeTitle(index: Int, newTitle: String) = editing.launch(context.container) {
        session.editTitle(board, index, newTitle, search)
        context.toast("標題改好了")
        // 搜尋結果裡改的：PTT 要退出搜尋才給改，終端已經不在結果裡，重搜一次
        if (search != null) refresh()
    }

    fun delete(index: Int) = deleting.launch(context.container) {
        session.deleteArticle(board, index, search)
        context.toast("文章刪掉了")
        if (search != null) refresh()
    }

    /**
     * 長按 [row] 跳出來的選單（前四項照 BePTT 文章列表的長按選單）：查詢作者資料（[UserInfoDialog]）、查詢文章資訊（[ArticleInfoDialog]）、
     * 尋找同作者文章、尋找同標題文章（[sameAuthor]、[sameTitle]：直接搜，結果跟工具列的搜尋一樣是另一頁，不記進搜尋紀錄）、
     * 「複製全文」；自己的文章多編輯、改標題、刪除。
     */
    fun rowActions(row: BoardRow.Article): List<MenuItem> {
        val busy = copying.active || editing.active || deleting.active
        val index = row.article.index
        val mine = index != null && ownArticle(row.article, account)
        val byAuthor = sameAuthor(row.article.author)
        val byTitle = sameTitle(row.article)
        return listOfNotNull(
            MenuItem("查詢作者資料", "author_info", onClick = { authorFor = row.article.author }),
            MenuItem("查詢文章資訊", "article_info", onClick = { infoFor = row }),
            // 已經在同一個搜尋結果裡就不給按（搜了也是同一頁）
            MenuItem("尋找同作者文章", "same_author", enabled = byAuthor != null && byAuthor != search, onClick = { byAuthor?.let(onSearch) }),
            MenuItem("尋找同標題文章", "same_title", enabled = byTitle != null && byTitle != search, onClick = { byTitle?.let(onSearch) }),
            MenuItem(if (copying.active) "複製中…" else "複製全文", "copy_all", enabled = !busy, onClick = { copyArticle(row.ref) }),
            if (mine) MenuItem("編輯文章", "edit_article", enabled = !busy, onClick = { onEdit(index) }) else null,
            if (mine) MenuItem("修改標題", "edit_title", enabled = !busy, onClick = { titleFor = row.article }) else null,
            if (mine) MenuItem("刪除文章", "delete_article", enabled = !busy, onClick = { deleteFor = row.article }) else null,
        )
    }

    // 捲到最後一列（「載入更早的文章」那列）就載入更舊的。載完最舊的序號變了會再看一次：還看得到最後一列就接著載
    LaunchedEffect(listState, session, board, search) {
        snapshotFlow {
            val info = listState.layoutInfo
            val atEnd = info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 1
            val wanted = atEnd && articles.isNotEmpty() && !loadingOlder && olderError == null
            wanted to articles.firstNotNullOfOrNull { it.index }
        }.collect { (wanted, _) -> if (wanted) loadOlder() }
    }

    Column(Modifier.fillMaxSize()) {
        AppBar(if (search == null) board else "${search.kind.label}：${search.query}", onBack = onBack) {
            if (search == null) online?.let { OnlineCount(it) }
            TerminalButton(onTerminal)
            if (search == null) {
                MoreMenu(
                    board,
                    listOf(
                        MenuItem(if (favorite.active) "正在加入我的最愛…" else "加入我的最愛", "add_favorite", enabled = !favorite.active, onClick = ::addFavorite),
                    ),
                )
            }
        }
        when {
            noResults -> Box(Modifier.weight(1f)) { NoResults(onSearchAgain = { searchOpen = true }) }
            navigation.error == null && (articles.isNotEmpty() || live != null) ->
                // 在最上面往下拉就重整（BePTT 的 refresh_layout）；轉圈到 refresh 做完
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(barScroll)) {
                    LazyColumn(Modifier.fillMaxSize().testTag("article_list"), state = listState) {
                        items(boardRows(articles), key = { it.key }) { row ->
                            ArticleRow(
                                row.article,
                                pinned = row.ref is ArticleRef.Pinned,
                                onClick = { onOpenArticle(row.ref, row.article) },
                                actions = rowActions(row),
                            )
                        }
                        item(key = "older") {
                            OlderRow(
                                loading = loadingOlder,
                                error = olderError,
                                hasOlder = (articles.firstNotNullOfOrNull { it.index } ?: 1) > 1,
                                endText = if (search == null) "已經是第一篇" else "沒有更多了",
                                onLoad = ::loadOlder,
                            )
                        }
                    }
                }
            else -> Box(Modifier.weight(1f)) {
                WaitingForPage(
                    if (search == null) "正在打開 $board…" else "正在搜尋 ${search.kind.label}「${search.query}」…",
                    onTerminal,
                    navigation.error,
                    navigation::retry,
                )
            }
        }
        // 最下面一條工具列（照 BePTT 看板頁下面那排）：發文、搜尋、重整；點了才跳出來，列表上不佔位置。
        // 往下捲就滑下去藏起來、往上捲再滑回來（200 ms，跟 BePTT 一樣）；列表在最上面一定看得到
        AnimatedVisibility(
            visible = barShown || listAtTop,
            enter = slideInVertically(tween(BAR_SLIDE_MS)) { it } + expandVertically(tween(BAR_SLIDE_MS)),
            exit = slideOutVertically(tween(BAR_SLIDE_MS)) { it } + shrinkVertically(tween(BAR_SLIDE_MS)),
        ) {
            BoardBottomBar(
                onPost = onPost,
                onSearch = { searchOpen = true },
                onRefresh = ::refresh,
                refreshing = refreshing,
            )
        }
    }
    if (searchOpen) {
        SearchDialog(
            board = board,
            onDismiss = { searchOpen = false },
            onSearch = {
                searchOpen = false
                onSearch(it)
            },
        )
    }
    titleFor?.let { article ->
        TitleDialog(
            current = article.title,
            hint = "預填的是列表上的標題，太長的會被切掉；分類要自己打，例如「[問題] 標題」",
            onDismiss = { titleFor = null },
            onConfirm = {
                titleFor = null
                changeTitle(article.index!!, it)
            },
        )
    }
    deleteFor?.let { article ->
        DeleteArticleDialog(
            onDismiss = { deleteFor = null },
            onConfirm = {
                deleteFor = null
                delete(article.index!!)
            },
        )
    }
    authorFor?.let { id -> UserInfoDialog(session, id, onDismiss = { authorFor = null }) }
    infoFor?.let { row ->
        ArticleInfoDialog(session, board, row.ref, search, title = row.article.title, onDismiss = { infoFor = null })
    }
}

/** 板裡現在有多少人（[Page.ArticleList.online]）：人形圖示加人數的小圓角框，照 BePTT 看板頁標題列右上角那個。TalkBack 唸「看板人氣 179 人」。 */
@Composable
private fun OnlineCount(count: Int) {
    Row(
        Modifier.padding(end = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = "看板人氣 $count 人" }
            .testTag("board_online"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

/**
 * 「尋找同作者文章」（看板長按一篇、文章頁的「⋮」）的搜尋：用作者的代號 [author] 搜作者（`a`）。
 * 代號不合 PTT 的規則（[searchProblem]，例如被刪的文章作者是「-」）就是 null，那一項不能按。
 */
fun sameAuthor(author: String): ArticleSearch? =
    ArticleSearch(SearchKind.AUTHOR, author).takeIf { searchProblem(it.kind, it.query) == null }

/**
 * 「尋找同標題文章」的搜尋：拿掉「R: 」之類記號的標題（[ArticleSummary.subject]）搜標題（`/`，PTT 找子字串，原文跟回文都找得到）。
 * 標題拿掉記號之後是空的就是 null。
 */
fun sameTitle(article: ArticleSummary): ArticleSearch? =
    ArticleSearch(SearchKind.TITLE, article.subject).takeIf { searchProblem(it.kind, it.query) == null }

/**
 * 文章頁「⋮」的「尋找同標題文章」：標頭的標題 [title]（完整的，列表上的可能被切掉）拿掉開頭的「Re: 」「Fw: 」再搜，
 * 跟 [ArticleSummary.subject] 一樣原文跟回文都找得到。拿掉之後是空的就是 null。
 */
fun sameTitle(title: String): ArticleSearch? =
    ArticleSearch(SearchKind.TITLE, title.replaceFirst(REPLY_PREFIX, "").trim()).takeIf { searchProblem(it.kind, it.query) == null }

/** 標頭標題開頭的回文、轉寄記號（可能疊好幾個，大小寫都有）。 */
private val REPLY_PREFIX = Regex("""^((Re|Fw):\s*)+""", RegexOption.IGNORE_CASE)

/**
 * 這篇是不是登入的人自己的、而且能編輯／改標題／刪除：作者欄是登入的代號 [account]、一般文章（有序號；置底文沒有）、
 * 還沒被刪。搜尋結果裡的也算（PTT 在搜尋模式不給改、不給刪，`Session` 會先退出搜尋、用文章代碼找到那篇）。
 * 跟文章頁「⋮」判斷的條件一樣，只是看列表的作者欄。
 * 代號不分大小寫（[sameAccount]）。
 */
fun ownArticle(article: ArticleSummary, account: String): Boolean =
    article.index != null && !article.deleted && sameAccount(article.author, account)

/**
 * 作者欄 [author] 是不是登入的代號 [account]。PTT 登入不分大小寫（pttbbs `searchuser` 不分大小寫找、換成註冊時的寫法），
 * 作者欄印的是註冊時的寫法：登入打 `abc`、作者欄是 `Abc` 也是自己。
 */
fun sameAccount(author: String?, account: String): Boolean =
    account.isNotEmpty() && author != null && author.equals(account, ignoreCase = true)

/**
 * 搜尋的對話框（照 BePTT 的板內搜尋：三個單選「標題／作者／推文數」加一個輸入框）。
 * 作者只能是英數的代號（鍵盤用英文）；推文數是「高於多少」的數字，負數找噓文（鍵盤用數字），0 在 PTT 等於取消所以不給搜。
 * 輸入框下面是選的那種的搜尋紀錄（[SearchHistory]，最近的在上）：點一筆直接搜、「×」刪那一筆、「清除」清掉這一種。送出的搜尋都記下來。
 * 跟 BePTT 一樣每次打開都從「標題」、空的輸入框開始（結果頁、找不到再開也不帶這次的條件）；換一種就清掉打的字（標題的字拿去搜作者、推文數多半不合）。
 */
@Composable
fun SearchDialog(board: String, onDismiss: () -> Unit, onSearch: (ArticleSearch) -> Unit) {
    val history = LocalContext.current.container.searchHistory
    var kind by rememberSaveable { mutableStateOf(SearchKind.TITLE) }
    var query by rememberSaveable { mutableStateOf("") }
    val choose = { k: SearchKind ->
        if (k != kind) {
            kind = k
            query = ""
        }
    }
    val problem = searchProblem(kind, query)
    val submit = { search: ArticleSearch ->
        history.add(search)
        onSearch(search)
    }
    val recent = history.of(kind)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜尋 $board") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (k in SearchKind.entries) {
                        RadioButton(selected = kind == k, onClick = { choose(k) }, modifier = Modifier.testTag("search_kind_${k.name.lowercase()}"))
                        Text(k.label, modifier = Modifier.clickable { choose(k) })
                        Spacer(Modifier.width(4.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(searchHint(kind)) },
                    supportingText = { if (query.isNotEmpty() && problem != null) Text(problem) },
                    isError = query.isNotEmpty() && problem != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (kind) {
                            SearchKind.TITLE -> KeyboardType.Text
                            SearchKind.AUTHOR -> KeyboardType.Ascii
                            SearchKind.PUSH_COUNT -> KeyboardType.Number
                        },
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("search_query"),
                )
                if (recent.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "搜尋紀錄",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { history.clear(kind) }, modifier = Modifier.testTag("search_history_clear")) { Text("清除") }
                    }
                    // 對話框擠不下（鍵盤打開）就在這裡捲，最多大約五筆高
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).testTag("search_history")) {
                        for (entry in recent) {
                            Row(
                                Modifier.fillMaxWidth().clickable { submit(ArticleSearch(kind, entry)) }.testTag("search_history_entry"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    entry,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                                )
                                IconButton(onClick = { history.remove(kind, entry) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "刪除這筆搜尋紀錄「$entry」")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { submit(ArticleSearch(kind, query.trim())) },
                enabled = problem == null,
                modifier = Modifier.testTag("search_confirm"),
            ) { Text("搜尋") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 輸入框的提示字。 */
fun searchHint(kind: SearchKind): String = when (kind) {
    SearchKind.TITLE -> "標題裡的關鍵字"
    SearchKind.AUTHOR -> "作者代號"
    SearchKind.PUSH_COUNT -> "推文數高於多少（負數找噓文）"
}

/**
 * [query] 不能搜的原因；可以搜就是 null。照 pttbbs `ask_filter_predicate`：作者最多 12 個英數字（IDLEN）、
 * 推文數是整數、0 等於取消（`atoi == 0` 就重畫）；標題只要不是空的。
 */
fun searchProblem(kind: SearchKind, query: String): String? {
    val q = query.trim()
    return when (kind) {
        SearchKind.TITLE -> if (q.isEmpty()) "請輸入關鍵字" else null
        SearchKind.AUTHOR -> when {
            q.isEmpty() -> "請輸入代號"
            !q.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } -> "代號只有英文與數字"
            q.length > 12 -> "代號最多 12 個字"
            else -> null
        }
        SearchKind.PUSH_COUNT -> when {
            q.isEmpty() -> "請輸入數字"
            !Regex("""-?\d{1,6}""").matches(q) -> "請輸入整數"
            q.toInt() == 0 -> "0 等於沒有條件，請輸入別的數字"
            else -> null
        }
    }
}

/** 搜尋沒有結果：PTT 說「找不到符合條件的文章」。 */
@Composable
private fun NoResults(onSearchAgain: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("no_results"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("找不到符合條件的文章", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onSearchAgain) { Text("換個條件") }
    }
}

/** 底部工具列收放的捲動門檻（px）與滑動時間（ms），照 BePTT 的數字。 */
private const val BAR_SCROLL_THRESHOLD = 5f
private const val BAR_SLIDE_MS = 200

/**
 * 看板頁最下面那條工具列（照 BePTT `activity_article_list` 下面那排圖示按鈕：鉛筆＝發文、放大鏡＝搜尋、箭頭＝重整）：
 * 「發文」搜尋結果那一頁沒有（[onPost] 是 null）；「重整」進行中（[refreshing]）圖示換成轉圈、不能點。圖示有 contentDescription 給螢幕閱讀器。
 */
@Composable
private fun BoardBottomBar(
    onPost: (() -> Unit)?,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
) {
    Column {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("board_bottom_bar"),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onPost != null) {
                IconButton(onClick = onPost, modifier = Modifier.testTag("post_button")) { Icon(Icons.Filled.Create, contentDescription = "發文") }
            }
            IconButton(onClick = onSearch, modifier = Modifier.testTag("search_button")) { Icon(Icons.Filled.Search, contentDescription = "搜尋") }
            IconButton(onClick = onRefresh, enabled = !refreshing, modifier = Modifier.testTag("refresh_button")) {
                if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, contentDescription = "重整")
            }
        }
    }
}

/** 列表最下面那列：載入中轉圈、失敗顯示原因與重試、還有更舊的就是一個按鈕，到第 1 篇了就說一聲（[endText]）。 */
@Composable
private fun OlderRow(loading: Boolean, error: String?, hasOlder: Boolean, endText: String, onLoad: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("older_articles"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            loading -> {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("正在載入更早的文章…")
            }
            error != null -> {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f, fill = false))
                TextButton(onClick = onLoad) { Text("重試") }
            }
            hasOlder -> TextButton(onClick = onLoad) { Text("載入更早的文章") }
            else -> Text(endText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 看板列表（或置底文清單）上的一列。[key] 給 LazyColumn 用。 */
sealed interface BoardRow {
    val key: String

    /** 一篇文章，點了用 [ref] 打開。key 一般文章是序號、置底文是 p 加倒數第幾筆。 */
    data class Article(val article: ArticleSummary, val ref: ArticleRef) : BoardRow {
        override val key: String
            get() = when (ref) {
                is ArticleRef.Numbered -> ref.index.toString()
                is ArticleRef.Pinned -> "p${ref.fromBottom}"
            }
    }
}

/**
 * 把 [articles]（PTT 的順序：序號由小到大、置底文在最後，見 `mergedWith`）排成畫面上的順序：
 * 置底文在最上面、照 PTT 的順序（跟 BePTT 一樣），接著一般文章**新的在上**。
 * 置底文沒有序號，算它是倒數第幾筆（畫面上看得到置底文，表示那一頁是列表的最後一頁，最後一筆就是最後一篇置底文）。
 */
fun boardRows(articles: List<ArticleSummary>): List<BoardRow.Article> {
    val pinned = articles.filter { it.index == null }
    // 置底文也跟一般文章一樣倒過來：PTT 列表最後一筆（fromBottom = 0）排最上面（使用者要的，BePTT 也是這樣）
    val pinnedRows = pinned.asReversed().mapIndexed { i, article -> BoardRow.Article(article, ArticleRef.Pinned(fromBottom = i)) }
    val newestFirst = articles.filter { it.index != null }.sortedByDescending { it.index }
        .map { BoardRow.Article(it, ArticleRef.Numbered(it.index!!)) }
    return pinnedRows + newestFirst
}

/**
 * 列表上的一篇（BePTT 的 `article_page_list`）：左邊 4dp 的色條（顏色照標記，[markColor]）、一欄分類加推文數（推文數大字、粗體、
 * 顏色照 PTT 的 ANSI 色：[pushColor]）、右邊標題（最多兩行）與下面一行作者、標記、日期。
 * 標題裡的「[問卦]」拿出來放分類那欄、標題不再重複（[titleParts]）。被刪的文章打不開（按 Enter PTT 什麼都不回）：變淡、不能點。
 * [pinned] 的（置底文）作者前面標一個「置底」。長按從下面升起 [actions] 的面板（[ActionSheet]，第一行是標題；有震一下；[actions] 空的就沒有長按）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleRow(article: ArticleSummary, onClick: () -> Unit, pinned: Boolean = false, actions: List<MenuItem> = emptyList()) {
    val (category, title) = remember(article.title) { titleParts(article.title) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val dim = article.deleted
    val textColor = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .combinedClickable(
                    enabled = !dim,
                    onClick = onClick,
                    // 長按的震動 combinedClickable 自己會做（hapticFeedbackEnabled 預設開），這裡再震就是兩下
                    onLongClick = if (actions.isEmpty()) null else ({ menuOpen = true }),
                )
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                .testTag("article_row"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(markColor(article.mark, dark)))
            Column(Modifier.width(52.dp).padding(start = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    category ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    article.pushCount?.let { pushLabel(it) } ?: "",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = article.pushCount?.let { pushColor(it, dark) } ?: textColor,
                    maxLines = 1,
                )
            }
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (pinned) {
                        Text("置底", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("pinned_tag"))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        article.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    article.mark?.let {
                        Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(article.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (menuOpen) ActionSheet(article.title, actions, onDismiss = { menuOpen = false })
    }
}

/**
 * 標題拆成分類與其餘：BePTT 用 `\[([\s\S]{1,4})\]` 抓第一個「[…]」（1～4 個字）當分類、從標題拿掉。
 * 「R: [問卦] 有沒有…」→（問卦，「R: 有沒有…」）；沒有的話分類是 null、標題照舊。
 */
fun titleParts(title: String): Pair<String?, String> {
    val match = CATEGORY.find(title) ?: return null to title
    val rest = (title.substring(0, match.range.first) + title.substring(match.range.last + 1)).replace(Regex("\\s{2,}"), " ").trim()
    return match.groupValues[1].trim() to rest
}

private val CATEGORY = Regex("""\[([^\[\]]{1,4})\]""")

/**
 * 推文數的顏色，照 PTT 終端上的 ANSI 色（pttbbs `readdoent`：爆是亮紅、10 以上亮黃、1～9 亮綠、噓的 X 是暗灰），
 * 色值用 BePTT 列表模式的那張表（深色主題亮一點，淺色主題深一點）。
 */
fun pushColor(count: Int, dark: Boolean): Color = when {
    count >= 100 -> if (dark) Color(0xFFFF6666) else Color(0xFFBE0200)
    count >= 10 -> if (dark) Color(0xFFFFFF66) else Color(0xFFFF8800)
    count > 0 -> if (dark) Color(0xFF66FF66) else Color(0xFF00BE02)
    else -> if (dark) Color(0xFF666666) else Color(0xFF444444)
}

/**
 * 左邊色條的顏色，照文章的標記（pttbbs `readdoent`）：「+」還沒讀過是藍（BePTT 未讀的預設色）、「~」有新推文是橘、
 * 「m」「M」已標記是紫、「!」已解決是綠、「s」「S」是青；讀過的（空白）淡淡的灰。
 */
fun markColor(mark: Char?, dark: Boolean): Color = when (mark) {
    '+' -> UnreadBlue
    '~' -> Color(0xFFFFA000)
    'm', 'M' -> Color(0xFF9C27B0)
    '!' -> Color(0xFF43A047)
    's', 'S' -> Color(0xFF26A69A)
    else -> if (dark) Color(0xFF3A3A3A) else Color(0xFFE0E0E0)
}

/** 還沒讀過的藍（BePTT 未讀的預設色）：文章列表「+」的色條、看板列表有新文章的藍點。深淺主題都一樣。 */
val UnreadBlue = Color(0xFF007FFF)

/** 推文數照 PTT 的寫法：爆、X1～XX、數字。 */
fun pushLabel(count: Int): String = when {
    count >= 100 -> "爆"
    count <= -100 -> "XX"
    count <= -10 -> "X${-count / 10}"
    else -> count.toString()
}

/** 存進 saved state（開文章再回來、轉螢幕）：每篇一個字串，欄位用 tab 分開（終端上的字不會有 tab）。 */
fun encodeArticles(articles: List<ArticleSummary>): ArrayList<String> =
    articles.mapTo(ArrayList()) { a ->
        listOf(a.index?.toString() ?: "", a.mark?.toString() ?: "", a.pushCount?.toString() ?: "", a.date, a.author, a.title)
            .joinToString("\t")
    }

fun decodeArticles(saved: List<String>): List<ArticleSummary> = saved.map { line ->
    val f = line.split("\t", limit = 6)
    ArticleSummary(f[0].toIntOrNull(), f[1].firstOrNull(), f[2].toIntOrNull(), f[3], f[4], f[5])
}

private val ArticlesSaver = Saver<List<ArticleSummary>, ArrayList<String>>(
    save = { encodeArticles(it) },
    restore = { decodeArticles(it) },
)
