package com.twtrubiks.quietptt.core.ptt

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

    data class Favorites(override val screen: Screen, val boards: List<Board>) : Page

    /** 看板裡的文章列表。 */
    data class ArticleList(override val screen: Screen, val board: String, val articles: List<ArticleSummary>) : Page

    /** 文章內容的其中一頁；[body] 是扣掉最下面那列進度的 23 列。 */
    data class ArticleView(override val screen: Screen, val body: List<String>, val progress: ArticleProgress) : Page

    /** 「您覺得這篇文章」推／噓／箭頭。 */
    data class PushPrompt(override val screen: Screen) : Page

    data class Unknown(override val screen: Screen) : Page
}
