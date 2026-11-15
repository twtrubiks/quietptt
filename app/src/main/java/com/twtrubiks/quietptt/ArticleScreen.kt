package com.twtrubiks.quietptt

import android.content.ClipData
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.twtrubiks.quietptt.core.ptt.ArticleHeader
import com.twtrubiks.quietptt.core.ptt.ArticleItem
import com.twtrubiks.quietptt.core.ptt.ArticleRef
import com.twtrubiks.quietptt.core.ptt.ArticleSearch
import com.twtrubiks.quietptt.core.ptt.ArticleSummary
import com.twtrubiks.quietptt.core.ptt.ArticleText
import com.twtrubiks.quietptt.core.ptt.Push
import com.twtrubiks.quietptt.core.ptt.PushType
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.ptt.articleUrl
import com.twtrubiks.quietptt.core.ptt.findUrls
import com.twtrubiks.quietptt.core.ptt.header
import com.twtrubiks.quietptt.core.ptt.imageUrl
import com.twtrubiks.quietptt.core.ptt.items
import com.twtrubiks.quietptt.core.terminal.Big5Uao
import com.twtrubiks.quietptt.core.terminal.ColorSpan
import kotlinx.coroutines.flow.first
import com.twtrubiks.quietptt.core.ptt.ArticleRow
import com.twtrubiks.quietptt.core.ptt.Page
import com.twtrubiks.quietptt.core.ptt.rows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 文章頁：整篇文章（含推文），排版照 BePTT 的文章頁。一進來叫 [Session.openArticle]（在列表上打序號跳過去再按 Enter，停在第一頁），
 * 往下捲到離最後一列 [PREFETCH_ROWS] 則以內就叫 [Session.articlePage] 讀下一頁（PgDn），用 [ArticleText] 照行號接起來，讀到最後一頁為止。
 * 只在捲到附近時才讀，沒看的頁不抓。返回時看板頁會叫 `showBoard`，由它按左鍵退回列表。
 *
 * - 最上面是標題卡（[ArticleText.header]：標題、作者、看板、時間），標頭那三列與分隔線不再當本文印出來
 * - 本文一列一個 `Text`，用一般字型自動換行（BePTT 全用一般字型）；看起來是 ASCII 圖或表格的列（`ArticleItem.Line.art`）維持等寬、不換行，
 *   橫向一起捲。簽名檔「※ 發信站」那幾列縮小變淡。網址可點（交給外部瀏覽器）；是圖片的網址（[imageUrl]，含 imgur）下面多一顆
 *   「開啟圖片」（BePTT 的按鈕），按了才抓、在原地展開（[InlineImage]），不自動載
 * - 推文（[ArticleText.items]）照 BePTT：左邊一條顏色（推綠、噓紅、→ 灰）、代號、樓層「12樓/推5」、時間靠右，
 *   有 IP 的板 IP 另一列；內容下面一行自動換行。同一個人連著推的只在第一則顯示代號與時間（BePTT 把它們疊成一組）；
 *   作者（標頭的代號）自己的推文代號上色、標「原PO」
 * - 「到底」（BePTT 的「到底部」）：直接跳到最後一頁（[Session.articleEnd]，End），不一頁一頁翻；中間沒讀的那段是一列
 *   「載入中間的 N 行」（[GapRow]），捲到它就讀：從上面捲下來補上半，從下面捲上來補下半（下面那則留在原地）。已經在最下面就變成「到頂」
 * - 「重整」與最後一列的「檢查新推文」（BePTT 文章最下面那顆）：退出文章再打開（[Session.reloadArticle]，Home 不會重讀檔案），
 *   **留在原地**：讀過的行留著，只重讀第一頁、看過結尾的話再讀最後一頁（新的推文）。從搜尋結果點進來的帶著 [search]（序號是結果裡的第幾筆）
 * - 點一則推文：突顯同一個人的推文（BePTT 的「突顯同作者推文」：那個人的每一則換底色，工具列上面一條「關閉」），再點他的一則取消、點別人的換人；
 *   長按跳出選單：複製內容、複製代號、查詢這個使用者（[UserInfoDialog]）
 * - 本文可以長按選字複製（[SelectionContainer]；推文的長按是選單，不在選取裡）；「⋮」裡「複製全文」把整篇（含標頭與推文）放進剪貼簿
 * - 「⋮」前四項跟看板長按的選單一樣：「查詢作者資料」（[UserInfoDialog]，查標頭的作者代號）、「查詢文章資訊」（[ArticleInfoDialog]：
 *   按左鍵退回列表按 `Q`，之後往下讀自己走回文章）、「尋找同作者文章」「尋找同標題文章」（[sameAuthor]、[sameTitle]：
 *   用標頭的作者、標題搜（[onSearch]），文章這層換成搜尋結果；這篇就是從同一個搜尋的結果點進來的話不能按）。
 *   再來「寄信給作者」（[onMail]）：開寫信頁，收件人填好作者、標題填「Re: 文章標題」（[replyTitle]），不引用文章。
 *   「文章字級」挑字級（[ArticleTextSize]，設定裡也能改）：標題卡、本文、推文的字一起放大縮小（[ArticleTextScale]）
 * - 最下面一條工具列（[ArticleBottomBar]）：「推文」「回文」「到底／到頂」「重整」「分享」；「回文」（[onReply]，帶著標頭的標題與作者）進回文頁，
 *   置底文（沒有序號）、被刪的文章沒有；「推文」按了才展開推文的輸入列（[PushBar]，BePTT 的 `fragment_article_push`）：
 *   推／噓／→ 切換、內容、「送出」（[Session.push]），送完收起來、重讀文章、捲到最下面；「✕」或返回鍵收起來
 * - 自己的文章（標頭的作者是登入的 [account]，而且是一般文章；搜尋結果裡的也算）「⋮」多了「編輯文章」（[onEdit]）、「修改標題」
 *   （[Session.editTitle]）、「刪除文章」（[Session.deleteArticle]，先問一次，刪完回看板）
 */
@Composable
fun ArticleScreen(
    session: Session,
    board: String,
    article: ArticleRef,
    search: ArticleSearch? = null,
    preview: ArticleSummary? = null,
    account: String = "",
    onEdit: () -> Unit = {},
    onReply: (title: String, author: String) -> Unit = { _, _ -> },
    onSearch: (ArticleSearch) -> Unit = {},
    onMail: (to: String, title: String) -> Unit = { _, _ -> },
    onTerminal: () -> Unit,
    onBack: () -> Unit,
    signedAs: String? = null,
) {
    val key = Triple(board, article, search)
    var text by remember(session, key) { mutableStateOf<ArticleText?>(null) }
    val navigation = rememberNavigation(session, key) {
        text = ArticleText.EMPTY + openArticle(board, article, search)
    }
    // 一次只讀一頁：往下捲接下一頁、補中間缺的、到底、檢查新推文共用
    var loading by remember(session, key) { mutableStateOf(false) }
    var moreError by remember(session, key) { mutableStateOf<String?>(null) }
    // 「到底」「推完文捲到最下面」途中：先不要自動讀（剛讀完、還沒捲過去時，畫面上那列缺的會被當成要補）
    var jumping by remember(session, key) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    // 推文列、改標題、刪文：在 App 的 scope 裡跑（離開這一頁也要把 PTT 的提示答完）
    var pushType by rememberSaveable(board, article) { mutableStateOf(PushType.PUSH) }
    var pushText by rememberSaveable(board, article) { mutableStateOf("") }
    // 推文的輸入列平常收著（BePTT 是按了「推文」才跳出來），按最下面的「推文」才展開；返回鍵先收起來
    var pushOpen by rememberSaveable(board, article) { mutableStateOf(false) }
    val pushing = remember(session, key) { Action() }
    val editing = remember(session, key) { Action() }
    val deleting = remember(session, key) { Action() }
    val sharing = remember(session, key) { Action() }
    val copying = remember(session, key) { Action() }
    var titleOpen by rememberSaveable { mutableStateOf(false) }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }
    // 點推文「查詢這個使用者」、「⋮」的「查詢作者資料」：查的是誰（對話框開著）
    var queried by rememberSaveable(board, article) { mutableStateOf<String?>(null) }
    // 「⋮」的「查詢文章資訊」對話框開著
    var infoOpen by rememberSaveable(board, article) { mutableStateOf(false) }
    // 「⋮」的「文章字級」對話框開著
    var textSizeOpen by rememberSaveable { mutableStateOf(false) }
    // 點一則推文突顯的是誰的推文（BePTT 的「突顯同作者推文」）；沒有突顯是 null
    var highlighted by rememberSaveable(board, article) { mutableStateOf<String?>(null) }

    /** 等列表照 [next] 重新排好（標題卡＋每一則＋最後那列），再捲動才捲得到新的列。 */
    suspend fun awaitLayout(next: ArticleText) {
        val expected = next.rows().size + 2
        withTimeoutOrNull(1_000) { snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it == expected } }
    }

    /** 捲到最後一列（標題卡在最前面，最後一列是「文章結束／載入下一頁」那列）。推完文是在 App 的 scope 裡叫的：捲動要回主執行緒。 */
    suspend fun scrollToEnd(next: ArticleText) = withContext(Dispatchers.Main.immediate) {
        awaitLayout(next)
        listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }

    /**
     * 讀一頁併進來。[keepBelow]：讀的是缺的那段的下半（從下面往上捲到它）：那段下面那一則（[ArticleRow.key]）留在畫面上原來的位置，
     * 新讀的行長在它上面。列表照 key 只會讓畫面最上面那則不動（那是缺的那列），新的行會把下面的擠下去，所以自己擺回去。
     *
     * 那則在哪裡是**讀回來的當下**才量（讀的途中使用者還在捲），換內容與擺位置在同一次排版生效（`requestScrollToItem`）。
     * 不用 `scrollToItem`：它要搶捲動權，手指還按著在拖的時候會丟 CancellationException，
     * 一路丟出去會讓下面自動讀的 LaunchedEffect 整個結束，之後往下捲不再預讀、缺的也不再補。
     */
    suspend fun load(keepBelow: Int? = null, read: suspend () -> Page.ArticleView): ArticleText? {
        if (text == null || loading) return null
        loading = true
        moreError = null
        try {
            val page = read()
            // 讀回來之後到換內容中間不能有 suspend：量位置、換內容、擺位置要在同一個瞬間（都在主執行緒，叫的人是畫面的 scope）
            val before = text ?: return null
            val next = before + page
            // 讀到的那頁沒有新的行（不該發生）：停下來給人按重試，不要一直讀同一頁
            if (next == before) {
                moreError = "讀不到更多內容"
                return null
            }
            val anchor = keepBelow?.let { k -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }?.offset }
            text = next
            if (anchor != null) {
                val index = next.rows().indexOfFirst { it.key == keepBelow }
                // 列表最前面多一個標題卡
                if (index >= 0) listState.requestScrollToItem(index + 1, -anchor)
            }
            return next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            moreError = e.message ?: e::class.simpleName
            return null
        } finally {
            loading = false
        }
    }

    /** 接在最後面的下一頁（還沒讀到文章結尾）：從最後讀到的那行的下一行開始。 */
    suspend fun loadMore() {
        val current = text ?: return
        if (current.end != null) return
        load { session.articlePage(board, article, search, line = (current.numbers.lastOrNull() ?: 0) + 1) }
    }

    /** 中間缺的 [gap]：從上面捲下來補上半（[gap] 的第一行起那頁），從下面捲上來補下半（到 [gap] 最後一行為止那頁）。 */
    suspend fun loadGap(gap: IntRange, fromBelow: Boolean, below: Int?) {
        if (fromBelow) {
            load(keepBelow = below) { session.articlePage(board, article, search, line = maxOf(gap.first, gap.last - Session.PAGE_LINES)) }
        } else {
            load { session.articlePage(board, article, search, line = gap.first) }
        }
    }

    /**
     * 等正在讀的那頁讀完。往下捲的預讀很常在跑（一打開文章就讀第二頁），「到底」「推完文重讀」碰到它不能什麼都不做：
     * 以前是直接 return，按了「到底」沒反應、推完文看不到自己那則。
     */
    suspend fun awaitIdle() {
        snapshotFlow { loading }.first { !it }
    }

    /** 「到底」：直接跳到最後一頁（End），中間沒讀的留一列「載入中間的 N 行」；已經有最後一頁就只捲過去。 */
    fun toEnd() {
        if (jumping) return
        // 先擋住自動讀，再等正在讀的讀完
        jumping = true
        scope.launch {
            try {
                awaitIdle()
                val current = text ?: return@launch
                val next = if (current.end == null) load { session.articleEnd(board, article, search) } ?: return@launch else current
                scrollToEnd(next)
            } finally {
                jumping = false
            }
        }
    }

    /**
     * 「檢查新推文」：退出再進來（PTT 才會重讀檔案）。第一頁跟原本的一樣（本文沒改）的話，原本讀過的行都留著、捲動位置不動，
     * 只重讀第一頁，看過結尾的話再讀一次最後一頁（新的推文在那裡；新推文超過一頁時中間留一列缺的）。第一頁變了就從第一頁重來。
     * [toEnd]：讀完捲到最下面（自己推完文要看到那則）。
     */
    suspend fun reload(toEnd: Boolean = false) {
        awaitIdle()
        val old = text ?: return
        if (loading) return
        loading = true
        moreError = null
        try {
            val page = session.reloadArticle(board, article, search)
            val first = ArticleText.EMPTY + page
            var next = if (old.lines.take(first.lines.size) == first.lines) old + page else first
            if (old.end != null && !page.progress.isLast) next += session.articleEnd(board, article, search)
            text = next
            if (toEnd) {
                jumping = true
                loading = false
                scrollToEnd(next)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            moreError = e.message ?: e::class.simpleName
        } finally {
            loading = false
            jumping = false
        }
    }

    /** 送出推文：推完 PTT 回到列表，重讀文章、捲到最下面看新推文。 */
    fun sendPush() {
        val problem = pushProblem(pushText, showsIp = text?.items()?.any { it is ArticleItem.Comment && it.push.ip != null } == true)
        if (problem != null || pushing.active) return
        val content = pushText.trim()
        pushing.launch(context.container) {
            session.push(board, article, pushType, content, search)
            pushText = ""
            pushOpen = false
            reload(toEnd = true)
        }
    }
    LaunchedEffect(pushing.error) { pushing.error?.let { context.toast("推文失敗：$it") } }
    BackHandler(enabled = pushOpen && !pushing.active) { pushOpen = false }

    /**
     * 「分享」：丟給系統的分享選單（BePTT 的 share），內容是標題加「※ 文章網址」那列的網址。
     * 網址在本文最後、簽名檔那幾列裡，還沒讀到就先把整篇讀完（[Session.wholeArticle]，讀完的整篇順便放上畫面）；
     * 沒有那一列（很舊的文章、轉錄文）就只分享看板與標題。
     */
    fun share() = sharing.launch(context.container) {
        var whole = text ?: return@launch
        if (!whole.complete && whole.articleUrl() == null) {
            whole = session.wholeArticle(board, article, search)
            text = whole
        }
        val title = whole.header?.title ?: preview?.title ?: ""
        val body = listOfNotNull("[$board] $title".trim(), whole.articleUrl()).joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(Intent.createChooser(send, "分享文章").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    LaunchedEffect(sharing.error) { sharing.error?.let { context.toast("分享失敗：$it") } }

    /** 把 [content] 放進剪貼簿（Android 13 起系統自己會提示複製了）。 */
    fun copy(content: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("PTT", content)))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) context.toast("已複製")
        }
    }

    /** 長按一則推文跳出的選單：複製推文內容、複製代號、查詢這個使用者（[UserInfoDialog]）。 */
    fun commentActions(push: Push): List<MenuItem> = listOf(
        MenuItem("複製推文內容", "copy_comment", enabled = push.text.isNotEmpty(), onClick = { copy(push.text) }),
        MenuItem("複製代號 ${push.user}", "copy_user", onClick = { copy(push.user) }),
        MenuItem("查詢 ${push.user}", "query_user", onClick = { queried = push.user }),
    )

    /** 「複製全文」：還沒讀完（或中間缺一段）就先把整篇讀完（[Session.wholeArticle]），讀完的整篇順便放上畫面。 */
    fun copyAll() = copying.launch(context.container) {
        var whole = text ?: return@launch
        if (!whole.complete) {
            whole = session.wholeArticle(board, article, search)
            text = whole
        }
        withContext(Dispatchers.Main) { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("PTT 文章", whole.plainText()))) }
        // Android 13 起系統自己會提示複製了
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) context.toast("已複製全文")
        if (!whole.complete) context.toast("文章太長，只複製了前 ${whole.lastLine} 行")
    }
    LaunchedEffect(copying.error) { copying.error?.let { context.toast("複製失敗：$it") } }

    fun changeTitle(newTitle: String) {
        val index = (article as? ArticleRef.Numbered)?.index ?: return
        editing.launch(context.container) {
            session.editTitle(board, index, newTitle, search)
            context.toast("標題改好了")
            reload()
        }
    }
    LaunchedEffect(editing.error) { editing.error?.let { context.toast("修改標題失敗：$it") } }

    fun delete() {
        val index = (article as? ArticleRef.Numbered)?.index ?: return
        deleting.launch(context.container) {
            session.deleteArticle(board, index, search)
            context.toast("文章刪掉了")
        }
    }
    LaunchedEffect(deleting.done) { if (deleting.done) onBack() }
    LaunchedEffect(deleting.error) { deleting.error?.let { context.toast("刪除失敗：$it") } }
    // 刪文途中不給返回（返回會叫看板頁去按左鍵，跟刪文的提示打架）
    BackHandler(enabled = deleting.active) {}

    // 捲到離最後一列 [PREFETCH_ROWS] 則以內（還沒讀到結尾）就讀下一頁；捲到中間缺的那列就補它：
    // 那列在畫面上半是從下面捲上來的（補下半、下面那則不動），在下半是從上面捲下來的（補上半）。
    // 讀完列表變了會再看一次：還在範圍裡就接著讀（內容不滿一個畫面、缺的不只一頁）
    LaunchedEffect(listState) {
        snapshotFlow {
            val current = text
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            val want = when {
                current == null || loading || jumping || moreError != null || visible.isEmpty() -> null
                else -> {
                    val gapRow = visible.firstOrNull { (it.key as? Int)?.let { k -> k < 0 } == true }
                    val gap = gapRow?.let { row -> current.gaps.firstOrNull { it.first == -(row.key as Int) / 2 } }
                    when {
                        gapRow != null && gap != null -> {
                            val below = visible.firstOrNull { it.index == gapRow.index + 1 }?.key as? Int
                            val upperHalf = gapRow.offset + gapRow.size / 2 < info.viewportEndOffset / 2
                            Want.Fill(gap, fromBelow = upperHalf && below != null, below = below)
                        }
                        current.end == null && visible.last().index >= info.totalItemsCount - 1 - PREFETCH_ROWS -> Want.More
                        else -> null
                    }
                }
            }
            want to current
        }.collect { (want, _) ->
            when (want) {
                Want.More -> loadMore()
                is Want.Fill -> loadGap(want.gap, want.fromBelow, want.below)
                null -> {}
            }
        }
    }

    // 捲到最後一列了（「到底」變「到頂」要看這個）
    val atEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 1
        }
    }
    val current = text

    Column(Modifier.fillMaxSize()) {
        AppBar(board, onBack = onBack) {
            TerminalButton(onTerminal)
            // 自己的文章才有編輯／改標題／刪除：標頭的作者是登入的代號、一般文章（置底文沒有序號）。
            // 搜尋結果裡的也給（PTT 不給在搜尋模式刪改，Session 會先退出搜尋、用文章代碼找到那篇）
            val mine = current != null && sameAccount(current.header?.author, account) &&
                article is ArticleRef.Numbered
            val busy = editing.active || deleting.active
            // 作者、標題用標頭的；還沒讀到標頭時用列表那一列的
            val author = (current?.header?.author ?: preview?.author)?.takeIf { it != "-" }
            val title = current?.header?.title ?: preview?.title
            val byAuthor = author?.let(::sameAuthor)
            val byTitle = current?.header?.title?.let { sameTitle(it) } ?: preview?.let { sameTitle(it) }
            // 前四項跟看板長按的選單一樣
            MoreMenu(
                title ?: board,
                listOfNotNull(
                    MenuItem("查詢作者資料", "author_info", enabled = author != null, onClick = { queried = author }),
                    MenuItem("查詢文章資訊", "article_info", onClick = { infoOpen = true }),
                    MenuItem("尋找同作者文章", "same_author", enabled = byAuthor != null && byAuthor != search, onClick = { byAuthor?.let(onSearch) }),
                    MenuItem("尋找同標題文章", "same_title", enabled = byTitle != null && byTitle != search, onClick = { byTitle?.let(onSearch) }),
                    MenuItem("寄信給作者", "mail_author", enabled = author != null, onClick = {
                        author?.let { onMail(it, title?.let(::replyTitle) ?: "") }
                    }),
                    MenuItem("複製全文", "copy_all", enabled = current != null && !copying.active, onClick = ::copyAll),
                    MenuItem("文章字級", "text_size", onClick = { textSizeOpen = true }),
                    if (mine) MenuItem("編輯文章", "edit_article", enabled = !busy, onClick = onEdit) else null,
                    if (mine) MenuItem("修改標題", "edit_title", enabled = !busy, onClick = { titleOpen = true }) else null,
                    if (mine) MenuItem("刪除文章", "delete_article", enabled = !busy, onClick = { deleteOpen = true }) else null,
                ),
            )
        }
        if (current == null || navigation.error != null) {
            // 還在打開：先用列表那一列畫標題卡（BePTT 點下去當下就有標題），下面轉圈；錯了也留著標題卡
            Column(Modifier.fillMaxSize()) {
                if (preview != null) ArticleTextScale {
                    HeaderCard(ArticleHeader(author = preview.author, board = board, title = preview.title, time = preview.date))
                }
                Box(Modifier.weight(1f)) { WaitingForPage("正在打開文章…", onTerminal, navigation.error, navigation::retry) }
            }
        } else {
            // 只在文章變了（讀了一頁）才重切。key 用行號（[ArticleRow.key]）：前面補進缺的行時，畫面上的那則不會跟著跳
            val header = remember(current) { current.header }
            val rows = remember(current) { current.rows() }
            val firstComment = remember(rows) { rows.indexOfFirst { it.item is ArticleItem.Comment } }
            val colors = pushColors()
            val artScroll = rememberScrollState() // ASCII 圖的列一起橫向捲
            val images = remember(session, key) { OpenedImages() } // 按過「開啟圖片」的，離開文章就丟
            // 整個列表都能長按選字（跨列也行）；按鈕那些、推文（長按是選單）用 DisableSelection 包起來
            SelectionContainer(Modifier.weight(1f)) {
                ArticleTextScale {
                    LazyColumn(Modifier.fillMaxSize().testTag("article_body"), state = listState) {
                        item(key = "header") {
                            if (header != null) HeaderCard(header)
                        }
                        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                            when (val item = row.item) {
                                is ArticleItem.Line -> BodyLine(item.text, item.colors, item.art, artScroll, images)
                                is ArticleItem.Signature -> Text(
                                    linkified(item.text),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
                                )
                                is ArticleItem.Comment -> DisableSelection {
                                    // 本文跟推文中間的線；第一則讀到的推文前面還缺一段（樓層數不出來）就不畫：它不一定是真的第一則
                                    if (index == firstComment && item.floor > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                    val previous = (rows.getOrNull(index - 1)?.item as? ArticleItem.Comment)?.push
                                    val user = item.push.user
                                    CommentRow(
                                        item,
                                        isAuthor = header != null && user == header.author,
                                        grouped = previous != null && previous.user == user,
                                        highlighted = user.equals(highlighted, ignoreCase = true),
                                        colors = colors,
                                        images = images,
                                        // 點同一個人的是取消，點別人的換成他
                                        onClick = { highlighted = user.takeUnless { it.equals(highlighted, ignoreCase = true) } },
                                        actions = commentActions(item.push),
                                    )
                                }
                                is ArticleItem.Gap -> DisableSelection {
                                    GapRow(
                                        item,
                                        loading = loading,
                                        error = moreError,
                                        onLoad = { scope.launch { loadGap(item.from..item.to, fromBelow = false, below = null) } },
                                    )
                                }
                            }
                        }
                        item(key = "more") {
                            DisableSelection {
                                MoreRow(
                                    // 缺的那列自己會轉圈；這裡只管接在最後面的
                                    loading = loading && current.end == null,
                                    error = moreError.takeIf { current.gaps.isEmpty() },
                                    complete = current.end != null,
                                    onLoad = { scope.launch { loadMore() } },
                                    onReload = { scope.launch { reload() } },
                                )
                            }
                        }
                    }
                }
            }
            // 突顯同作者推文的那條在列表下面：放上面的話一出現整個列表往下移，剛點的那則會從手指下跑掉
            highlighted?.let { user ->
                val count = rows.count { (it.item as? ArticleItem.Comment)?.push?.user.equals(user, ignoreCase = true) }
                HighlightBar(user, count, complete = current.complete, onClose = { highlighted = null })
            }
            if (pushOpen || pushing.active) {
                PushBar(
                    type = pushType,
                    onType = { pushType = it },
                    text = pushText,
                    onText = { pushText = it.replace("\n", " ") },
                    problem = pushProblem(pushText, showsIp = rows.any { (it.item as? ArticleItem.Comment)?.push?.ip != null }),
                    sending = pushing.active,
                    onSend = ::sendPush,
                    onClose = { pushOpen = false },
                    signedAs = signedAs,
                )
            } else {
                // 已經在最後一列、也讀到文章結尾了就變「到頂」
                val end = if (atEnd && current.end != null) EndAction.TOP else EndAction.BOTTOM
                // 回文要序號（列表上游標移過去按 y）：置底文沒有；標題、作者先拿標頭的，標頭認不出來用列表那一列
                val originalTitle = current.header?.title ?: preview?.title
                val originalAuthor = current.header?.author ?: preview?.author
                val replyTo = if (article is ArticleRef.Numbered && originalTitle != null && originalAuthor != null && originalAuthor != "-") {
                    originalTitle to originalAuthor
                } else {
                    null
                }
                ArticleBottomBar(
                    onPush = { pushOpen = true },
                    onReply = replyTo?.let { (t, a) -> { onReply(t, a) } },
                    end = end,
                    onEnd = {
                        when (end) {
                            EndAction.TOP -> scope.launch { listState.scrollToItem(0) }
                            EndAction.BOTTOM -> toEnd()
                        }
                    },
                    onReload = { scope.launch { reload() } },
                    reloading = loading,
                    onShare = ::share,
                    sharing = sharing.active,
                )
            }
        }
    }
    queried?.let { id -> UserInfoDialog(session, id, onDismiss = { queried = null }) }
    if (infoOpen) {
        ArticleInfoDialog(
            session,
            board,
            article,
            search,
            title = current?.header?.title ?: preview?.title ?: "",
            onDismiss = { infoOpen = false },
        )
    }
    if (textSizeOpen) {
        val textSize = context.container.articleTextSize
        ChoiceDialog(
            title = "文章字級",
            choices = ArticleTextSize.entries.map { it.label },
            selected = textSize.value.ordinal,
            onSelect = {
                textSize.set(ArticleTextSize.entries[it])
                textSizeOpen = false
            },
            onDismiss = { textSizeOpen = false },
            tag = "text_size",
        )
    }
    if (titleOpen) {
        TitleDialog(
            current = current?.header?.title ?: "",
            onDismiss = { titleOpen = false },
            onConfirm = {
                titleOpen = false
                changeTitle(it)
            },
        )
    }
    if (deleteOpen) {
        DeleteArticleDialog(
            onDismiss = { deleteOpen = false },
            onConfirm = {
                deleteOpen = false
                delete()
            },
        )
    }
}

/**
 * 文章與信的字（標題卡、本文、簽名檔、推文）照設定的字級（[ArticleTextSize]）放大縮小：系統的字體大小再乘上去，
 * 用 dp 的間距、色條、圖示不變。「標準」不換 [LocalDensity]：換成新的 [Density] 會變成照比例放大，
 * Android 14 起系統大字體的非線性放大（大字放得比較少）就沒了。
 */
@Composable
internal fun ArticleTextScale(content: @Composable () -> Unit) {
    val scale = LocalContext.current.container.articleTextSize.value.scale
    if (scale == 1f) {
        content()
        return
    }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * scale), content = content)
}

/** 刪文前問一次（文章頁的「⋮」與看板長按的選單共用）。 */
@Composable
fun DeleteArticleDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("確定要刪除文章?") },
        text = { Text("刪掉就找不回來了。PTT 會留下「(本文已被刪除)」。") },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm_delete")) { Text("刪除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 工具列第二顆現在是哪個動作：跳到最後、回到最上面。 */
private enum class EndAction(val label: String) { BOTTOM("到底"), TOP("到頂") }

/** 文章頁的列表要自動讀什麼：接在最後面的下一頁，或中間缺的那段（[fromBelow]：從下面捲上來，補下半、[below] 那則不動）。 */
private sealed interface Want {
    data object More : Want
    data class Fill(val gap: IntRange, val fromBelow: Boolean, val below: Int?) : Want
}

/** 離最後一列幾則以內就先讀下一頁（捲到底之前就讀好，不用停下來等）。 */
private const val PREFETCH_ROWS = 15

/**
 * 文章最下面那條工具列（照 BePTT `activity_artical_content` 下面那排圖示按鈕）：
 * 「推文」（對話框）按了把推文的輸入列（[PushBar]）展開；「到底」（[end]：直接跳到最後一頁；到了底變「到頂」）；
 * 「重整」（箭頭，就是「檢查新推文」：退出重進、留在原地，讀的時候變轉圈）；「分享」（[sharing] 讀整篇時變轉圈）。
 */
@Composable
private fun ArticleBottomBar(
    onPush: () -> Unit,
    onReply: (() -> Unit)?,
    end: EndAction,
    onEnd: () -> Unit,
    onReload: () -> Unit,
    reloading: Boolean,
    onShare: () -> Unit,
    sharing: Boolean,
) {
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("article_bottom_bar"),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPush, modifier = Modifier.testTag("push_button")) { Icon(AppIcons.Comment, contentDescription = "推文") }
        IconButton(onClick = { onReply?.invoke() }, enabled = onReply != null, modifier = Modifier.testTag("reply_button")) {
            Icon(AppIcons.Reply, contentDescription = "回文")
        }
        IconButton(onClick = onEnd, modifier = Modifier.testTag("end_button")) {
            val icon = when (end) {
                EndAction.BOTTOM -> AppIcons.AlignBottom
                EndAction.TOP -> AppIcons.AlignTop
            }
            Icon(icon, contentDescription = end.label)
        }
        IconButton(onClick = onReload, enabled = !reloading, modifier = Modifier.testTag("reload_bar_button")) {
            if (reloading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Refresh, contentDescription = "檢查新推文")
        }
        IconButton(onClick = onShare, enabled = !sharing, modifier = Modifier.testTag("share_button")) {
            if (sharing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Share, contentDescription = "分享")
        }
    }
}

/**
 * 推文的輸入列（BePTT 的 `fragment_article_push`，按了「推文」才出現）：左邊按鈕切換推／噓／→，中間內容「推文內容」，右邊「送出」，
 * 最右邊「✕」收起來（[onClose]）。一出現就把游標放進輸入框、叫出鍵盤。太長（PTT 一行放不下，[pushProblem]）不給送。送出中變成轉圈。
 * 記了兩組以上帳號時，輸入框下面一行「以 代號 推文」（[signedAs]）；太長的原因出現時換成原因。
 */
@Composable
private fun PushBar(
    type: PushType,
    onType: (PushType) -> Unit,
    text: String,
    onText: (String) -> Unit,
    problem: String?,
    sending: Boolean,
    onSend: () -> Unit,
    onClose: () -> Unit,
    signedAs: String? = null,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("push_bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val colors = pushColors()
        OutlinedButton(
            onClick = { onType(PushType.entries[(type.ordinal + 1) % PushType.entries.size]) },
            enabled = !sending,
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.testTag("push_type"),
        ) { Text(type.mark.toString(), color = colors.of(type), fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            singleLine = true,
            enabled = !sending,
            placeholder = { Text("推文內容") },
            isError = text.isNotEmpty() && problem != null,
            supportingText = when {
                text.isNotEmpty() && problem != null -> ({ Text(problem) })
                signedAs != null -> ({ Text("以 $signedAs 推文") })
                else -> null
            },
            modifier = Modifier.weight(1f).focusRequester(focusRequester).testTag("push_text"),
        )
        Spacer(Modifier.width(4.dp))
        if (sending) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onSend, enabled = text.isNotBlank() && problem == null, modifier = Modifier.testTag("push_send")) { Text("送出") }
            IconButton(onClick = onClose, modifier = Modifier.testTag("push_close")) { Text("✕") }
        }
    }
}

/** 改標題的對話框（文章頁與看板長按的選單共用）：預填現在的標題 [current]，輸入框下面的提示字是 [hint]。 */
@Composable
fun TitleDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    hint: String = "分類要自己打，例如「[問題] 標題」",
) {
    var title by rememberSaveable { mutableStateOf(current) }
    val problem = titleProblem(title, null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改標題") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.replace("\n", " ") },
                singleLine = true,
                isError = problem != null,
                supportingText = { Text(problem ?: hint) },
                modifier = Modifier.fillMaxWidth().testTag("title_field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title.trim()) }, enabled = problem == null && title.trim() != current, modifier = Modifier.testTag("confirm_title")) {
                Text("修改")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 推文不能送的原因；可以就是 null。pttbbs `comment` 一行的長度：78 扣掉標示 3、日期 6、空白 1、時間 6、代號（對齊的板補到 12），
 * 開了顯示 IP 的板（[showsIp]）再扣 15：也就是最多 [PUSH_BYTES]（或 [PUSH_BYTES_WITH_IP]）個 byte，中文算 2。超過的 PTT 直接不收。
 */
fun pushProblem(text: String, showsIp: Boolean): String? {
    val t = text.trim()
    if (t.isEmpty()) return "請輸入內容"
    val max = if (showsIp) PUSH_BYTES_WITH_IP else PUSH_BYTES
    val bytes = Big5Uao.encode(t).size
    return if (bytes > max) "太長了（最多 $max 個字元，中文算 2 個；現在 $bytes 個）" else null
}

const val PUSH_BYTES = 50
const val PUSH_BYTES_WITH_IP = 35

/**
 * 標題卡（BePTT 的 `artical_content_info_new`）：標題一行，底下作者、看板、時間。
 * 信的標頭右邊是「站內  收件人」（放在 [ArticleHeader.board]）：[boardLabel] 讓讀信頁寫「收件人」，不寫「看板」。
 */
@Composable
fun HeaderCard(header: ArticleHeader, boardLabel: String = "看板") {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().testTag("article_header")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                header.title ?: "（無標題）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            HeaderRow("作者", listOfNotNull(header.author, header.nickname?.let { "($it)" }).joinToString(" "))
            header.board?.let { HeaderRow(boardLabel, it) }
            header.time?.let { HeaderRow("時間", it) }
        }
    }
}

@Composable
private fun HeaderRow(label: String, value: String) {
    Row(Modifier.padding(top = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/**
 * 本文的一列：一般字型自動換行；像圖或表格的列（[art]，`ArticleItem.Line.art`）等寬、不換行、橫向一起捲（[artScroll]）。
 * 兩種列的左邊對齊在同一條線上。圖片網址下面有「開啟圖片」。
 * [colors] 是 PTT 上這列的顏色（[ColorSpan]，`ArticleText.colors`）：照 ANSI 的 16 色上在字上（[ansiColors]），有底色的段畫底色。
 */
@Composable
fun BodyLine(text: String, colors: List<ColorSpan>, art: Boolean, artScroll: ScrollState, images: OpenedImages) {
    val styled = colored(linkified(text), colors)
    if (art) {
        Text(
            styled,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().horizontalScroll(artScroll).padding(horizontal = 16.dp),
        )
    } else {
        Text(styled, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp))
        ImageLinks(text, images)
    }
}

/**
 * PTT 的 ANSI 色在 App 裡怎麼畫（BePTT 也是把 ANSI 色碼轉成帶顏色的字）：[fg] 是 8 色的前景、[bright] 是 `1;3x` 的亮色，
 * [bg] 是 8 色的底色（底色照終端的色值，不分主題：字是畫在那塊底色上的）。
 * 淺色主題上亮黃、亮青這種在白底看不見，前景換成深一點的一組；預設的白字（`37`）不上色，跟著主題的文字色，亮白只加粗。
 * 黑字（`30`）在 PTT 上是黑底黑字（看不見）、`1;30` 是暗灰：兩個都畫成灰，不會真的消失。
 */
private class AnsiColors(val fg: List<Color?>, val bright: List<Color?>, val bg: List<Color>)

@Composable
private fun ansiColors(): AnsiColors {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(dark) {
        if (dark) {
            AnsiColors(
                fg = listOf(Color(0xFF4A4A4A), Color(0xFFCD3131), Color(0xFF0DBC79), Color(0xFFE5A50A), Color(0xFF5C8CF0), Color(0xFFBC3FBC), Color(0xFF11A8CD), null),
                bright = listOf(Color(0xFF8A8A8A), Color(0xFFF14C4C), Color(0xFF23D18B), Color(0xFFF5F543), Color(0xFF7FA8FF), Color(0xFFD670D6), Color(0xFF29B8DB), null),
                bg = TERMINAL_BG,
            )
        } else {
            AnsiColors(
                fg = listOf(Color(0xFF9E9E9E), Color(0xFFB71C1C), Color(0xFF2E7D32), Color(0xFF8D6E00), Color(0xFF1565C0), Color(0xFF8E24AA), Color(0xFF00838F), null),
                bright = listOf(Color(0xFF757575), Color(0xFFD32F2F), Color(0xFF388E3C), Color(0xFFB26A00), Color(0xFF1E88E5), Color(0xFFAB47BC), Color(0xFF0097A7), null),
                bg = TERMINAL_BG,
            )
        }
    }
}

/**
 * 預設底色上一個 ANSI 前景色 [fg]（0～7，[bold] 是 `1;3x` 的亮色）在 App 裡的顏色，跟文章裡的字同一組（[ansiColors]）。
 * 預設的白字是 null（跟著主題的文字色）。看板列表的人氣用（[PopularityText]）。
 */
@Composable
fun ansiColor(fg: Int, bold: Boolean): Color? = ansiColors().let { if (bold) it.bright[fg] else it.fg[fg] }

/** 終端的 8 個底色（黑紅綠黃藍紫青白）；第 0 個（黑）是預設底色，不會畫。 */
private val TERMINAL_BG = listOf(
    Color(0xFF000000), Color(0xFFAA0000), Color(0xFF00AA00), Color(0xFFAA5500),
    Color(0xFF0000AA), Color(0xFFAA00AA), Color(0xFF00AAAA), Color(0xFFAAAAAA),
)

/** 有底色的段上字一律用終端的色值（字是畫在那塊底色上的），預設白字也要上成淺灰白，不然淺色主題會變成深字壓深底。 */
private val TERMINAL_FG = listOf(
    Color(0xFF000000), Color(0xFFAA0000), Color(0xFF00AA00), Color(0xFFAA5500),
    Color(0xFF0000AA), Color(0xFFAA00AA), Color(0xFF00AAAA), Color(0xFFAAAAAA),
)
private val TERMINAL_FG_BRIGHT = listOf(
    Color(0xFF555555), Color(0xFFFF5555), Color(0xFF55FF55), Color(0xFFFFFF55),
    Color(0xFF5555FF), Color(0xFFFF55FF), Color(0xFF55FFFF), Color(0xFFFFFFFF),
)

/** 把 [spans] 的顏色上到 [base]（已經標好連結）上；沒有顏色就原樣回傳。設定頁預覽簽名檔也用。 */
@Composable
internal fun colored(base: AnnotatedString, spans: List<ColorSpan>): AnnotatedString {
    if (spans.isEmpty()) return base
    val palette = ansiColors()
    return remember(base, spans, palette) {
        buildAnnotatedString {
            append(base)
            for (span in spans) {
                val end = span.end.coerceAtMost(base.length)
                if (span.start >= end) continue
                val onBackground = span.bg != 0
                val color = when {
                    onBackground -> (if (span.bold) TERMINAL_FG_BRIGHT else TERMINAL_FG)[span.fg]
                    span.bold -> palette.bright[span.fg]
                    else -> palette.fg[span.fg]
                }
                addStyle(
                    SpanStyle(
                        color = color ?: Color.Unspecified,
                        background = if (onBackground) palette.bg[span.bg] else Color.Unspecified,
                        fontWeight = if (span.bold) FontWeight.Bold else null,
                    ),
                    span.start,
                    end,
                )
            }
        }
    }
}

/**
 * [text] 裡是圖片的網址（[imageUrl]）：每一個一顆「開啟圖片」（BePTT 的 `article_conetent_buttom`，整列寬的圓角按鈕），
 * 按了換成圖（[InlineImage]）。不是圖片的網址沒有按鈕（連結本身可點、交給瀏覽器）。
 */
@Composable
private fun ImageLinks(text: String, images: OpenedImages) {
    val urls = remember(text) { findUrls(text).mapNotNull { (_, url) -> imageUrl(url)?.let { url to it } } }
    for ((url, image) in urls) {
        DisableSelection {
            if (images.opened[url] == true) {
                InlineImage(image, images)
            } else {
                FilledTonalButton(
                    onClick = { images.opened[url] = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("open_image"),
                ) { Text("開啟圖片") }
            }
        }
    }
}

/** 文字裡的網址（[findUrls]）標成可點的連結：點了交給系統（外部瀏覽器）。 */
@Composable
fun linkified(text: String): AnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    return remember(text, color) {
        val urls = findUrls(text)
        if (urls.isEmpty()) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                append(text)
                val styles = TextLinkStyles(style = SpanStyle(color = color, textDecoration = TextDecoration.Underline))
                for ((range, url) in urls) addLink(LinkAnnotation.Url(url, styles), range.first, range.last + 1)
            }
        }
    }
}

/** 樓層標示（BePTT 的「12樓/推5」）：第幾樓、這種標示的第幾則。 */
fun floorLabel(comment: ArticleItem.Comment): String = "${comment.floor}樓/${comment.push.type.mark}${comment.nth}"

/**
 * 一則推文（BePTT 的 `artical_conetent_list`）：左邊 4dp 的色條（推綠、噓紅、→ 灰），第一列代號（作者上色、標「原PO」）、樓層、時間靠右，
 * 開了顯示 IP 的板 IP 另一列；內容在下面自動換行、網址可點。[grouped]：跟上一則是同一個人，不再印代號與時間（BePTT 疊成一組）。
 * [highlighted]：突顯的那個人的推文，整列換底色（BePTT 的「突顯同作者推文」；連推的幾則接成一塊）。
 * 點一下是 [onClick]（突顯這個人的推文），長按跳出 [actions]（複製推文內容、複製代號、查詢這個使用者）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CommentRow(
    comment: ArticleItem.Comment,
    isAuthor: Boolean,
    grouped: Boolean,
    highlighted: Boolean,
    colors: PushColors,
    images: OpenedImages,
    onClick: () -> Unit,
    actions: List<MenuItem> = emptyList(),
) {
    val push = comment.push
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .combinedClickable(
                    onClickLabel = "突顯 ${push.user} 的推文",
                    onLongClickLabel = "推文選單",
                    onClick = onClick,
                    // 長按的震動 combinedClickable 自己會做，不用再震
                    onLongClick = if (actions.isEmpty()) null else ({ menuOpen = true }),
                )
                .height(IntrinsicSize.Min)
                .padding(start = 12.dp, end = 16.dp, top = if (grouped) 0.dp else 4.dp, bottom = 4.dp)
                .testTag("comment"),
        ) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(colors.of(push.type)))
            Column(Modifier.padding(start = 10.dp)) {
                if (!grouped) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            push.user,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isAuthor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        if (isAuthor) {
                            Spacer(Modifier.width(6.dp))
                            Text("原PO", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.width(8.dp))
                        // 前面有還沒讀的行就數不出第幾樓（0），不印
                        if (comment.floor > 0) {
                            Text(floorLabel(comment), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(push.time, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    push.ip?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (push.text.isNotEmpty()) {
                    Text(linkified(push.text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                    ImageLinks(push.text, images)
                }
            }
        }
        // 長按從下面升起的面板（複製推文內容、複製代號、查詢這個使用者），第一行是「代號：推文」
        if (menuOpen) {
            ActionSheet(if (push.text.isEmpty()) push.user else "${push.user}：${push.text}", actions, onDismiss = { menuOpen = false })
        }
    }
}

/**
 * 突顯同作者推文時在列表與工具列中間的一條（BePTT 的「關閉突顯同作者推文」那顆，它的在上面）：突顯的是誰、讀到的有幾則（還沒讀完整篇就說「目前讀到」）、「關閉」。
 * 底色跟突顯的推文一樣。
 */
@Composable
private fun HighlightBar(user: String, count: Int, complete: Boolean, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("highlight_bar")) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "突顯 $user 的推文：${if (complete) "" else "目前讀到 "}$count 則",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose, modifier = Modifier.testTag("clear_highlight_button")) { Text("關閉") }
        }
    }
}

/** 推綠、噓紅、→ 灰；深色主題用亮一點的綠與紅。 */
internal class PushColors(val push: Color, val boo: Color, val arrow: Color) {
    fun of(type: PushType): Color = when (type) {
        PushType.PUSH -> push
        PushType.BOO -> boo
        PushType.ARROW -> arrow
    }
}

@Composable
internal fun pushColors(): PushColors {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return PushColors(
        push = if (dark) Color(0xFF81C784) else Color(0xFF2E7D32),
        boo = if (dark) Color(0xFFEF5350) else Color(0xFFC62828),
        arrow = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 中間還沒讀的那段（「到底」直接跳到最後一頁、「檢查新推文」只重讀頭尾留下的）：捲到這裡自動讀，也可以按；
 * 讀的時候轉圈、失敗顯示原因與重試。
 */
@Composable
private fun GapRow(gap: ArticleItem.Gap, loading: Boolean, error: String?, onLoad: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("article_gap"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            loading -> {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("正在載入中間的 ${gap.size} 行…")
            }
            error != null -> {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f, fill = false))
                TextButton(onClick = onLoad) { Text("重試") }
            }
            else -> TextButton(onClick = onLoad) { Text("載入中間的 ${gap.size} 行") }
        }
    }
}

/**
 * 文章最後那列：翻頁中轉圈、失敗顯示原因與重試、翻完了說一聲加「檢查新推文」（BePTT 文章最下面那顆），
 * 不然是「載入下一頁」按鈕（捲到這裡也會自動翻）。
 */
@Composable
private fun MoreRow(loading: Boolean, error: String?, complete: Boolean, onLoad: () -> Unit, onReload: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("article_more"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            loading -> {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("正在載入…")
            }
            error != null -> {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f, fill = false))
                // 翻完了還出錯的是「檢查新推文」失敗：重試要再重讀一次（翻下一頁在翻完之後什麼都不做）
                TextButton(onClick = if (complete) onReload else onLoad) { Text("重試") }
            }
            complete -> {
                Text("— 文章結束 —", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onReload, modifier = Modifier.testTag("reload_button")) { Text("檢查新推文") }
            }
            else -> TextButton(onClick = onLoad) { Text("載入下一頁") }
        }
    }
}
