package com.twtrubiks.quietptt.capture

import com.twtrubiks.quietptt.core.ptt.ArticleItem
import com.twtrubiks.quietptt.core.ptt.ArticleRef
import com.twtrubiks.quietptt.core.ptt.ArticleSummary
import com.twtrubiks.quietptt.core.ptt.ArticleText
import com.twtrubiks.quietptt.core.ptt.LoginResult
import com.twtrubiks.quietptt.core.ptt.NewPost
import com.twtrubiks.quietptt.core.ptt.Page
import com.twtrubiks.quietptt.core.ptt.PostReply
import com.twtrubiks.quietptt.core.ptt.PushReply
import com.twtrubiks.quietptt.core.ptt.PushType
import com.twtrubiks.quietptt.core.ptt.pushAnchors
import com.twtrubiks.quietptt.core.ptt.ScreenParser
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.ptt.editableBody
import com.twtrubiks.quietptt.core.ptt.header
import com.twtrubiks.quietptt.core.ptt.items
import com.twtrubiks.quietptt.core.record.Redactor
import com.twtrubiks.quietptt.core.transport.WebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 只有這個板可以跑 [writeEndToEnd]：PTT 的測試板（每週清板）。 */
private const val TEST_BOARD = "Test"

/**
 * `capture --e2e-write`：會**寫**到 PTT 的那些（找看板、發文、推文、回文、改標題、編輯內文、刪文）用 App 的 [Session] 走一次。
 * **只在 Test 板**（寫死，不收別的板），**只動自己剛發的那一篇**：推文、改標題、編輯、刪文之前都先確認列表上那篇的作者是自己。
 * 中途失敗的話最後把那篇刪掉（一樣先確認是自己的）。帳密從環境變數 `PTT_ID`、`PTT_PASSWORD` 來，印出來的東西遮過帳號與 IP。
 * **只在使用者明確要求時跑**（平常的 `--e2e` 只看不寫）。
 *
 * 找看板（板名補完「Mo」「M」、中文「測試」）→ 發文的分類 → 發文 → 讀回來對標頭 → 推文（自己的文章 PTT 只給 →）→ 重讀看到推文 →
 * 回文到看板兩次（回自己那篇：採用原標題＋引用原文、新標題＋不引用；讀回來對標題、內文、引言，各自刪掉）→
 * 改標題 → 編輯內文 → 讀回來對本文 → 刪文。
 */
suspend fun writeEndToEnd() {
    val board = TEST_BOARD
    val id = System.getenv("PTT_ID")?.trim().orEmpty()
    val password = System.getenv("PTT_PASSWORD").orEmpty()
    require(id.isNotEmpty() && password.isNotEmpty()) { "沒有環境變數 PTT_ID、PTT_PASSWORD" }
    val secrets = listOf(id, password)
    fun say(text: String) = println(Redactor.redact(text, secrets))

    val http = OkHttpClient()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = Session(WebSocketTransport(http), scope)
    var failed = false
    suspend fun <T> step(name: String, block: suspend () -> T): T? {
        val started = System.currentTimeMillis()
        return try {
            block().also { say("✓ $name（${System.currentTimeMillis() - started} ms）") }
        } catch (e: Exception) {
            failed = true
            say("✗ $name：${e.message}")
            say(session.screens.value.toString())
            null
        }
    }
    fun check(ok: Boolean, problem: () -> String) {
        if (!ok) {
            failed = true
            say("  ✗ ${problem()}")
        }
    }

    /** 列表上序號 [index] 的那篇，作者要是自己；不是就丟例外（什麼都不做）。 */
    suspend fun mine(index: Int): ArticleSummary {
        val list = session.showBoard(board)
        val row = list.articles.firstOrNull { it.index == index }
            ?: session.olderArticles(board, before = index + 1).firstOrNull { it.index == index }
        check(row != null && row.author.equals(id, ignoreCase = true)) { "序號 $index 不是自己的文章（作者「${row?.author}」），不動它" }
        if (row == null || !row.author.equals(id, ignoreCase = true)) error("序號 $index 不是自己的文章")
        return row
    }

    /** 跑 [block] 的時候出現過的「按任意鍵繼續」畫面（發文、刪文之後 PTT 說了什麼），印出來給人看、補進文件。 */
    suspend fun <T> notices(name: String, block: suspend () -> T): T {
        val seen = LinkedHashSet<String>()
        val watcher = scope.launch {
            session.screens.collect { s -> if (ScreenParser.parse(s) is Page.PressAnyKey) seen += s.toString() }
        }
        try {
            return block()
        } finally {
            watcher.cancel()
            seen.lastOrNull()?.let { say("  $name 時的「按任意鍵繼續」：\n$it") }
        }
    }

    suspend fun readAll(ref: ArticleRef, first: Boolean = true): ArticleText {
        var text = ArticleText.EMPTY + (if (first) session.openArticle(board, ref) else session.reloadArticle(board, ref))
        var pages = 1
        while (!text.complete && pages < 10) {
            text += session.nextArticlePage(board, ref)
            pages++
        }
        return text
    }

    val stamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))
    var posted: Int? = null
    var deleted = false
    // 回出來還沒刪掉的（中途失敗時最後清掉）
    val replies = mutableListOf<Int>()
    try {
        step("連線") { session.connect() } ?: return
        val login = step("登入") { session.login(id, password) } ?: return
        if (login !is LoginResult.Success) {
            say("  登入失敗：$login")
            failed = true
            return
        }

        // 找看板：只讀
        step("找看板「Mo」（板名補完）") { session.searchBoards("Mo") }?.let {
            say("  ${it.boards.size} 個，截掉了：${it.truncated}，有 MobileComm：${it.boards.any { b -> b.name == "MobileComm" }}")
            check(it.boards.any { b -> b.name == "MobileComm" } && !it.truncated) { "應該有 MobileComm、沒截掉" }
        }
        step("找看板「M」（太多，PTT 只列一頁）") { session.searchBoards("M") }?.let {
            say("  ${it.boards.size} 個，截掉了：${it.truncated}")
            check(it.truncated) { "應該被截掉" }
        }
        step("找看板「測試」（中文關鍵字）") { session.searchBoards("測試") }?.let {
            say("  ${it.boards.map { b -> b.name }}")
            check(it.boards.any { b -> b.name == board }) { "應該有 $board" }
        }

        // 發文
        val options = step("發文的分類（Ctrl-P 讀完取消）") { session.postOptions(board) } ?: return
        say("  ${options.categories}")
        val title = "QuietPTT 自動測試 $stamp"
        val body = "這是 QuietPTT（Android 的 PTT App）的自動測試，等一下會自己刪掉。\n第二行 $stamp"
        val category = options.categories.indexOf("測試").takeIf { it >= 0 }
        step("發文「$title」（分類 ${category?.let { options.categories[it] }}）") {
            notices("發文") { session.post(board, NewPost(category = category, title = title, body = body)) }
        } ?: return
        val list = session.showBoard(board)
        val row = list.articles.lastOrNull { it.author.equals(id, ignoreCase = true) && it.title.contains(stamp) }
        if (row?.index == null) {
            say("  ✗ 列表上找不到剛發的那篇")
            failed = true
            return
        }
        posted = row.index
        say("  列表上：#${row.index}「${row.title}」")
        val ref = ArticleRef.Numbered(row.index!!)

        val text = step("讀回來") { readAll(ref) } ?: return
        val header = text.header
        say("  標頭：標題「${header?.title}」看板 ${header?.board}；本文 ${text.editableBody()}")
        check(header?.author.equals(id, ignoreCase = true)) { "標頭的作者不是自己" }
        check(header?.title == "[測試] $title" || header?.title == title) { "標題不對：「${header?.title}」" }
        check(text.editableBody().joinToString("\n").contains("第二行 $stamp")) { "本文不對" }

        // 推文：自己的文章 PTT 不問推／噓，只給 →
        mine(row.index!!)
        val pushText = "QuietPTT 推文測試 $stamp"
        step("推文「$pushText」") { session.push(board, ref, PushType.PUSH, pushText) }
        step("檢查新推文") { readAll(ref, first = false) }?.let { again ->
            val comments = again.items().filterIsInstance<ArticleItem.Comment>()
            val found = comments.firstOrNull { it.push.text.contains(stamp) }
            say("  推文 ${comments.size} 則；剛推的：${found?.push?.let { "${it.type.mark} ${it.text} ${it.time}" }}")
            check(found != null && found.push.user.equals(id, ignoreCase = true)) { "重讀之後沒看到剛推的那則" }
        }

        // 回文到看板（[Session.replyPost]）：回自己剛發的那篇兩次——採用原標題＋引用原文、新標題＋不引用。
        // 列表上找到回出來的那篇（作者是自己、標題有 stamp、不是原文），讀回來對標題、內文、引言，再刪掉（一樣先確認是自己的）
        suspend fun replyOnce(name: String, reply: PostReply, titleOk: (String) -> Boolean) {
            val original = mine(row.index!!)
            step(name) { notices(name) { session.replyPost(board, row.index!!, original, reply) } } ?: return
            val after = session.showBoard(board, newest = true)
            val replied = after.articles.lastOrNull {
                it.index != null && it.index!! > row.index!! && it.index !in replies && it.author.equals(id, ignoreCase = true) && it.title.contains(stamp)
            }
            if (replied?.index == null) {
                check(false) { "列表上找不到剛回的那篇" }
                return
            }
            replies += replied.index!!
            say("  列表上：#${replied.index}「${replied.title}」")
            val replyRef = ArticleRef.Numbered(replied.index!!)
            step("讀回來（#${replied.index}）") { readAll(replyRef) }?.let { text ->
                val body = text.editableBody()
                val quoted = body.filter { it.startsWith(": ") || it.startsWith("※ 引述") }
                say("  標頭：標題「${text.header?.title}」；本文 ${body.size} 行，引言 ${quoted.size} 行")
                check(text.header?.author.equals(id, ignoreCase = true)) { "標頭的作者不是自己" }
                check(text.header?.title?.let(titleOk) == true) { "標題不對：「${text.header?.title}」" }
                check(body.firstOrNull { it.isNotBlank() } == reply.body.lines().first()) { "內文的第一行不是打的那行：${body.take(3)}" }
                check(reply.quote == quoted.isNotEmpty()) { "引言不對（要引用：${reply.quote}）：$quoted" }
                if (reply.quote) check(body.any { it.startsWith(": ") && it.contains("第二行 $stamp") }) { "引言裡沒有原文：$quoted" }
                if (reply.footer.isNotEmpty()) {
                    val footer = reply.footer.lines()
                    val trimmed = body.dropLastWhile { it.isBlank() }
                    check(trimmed.takeLast(footer.size) == footer) { "文末不是加的那幾列：${body.takeLast(4)}" }
                    // 文末前面剛好空一行，再上面是引言（或內文）
                    val above = trimmed.dropLast(footer.size).takeLast(2)
                    check(above.size == 2 && above[0].isNotBlank() && above[1].isBlank()) { "文末前面不是剛好空一行：${trimmed.takeLast(footer.size + 3)}" }
                    if (reply.quote) check(above[0].startsWith(": ")) { "文末不在引言後面：${trimmed.takeLast(footer.size + 3)}" }
                }
            }
            mine(replied.index!!)
            step("刪掉回文 #${replied.index}") { session.deleteArticle(board, replied.index!!) }?.let { list ->
                val gone = list.articles.firstOrNull { it.index == replied.index }
                if (gone == null || gone.deleted) replies -= replied.index!! else check(false) { "回文沒有刪掉" }
            }
        }
        replyOnce("回文（採用原標題、引用原文）", PostReply("QuietPTT 回文測試 $stamp", title = null, quote = true, footer = "-----\nSent from QuietPTT")) {
            it.startsWith("Re: ") && it.contains("自動測試 $stamp")
        }
        replyOnce("回文（新標題、不引用）", PostReply("QuietPTT 回文測試二 $stamp\n第二行", title = "Re: QuietPTT 回文新標題 $stamp", quote = false)) {
            it == "Re: QuietPTT 回文新標題 $stamp"
        }

        // 改標題
        mine(row.index!!)
        val newTitle = "[測試] QuietPTT 改過標題 $stamp"
        step("改標題「$newTitle」") { session.editTitle(board, row.index!!, newTitle) }?.let { after ->
            val shown = after.articles.firstOrNull { it.index == row.index }?.title
            say("  列表上：「$shown」")
            check(shown?.contains("改過標題") == true) { "列表上的標題沒變" }
        }

        // 編輯內文
        mine(row.index!!)
        val before = step("編輯前讀一次") { readAll(ref) } ?: return
        val newBody = "改過的內文 $stamp\n第二行也改了"
        // 推文下面插一則回應（只有自己推的那一則）
        val replyText = "回應推文 $stamp"
        val replies = before.pushAnchors().take(1).map { PushReply(it.fromEnd, it.line, replyText) }
        check(replies.isNotEmpty()) { "編輯前沒讀到推文，沒辦法測在推文下面回應" }
        step("編輯內文、在推文下面回應") { session.editArticle(board, row.index!!, before.editableBody(), newBody, replies) }
        step("讀回來對本文") { readAll(ref) }?.let { after ->
            val body2 = after.editableBody()
            say("  本文：$body2")
            check(body2.joinToString("\n").contains("改過的內文 $stamp") && body2.none { it.contains("自動測試") }) { "本文沒改成" }
            check(after.items().any { it is ArticleItem.Comment && it.push.text.contains(stamp) }) { "編輯之後推文不見了" }
            val items = after.items()
            val pushAt = items.indexOfFirst { it is ArticleItem.Comment && it.push.text.contains(stamp) }
            val replyAt = items.indexOfFirst { it is ArticleItem.Line && it.text.contains(replyText) }
            say("  推文在第 $pushAt 則、回應在第 $replyAt 則")
            check(replies.isEmpty() || replyAt == pushAt + 1) { "回應沒有插在推文下面" }
        }

        // 刪文
        mine(row.index!!)
        step("刪文") { notices("刪文") { session.deleteArticle(board, row.index!!) } }?.let { after ->
            val gone = after.articles.firstOrNull { it.index == row.index }
            say("  列表上：作者「${gone?.author}」「${gone?.title}」")
            check(gone == null || gone.deleted) { "沒有刪掉" }
            deleted = gone == null || gone.deleted
        }
    } finally {
        for (index in replies.toList()) {
            runCatching {
                mine(index)
                session.deleteArticle(board, index)
                say("  （清掉了自己回的 #$index）")
            }.onFailure { say("  ✗ 沒能清掉回文 #$index：${it.message}") }
        }
        val index = posted
        if (index != null && !deleted) {
            // 中途失敗：把自己那篇刪掉（一樣先確認是自己的）
            runCatching {
                mine(index)
                session.deleteArticle(board, index)
                say("  （清掉了自己發的 #$index）")
            }.onFailure { say("  ✗ 沒能清掉 #$index：${it.message}") }
        }
        session.close()
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        say(if (failed) "有步驟失敗" else "全部走完")
    }
}
