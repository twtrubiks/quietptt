package com.twtrubiks.quietptt.core.ptt

import com.twtrubiks.quietptt.core.terminal.ColorSpan
import com.twtrubiks.quietptt.core.terminal.Screen

/**
 * 認出來的畫面。[ScreenParser] 把 [Screen] 變成這個，Compose 照它畫原生的列表；
 * 認不出來的是 [Unknown]，App 會退到終端畫面讓使用者自己按，永遠不會卡死。
 */
sealed interface Page {
    val screen: Screen

    /** 「請輸入代號」；[error] 是上一次登入失敗的訊息（密碼不對之類）。 */
    data class Login(override val screen: Screen, val error: String? = null) : Page

    data class PasswordPrompt(override val screen: Screen) : Page

    /** 「您想刪除其他重複登入的連線嗎」。 */
    data class DuplicateLoginPrompt(override val screen: Screen) : Page

    /** 「您要刪除以上錯誤嘗試的記錄嗎」。 */
    data class DeleteFailedLoginsPrompt(override val screen: Screen) : Page

    /** 「請按任意鍵繼續」。 */
    data class PressAnyKey(override val screen: Screen) : Page

    data class MainMenu(override val screen: Screen) : Page

    /**
     * 看板列表（我的最愛，或按 `y` 列出全部看板）。[allBoards]：第 1 列的提示是「[y]只列最愛」而不是「[y]列出全部」，
     * 也就是正在列全部看板（pttbbs `board_fav_cmds`／`myfav_cmds`）；按 `/` 用中文關鍵字找看板時在這個模式。
     */
    data class Favorites(
        override val screen: Screen,
        val boards: List<Board>,
        val allBoards: Boolean = false,
        /**
         * 不是我的最愛，是分組討論區裡某個分類（或即時熱門看板）的看板列表：版面一樣（pttbbs `brdlist_coldefs`），
         * 只有最下面那列的標籤不同：我的最愛（按了 `y` 列出全部也一樣）是「 我的最愛 」，分類是「 看板列表 」（`brdlist_caption`，真機）。
         * 左鍵退回上一層（子分類或【分類看板】）。分類的列表第 1 列一直是「[y]只列最愛」，所以 [allBoards] 也是 true；
         * 但分類裡按 `y` 什麼都不做（`fav_cmd_yank`，真機）：**先看這個、再看 [allBoards]**。
         */
        val inClass: Boolean = false,
        /** 游標停在哪個序號（第 0 欄「>」或「●」）；找不到是 null。 */
        val cursor: Int? = null,
    ) : Page

    /**
     * 分組討論區的根目錄（主選單 `C`，第 0 列「【分類看板】」）：第 1～6 列是圖、第 7 列起每列一個分類（pttbbs `board.c`，真機）：
     * 10 格空白、7 格序號、隱板記號、2 格「ˇ」、40 格中文敘述、板主。沒有板名與欄位標頭。[cursor] 是游標所在的序號（第 10 欄「>」或「●」）。
     */
    data class ClassRoot(override val screen: Screen, val entries: List<ClassEntry>, val cursor: Int? = null) : Page

    /**
     * 私人信件區的選單（主選單 `M`，第 0 列「【私人信件區】」，真機）。[noMail]：按了 `R` 之後最下面那列說「 ◆ 您沒有來信 … [按任意鍵繼續]」
     * （pttbbs `m_read` 的 `vmsg`，照原始碼寫的）：PTT 在等一個鍵。
     */
    data class MailMenu(override val screen: Screen, val noMail: Boolean = false) : Page

    /** 主選單 `t` Enter 的【休閒聊天區】（查詢網友在這裡，pttbbs `talklist`；照原始碼寫的）。 */
    data class TalkMenu(override val screen: Screen) : Page

    /** 信箱的列表（第 0 列「【郵件選單】」，pttbbs `mailtitle`）。[cursor] 是游標所在的序號。 */
    data class MailList(override val screen: Screen, val mails: List<MailSummary>, val cursor: Int? = null) : Page

    /**
     * 找看板的輸入框（pttbbs `CompleteBoard`，在看板列表按 `s`「【 搜尋全站看板 】」或 `a`「【 增加我的最愛 】」）：
     * 第 1 列「請輸入看板名稱(按空白鍵自動搜尋): 」後面是打到一半的字（[typed]）；[adding] 是 `a` 的那種（「請輸入欲加入的看板名稱」）。
     * 按了空白鍵之後第 2 列「相關資訊一覽表」、第 3 列起是候選的板名（[candidates]，還沒列出來是 null）；
     * 一頁列不完時最後一格是「...」（[truncated]，**真機**：打「M」；再按空白鍵不會翻頁）。
     * [more] 是最下面「按空白鍵可列出更多項目」，照 pttbbs `name.c` 寫的，真機上沒看到過。
     */
    data class BoardNamePrompt(
        override val screen: Screen,
        val adding: Boolean,
        val typed: String,
        val candidates: List<String>?,
        val more: Boolean,
        val truncated: Boolean = false,
    ) : Page

    /** 看板列表按 `/`：第 22 列「請輸入看板中文關鍵字:」。 */
    data class BoardKeywordPrompt(override val screen: Screen) : Page

    /** 我的最愛按 `M`（移動位置）：第 22 列「請輸入新次序:」（pttbbs `imovefav`，照原始碼寫的）。答 1 起的新位置 Enter。 */
    data class MoveFavoritePrompt(override val screen: Screen) : Page

    /**
     * 看板裡的文章列表。[filtered] 是搜尋的結果（pttbbs 的 `MODE_SELECT`：標題列右邊的「看板《》」變成「系列《》」、最下面一列的「文章列表」變成「系列文章」，真機確認）：
     * 這時序號是結果裡的第幾筆（1 起），不是文章本來的序號；打序號 Enter 跳的也是結果裡的第幾筆。
     */
    data class ArticleList(
        override val screen: Screen,
        val board: String,
        val articles: List<ArticleSummary>,
        val filtered: Boolean = false,
        /** 游標停在哪一篇（序號）；在置底文上、或找不到游標是 null。 */
        val cursor: Int? = null,
        /**
         * 板裡現在有多少人：第 2 列欄位標題右邊的「人氣:179」（pttbbs `mbbsd/bbs.c` 的 `readtitle`，至少是 1，真機）。
         * 冷靜中的板那裡是「[靜]」，沒有數字，是 null。
         */
        val online: Int? = null,
    ) : Page {
        /** 是不是 [name] 這個看板（板名不分大小寫）。 */
        fun isBoard(name: String): Boolean = board.equals(name, ignoreCase = true)
    }

    /**
     * 文章內容的其中一頁；[body] 是扣掉最下面那列進度的 23 列（去掉列尾空白，有底色的空白留著：ANSI 圖是用上色的空白畫的）。
     * [colors] 跟 [body] 一列對一列，是那列有顏色的幾段（[ColorSpan]）；沒顏色的列是空的。
     */
    data class ArticleView(
        override val screen: Screen,
        val body: List<String>,
        val progress: ArticleProgress,
        val colors: List<List<ColorSpan>> = body.map { emptyList() },
        /**
         * 進度列畫到最右邊的「(←)離開」了。按鍵後面加 Ctrl-L 時 PTT 先清掉整個畫面、由上往下一列一列重畫（pttbbs `redrawwin`），
         * 進度列最後畫：看到「離開」就是整頁都到了，不用再等畫面安靜（`Session.pressPage`）。
         */
        val footerDone: Boolean = true,
    ) : Page

    /** 「您覺得這篇文章 1.值得推薦 2.給它噓聲 3.只加→註解 [1]? 」（pttbbs `comment`，按 `X` 之後）。 */
    data class PushPrompt(override val screen: Screen) : Page

    /** 推文內容的輸入框：最下面一列「推 代號:」（[type] 是推／噓／→）。作者本人或推太快時跳過上一個提示直接到這裡。 */
    data class CommentPrompt(override val screen: Screen, val type: PushType) : Page

    /** 文章列表按 `d` 之後第 1 列的「請確定刪除(Y/N)?[N] 」。 */
    data class DeletePrompt(override val screen: Screen) : Page

    /** 文章列表按 `T` 之後第 22 列的「標題：目前的標題」（pttbbs `edit_title`）；[current] 是框裡現在的字。 */
    data class TitlePrompt(override val screen: Screen, val current: String) : Page

    /**
     * 發文的表單（在文章列表按 Ctrl-P，pttbbs `do_general`）：第 19 列「發表文章於【 板名 】」、第 21 列「種類：1.問題 2.建議 …(1-8或不選)」。
     * [categories] 是列出來的分類（照順序，答的是第幾個）；選完種類（或按 Enter 不選）第 22 列出現「標題：」，[titleAsked] 為 true。
     */
    data class PostForm(
        override val screen: Screen,
        val board: String,
        val categories: List<String>,
        val titleAsked: Boolean,
    ) : Page

    /**
     * 文章列表按 `y` 之後最下面兩列的提示（pttbbs `do_generalboardreply`／`do_reply`，照原始碼寫的）：
     * 「▲ 回應至 (F)看板 (M)作者信箱 (B)二者皆是 (Q)取消？[F] 」（不能寄信的人沒有 M、B）、
     * 「▲ 無法回應至看板。 改回應至 (M)作者信箱 (Q)取消？[Q] 」、「很抱歉, 本板不開放回覆文章，要改回信給作者嗎？ [y/N]: 」。[text] 是那一列的字。
     */
    data class ReplyPrompt(override val screen: Screen, val text: String) : Page {
        /** 可以回到看板（有「(F)看板」）。 */
        val toBoard: Boolean get() = text.contains("(F)看板")

        /** 這個板不開放回文（`BRD_NOREPLY`）。 */
        val noReply: Boolean get() = text.contains("不開放回覆")
    }

    /**
     * 回文的表單（`y` 答 f 之後，pttbbs `do_post_article` 的 `do_reply_title`，照原始碼寫的）：清畫面、印板規，
     * 第 19 列「發表文章於【 板名 】」、第 20 列「原標題: Re: …」、第 21 列「採用原標題[Y/n]? 」（答 n 變成「新標題：」，後面預填原標題），
     * 然後第 22 列「請問要引用原文嗎(Y/N/All/Repost)？[Y] 」（`vedit2` → `do_quote`）。畫面不清，答過的留在上面：[step] 是正在問的那一個。
     */
    data class ReplyForm(override val screen: Screen, val board: String, val step: Step) : Page {
        enum class Step { KEEP_TITLE, NEW_TITLE, QUOTE, OTHER }
    }

    /**
     * 回文存檔時引言太多（pttbbs `check_quote`：「: 」開頭的列除以 4 比其他有字的列多）：第 4 列「本篇文章的引言比例超過 80%，請您做些微的修正：」、
     * 第 12 列「(E)繼續編輯 (W)強制寫入？[E] 」。這時還沒存。
     */
    data class QuoteRatioPrompt(override val screen: Screen) : Page

    /**
     * 編輯器（pttbbs `vedit`）：最下面一列「編輯文章 … ║插入│aipr║  1:  1」。[insertMode] 插入／取代，[indentMode] 自動縮排，
     * [line]／[column] 是游標在第幾行、第幾欄（1 起）。
     */
    data class Editor(
        override val screen: Screen,
        val insertMode: Boolean,
        val indentMode: Boolean,
        val line: Int = 1,
        val column: Int = 1,
    ) : Page

    /** 編輯器按 Ctrl-X 之後的「確定要儲存檔案嗎？」（標題列「檔案處理」）。 */
    data class SavePrompt(override val screen: Screen) : Page

    /** 存檔時的「請選擇簽名檔 (1-9, 0=不加 x=隨機)[0]: 」（有簽名檔的人才會被問）。 */
    data class SignaturePrompt(override val screen: Screen) : Page

    /** 匿名板存檔時的「請輸入你想用的ID，或直接按[Enter]暱名，或按[r][R]用真名：」。 */
    data class AnonymousPrompt(override val screen: Screen) : Page

    /** 「確定[y/N]」（匿名板用名稱之後、推文送出前）與「確定(Y/N)?[n]」（改標題）。[text] 是那一列的字（改標題要扣稿費時看得出來）。 */
    data class ConfirmPrompt(override val screen: Screen, val text: String = "") : Page

    /**
     * 寄信、回信途中、進編輯器之前的畫面（pttbbs `mail.c`）：第 0 列是 `vs_hdr` 的標題
     * 「【站內寄信】」（`m_send`）、「【回  信】」（`mail_reply`）或「【群組信件】」（`multi_reply`）。
     * 畫面不會清，答過的提示還留在上面：[step] 是正在問的那一個（[ScreenParser] 照流程由後往前認）。
     */
    data class MailForm(override val screen: Screen, val step: Step) : Page {
        enum class Step {
            /** 第 1 列「請輸入使用者代號: 」（`usercomplete(MSG_UID)`）。 */
            RECIPIENT,

            /** 第 2 列「主題：」（`mail_ui_send`，新的一封才問）。 */
            SUBJECT,

            /** 第 2 列「請問要回信給原發信者(r)還是全部的人(a)，或離開(q) [r/a/q]? 」（群組信件，`multi_reply`）。 */
            GROUP_REPLY,

            /** 第 7 列「確定仍然要寄信給對方嗎[y/N]? 」（寄件人的帳號被重新註冊過，`mail_reply`）。 */
            REREGISTERED,

            /** 第 4 列「採用原標題[Y/n]? 」（`do_reply_title`；第 3 列是「原標題: Re: …」）。 */
            KEEP_TITLE,

            /** 第 4 列「新標題：」，後面預填原標題（`getdata_str`）。 */
            NEW_TITLE,

            /** 第 22 列「今日免費額度(N封)已滿，寄此封信需 M Ptt幣，確定繼續？[y/N] 」（`mail_precheck_quota`）。 */
            QUOTA,

            /** 第 22 列「請問要引用原文嗎(Y/N/All/Repost)？[Y] 」（`do_quote`：回信進編輯器之前）。 */
            QUOTE,

            /** 都不是：正在印收信人、標題，或在等下一個提示。 */
            OTHER,
        }
    }

    /** 信寄出去之後第 22 列的「已順利寄出，是否自存底稿(Y/N)？[N] 」（pttbbs `hold_mail`；看到它就是寄出去了）。 */
    data class BackupPrompt(override val screen: Screen) : Page

    data class Unknown(override val screen: Screen) : Page
}
