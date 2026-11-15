package com.twtrubiks.quietptt.core.ptt

import com.twtrubiks.quietptt.core.terminal.Attr
import com.twtrubiks.quietptt.core.terminal.ColorSpan
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
        // 文章頁最下面那列是固定的進度列：先認它，內文裡寫了什麼字都不算
        if (bottom.trimStart().startsWith("瀏覽")) return articleView(screen)
        // 編輯器最下面那列是「編輯文章 … ║插入│aipr║  1:  1」（pttbbs `edit_msg`）：也先認它，正在打的字不算
        editor(screen)?.let { return it }
        // 找看板的輸入框與候選清單：最下面可能是「按空白鍵可列出更多項目 [按任意鍵繼續]」，要排在任意鍵前面
        boardNamePrompt(screen)?.let { return it }
        // 推文內容的輸入框「推 代號:」在最下面一列（蓋掉文章的進度列）
        commentPrompt(screen)?.let { return it }
        // 私人信件區按 R 沒有信：最下面那列是「 ◆ 您沒有來信 … [按任意鍵繼續]」（`vmsg`，照原始碼寫的），
        // 要在任意鍵的提示前面認，不然分不出是信箱空的
        if (top.filterNot { it.isWhitespace() }.startsWith("【私人信件區】") && bottom.contains("您沒有來信")) {
            return Page.MailMenu(screen, noMail = true)
        }
        prompt(screen)?.let { return it }
        postForm(screen)?.let { return it }
        // 只看固定的列（不像上面的提示整個畫面找）：「標題：」「請確定刪除」在文章標題裡也可能出現
        deletePrompt(screen)?.let { return it }
        titlePrompt(screen)?.let { return it }
        // 標題列比對前先拿掉空格（置中的字可能被補空格）
        val title = top.filterNot { it.isWhitespace() }
        // 標題列是「【這一頁】　中間的字　看板《最近進過的板》」：右邊的「看板《》」每一頁都有（進過板之後），
        // 只有左邊的【】分得出是哪一頁。真機：從 Soft_Job 退回我的最愛，標題列是「【看板列表】 批踢踢實業坊 看板《Soft_Job》」
        return when {
            title.startsWith("【主功能表】") -> Page.MainMenu(screen)
            // 我的最愛的標題列是「【看板列表】　　批踢踢實業坊」（2026-11 真機截圖），沒有「我的最愛」這幾個字；分類、即時熱門看板的列表也是這個標題（真機）。
            // 第 1 列是指令提示：我的最愛是「[y]列出全部」，按 y 列出全部看板之後變成「[y]只列最愛」（pttbbs `board_fav_cmds`，真機）；
            // 分類、即時熱門看板的列表一直是「[y]只列最愛」（`Class()` 先 `LIST_BRD()`，真機）
            // 最下面那列的標籤：我的最愛不管有沒有按 y 都是「 我的最愛 」，分類、即時熱門看板的列表是「 看板列表 」（`brdlist_caption`，真機）
            title.startsWith("【看板列表】") -> Page.Favorites(
                screen,
                boards(screen),
                allBoards = lines[1].contains("只列最愛"),
                inClass = !bottom.trimStart().startsWith("我的最愛"),
                cursor = boardCursor(screen),
            )
            // 分組討論區的根目錄（pttbbs `board.c` 的 `redraw_title("分類看板")`，真機）
            title.startsWith("【分類看板】") -> {
                val rows = classRows(screen)
                Page.ClassRoot(screen, rows.map { it.first }, cursor = rows.firstOrNull { it.second }?.first?.index)
            }
            // 私人信件區的選單（真機）；按了 R 沒有信的「您沒有來信」上面先認走了
            title.startsWith("【私人信件區】") -> Page.MailMenu(screen, noMail = bottom.contains("您沒有來信"))
            // 主選單 t Enter（pttbbs `talklist`，選單的標題是「Talk 【 休閒聊天區 】」裡【】的字；照原始碼寫的）
            title.startsWith("【休閒聊天區】") -> Page.TalkMenu(screen)
            // 主選單 u Enter（pttbbs `userlist`：「User 【 個人設定區 】」）與裡面的「My Files 【個人檔案】」（`myfilelist`）；照原始碼寫的
            title.startsWith("【個人設定區】") || title.startsWith("【個人檔案】") -> Page.UserMenu(screen)
            // 寄信、回信（pttbbs `mail.c` 的 `vs_hdr`：「【站內寄信】」「【回  信】」「【群組信件】」；照原始碼寫的）
            title.startsWith("【站內寄信】") || title.startsWith("【回信】") || title.startsWith("【群組信件】") -> mailForm(screen)
            // 信箱的列表（pttbbs `mailtitle`，真機）
            title.startsWith("【郵件選單】") -> {
                val rows = mailRows(screen)
                Page.MailList(screen, rows.map { it.first }, cursor = rows.firstOrNull { it.second }?.first?.index)
            }
            // 文章列表左邊是「【板主:某某】」（沒有板主時是別的字），所以認右邊的「看板《」；主選單、看板列表上面已經先認走了。
            // 搜尋結果（pttbbs `MODE_SELECT`）右邊是「系列《MobileComm》」（`mbbsd/menu.c` 的 `title_tail_msgs`，**真機確認**），
            // 最下面那列的「文章列表」也變成「系列文章」。認標題列：最下面那列會被提示蓋掉
            top.contains("看板《") || top.contains("系列《") -> {
                val rows = articleRows(screen)
                Page.ArticleList(
                    screen,
                    boardName(top),
                    rows.map { it.article },
                    filtered = top.contains("系列《"),
                    cursor = rows.firstOrNull { it.cursor }?.article?.index,
                    online = lines.getOrNull(2)?.let { ONLINE.find(it) }?.groupValues?.get(1)?.toIntOrNull(),
                )
            }
            else -> Page.Unknown(screen)
        }
    }

    /**
     * 編輯簽名檔的畫面上印出來的簽名檔（pttbbs `user.c` `showsignature`，照原始碼寫的）：第 2 列起，每個簽名檔先一列「【 簽名檔.N 】」
     * （N 是 1～9，一個比一個大），下面是內容，最多 6 列（`MAX_SIGLINES`），到下一個「【 簽名檔.」或畫面最下面為止。
     * 內容後面的空列去掉（畫面上分不出是簽名檔自己的空列還是沒印東西）；列尾的空白也去掉，有底色的留著。
     * 最後一個可能被畫面截掉（列數不夠就不印了）。
     */
    fun signatures(screen: Screen): List<SignatureFile> {
        val out = ArrayList<SignatureFile>()
        var number = 0
        var lines = ArrayList<String>()
        var colors = ArrayList<List<ColorSpan>>()
        fun flush() {
            if (number == 0) return
            while (lines.isNotEmpty() && lines.last().isBlank()) {
                lines.removeAt(lines.lastIndex)
                colors.removeAt(colors.lastIndex)
            }
            out += SignatureFile(number, lines, colors)
        }
        for (row in SIGNATURE_FIRST_ROW until screen.rows) {
            val line = screen.lines[row]
            val header = SIGNATURE_HEADER.matchEntire(line)?.groupValues?.get(1)?.toInt()
            if (header != null && header > number) {
                flush()
                number = header
                lines = ArrayList()
                colors = ArrayList()
            } else if (number != 0) {
                val spans = screen.spans(row)
                val keep = maxOf(line.trimEnd().length, spans.filter { it.bg != 0 }.maxOfOrNull { it.end } ?: 0)
                lines += line.take(keep)
                colors += spans.filter { it.start < keep }
            }
        }
        flush()
        return out
    }

    /**
     * 標題列有沒有寫「你有新信件」（pttbbs `menu.c` 的 `redraw_title`：`ISNEWMAIL` 時把中間那格的字換成紅底閃爍的「你有新信件」，照原始碼寫的）。
     * 中間那格平常是「批踢踢實業坊」，文章列表是板的中文名稱；左邊的【】、右邊的「看板《》」不變，[parse] 照樣認得出是哪一頁。
     * 只有第 0 列是標題列的頁回 true／false：主選單、看板列表、分類看板、私人信件區、休閒聊天區、個人設定區、信箱、文章列表。
     * 其他頁（文章內文、提示、編輯器、認不出來的）第 0 列不是標題列，回 null（看不出來）。
     */
    fun newMail(page: Page): Boolean? = when (page) {
        is Page.MainMenu, is Page.Favorites, is Page.ClassRoot, is Page.MailMenu, is Page.TalkMenu, is Page.UserMenu, is Page.MailList, is Page.ArticleList ->
            page.screen.lines.first().contains("你有新信件")
        else -> null
    }

    /**
     * 登入途中的提示常常疊在同一個畫面上：代號下面一列問密碼、密碼下面問要不要刪重複登入、再下面「請按任意鍵繼續」。
     * 游標停在正在問的那一列，也就是**最下面**的那個提示，所以照列號取最下面的。沒有任何提示回 null。
     */
    private fun prompt(screen: Screen): Page? {
        val lines = screen.lines
        var found: Prompt? = null
        var foundRow = -1
        for (prompt in Prompt.entries) {
            val row = lines.indexOfLast { line -> prompt.words.any { line.contains(it) } }
            // 只會畫在最下面兩列的提示，出現在上面（文章標題裡）不算
            if (prompt.bottomOnly && row < lines.size - 2) continue
            if (row > foundRow) {
                found = prompt
                foundRow = row
            }
        }
        return when (found) {
            null -> null
            Prompt.LOGIN -> Page.Login(screen, loginError(lines))
            Prompt.PASSWORD -> Page.PasswordPrompt(screen)
            Prompt.DUPLICATE_LOGIN -> Page.DuplicateLoginPrompt(screen)
            Prompt.DELETE_FAILED_LOGINS -> Page.DeleteFailedLoginsPrompt(screen)
            Prompt.PUSH -> Page.PushPrompt(screen)
            Prompt.SAVE -> Page.SavePrompt(screen)
            Prompt.SIGNATURE -> Page.SignaturePrompt(screen)
            Prompt.ANONYMOUS -> Page.AnonymousPrompt(screen)
            Prompt.MAIL_BACKUP -> Page.BackupPrompt(screen)
            Prompt.REPLY -> Page.ReplyPrompt(screen, lines[foundRow].trim())
            Prompt.QUOTE_RATIO -> Page.QuoteRatioPrompt(screen)
            Prompt.CONFIRM, Prompt.CONFIRM_YN, Prompt.DELETE_FAVORITE -> Page.ConfirmPrompt(screen, lines[foundRow].trim())
            Prompt.BOARD_KEYWORD -> Page.BoardKeywordPrompt(screen)
            Prompt.MOVE_FAVORITE -> Page.MoveFavoritePrompt(screen)
            Prompt.ANY_KEY -> Page.PressAnyKey(screen)
        }
    }

    /** 每一種提示認的字。密碼打錯的訊息也算登入頁：錯誤訊息在密碼那列下面，就是又回到要你輸入代號了。 */
    private enum class Prompt(val bottomOnly: Boolean, vararg val words: String) {
        LOGIN(false, "請輸入代號", "密碼不對", "無此帳號"),
        PASSWORD(false, "請輸入您的密碼"),
        DUPLICATE_LOGIN(false, "您想刪除其他重複登入的連線嗎"),
        DELETE_FAILED_LOGINS(false, "您要刪除以上錯誤嘗試的記錄嗎"),
        PUSH(false, "您覺得這篇文章"),
        // 發文存檔（pttbbs `edit.c`）：Ctrl-X 之後、簽名檔、匿名板的名稱與確認
        SAVE(false, "確定要儲存檔案嗎"),
        SIGNATURE(false, "請選擇簽名檔"),
        ANONYMOUS(false, "請輸入你想用的ID"),
        // 信寄出去之後（pttbbs `hold_mail`，第 22 列）：「已順利寄出，是否自存底稿(Y/N)？[N] 」；上面可能還留著簽名檔的畫面
        MAIL_BACKUP(true, "是否自存底稿"),
        // 文章列表按 `y`（pttbbs `do_generalboardreply` 第 22 列、`do_reply` 的 `vans` 第 23 列）：「▲ 回應至 (F)看板 …」
        // 「▲ 無法回應至看板。 改回應至 …」「很抱歉, 本板不開放回覆文章，…」。列表最下面那列是「(y)回應」，沒有「回應至」
        REPLY(true, "回應至", "不開放回覆文章"),
        // 回文存檔時引言太多（`check_quote`，第 12 列）：「(E)繼續編輯 (W)強制寫入？[E] 」；上面還留著「確定要儲存檔案嗎」
        QUOTE_RATIO(false, "(W)強制寫入"),
        CONFIRM(false, "確定[y/N]"),
        // 改標題（pttbbs `edit_title`）：「確定(Y/N)?[n] 」，要扣稿費時是「修改此標題需扣除全部稿費 N 批幣，確定(Y/N)?[n] 」
        CONFIRM_YN(true, "確定(Y/N)?"),
        // 看板列表按 `/`（`board_cmd_search_keyword`）
        BOARD_KEYWORD(true, "請輸入看板中文關鍵字"),
        // 我的最愛按 `d`（`fav_cmd_toggle_or_del`）：最下面那列「你確定刪除嗎? [N/y]」（`vans`，只有 y 算答應）；照原始碼寫的
        DELETE_FAVORITE(true, "你確定刪除嗎"),
        // 我的最愛按 `M`（`imovefav`）：第 22 列「請輸入新次序:」
        MOVE_FAVORITE(true, "請輸入新次序"),
        // 「請按任意鍵繼續」是整列置中的那種；「找不到符合條件的文章。 [按任意鍵繼續]」這種訊息（`vshowmsg`）右邊是「[按任意鍵繼續]」
        ANY_KEY(false, "按任意鍵繼續"),
    }

    /**
     * 發文的表單（pttbbs `do_general`）：第 19 列「發表文章於【 MobileComm 】 …看板」、
     * 第 21 列「種類：1.問題 2.建議 3.討論 … (1-8或不選)」（每個分類最多 4 個 byte，`%4.4s`）；選完之後第 22 列「標題：」。
     * 板規（`post.note`）會先印在上面幾列，不管它。
     * 回文（`y` 答 f）也是這個表單，但沒有「種類：」，是「原標題: 」「採用原標題[Y/n]? 」：認成 [Page.ReplyForm]。
     */
    private fun postForm(screen: Screen): Page? {
        val lines = screen.lines
        val headerRow = lines.indexOfFirst { it.contains("發表文章於") }
        if (headerRow < 0) return null
        val board = lines[headerRow].substringAfter("【", "").substringBefore("】").trim()
        val kindRow = lines.withIndex().firstOrNull { (row, line) -> row > headerRow && line.trimStart().startsWith("種類：") }?.index
            ?: return replyForm(screen, headerRow, board)
        val kinds = lines[kindRow].substringAfter("種類：")
        val categories = CATEGORY.findAll(kinds).map { it.groupValues[2] }.toList()
        val titleAsked = lines.drop(kindRow + 1).any { it.trimStart().startsWith("標題：") }
        return Page.PostForm(screen, board, categories, titleAsked)
    }

    /**
     * 回文的表單（pttbbs `do_reply_title(20, …)`，照原始碼寫的）：「發表文章於」下面第 20 列「原標題: 」、第 21 列「採用原標題[Y/n]? 」，
     * 答 n 之後同一列換成「新標題：」；最後第 22 列「請問要引用原文嗎」。答過的提示留在畫面上，**由後往前**認。
     * 「發表文章於」下面什麼都還沒有（正在畫）是 [Page.ReplyForm.Step.OTHER]。
     */
    private fun replyForm(screen: Screen, headerRow: Int, board: String): Page.ReplyForm {
        val lines = screen.lines
        val below = lines.drop(headerRow + 1)
        val step = when {
            lines[screen.rows - 2].contains("請問要引用原文嗎") -> Page.ReplyForm.Step.QUOTE
            below.any { it.trimStart().startsWith("新標題：") } -> Page.ReplyForm.Step.NEW_TITLE
            below.any { it.contains("採用原標題") } -> Page.ReplyForm.Step.KEEP_TITLE
            else -> Page.ReplyForm.Step.OTHER
        }
        return Page.ReplyForm(screen, board, step)
    }

    /**
     * 編輯器的狀態列：「║插入│aipr║  1:  1」（`edit_msg`：插入／取代、a 彩色、i 自動縮排、p 注音、r raw，大寫是開著；
     * 最後是游標的「行:欄」，`%3d:%3d`）。
     */
    private fun editor(screen: Screen): Page? {
        val bottom = screen.lines.last()
        if (!bottom.contains("編輯文章")) return null
        val status = EDITOR_STATUS.find(bottom) ?: return null
        return Page.Editor(
            screen,
            insertMode = status.groupValues[1] == "插入",
            indentMode = status.groupValues[2][1] == 'I',
            line = status.groupValues[3].toIntOrNull() ?: 1,
            column = status.groupValues[4].toIntOrNull() ?: 1,
        )
    }

    /**
     * 找看板的輸入框（pttbbs `CompleteBoard`→`complete_engine`，照原始碼寫的）：第 0 列「【 搜尋全站看板 】」或「【 增加我的最愛 】」，
     * 第 1 列「請輸入看板名稱(按空白鍵自動搜尋): 」或「請輸入欲加入的看板名稱(按空白鍵自動搜尋)：」，後面是打到一半的字。
     * 按了空白鍵：第 2 列「相關資訊一覽表」（**真機**是「---- 相關資訊一覽表 ----」），第 3 列起候選的板名一欄一欄排（`ShowVector`）。
     * 一頁放不下時（**真機**：打「M」）最後一格是「...」（[Page.BoardNamePrompt.truncated]），再按空白鍵畫面不變、不會翻頁，只能多打幾個字。
     * 最下面「按空白鍵可列出更多項目」（`vshowmsg`）是照原始碼留著的，真機上打「M」沒看到。
     */
    private fun boardNamePrompt(screen: Screen): Page? {
        val lines = screen.lines
        val prompt = lines[1]
        val adding = prompt.contains("請輸入欲加入的看板名稱")
        if (!adding && !prompt.contains("請輸入看板名稱")) return null
        val typed = prompt.substringAfterLast(')').trimStart(':', '：', ' ').trim()
        val listed = lines[2].contains("相關資訊一覽表")
        val candidates = if (!listed) {
            null
        } else {
            (LIST_FIRST_ROW until screen.rows - 1).flatMap { row -> lines[row].split(' ').filter { BOARD_NAME.matches(it) } }
        }
        val truncated = listed && (LIST_FIRST_ROW until screen.rows - 1).any { row -> lines[row].split(' ').any { it == "..." } }
        return Page.BoardNamePrompt(
            screen,
            adding,
            typed,
            candidates,
            more = lines.last().contains("按空白鍵可列出更多項目"),
            truncated = truncated,
        )
    }

    /** 推文內容的輸入框（pttbbs `comment`）：最下面一列「推 代號:」（對齊的板是「推 代號        :」），游標在冒號後面。 */
    private fun commentPrompt(screen: Screen): Page? {
        val bottom = screen.lines.last()
        val match = COMMENT_PROMPT.matchEntire(bottom) ?: return null
        val type = PushType.of(match.groupValues[1][0]) ?: return null
        return Page.CommentPrompt(screen, type)
    }

    /**
     * 寄信、回信進編輯器之前的畫面（pttbbs `m_send`／`mail_reply`／`multi_reply` → `mail_ui_send`，照原始碼寫的）。
     * 畫面不清，答過的提示留在上面，所以照流程**由後往前**認：第 22 列的引用原文、今日免費額度 → 帳號重新註冊 → 新標題 → 採用原標題 →
     * 群組信件 → 第 2 列「主題：」→ 第 1 列「請輸入使用者代號」。
     * （回信時答了「確定仍然要寄信給對方嗎」y 之後 PTT 從第 3 列起清掉，那一列不會留著。）
     */
    private fun mailForm(screen: Screen): Page.MailForm {
        val lines = screen.lines
        val promptRow = lines[screen.rows - 2]
        val step = when {
            promptRow.contains("請問要引用原文嗎") -> Page.MailForm.Step.QUOTE
            promptRow.contains("確定繼續？[y/N]") -> Page.MailForm.Step.QUOTA
            lines.any { it.contains("確定仍然要寄信給對方嗎") } -> Page.MailForm.Step.REREGISTERED
            lines.any { it.trimStart().startsWith("新標題：") } -> Page.MailForm.Step.NEW_TITLE
            lines.any { it.contains("採用原標題") } -> Page.MailForm.Step.KEEP_TITLE
            lines.any { it.contains("請問要回信給原發信者") } -> Page.MailForm.Step.GROUP_REPLY
            lines[2].trimStart().startsWith("主題：") -> Page.MailForm.Step.SUBJECT
            lines[1].contains("請輸入使用者代號") -> Page.MailForm.Step.RECIPIENT
            else -> Page.MailForm.Step.OTHER
        }
        return Page.MailForm(screen, step)
    }

    /** 文章列表按 `d`：第 1 列「請確定刪除(Y/N)?[N] 」（pttbbs `del_post`、`MSG_DEL_NY`）。 */
    private fun deletePrompt(screen: Screen): Page? =
        if (screen.lines[1].trimStart().startsWith("請確定刪除")) Page.DeletePrompt(screen) else null

    /** 文章列表按 `T`：第 22 列「標題：」後面預填目前的標題（pttbbs `edit_title`，`getdata_buf`）。 */
    private fun titlePrompt(screen: Screen): Page? {
        val row = screen.lines[screen.rows - 2]
        if (!row.startsWith("標題：")) return null
        return Page.TitlePrompt(screen, row.removePrefix("標題：").trim())
    }

    /** 登入頁上一次失敗的訊息，例如「密碼不對或無此帳號」。 */
    private fun loginError(lines: List<String>): String? =
        lines.map { it.trim() }.lastOrNull { it.contains("密碼不對") || it.contains("無此帳號") }

    /** 第一列「看板《Gossiping》」裡的板名。 */
    private fun boardName(top: String): String {
        val start = top.indexOf('《')
        val end = top.indexOf('》', start + 1)
        return if (start >= 0 && end > start) top.substring(start + 1, end) else ""
    }

    /**
     * 我的最愛（看板列表）的每一行。欄位是固定的（pttbbs `mbbsd/board.c` 的 `brdlist_coldefs`，欄位以半形算、0 起；真機對過）：
     *
     * ```
     * 0      游標「>」
     * 1～6   序號 %6d
     * 7      隱板記號
     * 8～9   未讀「ˇ」（全形，佔兩欄）
     * 10～22 板名
     * 23～29 分類（4 欄）、空白、「◎」看板／「Σ」群組／「☆」連結
     * 30～63 中文敘述
     * 64～66 人氣：「爆!」「HOT」「10 」「 3 」「   」——後面緊接著板主，沒有空格（真機：「爆!rayccccc/P…」）；顏色見 [Popularity]
     * 67～   板主（太長截成「…」）
     * ```
     *
     * 目錄（資料夾）另外畫（`brdlist_folder`）：「%7d %c MyFavFolder  目錄 □標題」，標題從第 30 欄起。
     * 分隔線（`brdlist_separator`）也有序號：「%7d %c ------------      -----…」，板名的位置是一串「-」，不是看板，跳過。
     */
    private fun boards(screen: Screen): List<Board> = (LIST_FIRST_ROW until screen.rows - 1).mapNotNull { boardRow(screen, it) }

    /** 看板列表的游標：第 0 欄是「>」（或舊式的「●」）的那一列的序號。 */
    private fun boardCursor(screen: Screen): Int? = (LIST_FIRST_ROW until screen.rows - 1)
        .firstOrNull { screen.text(it, 0, 1).let { c -> c == ">" || c == "●" } }
        ?.let { boardRow(screen, it)?.index }

    private fun boardRow(screen: Screen, row: Int): Board? {
        val index = screen.text(row, 0, 7).filter { it.isDigit() }.toIntOrNull() ?: return null
        val name = screen.text(row, 10, 23).trim().ifEmpty { return null }
        if (name.all { it == '-' }) return null // 分隔線
        // 我的最愛的目錄是「□」（brdlist_folder）；分類看板裡的子分類是「Σ」（BRD_GROUPBOARD，有類別欄）
        val symbol = screen.text(row, 27, 30).trim()
        val isFolder = symbol == "□" || symbol == "Σ"
        return Board(
            index = index,
            name = name,
            category = if (symbol == "□") "" else screen.text(row, 23, 27).trim(),
            title = (if (symbol == "□") screen.text(row, 30) else screen.text(row, 30, 64)).trim(),
            unread = screen.text(row, 8, 10).contains('ˇ'),
            isFolder = isFolder,
            popularity = if (symbol == "□") null else popularity(screen, row),
        )
    }

    /**
     * 第 [row] 列的人氣欄（64～66 欄）：字拿掉空白與「爆!」的「!」，顏色取第一個不是空白的字的（10 人以下是「 3 」，前面是空白）。
     * 整欄空白（沒人）是 null。
     */
    private fun popularity(screen: Screen, row: Int): Popularity? {
        val text = screen.text(row, 64, 67).trim().removeSuffix("!").ifEmpty { return null }
        val attr = (64 until 67).map { screen.cells[row][it] }.first { it.ch != ' ' }.attr
        return Popularity(text, fg = if (attr.reverse) attr.bg else attr.fg, bold = attr.bold)
    }

    /**
     * 文章列表的每一行。欄位是固定的（pttbbs `mbbsd/bbs.c` 的 `readdoent` 與欄寬 `bbs_coldefs`，欄位以半形算、0 起）：
     *
     * ```
     * 0     游標（「>」；舊式游標「●」佔 0～1 欄，會蓋掉 6 位數序號的第一位）
     * 1～6  序號 %6d 靠右；置底文是「  ★ 」
     * 7     空白
     * 8     標記：+ 未讀、~ 有新推文、m/M 已標記、! 已解決、s/S、=、*、#、D…（沒有就是空白）
     * 9～10 推文數：爆、1～99、X1～X9、XX、--（鎖文）
     * 11～16 日期 %-6.5s（「11/07 」「 7/18 」）
     * 17～29 作者
     * 30～  「□ 標題」（回文是「R: 」、轉錄「轉 」、鎖文「鎖 」、連署「ˇ 」，只拿掉一般文章的「□」）
     * ```
     *
     * 推文數後面**緊接著日期，沒有空格**（「+ 511/07」）：以前用正規式要求中間有空格，有推文的列全都認不出來。
     * 被刪的文章站方可能用 %7d 印序號、超過 6 欄被截成「…」，跟舊式游標一樣序號不可靠，改用上下列的序號推（列表上的序號是連號的）。
     */
    private fun articleRows(screen: Screen): List<ArticleRow> {
        val rows = (LIST_FIRST_ROW until screen.rows - 1).mapNotNull { articleRow(screen, it) }
        return rows.mapIndexed { i, row ->
            if (row.indexIsSure || row.article.index == null) return@mapIndexed row
            val before = rows.getOrNull(i - 1)?.takeIf { it.indexIsSure }?.article?.index
            val after = rows.getOrNull(i + 1)?.takeIf { it.indexIsSure }?.article?.index
            val index = before?.plus(1) ?: after?.minus(1) ?: row.article.index
            ArticleRow(row.article.copy(index = index), indexIsSure = true, cursor = row.cursor)
        }
    }

    /** [cursor]：游標在這一列（第 0 欄的「>」，或舊式游標「●」）。 */
    private class ArticleRow(val article: ArticleSummary, val indexIsSure: Boolean, val cursor: Boolean)

    private fun articleRow(screen: Screen, row: Int): ArticleRow? {
        val date = screen.text(row, 11, 17).trim()
        if (!DATE.matches(date)) return null
        val number = screen.text(row, 0, 7)
        val pinned = number.contains('★')
        val digits = number.filter { it.isDigit() }
        if (!pinned && digits.isEmpty()) return null
        val mark = screen.text(row, 8, 9).firstOrNull()?.takeUnless { it == ' ' }
        val title = screen.text(row, 30).trim().removePrefix("□").trim()
        val article = ArticleSummary(
            index = if (pinned) null else digits.toInt(),
            mark = mark,
            pushCount = pushCount(screen.text(row, 9, 11).trim()),
            date = date,
            author = screen.text(row, 17, 30).trim(),
            title = title,
        )
        return ArticleRow(
            article,
            indexIsSure = !number.contains('●') && !number.contains('…'),
            cursor = number.startsWith(">") || number.contains('●'),
        )
    }

    private fun pushCount(text: String): Int? = when {
        text.isEmpty() -> null
        text == "爆" -> 100
        text == "XX" -> -100
        text.length == 2 && text[0] == 'X' && text[1].isDigit() -> -10 * text[1].digitToInt()
        else -> text.toIntOrNull()
    }

    /**
     * 文章頁：最下面那列「瀏覽 第 1/7 頁 (  5%)  目前顯示: 第 01~22 行 (y)回應(X)推文 (←)離開(h)說明」（真機）。
     * 很長的文章一開始沒有總頁數：「瀏覽 第 1 頁 (  1%)」。
     *
     * 這一頁有依讀者不同的字（`\*s` 這類，系統信、一些公告）或移位碼時，pmore 把「目前顯示: 第 a~b 行」換成
     * 「▲此頁內容會依閱讀者不同,原文未必有您的資料」或「▲此頁內容含移位碼,可能會顯示偽造的系統訊息」
     * （`PMORE_MSG_WARN_FAKEUSERINFO`／`_MOVECMD`；真機，信箱裡的系統信）。
     * 第 1 頁的行號還算得出來（從第 1 行開始，跟 [ArticleText.plus] 一樣算：標頭下面的分隔線不算一行）；後面幾頁算不出來，認不得（App 退到終端畫面）。
     */
    private fun articleView(screen: Screen): Page {
        val bottom = screen.lines.last()
        val page = PAGE_OF.find(bottom)
        val percent = PERCENT.find(bottom)
        val rows = ROWS_SHOWN.find(bottom)
        if (page == null || percent == null) return Page.Unknown(screen)
        val rows23 = screen.rows - 1
        val spans = List(rows23) { screen.spans(it) }
        // 去掉列尾空白；有底色的空白留著（ANSI 圖是用上色的空白畫的，去掉就缺一塊）
        val body = List(rows23) { row ->
            val line = screen.lines[row]
            val keep = maxOf(line.trimEnd().length, spans[row].lastOrNull { it.bg != Attr.DEFAULT_BG }?.end ?: 0)
            line.substring(0, keep.coerceAtMost(line.length))
        }
        val colors = List(rows23) { row ->
            val keep = body[row].length
            spans[row].mapNotNull { span -> if (span.start >= keep) null else span.copy(end = span.end.coerceAtMost(keep)) }
        }
        val pageNumber = page.groupValues[1].toInt()
        val pages = page.groupValues[2].toIntOrNull()
        val percentValue = percent.groupValues[1].toInt()
        val (firstLine, lastLine) = when {
            rows != null -> rows.groupValues[1].toInt() to rows.groupValues[2].toInt()
            pageNumber == 1 && READER_WARNINGS.any { bottom.contains(it) } -> {
                val last = percentValue >= 100 || (pages != null && pageNumber >= pages)
                1 to firstPageLastLine(body, last)
            }
            else -> return Page.Unknown(screen)
        }
        val progress = ArticleProgress(page = pageNumber, pages = pages, percent = percentValue, firstLine = firstLine, lastLine = lastLine)
        return Page.ArticleView(screen, body, progress, colors, footerDone = bottom.contains("離開"))
    }

    /**
     * 第 1 頁最後一列是第幾行（進度列沒寫的時候）：有標頭的話第 3 列的分隔線不算一行。
     * 已經是最後一頁的話算到最後一個有字的列（下面空著的不是文章的行）。
     */
    private fun firstPageLastLine(body: List<String>, last: Boolean): Int {
        val separator = body.getOrNull(ArticleText.SEPARATOR_ROW)?.let(ArticleText::isSeparator) == true
        val row = if (last) body.indexOfLast { it.isNotBlank() }.coerceAtLeast(0) else body.lastIndex
        return if (separator && row > ArticleText.SEPARATOR_ROW) row else row + 1
    }

    /**
     * 【分類看板】根目錄的每一列（pttbbs `board.c`：`prints("          %s%s%*s %s", col_num, t_desc, pad, t_bm)`，`col_num` 是「%7d%c%s」；真機 對過）：
     *
     * ```
     * 0～9   空白（游標畫在第 10 欄，蓋掉序號前面的空白）
     * 10～16 序號 %7d
     * 17     隱板記號
     * 18～19 「ˇ」有新文章（全形）、「D 」選取、或空白
     * 20～59 中文敘述（40 欄）
     * 61～   板主
     * ```
     * 第二個值是游標在不在這一列。
     */
    private fun classRows(screen: Screen): List<Pair<ClassEntry, Boolean>> =
        (CLASS_FIRST_ROW until screen.rows - 1).mapNotNull { row ->
            val number = screen.text(row, 10, 17)
            val index = number.filter { it.isDigit() }.toIntOrNull() ?: return@mapNotNull null
            val title = screen.text(row, 20, 60).trim().ifEmpty { return@mapNotNull null }
            val cursor = number.first() == '>' || number.first() == '●'
            ClassEntry(index, title, unread = screen.text(row, 18, 20).contains('ˇ')) to cursor
        }

    /**
     * 信箱列表的每一列（pttbbs `mail.c` 的 `maildoent`：「%5d %c 」序號與狀態、日期 %-6s、作者、「◇ 標題」；真機 對過：游標在第 0 欄、序號到第 5 欄、狀態第 7 欄、日期第 9 欄起、作者第 15 欄起、標題第 30 欄起）。
     * 欄位之間的空白不算死，用正規式切：序號、狀態字元、日期「11/08」、作者（沒有空白）、剩下的是標題。第二個值是游標在不在這一列。
     */
    private fun mailRows(screen: Screen): List<Pair<MailSummary, Boolean>> =
        (LIST_FIRST_ROW until screen.rows - 1).mapNotNull { row ->
            val line = screen.lines[row]
            val m = MAIL_ROW.matchEntire(line) ?: return@mapNotNull null
            val (cursor, index, state, date, author, title) = m.destructured
            MailSummary(index.toInt(), state.firstOrNull() ?: ' ', date, author, title.removePrefix("◇").trim()) to cursor.isNotEmpty()
        }

    /**
     * 文章資訊的框（文章列表按 `Q`，pttbbs `bbs.c` `view_postinfo`）：游標那篇的上面或下面畫一個框，
     * 「┌──┐」與「└──┘」中間每一列是「│ 內容」，右邊界「│」另外印在第 76 欄：
     *
     * ```
     * │ 文章代碼(AID): #1f2-Ab_9 (MobileComm) [ptt.cc] [討論] s24u 綠線案例       │
     * │ 文章網址: https://www.ptt.cc/bbs/MobileComm/M.1762412345.A.1B2.html       │
     * │ 這一篇文章值 34 Ptt幣                                                     │
     * ```
     *
     * （價格那列也可能是「特殊文章，無價格記錄」或匿名文章的「匿名管理編號: …」。）框畫完之後最下面是 `pressanykey` 的「請按任意鍵繼續」。
     * 代碼是「文章代碼(AID)」那列「#」後面的 8 個字；網址是框裡第一個 http(s) 開頭的；其他不空的列照抄成 [ArticleInfo.notes]。沒有框回 null。
     */
    fun articleInfo(screen: Screen): ArticleInfo? {
        val lines = screen.lines
        val top = lines.indexOfFirst { it.trimStart().startsWith("┌") }
        if (top < 0) return null
        val bottom = (top + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith("└") } ?: return null
        val rows = lines.subList(top + 1, bottom)
            .map { it.trim().removePrefix("│").removeSuffix("│").trim() }
            .filter { it.isNotEmpty() }
        val aidRow = rows.firstOrNull { it.contains("AID") || it.contains("文章代碼") }
        val aid = aidRow?.let { AID.find(it)?.groupValues?.get(1) }
        val url = rows.firstNotNullOfOrNull { WEB_URL.find(it)?.value }
        val notes = rows.filter { it != aidRow && (url == null || !it.contains(url)) }
        return ArticleInfo(aid, url, notes)
    }

    /** 列表從第 3 列（0 起算）開始：第 0 列標題、第 1 列說明、第 2 列欄位名稱。 */
    private const val LIST_FIRST_ROW = 3

    /** 【分類看板】根目錄第 1～6 列是圖（`header_lines = 7`），列表從第 7 列開始。 */
    private const val CLASS_FIRST_ROW = 7

    // 信箱的一列：「>   12 + 11/08  someone        ◇ 標題」。狀態字元是 + 空白 M m R r D 其中一個（空白時那一格就是空白）
    private val MAIL_ROW = Regex("""^([>●])?\s*(\d+) ([+ MmRrD]) +(\d{1,2}/\d{2}) +(\S+) +(.*?)\s*$""")

    // 文章代碼：「#」後面 8 個字（pttbbs `aidu2aidc`：0-9A-Za-z、減號、底線）
    private val AID = Regex("""#([0-9A-Za-z_\-]{8})(?![0-9A-Za-z_\-])""")
    // 文章資訊框裡的網址：到空白或右邊界「│」為止
    private val WEB_URL = Regex("""https?://[^\s│]+""")

    // 文章列表第 2 列（欄位標題）右邊的「人氣:179」（pttbbs `readtitle` 的 `col_header_right`）
    /** 編輯簽名檔的畫面從第 2 列起印簽名檔（第 0 列是問句，`showsignature` 的 `move(2, 0)`）。 */
    private const val SIGNATURE_FIRST_ROW = 2

    /** 一個簽名檔的開頭那列「【 簽名檔.3 】」（`showsignature`）。 */
    private val SIGNATURE_HEADER = Regex("""【 簽名檔\.([1-9]) 】\s*""")

    private val ONLINE = Regex("""人氣:(\d+)""")

    // 日期欄：「11/07」「7/18」（前面的空白 trim 掉了）
    private val DATE = Regex("""\d{1,2}/\d{1,2}""")
    private val PAGE_OF = Regex("""第\s*(\d+)(?:/(\d+))?\s*頁""")
    private val PERCENT = Regex("""\(\s*(\d+)%\)""")
    private val ROWS_SHOWN = Regex("""(\d+)~(\d+)\s*行""")
    // pmore 換掉「目前顯示」的兩種警告（`pmore.c` 的 `PMORE_MSG_WARN_FAKEUSERINFO`、`PMORE_MSG_WARN_MOVECMD`）
    private val READER_WARNINGS = listOf("此頁內容會依閱讀者不同", "此頁內容含移位碼")

    // 「1.問題 2.建議 」：分類不含空白與括號（後面的「(1-8或不選)」不是分類）。每個分類是 `%4.4s`，不到 4 個 byte 的前面補空白：
    // 一個字的分類是「5.  圖」（**真機**：Test 板「1.測試 2.色彩 3.控制 4.簽名 5.  圖 6.動畫 7.互動 8.公告」）
    private val CATEGORY = Regex("""(\d)\.\s*([^\s(]+)""")
    private val EDITOR_STATUS = Regex("""║(插入|取代)│([aA][iI][pP][rR])║\s*(\d+):\s*(\d+)""")

    // 板名：英數、底線、減號、點（**真機**：候選清單裡有「M.D.Scout87」）；清單太長時最後一格是「...」，不是板名
    private val BOARD_NAME = Regex("""[A-Za-z0-9_.\-]*[A-Za-z0-9_\-][A-Za-z0-9_.\-]*""")

    // 「推 someone:」「噓 someone        :」（推文內容還沒打之前整列就這樣）
    private val COMMENT_PROMPT = Regex("""\s*([推噓→]) [A-Za-z0-9_]+\s*:\s*""")
}
