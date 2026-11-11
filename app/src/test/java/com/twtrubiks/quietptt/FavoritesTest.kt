package com.twtrubiks.quietptt

import com.twtrubiks.quietptt.core.ptt.Board
import com.twtrubiks.quietptt.core.ptt.Popularity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 我的最愛存進 saved state 再讀回來（轉螢幕、從看板回來先列上次的）。 */
class FavoritesTest {
    @Test
    fun boardsSurviveSavedState() {
        val boards = listOf(
            Board(1, "Stock", "學術", "◎[股票] 標題", unread = true, popularity = Popularity("爆", fg = 4, bold = true)),
            Board(2, "MyFavFolder", "", "我的目錄", isFolder = true),
            Board(25, "C_Chat", "閒談", "", popularity = Popularity("3")),
        )
        assertEquals(boards, decodeBoards(encodeBoards(boards)))
    }
}
