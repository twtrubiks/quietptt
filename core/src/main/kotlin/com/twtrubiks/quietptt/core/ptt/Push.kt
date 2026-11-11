package com.twtrubiks.quietptt.core.ptt

import com.twtrubiks.quietptt.core.terminal.ColorSpan
import com.twtrubiks.quietptt.core.terminal.Screen

/** 推文的種類：「推」「噓」「→」。 */
enum class PushType(val mark: Char) {
    PUSH('推'),
    BOO('噓'),
    ARROW('→'),
    ;

    companion object {
        fun of(mark: Char): PushType? = entries.firstOrNull { it.mark == mark }
    }
}

/**
 * 一則推文。[text] 是內容（去掉前後空白）；[time] 是「07/18 09:54」（很舊的推文只有「07/18」）；
 * 開了顯示 IP 的板有 [ip]。PTT 把太長的推文切成好幾列（第二列起是「→」、同一個人、同一個時間），
 * [ArticleText.items] 會把它們接回一則。
 */
data class Push(
    val type: PushType,
    val user: String,
    val text: String,
    val time: String,
    val ip: String? = null,
)

/**
 * 文章的標頭：第一頁最上面三列（**真機**）
 * 「 作者  chanifa (股師)                                        看板  MobileComm」「 標題  [討論] s24u 綠線案例」「 時間  Fri Nov  6 09:19:28 2026」。
 * 轉錄、站內信之類的少一兩列也認得（至少要有「作者」那列）。
 */
data class ArticleHeader(
    val author: String,
    val nickname: String? = null,
    val board: String? = null,
    val title: String? = null,
    val time: String? = null,
)

/** 文章的一個段落：本文的一列、簽名檔的一列，或一則推文。 */
sealed interface ArticleItem {
    /** 本文的一列；[colors] 是這列有顏色的幾段（PTT 的 ANSI 色，[ArticleText.colors]），沒有就空的。 */
    data class Line(val text: String, val colors: List<ColorSpan> = emptyList()) : ArticleItem

    /** 簽名檔那幾列「※ 發信站: …」「※ 文章網址: …」「※ 編輯: …」（BePTT 把它們縮小、變淡）。 */
    data class Signature(val text: String) : ArticleItem

    /**
     * [floor] 是第幾樓（所有推文一起數，1 起）；[nth] 是這種標示的第幾則（BePTT 的「12樓/推5」）。
     * 前面有還沒讀的行（[Gap]）就數不出來，兩個都是 0。
     */
    data class Comment(val push: Push, val floor: Int, val nth: Int) : ArticleItem

    /** 中間還沒讀的第 [from]～[to] 行（[ArticleText.gaps]）。 */
    data class Gap(val from: Int, val to: Int) : ArticleItem {
        val size: Int get() = to - from + 1
    }
}

/** [items] 的一則加上一個不會變的 [key]（App 列表的 key）：前面插進新讀的行時，列表照 key 留在原地，不會跳。 */
data class ArticleRow(val key: Int, val item: ArticleItem)

/** 簽名檔「※ 文章網址: https://www.ptt.cc/bbs/…」那列的網址（App 的「分享」）；還沒翻到或沒有那列就 null。 */
fun ArticleText.articleUrl(): String? = lines.firstNotNullOfOrNull { ARTICLE_URL.find(it)?.groupValues?.get(1) }

private val ARTICLE_URL = Regex("""^※\s+文章網址[:：]\s*(https?://\S+)""")

/** 標頭（[ArticleHeader]）；還沒有標頭（還沒載到、或不是一般文章）就 null。 */
val ArticleText.header: ArticleHeader?
    get() {
        val first = lines.firstOrNull() ?: return null
        val author = AUTHOR_LINE.matchEntire(first) ?: return null
        val title = lines.getOrNull(1)?.let { TITLE_LINE.matchEntire(it) }?.groupValues?.get(1)
        val time = lines.getOrNull(2)?.let { TIME_LINE.matchEntire(it) }?.groupValues?.get(1)
        return ArticleHeader(
            author = author.groupValues[1],
            nickname = author.groupValues[2].ifEmpty { null },
            board = author.groupValues[3].ifEmpty { null },
            title = title?.ifBlank { null },
            time = time?.ifBlank { null },
        )
    }

/** 標頭「作者」那欄的代號；還沒有標頭就 null。用來標出作者自己的推文。 */
val ArticleText.author: String?
    get() = header?.author

/**
 * 把文章的列切成本文、簽名檔與推文。認得出標頭（[header]）的話標頭那幾列與下面那條「───」不算本文（App 另外畫成標題卡）。
 *
 * 推文列照 pttbbs `mbbsd/bbs.c` `recommend` 與 `mbbsd/comments.c` `FormatCommentString` 的欄位切：
 * 0～1 標示（推／噓／→，全形佔兩欄）、2 空白、3 起代號、緊接「:」，然後內容補到第 66 欄、66 空白、67～77 時間「MM/DD HH:MM」
 * （`maxlength`＝78－3－6－1－6－代號長度，所以時間的位置跟代號多長無關）。
 * 代號後面補不補空白是**看板的設定**（`BRD_ALIGNEDCMT`）：開了補到 IDLEN＝12，「:」在第 15 欄（**真機**：MobileComm）；
 * 沒開「:」緊接代號（**真機**：Soft_Job「推 CaptPlanet: 不用了，謝謝」）。
 * 板上開了顯示 IP（`BRD_COMMENT_SHOWIP`）時內容少 15 欄：內容到第 50 欄、51～65 IP 靠右、66 空白、67～ 時間。
 * 很舊的推文只有日期：內容到第 72 欄、73～77「MM/DD」。
 * 認不出來的列照樣是本文，不會丟。
 *
 * PTT 把超過寬度的推文切成多列，接下來那幾列是「→」、同一個人、同一個時間：上一列是滿的（內容填到接近欄寬）才接起來，
 * 不然是那個人在同一分鐘內真的推了兩則。
 */
fun ArticleText.items(): List<ArticleItem> = rows().map { it.item }

/** 同 [items]，每一則帶著 key：本文、簽名檔、推文是它第一列的行號乘 2（分隔線那種同號的列加 1），缺的那段是負的。 */
fun ArticleText.rows(): List<ArticleRow> = headerRows().let { skip ->
    lines.drop(skip).toRows(colors.drop(skip), numbers.drop(skip))
}

/**
 * 可以編輯的本文（App 的「編輯文章」、[Session.editArticle]）：標頭三列與分隔線之後、簽名檔之前的那些列。
 * 簽名檔從「※ 發信站」前面那列「--」算起（pttbbs `write_file` 存檔時加的）；沒有「--」就從「※ 發信站」算起；都沒有就整篇到底。
 * 推文在簽名檔後面，不會算進來。整篇要讀完（[ArticleText.complete]）才準。
 */
fun ArticleText.editableBody(): List<String> {
    val body = lines.drop(headerRows())
    val site = body.indexOfFirst { it.trimStart().startsWith("※ 發信站") }
    val end = when {
        site < 0 -> body.size
        site > 0 && body[site - 1].trimEnd() == "--" -> site - 1
        else -> site
    }
    return body.subList(0, end).map { it.trimEnd() }
}

/** 標頭佔幾列：作者、標題、時間，加上下面那條分隔線；沒有標頭就是 0。 */
private fun ArticleText.headerRows(): Int {
    if (header == null) return 0
    var skip = 1
    if (lines.getOrNull(skip)?.let(TITLE_LINE::matches) == true) skip++
    if (lines.getOrNull(skip)?.let(TIME_LINE::matches) == true) skip++
    if (lines.getOrNull(skip)?.let(::isHeaderSeparator) == true) skip++
    return skip
}

private fun isHeaderSeparator(text: String) = text.isNotBlank() && text.trim().all { it == '─' }

/** [colors] 跟這些列一列對一列（少給的當沒顏色）；只有本文的列帶顏色，簽名檔與推文照 App 自己的樣式畫。 */
internal fun List<String>.toItems(colors: List<List<ColorSpan>> = emptyList()): List<ArticleItem> =
    toRows(colors, indices.map { it + 1 }).map { it.item }

/** [numbers] 是每一列的行號（[ArticleText.numbers]）：跳號的地方插一則 [ArticleItem.Gap]，之後的樓層數不出來。 */
internal fun List<String>.toRows(colors: List<List<ColorSpan>>, numbers: List<Int>): List<ArticleRow> {
    val out = ArrayList<ArticleRow>(size)
    var lastFull = false // 上一則推文的最後一列是不是滿的（可能被切了）
    var floor = 0
    var floorsKnown = true
    val nth = IntArray(PushType.entries.size)
    for ((index, line) in withIndex()) {
        val number = numbers[index]
        val previousNumber = numbers.getOrNull(index - 1)
        if (previousNumber != null && number > previousNumber + 1) {
            out += ArticleRow(-(previousNumber + 1) * 2, ArticleItem.Gap(previousNumber + 1, number - 1))
            lastFull = false
            floorsKnown = false
        }
        val key = if (previousNumber == number) number * 2 + 1 else number * 2
        val parsed = parsePush(line)
        if (parsed == null) {
            val item = if (SIGNATURE.containsMatchIn(line)) ArticleItem.Signature(line) else ArticleItem.Line(line, colors.getOrElse(index) { emptyList() })
            out += ArticleRow(key, item)
            lastFull = false
            continue
        }
        val (push, full) = parsed
        val last = out.lastOrNull()
        val previous = last?.item as? ArticleItem.Comment
        if (previous != null && lastFull && push.type == PushType.ARROW &&
            push.user == previous.push.user && push.time == previous.push.time && push.ip == previous.push.ip
        ) {
            out[out.lastIndex] = last.copy(item = previous.copy(push = previous.push.copy(text = previous.push.text + push.text)))
        } else if (floorsKnown) {
            out += ArticleRow(key, ArticleItem.Comment(push, floor = ++floor, nth = ++nth[push.type.ordinal]))
        } else {
            out += ArticleRow(key, ArticleItem.Comment(push, floor = 0, nth = 0))
        }
        lastFull = full
    }
    return out
}

/**
 * 這一列是不是 ASCII 圖、對齊的表格之類要靠等寬字型的東西：有製表符（「│─┌┐└┘├┤┬┴┼」、「═║╔」）、方塊（「█▓▒░▀▄」），
 * 或字與字之間連著 3 個以上空白（用空白排的欄位）。本文其他的列用一般字型自動換行（BePTT 全部用一般字型，表格會歪）。
 */
fun looksLikeArt(line: String): Boolean =
    line.any { it in '\u2500'..'\u259F' } || ALIGNED_BY_SPACES.containsMatchIn(line)

/** 一列文字裡的網址（http／https）：從哪個字到哪個字、網址本身。結尾的標點（「。」「，」「)」之類）不算網址。 */
fun findUrls(text: String): List<Pair<IntRange, String>> = URL.findAll(text).mapNotNull { match ->
    val url = match.value.trimEnd { it in URL_TRAILING }
    if (url.length <= "https://".length) null
    else (match.range.first until match.range.first + url.length) to url
}.toList()

/** 推文列切出來的 [Push]，和它的內容是不是把欄位填滿了（下一列可能是它的延續）。 */
private data class ParsedPush(val push: Push, val full: Boolean)

private fun parsePush(line: String): ParsedPush? {
    val type = line.getOrNull(0)?.let(PushType::of) ?: return null
    if (line.getOrNull(1) != ' ') return null
    // 代號（英數字，最多 IDLEN＝12）後面可能補了空白（看板有開對齊），接著「:」；標示佔 0～1 欄、2 是空白，所以代號從第 3 欄起
    val colon = line.indexOf(':', startIndex = 2).takeIf { it > 2 } ?: return null
    val user = line.substring(2, colon).trimEnd()
    if (user.isEmpty() || user.length > IDLEN || !user.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return null
    val columns = Columns(line)
    val contentStart = columns.startOf(colon + 1)

    val time = columns.text(67).trim()
    if (TIME.matches(time)) {
        val ip = columns.text(51, 66).trim().takeIf { IP.matches(it) }
        val contentEnd = if (ip != null) 51 else 66
        val content = columns.text(contentStart, contentEnd)
        return ParsedPush(Push(type, user, content.trim(), time, ip), full = isFull(content, contentEnd - contentStart))
    }
    val date = columns.text(73).trim()
    if (DATE.matches(date)) {
        val content = columns.text(contentStart, 73)
        return ParsedPush(Push(type, user, content.trim(), date), full = isFull(content, 73 - contentStart))
    }
    return null
}

/** 照終端的欄位切一列文字：全形字（非 ASCII）佔兩欄，跟 [Screen.text] 一樣。 */
private class Columns(private val line: String) {
    // starts[i] 是第 i 個字的起始欄
    private val starts = IntArray(line.length + 1).also { starts ->
        var col = 0
        for ((i, ch) in line.withIndex()) {
            starts[i] = col
            col += if (Screen.isWide(ch)) 2 else 1
        }
        starts[line.length] = col
    }

    /** 第 [index] 個字從第幾欄開始。 */
    fun startOf(index: Int): Int = starts[index.coerceIn(0, line.length)]

    /** 第 [fromCol] 欄（含）到 [toCol] 欄（不含）的字；全形字只有一半在範圍內時照樣取。 */
    fun text(fromCol: Int, toCol: Int = Int.MAX_VALUE): String = buildString {
        for ((i, ch) in line.withIndex()) {
            val start = starts[i]
            val end = starts[i + 1]
            if (end > fromCol && start < toCol) append(ch)
        }
    }
}

private fun widthOf(text: String): Int = text.sumOf { if (Screen.isWide(it)) 2 else 1 }

/** 內容欄（[width] 欄）是不是幾乎填滿了：下一列才可能是被切開的後半。 */
private fun isFull(content: String, width: Int): Boolean = widthOf(content.trimEnd()) >= width - FULL_SLACK

/** 代號最長幾個字（pttbbs IDLEN）。 */
private const val IDLEN = 12

/**
 * 離填滿還差幾欄以內算「滿的」。**真機**（MobileComm、Soft_Job、Stock 各一篇）：被切開的那列最多填到差 1～3 欄
 * （「…的時候怎」＋「麼有效率地篩選」、「…主鏡頭 超」＋「廣角」都差 3 欄），偶爾 4；自己分開打的兩列差 8 欄以上。
 */
private const val FULL_SLACK = 4

// 標頭三列（真機）：「 作者  chanifa (股師)   看板  MobileComm」，暱稱與看板可能沒有（站內信、轉錄）
// 信件的標頭右邊是「站內  收件人」（pttbbs `mail.c`），不是「看板」：放進 board 那格
private val AUTHOR_LINE = Regex("""^\s*作者\s+(\S+)(?:\s+\((.*)\))?(?:\s+(?:看板|站內)\s+(\S+))?\s*$""")
private val TITLE_LINE = Regex("""^\s*標題\s*(.*?)\s*$""")
private val TIME_LINE = Regex("""^\s*時間\s*(.*?)\s*$""")

// 簽名檔那幾列（BePTT 用同一個樣子認）
private val SIGNATURE = Regex("""^※\s+(發信站|文章網址|轉錄者|編輯|轉錄至|引述)[:：]""")

// 字與字之間連著 3 個以上空白：用空白排的欄位
private val ALIGNED_BY_SPACES = Regex("""\S {3,}\S""")

// 網址只有 ASCII（RFC 3986 的那些字，不含引號與括號；中文、全形標點都不是網址）
private val URL = Regex("""https?://[A-Za-z0-9\-._~:/?#\[\]@!$&*+,;=%]+""")
private const val URL_TRAILING = ".,;:!?"

private val TIME = Regex("""\d\d/\d\d \d\d:\d\d""")
private val DATE = Regex("""\d\d/\d\d""")
private val IP = Regex("""\d{1,3}(\.\d{1,3}){3}""")
