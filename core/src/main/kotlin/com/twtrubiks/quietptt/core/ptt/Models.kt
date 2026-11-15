package com.twtrubiks.quietptt.core.ptt

import com.twtrubiks.quietptt.core.terminal.Attr
import com.twtrubiks.quietptt.core.terminal.ColorSpan

/**
 * 我的最愛或看板列表上的一個看板。[category] 是「綜合」「八卦」這種分類；目錄（資料夾）的 [isFolder] 為 true。
 * [popularity] 是最右邊那欄的人氣（板裡現在有多少人），欄位空白（沒人、目錄）是 null。
 */
data class Board(
    val index: Int,
    val name: String,
    val category: String,
    val title: String,
    val unread: Boolean = false,
    val isFolder: Boolean = false,
    val popularity: Popularity? = null,
)

/**
 * 看板列表的人氣欄（pttbbs `mbbsd/board.c` 的 `col_nuser`，照原始碼寫的）。[text] 是 PTT 印的字，「爆!」拿掉「!」：
 * 1～99 人印人數，100 人以上「HOT」，1000 人以上「爆」；沒人但在投票是「V」，冷靜中是「靜」。
 * [fg]、[bold] 是那幾個字在 PTT 上的顏色（ANSI 0～7，[bold] 是 `1;3x` 的亮色）：PTT 靠顏色分級，只看字分不出是哪一級——
 * 11～50 人亮黃、51～99 人亮紅；HOT 與 1000 人以上的爆是亮白、2000 人以上亮紅、5000 人以上亮藍
 * （站方開了 `EXTRA_HOTBOARD_COLORS` 的話，一萬人以上依序是亮青、亮綠、亮黃、亮紫）。
 */
data class Popularity(val text: String, val fg: Int = Attr.DEFAULT_FG, val bold: Boolean = false)

/**
 * 分組討論區（主選單 `C`，【分類看板】）根目錄的一項：[index] 序號、[title] 中文敘述（根目錄不印板名）、[unread] 有新文章「ˇ」。
 * 線上最後一項（第 14 項）是「--> 即時熱門看板 <--」（pttbbs 的 `BRD_TOP` 群組，進去是人氣排名；真機）。
 */
data class ClassEntry(val index: Int, val title: String, val unread: Boolean = false) {
    /** 是不是「即時熱門看板」那一項。 */
    val isHot: Boolean get() = title.contains("即時熱門看板")

    /** 敘述的前半是名稱、兩個以上空白之後是說明（「國家研究院  專業區」→「國家研究院」）。 */
    val name: String get() = title.split(TWO_SPACES, limit = 2)[0]
    val description: String get() = title.split(TWO_SPACES, limit = 2).getOrElse(1) { "" }.trim()

    private companion object {
        val TWO_SPACES = Regex("""\s{2,}""")
    }
}

/**
 * 信箱（主選單 `M` → `R`，【郵件選單】）的一封信：[index] 序號、[state] 狀態字元（`+` 未讀、空白已讀、`M`/`m` 標記、`R`/`r` 已回信、`D` 選取），
 * [date]「11/08」、[author] 寄件人、[title] 標題（前面的「◇」拿掉；回信是「R: 」、轉寄是「轉 」留著）。
 */
data class MailSummary(val index: Int, val state: Char, val date: String, val author: String, val title: String) {
    val unread: Boolean get() = state == '+' || state == 'M' || state == 'R'
}

/** 文章列表上的一行。[index] 是序號（置底文是 null）；[pushCount] 是推文數字（「爆」是 100、「X1」是 -10，類推）。 */
data class ArticleSummary(
    val index: Int?,
    val mark: Char?,
    val pushCount: Int?,
    val date: String,
    val author: String,
    val title: String,
) {
    /**
     * 被刪掉的文章：作者欄是「-」，標題是「(本文已被刪除) [代號]」之類（**真機**：MobileComm 153906）。
     * 打不開：游標移到它上面按 Enter，畫面不會變（**真機**），等畫面變會等到逾時。
     */
    val deleted: Boolean get() = author == "-"

    /**
     * 標題拿掉列表上的回文、轉錄記號（pttbbs `readdoent`：回文「R: 」、轉錄「轉 」、鎖文「鎖 」、連署「ˇ 」；一般文章的「□」解析時就拿掉了）：
     * 剩下的是 pttbbs `subject()` 去掉「Re: 」「Fw: 」的那段，原文跟回文都有。看板長按「尋找同標題文章」拿它搜標題（PTT 的標題搜尋是找子字串）。
     * 列表太窄被切掉的標題也還是完整標題的一段，一樣搜得到。
     */
    val subject: String get() = title.replaceFirst(SUBJECT_MARK, "").trim()
}

/** 列表上標題前面的記號（[ArticleSummary.subject]）。後面一定接空白：「轉職…」這種標題不是轉錄。 */
private val SUBJECT_MARK = Regex("""^(R:|轉|鎖|ˇ)\s+""")

/**
 * 把另一頁的文章 [more] 併進目前列出來的這些：照序號排、同一個序號用 [more] 的（推文數比較新），
 * 置底文（沒有序號）放最後面——[more] 有置底文就用它的（只有最後一頁有置底文，它就是最新的那一份）。
 */
fun List<ArticleSummary>.mergedWith(more: List<ArticleSummary>): List<ArticleSummary> {
    val numbered = (filter { it.index != null } + more.filter { it.index != null })
        .associateBy { it.index!! } // 後面的（more）蓋掉前面的
        .toSortedMap()
        .values
    val pinned = more.filter { it.index == null }.ifEmpty { filter { it.index == null } }
    return numbered + pinned
}

/**
 * [more] 跟目前列出來的接不接得上：序號重疊或剛好相鄰。接不上的併進來中間會缺一段，而往下捲只從最舊的那篇往前載，缺的永遠補不回來。
 * 真機：搜尋結果按左鍵退出來，終端停在結果裡選的那篇所在的頁（MobileComm 的 153787，App 列的是 153871 起），不一定是最新的那頁。
 * 目前沒有一般文章、或 [more] 只有置底文，就算接得上。
 */
fun List<ArticleSummary>.connectsWith(more: List<ArticleSummary>): Boolean {
    val mine = mapNotNull { it.index }
    val theirs = more.mapNotNull { it.index }
    if (mine.isEmpty() || theirs.isEmpty()) return true
    return theirs.min() <= mine.max() + 1 && theirs.max() >= mine.min() - 1
}

/** 要打開文章列表上的哪一篇：一般文章看序號；置底文沒有序號，看它是倒數第幾個。 */
sealed interface ArticleRef {
    /** 序號 [index] 的那篇：打序號跳過去。 */
    data class Numbered(val index: Int) : ArticleRef

    /** 置底文：列表最後一筆往上數 [fromBottom] 筆（最後一筆是 0）。 */
    data class Pinned(val fromBottom: Int) : ArticleRef
}

/**
 * 文章頁最下面那一列的進度：「瀏覽 第 2/7 頁 ( 11%)  目前顯示: 第 22~44 行」。
 * 很長的文章一開始不知道總頁數（真機：「瀏覽 第 1 頁 (  1%)」），[pages] 是 null。行號從 1 起，是 PTT 算的（標頭的分隔線不算一行）。
 */
data class ArticleProgress(
    val page: Int,
    val pages: Int?,
    val percent: Int,
    val firstLine: Int,
    val lastLine: Int,
) {
    /** 最後一頁了：100%，或已經是第 [pages] 頁。 */
    val isLast: Boolean get() = percent >= 100 || (pages != null && page >= pages)
}

/**
 * 一篇文章到目前為止讀到的內容。[lines] 一列一行、照行號排（第一頁照畫面原樣，含標頭下面那條分隔線）；
 * [numbers] 跟 [lines] 一列對一列，是那列是 PTT 的第幾行（1 起；分隔線不算一行，跟它上面那行同號）。
 * 中間可以缺一段（「到底」直接跳到最後一頁、「檢查新推文」只重讀頭尾：[gaps]），缺的那段在 App 上是一列「載入中間的 N 行」。
 * [lastLine] 是從第 1 行起連著讀到第幾行；[end] 是文章的最後一行（看過最後一頁才知道，不然 null）；
 * [complete] 是整篇都有了（看過最後一頁、中間也不缺）。
 *
 * 每讀一頁用 [plus] 併進來：照行號放，同一行新讀的蓋掉舊的（真機：相鄰兩頁重疊一行，「01~22」「22~44」「44~66」；
 * 最後一頁對齊文章結尾，重疊更多，「110~132」之後是「116~138」）。讀到最後一頁時，比它的最後一行還後面的丟掉（文章被改短了）；
 * 讀到比 [end] 還後面的行，[end] 作廢（文章變長了：有新推文）。
 */
data class ArticleText(
    val lines: List<String>,
    val lastLine: Int,
    val complete: Boolean,
    /** 跟 [lines] 一列對一列：那列有顏色的幾段（[Page.ArticleView.colors]）；fixture 或純文字的文章全是空的。 */
    val colors: List<List<ColorSpan>> = lines.map { emptyList() },
    val numbers: List<Int> = lines.indices.map { it + 1 },
    val end: Int? = if (complete) lastLine else null,
) {
    /** 中間缺的行（不含最後一頁之後還沒讀的：那個看 [end]）。 */
    val gaps: List<IntRange>
        get() = buildList {
            var previous = 0
            for (number in numbers) {
                if (number > previous + 1) add(previous + 1..number - 1)
                previous = maxOf(previous, number)
            }
        }

    /** 有沒有讀過第 [line] 行。 */
    fun has(line: Int): Boolean = numbers.binarySearch(line) >= 0

    operator fun plus(page: Page.ArticleView): ArticleText {
        val progress = page.progress
        // 第一頁的第 3 列是標頭下面的分隔線「───」，不是文章的一行：它之後的列號要少算一
        val separator = progress.firstLine == 1 && page.body.getOrNull(SEPARATOR_ROW)?.let(::isSeparator) == true
        // 排序用的鍵：第 n 行是 2n，分隔線是 2×3＋1（排在第 3 行後面、第 4 行前面）
        val rows = java.util.TreeMap<Int, Row>()
        for (i in lines.indices) rows[sortKey(i)] = Row(numbers[i], lines[i], colors.getOrElse(i) { emptyList() })
        val fresh = ArrayList<Pair<Int, Row>>()
        for (row in page.body.indices) {
            val line = when {
                separator && row == SEPARATOR_ROW -> {
                    fresh += SEPARATOR_KEY to Row(SEPARATOR_ROW, page.body[row], page.colors.getOrElse(row) { emptyList() })
                    continue
                }
                separator && row > SEPARATOR_ROW -> progress.firstLine + row - 1
                else -> progress.firstLine + row
            }
            if (line > progress.lastLine) continue
            fresh += line * 2 to Row(line, page.body[row], page.colors.getOrElse(row) { emptyList() })
        }
        for ((key, row) in fresh) rows[key] = row
        val newEnd = when {
            progress.isLast -> progress.lastLine
            end != null && progress.lastLine > end -> null
            else -> end
        }
        val kept = if (newEnd == null) rows.values.toList() else rows.values.filter { it.number <= newEnd }
        var contiguous = 0
        for (row in kept) {
            if (row.number == contiguous + 1) contiguous++ else if (row.number > contiguous) break
        }
        return ArticleText(
            lines = kept.map { it.text },
            lastLine = contiguous,
            complete = newEnd != null && contiguous >= newEnd,
            colors = kept.map { it.colors },
            numbers = kept.map { it.number },
            end = newEnd,
        )
    }

    /** 第 [index] 列的排序鍵（[plus]）：跟上一列同號的是分隔線。 */
    private fun sortKey(index: Int): Int =
        if (index > 0 && numbers[index] == numbers[index - 1]) numbers[index] * 2 + 1 else numbers[index] * 2

    private class Row(val number: Int, val text: String, val colors: List<ColorSpan>)

    /**
     * 複製全文用的純文字：一列一行，列尾的空白去掉（標頭三列在 PTT 上是藍底一路畫到右邊，[lines] 為了上色把有底色的空白留著，
     * 複製出去就是一長串空白）。
     */
    fun plainText(): String = lines.joinToString("\n") { it.trimEnd() }

    companion object {
        val EMPTY = ArticleText(emptyList(), 0, complete = false, colors = emptyList())

        internal const val SEPARATOR_ROW = 3
        private const val SEPARATOR_KEY = SEPARATOR_ROW * 2 + 1

        internal fun isSeparator(text: String) = text.isNotBlank() && text.trim().all { it == '─' }
    }
}

/**
 * 查詢使用者的結果（pttbbs `my_query`）：[id] 與 [nickname] 是「《ＩＤ暱稱》id (暱稱)」，[fields] 是其他的「《標籤》值」照畫面的順序
 * （經濟狀況、登入次數、有效文章、目前動態、私人信箱、上次上站、上次故鄉、五子棋、象棋戰績……，站方加了欄位也照抄），
 * [plan] 是下面的名片檔（沒有就空的）。
 */
data class UserInfo(
    val id: String,
    val nickname: String?,
    val fields: List<Pair<String, String>>,
    val plan: List<String>,
)

/**
 * 自己的一個簽名檔（[Session.signatures]，pttbbs `showsignature` 印在畫面上的）：[number] 是 1～9，[lines] 是內容（最多 6 列，後面的空列去掉了），
 * [colors] 跟 [lines] 一列對一列，是那列有顏色的幾段。
 */
data class SignatureFile(
    val number: Int,
    val lines: List<String>,
    val colors: List<List<ColorSpan>> = lines.map { emptyList() },
)

/**
 * 文章資訊（文章列表按 `Q`，pttbbs `bbs.c` `view_postinfo`）：[aid] 是文章代碼（不含「#」，8 個字），[url] 是網頁版的網址，
 * [notes] 是框裡其他的列照抄（「這一篇文章值 N Ptt幣」「特殊文章，無價格記錄」「匿名管理編號: …」之類，站方加了也照抄）。
 * 讀不到的是 null。
 */
data class ArticleInfo(val aid: String?, val url: String?, val notes: List<String>)

/**
 * 看板裡搜尋文章的三種方式（pttbbs `mbbsd/read.c` 的 `read_nav_cmds`）：[key] 是在文章列表按的鍵，
 * [prompt] 是按了之後最下面一列出現的提示（`ask_filter_predicate`：「搜尋標題: 」「搜尋作者: 」「搜尋推文數高於多少 (<0則搜噓文數) 的文章: 」）。
 */
enum class SearchKind(val key: String, val label: String, val prompt: String) {
    TITLE("/", "標題", "搜尋標題"),
    AUTHOR("a", "作者", "搜尋作者"),
    /** 推文數高於多少；負數是噓文數。0 在 PTT 等於取消。 */
    PUSH_COUNT("Z", "推文數", "搜尋推文數"),
}

/** 一次搜尋：怎麼搜、搜什麼（[query] 已去掉前後空白）。 */
data class ArticleSearch(val kind: SearchKind, val query: String)

/**
 * 要發的一篇文章（`Session.post`）。[category] 是看板分類裡的第幾個（0 起；null 是「不選」，標題前不加「[分類]」），
 * [title] 不含分類（PTT 自己會加在前面），[signature] 是簽名檔：'0' 不加、'1'～'9' 第幾個、'x' 隨機（pttbbs 的「請選擇簽名檔」）。
 * [footer] 是接在內文最後面的字（App 的「Sent from QuietPTT」；空的就不加），跟內文之間空一行。
 */
data class NewPost(val category: Int?, val title: String, val body: String, val signature: Char = '0', val footer: String = "") {
    init {
        require(signature == 'x' || signature in '0'..'9') { "簽名檔只能是 0～9 或 x" }
    }
}

/**
 * 要寄的一封站內信（`Session.sendMail`）：[to] 收信人的代號、[title] 主題、[body] 內文；[signature] 跟發文一樣（'0' 不加、'1'～'9'、'x' 隨機），
 * [backup] 是「是否自存底稿」答 y（自己的信箱留一份）。
 */
data class NewMail(val to: String, val title: String, val body: String, val signature: Char = '0', val backup: Boolean = false) {
    init {
        require(signature == 'x' || signature in '0'..'9') { "簽名檔只能是 0～9 或 x" }
    }
}

/**
 * 回一封信（`Session.replyMail`）：[title] 是 null 就用 PTT 給的標題（「Re: 原標題」，「採用原標題」答 y），不然換成這個；
 * [quote] 是「請問要引用原文嗎」答 y（引言放在內文下面）；[signature]、[backup] 同 [NewMail]。
 */
data class MailReply(
    val body: String,
    val title: String? = null,
    val quote: Boolean = true,
    val signature: Char = '0',
    val backup: Boolean = false,
) {
    init {
        require(signature == 'x' || signature in '0'..'9') { "簽名檔只能是 0～9 或 x" }
    }
}

/**
 * 編輯自己的文章時，插在某則推文下面的回應（`Session.editArticle`）：[fromEnd]、[push] 是那則推文的 [PushAnchor]
 * （最後一列離文章最後一行幾行、那一列的文字），[text] 是要插進去的字（可以好幾列）。
 */
data class PushReply(val fromEnd: Int, val push: String, val text: String)

/**
 * 回一篇文章到看板（`Session.replyPost`）：[title] 是 null 就用 PTT 給的標題（「Re: 原標題」，「採用原標題」答 y），不然換成這個；
 * [quote] 是「請問要引用原文嗎」答 y（引言放在內文下面）；[signature]、[footer] 同 [NewPost]（有引言的話 [footer] 接在引言後面，整篇的最後）。
 */
data class PostReply(
    val body: String,
    val title: String? = null,
    val quote: Boolean = true,
    val signature: Char = '0',
    val footer: String = "",
) {
    init {
        require(signature == 'x' || signature in '0'..'9') { "簽名檔只能是 0～9 或 x" }
    }
}

/** 發文表單上的選項（Ctrl-P 之後 PTT 列的「種類」）：[categories] 是看板的分類（最多 8 個；沒設定的板是 pttbbs 預設的 8 個）。 */
data class PostOptions(val categories: List<String>)

/** 找看板（`Session.searchBoards`）的結果：[truncated] 是板名補完的清單太長、PTT 只列了一頁（最後一格「...」），要多打幾個字。 */
data class BoardSearchResult(val boards: List<Board>, val truncated: Boolean = false)
