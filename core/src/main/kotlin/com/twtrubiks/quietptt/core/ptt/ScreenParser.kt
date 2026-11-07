package com.twtrubiks.quietptt.core.ptt

import com.twtrubiks.quietptt.core.terminal.Screen

/**
 * 畫面辨識：從 24x80 的文字認出目前在哪一頁、把列表讀成資料。
 *
 * 全部靠文字特徵，所以站方改版面會壞：每個判斷旁邊都註明認的是哪個字，
 * 測試用 `core/src/test/resources/screens/` 裡真的畫面（用 tools/capture 抓）。
 * 認不出來回 [Page.Unknown]，App 退到終端畫面。
 */
object ScreenParser {
    fun parse(screen: Screen): Page {
        val lines = screen.lines
        val top = lines.first()
        val bottom = lines.last()
        return when {
            screen.contains("請輸入代號") -> Page.Login(screen, loginError(lines))
            screen.contains("請輸入您的密碼") -> Page.PasswordPrompt(screen)
            screen.contains("您想刪除其他重複登入的連線嗎") -> Page.DuplicateLoginPrompt(screen)
            screen.contains("您要刪除以上錯誤嘗試的記錄嗎") -> Page.DeleteFailedLoginsPrompt(screen)
            screen.contains("您覺得這篇文章") -> Page.PushPrompt(screen)
            screen.contains("請按任意鍵繼續") -> Page.PressAnyKey(screen)
            top.contains("【主功能表】") -> Page.MainMenu(screen)
            top.contains("【我 的 最愛】") -> Page.Favorites(screen, boards(screen))
            bottom.contains("瀏覽") && bottom.contains("目前顯示") -> articleView(screen)
            top.contains("看板《") -> Page.ArticleList(screen, boardName(top), articles(screen))
            else -> Page.Unknown(screen)
        }
    }

    /** 登入頁上一次失敗的訊息，例如「密碼不對或無此帳號」。 */
    private fun loginError(lines: List<String>): String? =
        lines.map { it.trim() }.firstOrNull { it.contains("密碼不對") || it.contains("無此帳號") }

    /** 第一列「看板《Gossiping》」裡的板名。 */
    private fun boardName(top: String): String {
        val start = top.indexOf('《')
        val end = top.indexOf('》', start + 1)
        return if (start >= 0 && end > start) top.substring(start + 1, end) else ""
    }

    /**
     * 我的最愛的每一行：`  1 ˇ Gossiping    綜合 ◎[八卦] 板名  人氣`。
     * 游標所在的那行開頭是「●」或「>」。「◎」前面是序號、未讀標記、板名、分類，後面是看板標題（結尾可能有人氣）。
     * 目錄（資料夾）沒有分類那一欄、標題前是「□」。
     */
    private fun boards(screen: Screen): List<Board> = screen.lines.drop(LIST_FIRST_ROW).dropLast(1).mapNotNull { boardRow(it) }

    private fun boardRow(line: String): Board? {
        val cleaned = line.replaceFirst(CURSOR, " ")
        val marker = cleaned.indexOfFirst { it == '◎' || it == '□' }
        if (marker < 0) return null
        val left = cleaned.substring(0, marker).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val index = left.getOrNull(0)?.toIntOrNull() ?: return null
        // 未讀標記「ˇ」可能自己一格，也可能緊貼著板名
        var rest = left.drop(1)
        var unread = false
        if (rest.firstOrNull() == "ˇ") {
            unread = true
            rest = rest.drop(1)
        } else if (rest.firstOrNull()?.startsWith("ˇ") == true) {
            unread = true
            rest = listOf(rest[0].drop(1)) + rest.drop(1)
        }
        val name = rest.getOrNull(0) ?: return null
        val isFolder = cleaned[marker] == '□'
        val category = if (isFolder) "" else rest.getOrNull(1) ?: ""
        // TODO：人氣與板主其實是固定欄位，等有真的 fixture 改成照欄位切（Screen.text），不要用結尾的樣子猜
        val title = cleaned.substring(marker + 1).trim().replace(TRAILING_POPULARITY, "")
        return Board(index, name, category, title, unread, isFolder)
    }

    /**
     * 文章列表的每一行：`  1234 爆 11/07 author       □ [問卦] 標題`、`  ★  12 + 3 11/07 ...`。
     * 置底文的序號是「★」。推文數 1～99、爆（≥100）、X1～X9（-10 起每 10 一級）、XX（≤ -100）。
     */
    private fun articles(screen: Screen): List<ArticleSummary> =
        screen.lines.drop(LIST_FIRST_ROW).dropLast(1).mapNotNull { articleRow(it) }

    private fun articleRow(line: String): ArticleSummary? {
        val m = ARTICLE_ROW.matchEntire(line.replaceFirst(CURSOR, " ").trimEnd()) ?: return null
        val (index, mark, push, date, author, title) = m.destructured
        return ArticleSummary(
            index = index.toIntOrNull(),
            mark = mark.trim().firstOrNull(),
            pushCount = pushCount(push.trim()),
            date = date,
            author = author,
            title = title.trim(),
        )
    }

    private fun pushCount(text: String): Int? = when {
        text.isEmpty() -> null
        text == "爆" -> 100
        text == "XX" -> -100
        text.length == 2 && text[0] == 'X' && text[1].isDigit() -> -10 * text[1].digitToInt()
        else -> text.toIntOrNull()
    }

    /** 文章頁：最下面那列「瀏覽 第 1/3 頁 ( 33%)  目前顯示: 第 01~23 行」。 */
    private fun articleView(screen: Screen): Page {
        val bottom = screen.lines.last()
        val page = PAGE_OF.find(bottom)
        val rows = ROWS_SHOWN.find(bottom)
        if (page == null || rows == null) return Page.Unknown(screen)
        val progress = ArticleProgress(
            page = page.groupValues[1].toInt(),
            pages = page.groupValues[2].toInt(),
            firstLine = rows.groupValues[1].toInt(),
            lastLine = rows.groupValues[2].toInt(),
        )
        return Page.ArticleView(screen, screen.lines.dropLast(1).map { it.trimEnd() }, progress)
    }

    /** 列表從第 3 列（0 起算）開始：第 0 列標題、第 1 列說明、第 2 列欄位名稱。 */
    private const val LIST_FIRST_ROW = 3

    private val CURSOR = Regex("^[●>]")
    private val TRAILING_POPULARITY = Regex("\\s+(\\d+|爆!?|HOT)(\\s+\\S+)?\\s*$")
    // 序號（6 欄）、1 格標記（m/M 已標記、s/S 待處理…）、推文數（2 欄）、日期、作者、標題（開頭的「□」是一般文章的記號，去掉）
    private val ARTICLE_ROW = Regex("""^\s*(\d+|★)([^\s\d爆X]?)\s*(爆|XX|X\d|\d{1,3})?\s+(\d{1,2}/\d{1,2})\s+(\S+)\s+(?:□\s*)?(.*)$""")
    private val PAGE_OF = Regex("""第\s*(\d+)/(\d+)\s*頁""")
    private val ROWS_SHOWN = Regex("""第\s*(\d+)~(\d+)\s*行""")
}
