package com.twtrubiks.quietptt.capture

import com.twtrubiks.quietptt.core.ptt.ArticleItem
import com.twtrubiks.quietptt.core.ptt.ArticleRef
import com.twtrubiks.quietptt.core.ptt.ArticleSearch
import com.twtrubiks.quietptt.core.ptt.ArticleSummary
import com.twtrubiks.quietptt.core.ptt.ArticleText
import com.twtrubiks.quietptt.core.ptt.Board
import com.twtrubiks.quietptt.core.ptt.ClassEntry
import com.twtrubiks.quietptt.core.ptt.MailSummary
import com.twtrubiks.quietptt.core.ptt.LoginResult
import com.twtrubiks.quietptt.core.ptt.Page
import com.twtrubiks.quietptt.core.ptt.PushType
import com.twtrubiks.quietptt.core.ptt.SearchKind
import com.twtrubiks.quietptt.core.ptt.UserInfo
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.ptt.author
import com.twtrubiks.quietptt.core.ptt.connectsWith
import com.twtrubiks.quietptt.core.ptt.findUrls
import com.twtrubiks.quietptt.core.ptt.header
import com.twtrubiks.quietptt.core.ptt.items
import com.twtrubiks.quietptt.core.ptt.looksLikeArt
import com.twtrubiks.quietptt.core.ptt.mergedWith
import com.twtrubiks.quietptt.core.record.Redactor
import kotlinx.coroutines.flow.first
import com.twtrubiks.quietptt.core.terminal.Keys
import com.twtrubiks.quietptt.core.ptt.ScreenParser
import com.twtrubiks.quietptt.core.transport.WebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient

/**
 * `capture --e2e [看板]`：用 App 的 [Session]（同一份程式碼）連真的 PTT，照 App 的順序走一次，印出每一步的結果與花的時間。
 * 帳密從環境變數 `PTT_ID`、`PTT_PASSWORD` 來；印出來的東西都遮過帳號與 IP。**只看不寫**：不發文、不推文。
 *
 * 登入 → 我的最愛 → 進板 → 載入更早的 → 開推文最多的那篇、翻到最後、推文切成一則一則、標頭與排版 → 檢查新推文（重讀）→
 * 複製全文（[Session.wholeArticle]）→ 文章資訊（[Session.articleInfo]，一般文章與置底文）→ 退回 → 重整（[Session.reloadBoard]）→ 開置底文、在文章裡重整 → 搜尋（[searches]）→ 退回我的最愛 →
 * 再進一次同一個板（`showBoard(newest = true)`）→ 首頁另外三個分頁（[homeTabs]：分組討論、即時熱門、私人信件）→ 查詢網友（[queries]）。
 * 翻完文章之後試文章裡的跳頁（[jumps]：Home、End、`:` 行號、PgDn）。
 */
suspend fun endToEnd(board: String) {
    val id = System.getenv("PTT_ID")?.trim().orEmpty()
    val password = System.getenv("PTT_PASSWORD").orEmpty()
    require(id.isNotEmpty() && password.isNotEmpty()) { "沒有環境變數 PTT_ID、PTT_PASSWORD" }
    val secrets = listOf(id, password)
    fun say(text: String) = println(Redactor.redact(text, secrets))

    val http = OkHttpClient()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = Session(WebSocketTransport(http), scope)
    var failed = false
    // [private]：畫面上是私人的東西（信箱、信）：失敗時不印畫面、不印例外訊息（裡面可能帶著畫面上的字），只印認成哪一種畫面
    suspend fun <T> step(name: String, private: Boolean = false, block: suspend () -> T): T? {
        val started = System.currentTimeMillis()
        return try {
            block().also { say("✓ $name（${System.currentTimeMillis() - started} ms）") }
        } catch (e: Exception) {
            failed = true
            if (private) {
                say("✗ $name：${e::class.simpleName}；畫面是 ${ScreenParser.parse(session.screens.value)::class.simpleName}（私人畫面不印）")
            } else {
                say("✗ $name：${e.message}")
                say(session.screens.value.toString())
            }
            null
        }
    }
    try {
        step("連線") { session.connect() } ?: return
        val login = step("登入") { session.login(id, password) } ?: return
        say("  結果：${login::class.simpleName}")
        if (login !is LoginResult.Success) {
            failed = true
            if (login is LoginResult.Failed) say(login.screen.toString())
            return
        }
        val favorites = step("我的最愛") { session.showFavorites() } ?: return
        say("  ${favorites.boards.size} 個看板，有 $board：${favorites.boards.any { it.name.equals(board, ignoreCase = true) }}")
        // 整份（超過一頁要翻）：只印數字，不印使用者的看板
        val all = step("我的最愛整份（翻頁）") { session.allFavorites() } ?: return
        checkFavorites(all, favorites, session, ::say).also { if (!it) failed = true }
        if (all.size > 20) {
            // 游標停在第 2 頁（像從後面幾頁的板退回來）：要先按 Home 回第 1 頁再翻
            session.send(Keys.PAGE_DOWN)
            session.screens.first { (ScreenParser.parse(it) as? Page.Favorites)?.boards?.firstOrNull()?.index?.let { i -> i > 1 } == true }
            val fromPage2 = step("游標在第 2 頁時讀整份") { session.allFavorites() } ?: return
            say("  ${fromPage2.size} 個，跟第一次的一樣：${fromPage2 == all}，終端回到第 1 頁：${(session.page as? Page.Favorites)?.boards?.firstOrNull()?.index == all.first().index}")
            if (fromPage2 != all) failed = true
        }

        val list = step("進 $board") { session.showBoard(board) } ?: return
        val numbered = list.articles.mapNotNull { it.index }
        say("  ${list.articles.size} 篇（序號 ${numbered.minOrNull()}～${numbered.maxOrNull()}，置底 ${list.articles.count { it.index == null }}），有推文數的 ${list.articles.count { it.pushCount != null }} 篇")
        if (numbered.size + list.articles.count { it.index == null } != list.articles.size || numbered.zipWithNext().any { (a, b) -> b != a + 1 }) {
            failed = true
            say("  ✗ 序號不連續：$numbered")
        }

        var articles = list.articles
        repeat(2) { round ->
            val oldest = articles.firstNotNullOfOrNull { it.index } ?: return@repeat
            val older = step("載入更早的（第 ${round + 1} 次，比 $oldest 舊的）") { session.olderArticles(board, before = oldest) } ?: return@repeat
            articles = articles.mergedWith(older)
            say("  拿到 ${older.size} 篇（${older.firstOrNull()?.index}～${older.lastOrNull()?.index}），列表共 ${articles.size} 篇")
            if (older.isEmpty() || older.last().index != oldest - 1) {
                failed = true
                say("  ✗ 沒有接上：最舊的是 $oldest")
            }
        }

        val target = list.articles.filter { it.index != null && it.author != "-" }.maxBy { it.pushCount ?: -1 }
        say("  要開的：#${target.index} 推文 ${target.pushCount}")
        val ref = ArticleRef.Numbered(target.index!!)
        val first = step("開文章") { session.openArticle(board, ref) } ?: return
        say("  ${first.progress}")
        var text = ArticleText.EMPTY + first
        var pages = 1
        while (!text.complete && pages < 40) {
            val page = step("下一頁") { session.nextArticlePage(board, ref) } ?: break
            text += page
            pages++
            say("  ${page.progress}")
        }
        val pushes = text.lines.count { it.startsWith("推 ") || it.startsWith("噓 ") || it.startsWith("→ ") }
        say("  翻了 $pages 頁，${text.lines.size} 列（到第 ${text.lastLine} 行），推文 $pushes 則，翻完：${text.complete}")
        if (!text.complete) failed = true
        checkComments(text, ::say).let { ok -> if (!ok) failed = true }
        if (!checkLayout(text, target, ::say)) failed = true
        if (!jumps(session, board, ref, text, { name, block -> step(name, false, block) }, ::say)) failed = true

        // 「檢查新推文」：在最後一頁按，退回列表（游標停在這篇上）再打開，回到第一頁
        val reloaded = step("檢查新推文（重讀）") { session.reloadArticle(board, ref) }
        if (reloaded == null) failed = true else {
            say("  ${reloaded.progress}")
            val again = ArticleText.EMPTY + reloaded
            if (reloaded.progress.firstLine != 1 || again.header != text.header) {
                say("  ✗ 重讀之後不是同一篇的第一頁：${again.header}")
                failed = true
            }
        }

        // 看板長按「複製全文」：一次打開、翻到底、退回列表。跟上面一頁一頁讀的一樣（中間有人推文就多幾行）
        val whole = step("複製全文（整篇一次讀完、退回列表）") { session.wholeArticle(board, ref) }
        if (whole == null) failed = true else {
            val same = whole.lines.take(text.lines.size) == text.lines
            val onList = session.page is Page.ArticleList
            say("  ${whole.lines.size} 列（到第 ${whole.lastLine} 行），翻完：${whole.complete}，跟上面讀的一樣：$same，退回列表：$onList")
            if (!whole.complete || !same || !onList) failed = true
        }

        // 看板長按「查詢文章資訊」：游標移到那篇按 Q、讀框、Enter 回列表。代碼要有、網址要是這個板的
        val info = step("文章資訊（序號 Enter、Q）") { session.articleInfo(board, ref) }
        if (info == null) failed = true else {
            val onList = session.page is Page.ArticleList
            say("  #${info.aid}　${info.url}　${info.notes}，回到列表：$onList")
            if (info.aid == null || info.url?.contains("/$board/", ignoreCase = true) != true || !onList) failed = true
        }
        val pinnedInfo = step("置底文的文章資訊（End、Q）") { session.articleInfo(board, ArticleRef.Pinned(fromBottom = 0)) }
        if (pinnedInfo == null) failed = true else {
            val onList = session.page is Page.ArticleList
            say("  #${pinnedInfo.aid}　${pinnedInfo.notes}，回到列表：$onList")
            if (pinnedInfo.aid == null || !onList) failed = true
        }

        step("退回 $board") { session.showBoard(board) } ?: return
        // 「重整」：在板裡按左鍵退回看板列表、再進一次，拿到最新的那頁
        val fresh = step("重整（退出重進）") { session.reloadBoard(board) }
        if (fresh == null) failed = true else {
            val before = numbered.maxOrNull() ?: 0
            val now = fresh.articles.mapNotNull { it.index }.maxOrNull() ?: 0
            say("  ${fresh.articles.size} 篇，最新 #$now（進板時 #$before），置底 ${fresh.articles.count { it.index == null }}，跟進板那頁接得上：${list.articles.connectsWith(fresh.articles)}")
            if (!fresh.isBoard(board) || fresh.filtered || now < before) failed = true
        }
        val pinned = step("開置底文") { session.openArticle(board, ArticleRef.Pinned(fromBottom = 0)) }
        if (pinned != null) say("  ${pinned.body.getOrNull(1)?.trim()}　${pinned.progress}")
        // 停在文章裡叫重整：先退回列表再退出重進
        val freshFromArticle = step("在置底文裡重整") { session.reloadBoard(board) }
        if (freshFromArticle == null || !freshFromArticle.isBoard(board) || session.page !is Page.ArticleList) failed = true
        step("再退回 $board") { session.showBoard(board) } ?: return
        if (!searches(session, board, list.articles, target, { name, block -> step(name, false, block) }, ::say)) failed = true
        val back = step("退回我的最愛") { session.showFavorites() } ?: return
        say("  ${back.boards.size} 個看板，標題列：${back.screen.lines.first().trim()}")
        if (session.page !is Page.Favorites) failed = true
        // 從看板退回來游標停在那個板（可能在後面幾頁）：整份再讀一次要跟一開始的一樣
        val again = step("退回之後再讀整份") { session.allFavorites() } ?: return
        // 「有新文章」的記號（unread）隔了這麼久會變（剛讀過的板、別的板有新文）：不算，只說幾個變了
        val unreadChanged = again.zip(all).count { (a, b) -> a.unread != b.unread }
        val sameBoards = again.map { it.copy(unread = false) } == all.map { it.copy(unread = false) }
        say("  ${again.size} 個，跟一開始的一樣：$sameBoards（「有新文章」記號變了 $unreadChanged 個）")
        if (!sameBoards) failed = true
        // 第二次進同一個板（App 從我的最愛再點一次）：PTT 停在上次游標的位置，newest 要走到最新那頁
        val reentered = step("再進一次 $board（走到最新那頁）") { session.showBoard(board, newest = true) } ?: return
        val newestNow = reentered.articles.mapNotNull { it.index }.maxOrNull() ?: 0
        val pinnedNow = reentered.articles.count { it.index == null }
        say("  ${reentered.articles.size} 篇，最新 #$newestNow（進板時 #${numbered.maxOrNull()}），置底 $pinnedNow")
        if (newestNow < (numbered.maxOrNull() ?: 0) || pinnedNow != list.articles.count { it.index == null }) failed = true
        step("最後退回我的最愛") { session.showFavorites() } ?: return
        if (!homeTabs(session, board, ::step, ::say)) failed = true
        // 查自己的畫面有經濟狀況、上次故鄉這些私人的東西：失敗時不印畫面
        if (!queries(session, id, target.author, { name, block -> step(name, true, block) }, ::say)) failed = true
    } finally {
        session.close()
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        say(if (failed) "有步驟失敗" else "全部走完")
    }
}

/**
 * 文章裡的跳頁（App 的「到底」、往上捲補中間缺的段）：Home 回第一頁 → End 直接到最後一頁（中間留著缺的段）→
 * 跳到中間某一行（`:` 行號 Enter，那行排在第一列）→ 在下一頁的範圍裡用 PgDn。每次讀到的都要跟一頁一頁翻的 [text] 對得上。
 */
private suspend fun jumps(
    session: Session,
    board: String,
    ref: ArticleRef,
    text: ArticleText,
    step: suspend (String, suspend () -> Any?) -> Any?,
    say: (String) -> Unit,
): Boolean {
    var ok = true
    fun same(page: Page.ArticleView): Boolean = (text + page).lines == text.lines

    /**
     * 對不上時印出哪幾行不一樣（看板上公開的內容，最多 25 列、每列前 40 個字）：
     * 才分得出是文章剛好在這時候被改了（例如推文被板主刪掉，行數不變、內容變了）還是畫面沒讀對。
     */
    fun showDifferences(page: Page.ArticleView) {
        val merged = text + page
        if (merged.lines == text.lines) return
        if (merged.lines.size != text.lines.size) say("    行數不一樣：一頁一頁翻的 ${text.lines.size} 列，併進這頁之後 ${merged.lines.size} 列")
        val differ = text.lines.indices.filter { merged.lines.getOrNull(it) != text.lines[it] }
        say("    不一樣的有 ${differ.size} 列：")
        for (i in differ.take(25)) {
            say("    第 ${text.numbers[i]} 行：一頁一頁翻的「${text.lines[i].take(40)}」，這次「${merged.lines.getOrNull(i)?.take(40)}」")
        }
    }
    val home = step("回第一頁（Home）") { session.articlePage(board, ref, line = 1) } as Page.ArticleView? ?: return false
    say("  ${home.progress}")
    showDifferences(home)
    if (home.progress.firstLine != 1 || !same(home)) ok = false
    val end = step("到底（End，一次到最後一頁）") { session.articleEnd(board, ref) } as Page.ArticleView? ?: return false
    val jumped = ArticleText.EMPTY + home + end
    say("  ${end.progress}，結尾 ${jumped.end}（一頁一頁翻到 ${text.lastLine}），中間缺 ${jumped.gaps}，跟翻的一樣：${same(end)}")
    showDifferences(end)
    if (!end.progress.isLast || jumped.end != text.end || !same(end)) ok = false
    if (text.lastLine > Session.PAGE_LINES * 3) {
        val middle = text.lastLine / 2
        val page = step("跳到第 $middle 行（:$middle Enter）") { session.articlePage(board, ref, line = middle) } as Page.ArticleView? ?: return false
        say("  ${page.progress}，跟翻的一樣：${same(page)}")
        showDifferences(page)
        if (page.progress.firstLine != middle || !same(page)) ok = false
        val next = page.progress.lastLine + 1
        val down = step("下一頁的第 $next 行（PgDn）") { session.articlePage(board, ref, line = next) } as Page.ArticleView? ?: return false
        say("  ${down.progress}，跟翻的一樣：${same(down)}")
        showDifferences(down)
        if (down.progress.firstLine != page.progress.lastLine || !same(down)) ok = false
    } else {
        say("  文章只有 ${text.lastLine} 行，不試跳到中間")
    }
    return ok
}

/**
 * 長按推文「查詢 xxx」（[Session.queryUser]）：查自己、查文章作者、查一個不存在的代號，每次都要回到主選單。
 * 欄位的值有上站時間、故鄉之類的，**只印標籤**跟對不對得上代號。
 */
private suspend fun queries(
    session: Session,
    self: String,
    author: String,
    step: suspend (String, suspend () -> Any?) -> Any?,
    say: (String) -> Unit,
): Boolean {
    var ok = true
    for ((name, id) in listOf("自己" to self, "文章作者" to author)) {
        val info = step("查詢網友（$name）") { session.queryUser(id) ?: "沒有這個人" }
        if (info !is UserInfo) {
            say("  ✗ 查不到 $name")
            ok = false
            continue
        }
        say("  代號對得上：${info.id.equals(id, ignoreCase = true)}，有暱稱：${info.nickname != null}，欄位：${info.fields.map { it.first }}，名片 ${info.plan.size} 列")
        if (!info.id.equals(id, ignoreCase = true) || info.fields.size < 3) ok = false
        if (session.page !is Page.MainMenu) ok = false
    }
    val nobody = step("查詢網友（不存在的代號）") { session.queryUser("zzqqxxjj0099") ?: "沒有這個人" }
    say("  $nobody，回到主選單：${session.page is Page.MainMenu}")
    if (nobody != "沒有這個人" || session.page !is Page.MainMenu) ok = false
    return ok
}

/**
 * 首頁另外三個分頁（`HomeScreen`）照 App 的順序走：【分類看板】→ 一個分類（有子分類「Σ」的話再進一層）→ 退回我的最愛 →
 * 即時熱門看板 → 從熱門的列表進 [board]（`s` 換過去）→ 退回我的最愛 → 信箱 → 讀最新一封**已讀的**信（未讀的不開：開了 PTT 就當成讀過）→ 退回我的最愛。
 * 分類、熱門看板是公開的，印名字；信只印數字（幾封、未讀幾封、讀到幾行、標頭讀不讀得出來），不印寄件人、標題、內文。
 */
private suspend fun homeTabs(
    session: Session,
    board: String,
    step: suspend (String, Boolean, suspend () -> Any?) -> Any?,
    say: (String) -> Unit,
): Boolean {
    var ok = true
    @Suppress("UNCHECKED_CAST")
    val classes = step("分組討論區", false) { session.showClasses() } as List<ClassEntry>? ?: return false
    val hot = classes.firstOrNull { it.isHot }
    say("  ${classes.size} 個分類（${classes.joinToString("、") { "${it.index}.${it.name}" }}），即時熱門看板是第 ${hot?.index} 項")
    if (classes.isEmpty() || hot == null || session.page !is Page.ClassRoot) ok = false
    val first = classes.first { !it.isHot }
    @Suppress("UNCHECKED_CAST")
    val inFirst = step("分類「${first.name}」", false) { session.showClass(listOf(first.index)) } as List<Board>? ?: return false
    say("  ${inFirst.size} 項，子分類 ${inFirst.count { it.isFolder }} 個：${inFirst.take(5).joinToString("、") { it.name }}…；終端是分類的列表：${(session.page as? Page.Favorites)?.inClass}")
    if (inFirst.isEmpty() || (session.page as? Page.Favorites)?.inClass != true) ok = false
    inFirst.firstOrNull { it.isFolder }?.let { folder ->
        @Suppress("UNCHECKED_CAST")
        val inner = step("子分類「${folder.name}」", false) { session.showClass(listOf(first.index, folder.index)) } as List<Board>?
        if (inner == null) ok = false
        else say("  ${inner.size} 項（看板 ${inner.count { !it.isFolder }} 個）：${inner.take(5).joinToString("、") { it.name }}…")
    }
    val fromClass = step("從分類退回我的最愛", false) { session.showFavorites() } as Page.Favorites? ?: return false
    if (fromClass.inClass || fromClass.allBoards) ok = false
    @Suppress("UNCHECKED_CAST")
    val hotBoards = step("即時熱門看板", false) { session.hotBoards() } as List<Board>? ?: return false
    say("  ${hotBoards.size} 個看板，前 5 名：${hotBoards.take(5).joinToString("、") { it.name }}；序號一路變大：${hotBoards.map { it.index }.zipWithNext().all { (a, b) -> b > a }}")
    if (hotBoards.isEmpty()) ok = false
    val entered = step("從熱門看板的列表進 $board", false) { session.showBoard(board) } as Page.ArticleList? ?: return false
    say("  ${entered.articles.size} 篇")
    if (!entered.isBoard(board)) ok = false
    step("從看板退回我的最愛", false) { session.showFavorites() } ?: return false
    // 信箱：只印數字
    @Suppress("UNCHECKED_CAST")
    val mails = step("信箱", true) { session.showMails() } as List<MailSummary>? ?: return false
    val numbers = mails.map { it.index }
    say("  ${mails.size} 封（未讀 ${mails.count { it.unread }}），序號一路變大：${numbers.zipWithNext().all { (a, b) -> b > a }}，" +
        "終端在信箱列表：${session.page is Page.MailList || (mails.isEmpty() && session.page is Page.MailMenu)}")
    if (numbers.zipWithNext().any { (a, b) -> b <= a }) ok = false
    val read = mails.lastOrNull { !it.unread }
    if (read == null) {
        say("  沒有已讀的信，不開（未讀的開了就變成讀過）")
    } else {
        val text = step("讀一封已讀的信（第 ${read.index} 封）", true) { session.wholeMail(read.index) } as ArticleText?
        if (text == null) ok = false
        else {
            say("  ${text.lines.size} 列、讀到第 ${text.lastLine} 行、讀完：${text.complete}；標頭讀得出來：${text.header != null}；退回信箱列表：${session.page is Page.MailList}")
            if (!text.complete || session.page !is Page.MailList) ok = false
        }
    }
    val fromMail = step("從信箱退回我的最愛", false) { session.showFavorites() } as Page.Favorites?
    if (fromMail == null || fromMail.inClass) ok = false
    return ok
}

/**
 * 整份我的最愛 [all] 對不對：第一頁 [first] 是它的開頭、序號一路變大、翻完終端回到第 1 頁。
 * 只印數字（幾個、幾頁、序號範圍、跳過的序號——分隔線、目錄幾個），不印板名。
 */
private fun checkFavorites(all: List<Board>, first: Page.Favorites, session: Session, say: (String) -> Unit): Boolean {
    val numbers = all.map { it.index }
    val skipped = numbers.firstOrNull()?.let { lo -> (lo..numbers.last()).filter { it !in numbers } }.orEmpty()
    val now = session.page as? Page.Favorites
    val backOnFirstPage = now?.boards?.firstOrNull()?.index == first.boards.firstOrNull()?.index
    say("  ${all.size} 個（第一頁 ${first.boards.size} 個，${((numbers.lastOrNull() ?: 0) + 19) / 20} 頁），序號 ${numbers.firstOrNull()}～${numbers.lastOrNull()}，" +
        "沒列出來的序號（分隔線）${skipped.size} 個，目錄 ${all.count { it.isFolder }} 個；翻完終端回到第 1 頁：$backOnFirstPage")
    val ok = all.take(first.boards.size) == first.boards && numbers.zipWithNext().all { (a, b) -> b > a } && backOnFirstPage
    if (!ok) say("  ✗ 整份跟第一頁對不上、序號沒有一路變大、或終端沒回到第 1 頁")
    return ok
}

/**
 * 看板裡搜尋（[Session.searchArticles]）：標題（列表上最常見的分類字）→ 開最新的一筆、檢查新推文（重讀）→ 搜尋結果的重整（重搜一次）→
 * 退回來載入更前面的結果 → 作者（[target] 的作者）→ 同標題（[target] 拿掉「R: 」的標題，看板長按的「尋找同標題文章」）→
 * 推文數（高於 30）→ 找不到的關鍵字 → 退回整個列表。
 * 站方對搜尋有頻率限制（pttbbs `activity_nav_track`），這裡只搜 6 次。
 */
private suspend fun searches(
    session: Session,
    board: String,
    articles: List<ArticleSummary>,
    target: ArticleSummary,
    step: suspend (String, suspend () -> Any?) -> Any?,
    say: (String) -> Unit,
): Boolean {
    var ok = true
    fun check(condition: Boolean, problem: () -> String) {
        if (!condition) {
            ok = false
            say("  ✗ ${problem()}")
        }
    }
    fun describe(results: Page.ArticleList) {
        val indices = results.articles.mapNotNull { it.index }
        say("  ${results.articles.size} 筆（序號 ${indices.minOrNull()}～${indices.maxOrNull()}），系列文章：${results.filtered}，最下面一列：${results.screen.lines.last().trim()}")
        check(results.filtered) { "不是搜尋結果" }
        check(results.articles.none { it.index == null }) { "結果裡有置底文" }
        check(indices.zipWithNext().all { (a, b) -> b == a + 1 }) { "序號不連續：$indices" }
    }

    val keyword = articles.mapNotNull { Regex("""^(?:R: |Fw: )?\[(.+?)]""").find(it.title)?.groupValues?.get(1) }
        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: articles.first().title.take(2)
    val byTitle = ArticleSearch(SearchKind.TITLE, keyword)
    @Suppress("UNCHECKED_CAST")
    val titles = step("搜尋標題「$keyword」") { session.searchArticles(board, byTitle) } as Page.ArticleList?
    if (titles == null) {
        say("  ✗ 找不到（列表上明明有）")
        return false
    }
    describe(titles)
    check(titles.articles.all { it.title.contains(keyword, ignoreCase = true) }) {
        "標題沒有「$keyword」：${titles.articles.filterNot { it.title.contains(keyword, ignoreCase = true) }.map { it.title }}"
    }
    val newest = titles.articles.last()
    val opened = step("開結果裡最新的 #${newest.index}") { session.openArticle(board, ArticleRef.Numbered(newest.index!!), byTitle) } as Page.ArticleView?
    if (opened != null) {
        val header = opened.body.getOrNull(1)?.trim().orEmpty()
        say("  $header")
        // 列表上的標題可能被截掉（標題欄寬有限），比前面一段就好
        val shown = newest.title.removePrefix("R: ").removePrefix("Fw: ").take(10)
        check(header.contains(shown)) { "開到的不是那篇：列表上是「${newest.title}」" }
        // 「檢查新推文」：退回的是搜尋結果（不會再搜一次），游標停在這筆上，再打開
        val reloaded = step("結果裡的這篇檢查新推文（重讀）") { session.reloadArticle(board, ArticleRef.Numbered(newest.index!!), byTitle) } as Page.ArticleView?
        check(reloaded?.body?.getOrNull(1)?.trim() == header) { "重讀之後不是同一篇：「${reloaded?.body?.getOrNull(1)?.trim()}」" }
    }
    // 搜尋結果頁的「重整」：在文章裡也行，先退回結果、退出系列文章模式、再搜一次。拿到的跟剛剛的一樣（中間有新文章就多幾筆）
    val again = step("搜尋結果重整（重搜一次）") { session.reloadBoard(board, byTitle) } as Page.ArticleList?
    if (again == null) check(false) { "重搜找不到" } else {
        describe(again)
        val was = titles.articles.mapNotNull { it.index }
        val now = again.articles.mapNotNull { it.index }
        check(now.isNotEmpty() && now.max() >= was.max()) { "重搜之後比較少：$was → $now" }
    }
    val oldest = titles.articles.firstNotNullOfOrNull { it.index }
    if (oldest != null && oldest > 1) {
        @Suppress("UNCHECKED_CAST")
        val older = step("結果裡更前面的（比 $oldest 前）") { session.olderArticles(board, before = oldest, search = byTitle) } as List<ArticleSummary>?
        if (older != null) {
            say("  拿到 ${older.size} 筆（${older.firstOrNull()?.index}～${older.lastOrNull()?.index}）")
            check(older.isNotEmpty() && older.last().index == oldest - 1) { "沒有接上：最前面的是 $oldest" }
            check(older.all { it.title.contains(keyword, ignoreCase = true) }) { "更前面的有標題沒有「$keyword」的" }
        }
    }

    val byAuthor = ArticleSearch(SearchKind.AUTHOR, target.author)
    val authors = step("搜尋作者 ${target.author}") { session.searchArticles(board, byAuthor) } as Page.ArticleList?
    if (authors == null) check(false) { "找不到（列表上明明有）" } else {
        describe(authors)
        check(authors.articles.all { it.author.equals(target.author, ignoreCase = true) }) {
            "作者不是 ${target.author}：${authors.articles.map { it.author }.distinct()}"
        }
    }

    // 看板長按「尋找同標題文章」：拿掉「R: 」之類記號的標題搜（PTT 找子字串，原文跟回文都有）；結果裡要有同標題的
    val bySubject = ArticleSearch(SearchKind.TITLE, target.subject)
    val subjects = step("搜尋同標題「${target.subject}」") { session.searchArticles(board, bySubject) } as Page.ArticleList?
    if (subjects == null) check(false) { "找不到（列表上明明有）" } else {
        describe(subjects)
        check(subjects.articles.any { it.subject.startsWith(target.subject.take(10)) }) { "結果裡沒有同標題的：${subjects.articles.map { it.title }}" }
    }

    val byPushes = ArticleSearch(SearchKind.PUSH_COUNT, "30")
    val pushes = step("搜尋推文數高於 30") { session.searchArticles(board, byPushes) } as Page.ArticleList?
    if (pushes == null) say("  沒有結果") else {
        describe(pushes)
        say("  推文數：${pushes.articles.map { it.pushCount }}")
        check(pushes.articles.all { (it.pushCount ?: 0) >= 30 }) { "有推文數不到 30 的" }
    }

    val nothing = step("搜尋找不到的標題") { session.searchArticles(board, ArticleSearch(SearchKind.TITLE, "qzxjvkw")) ?: "沒有結果" }
    check(nothing == "沒有結果") { "應該找不到：$nothing" }
    say("  現在的畫面：${session.page::class.simpleName}，搜尋結果：${(session.page as? Page.ArticleList)?.filtered}")

    val back = step("退回整個 $board") { session.showBoard(board) } as Page.ArticleList?
    check(back != null && !back.filtered) { "沒有退回整個列表" }
    return ok
}

/**
 * 整篇的推文切得對不對（[items]）：「推／噓／→」開頭、像推文的列（有「:」）都要切成推文，切不出來的印出來；
 * 印幾則、接回了幾列、作者的推文幾則，再印一則接起來的讓人看接得對不對。
 */
private fun checkComments(text: ArticleText, say: (String) -> Unit): Boolean {
    val items = text.items()
    val comments = items.filterIsInstance<ArticleItem.Comment>()
    val pushLike = text.lines.count(::looksLikePush)
    val missed = items.filterIsInstance<ArticleItem.Line>().filter { looksLikePush(it.text) }
    val author = text.author
    say("  推文：$pushLike 列切成 ${comments.size} 則（接回 ${pushLike - missed.size - comments.size} 列），作者 ${author != null}，作者的推文 ${comments.count { it.push.user == author }} 則，有 IP 的 ${comments.count { it.push.ip != null }} 則")
    comments.map { it.push }.filter { p -> p.text.length > 40 }.maxByOrNull { it.text.length }
        ?.let { say("  最長的一則：${it.type.mark} ${it.time} ${it.text.length} 字「${it.text}」") }
    missed.take(10).forEach { say("  ✗ 沒切出來的推文列：「${it.text}」") }
    // 沒接起來、但同一個人同一分鐘接著一列「→」的：給人看是不是其實該接
    comments.zipWithNext().filter { (a, b) -> b.push.type == PushType.ARROW && a.push.user == b.push.user && a.push.time == b.push.time }
        .forEach { (a, b) -> say("  ? 同一人同一分鐘、沒接起來：「${a.push.text}」＋「${b.push.text}」") }
    return missed.isEmpty()
}

/**
 * 文章頁的排版（BePTT 那樣）：標頭認得出來、標題跟列表上的一樣；印出簽名檔、等寬的列、網址各有幾個，給人看認得合不合理。
 * 認不出標頭算失敗（一般文章都有）。
 */
private fun checkLayout(text: ArticleText, listed: ArticleSummary, say: (String) -> Unit): Boolean {
    val header = text.header
    val items = text.items()
    val body = items.filterIsInstance<ArticleItem.Line>()
    val art = body.count { looksLikeArt(it.text) }
    val urls = items.sumOf { item ->
        when (item) {
            is ArticleItem.Line -> findUrls(item.text).size
            is ArticleItem.Signature -> findUrls(item.text).size
            is ArticleItem.Comment -> findUrls(item.push.text).size
            is ArticleItem.Gap -> 0
        }
    }
    say("  標頭：${header?.let { "看板 ${it.board}、時間 ${it.time}、暱稱 ${it.nickname != null}" }}")
    say("  本文 ${body.size} 列（等寬 $art 列）、簽名檔 ${items.count { it is ArticleItem.Signature }} 列、網址 $urls 個")
    body.filter { looksLikeArt(it.text) }.take(3).forEach { say("  等寬：「${it.text.take(40)}」") }
    if (header == null) {
        say("  ✗ 認不出標頭：「${text.lines.firstOrNull()}」")
        return false
    }
    // 列表上的標題前面可能有「R: 」「轉 」（標頭是「Re: 」「Fw: 」），也可能被截短
    val shown = listed.title.removePrefix("R: ").removePrefix("轉 ").take(10)
    if (header.title?.contains(shown) != true) {
        say("  ✗ 標頭的標題「${header.title}」跟列表上的「${listed.title}」對不上")
        return false
    }
    return true
}

/** 像推文的列：「推 」「噓 」「→ 」開頭，後面有「:」。 */
private fun looksLikePush(line: String) = line.length > 2 && line[0] in "推噓→" && line[1] == ' ' && ':' in line
