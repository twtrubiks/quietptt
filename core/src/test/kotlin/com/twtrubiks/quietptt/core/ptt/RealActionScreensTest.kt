package com.twtrubiks.quietptt.core.ptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 發文、找看板的**真機**畫面（Test 板，`tools/capture --ws` 存的；發文走到存檔提示就放棄，沒有發出去）；
 * 回文、文章資訊是 `--e2e-write` 在 Test 板回自己那篇時存的。
 */
class RealActionScreensTest {
    @Test
    fun postFormOnTheTestBoard() {
        val form = ScreenParser.parse(Fixtures.screen("post-form")) as Page.PostForm
        assertEquals("Test", form.board)
        // 「5.  圖」：一個字的分類前面補空白（%4.4s），也要算進去，不然後面的號碼全部錯位
        assertEquals(listOf("測試", "色彩", "控制", "簽名", "圖", "動畫", "互動", "公告"), form.categories)
        assertFalse(form.titleAsked)
        // 種類按 Enter 不選之後第 22 列「標題：」
        assertTrue((ScreenParser.parse(Fixtures.screen("post-title")) as Page.PostForm).titleAsked)
    }

    @Test
    fun editorSaveAndAbort() {
        val editor = ScreenParser.parse(Fixtures.screen("post-editor")) as Page.Editor
        assertTrue(editor.insertMode)
        assertFalse(editor.indentMode)
        assertEquals(1, editor.line)
        assertEquals(1, editor.column)
        // Ctrl-X：「【檔案處理】」整頁，「確定要儲存檔案嗎？」要打字再按 Enter（真機：只送 a 的話只是打進輸入框）
        assertTrue(ScreenParser.parse(Fixtures.screen("post-save-prompt")) is Page.SavePrompt)
        // 答 a：「文章 沒有 存入」，最下面「請按任意鍵繼續」（上面那列「確定要儲存檔案嗎」不算）
        assertTrue(ScreenParser.parse(Fixtures.screen("post-aborted")) is Page.PressAnyKey)
    }

    @Test
    fun noticesAfterPostingAndDeleting() {
        // 發文存檔之後：第 0 列簽名檔（有簽名檔的人才問）、第 1 列「順利貼出佈告，本篇文章不列入記錄，敬請包涵。」（Test 板不計篇數）、最下面任意鍵
        val saved = ScreenParser.parse(Fixtures.screen("post-saved"))
        assertTrue("$saved", saved is Page.PressAnyKey)
        assertTrue(saved.screen.lines[1].startsWith("順利貼出佈告"))
        // 刪文答 y 之後：列表上半沒清掉、第 14 列「正在刪除文章: …」、最下面任意鍵
        val deleted = ScreenParser.parse(Fixtures.screen("delete-notice"))
        assertTrue("$deleted", deleted is Page.PressAnyKey)
        assertTrue(deleted.screen.lines[14].startsWith("正在刪除文章"))
    }

    @Test
    fun replyPromptAndFormOnTheTestBoard() {
        // `--e2e-write` 回自己那篇時存的（回出來的兩篇都刪掉了）。列表按 y：第 22 列「▲ 回應至 (F)看板 (M)作者信箱 (B)二者皆是 (Q)取消？[F]」，
        // 最下面那列還是列表的說明（裡面的「(y)回應」不能認成這個提示）
        val prompt = ScreenParser.parse(Fixtures.screen("reply-prompt")) as Page.ReplyPrompt
        assertTrue(prompt.toBoard)
        assertFalse(prompt.noReply)
        assertTrue(prompt.text.startsWith("▲ 回應至 (F)看板"))
        // 答 f：清畫面印發文綱領，第 19 列「發表文章於【 Test 】」、第 20 列「原標題: Re: …」、第 21 列「採用原標題[Y/n]?」
        val keep = ScreenParser.parse(Fixtures.screen("reply-form")) as Page.ReplyForm
        assertEquals("Test", keep.board)
        assertEquals(Page.ReplyForm.Step.KEEP_TITLE, keep.step)
        assertTrue(keep.screen.lines[20].startsWith("原標題: Re: [測試]"))
        // 答 y：同一列留著「採用原標題[Y/n]? y」，第 22 列問引用原文
        assertEquals(Page.ReplyForm.Step.QUOTE, (ScreenParser.parse(Fixtures.screen("reply-form-quote")) as Page.ReplyForm).step)
        // 答 n：同一列換成「新標題：」，後面預填「Re: 原標題」（Ctrl-Y 清掉再打）
        val newTitle = ScreenParser.parse(Fixtures.screen("reply-form-new-title")) as Page.ReplyForm
        assertEquals(Page.ReplyForm.Step.NEW_TITLE, newTitle.step)
        assertTrue(newTitle.screen.lines[21].startsWith("新標題：Re: [測試]"))
    }

    @Test
    fun articleInfoBoxOnTheTestBoard() {
        // Test 板自己那篇按 Q（之後刪掉了）：框畫在游標那列上面，下框線帶著「[再按 Q 切換價格列表]」，最下面任意鍵
        val screen = Fixtures.screen("article-info")
        assertTrue(ScreenParser.parse(screen) is Page.PressAnyKey)
        val info = ScreenParser.articleInfo(screen)!!
        assertEquals("1gyN_AS-", info.aid)
        assertEquals("https://www.ptt.cc/bbs/Test/M.1794211786.A.73E.html", info.url)
        assertEquals(listOf("這一篇文章值 0 Ptt幣"), info.notes)
    }

    @Test
    fun boardNameCompletion() {
        val page = ScreenParser.parse(Fixtures.screen("board-complete")) as Page.BoardNamePrompt
        assertEquals("Mo", page.typed)
        val candidates = page.candidates!!
        assertTrue(candidates.containsAll(listOf("Mo-Musume", "MobileComm", "mobilesales", "movie_Sugges", "MountainClub")))
        assertEquals(44, candidates.size)
        assertFalse(page.truncated)
        assertFalse(page.more)
    }

    @Test
    fun aLongCompletionListEndsWithDotsAndIsNotPaged() {
        // 打「M」：一頁放不下，最後一格「...」；真機再按空白鍵畫面不變
        val page = ScreenParser.parse(Fixtures.screen("board-complete-truncated")) as Page.BoardNamePrompt
        assertEquals("M", page.typed)
        assertTrue(page.truncated)
        assertFalse(page.more)
        val candidates = page.candidates!!
        assertTrue("M.D.Scout87" in candidates) // 板名可以有「.」
        assertFalse("..." in candidates)
        assertEquals(95, candidates.size)
    }

    @Test
    fun boardsByKeyword() {
        // 列出全部看板（[y]只列最愛）按 / 打「測試」
        val page = ScreenParser.parse(Fixtures.screen("board-keyword-results")) as Page.Favorites
        assertTrue(page.allBoards)
        assertEquals(listOf("SOB_TEST", "Test"), page.boards.map { it.name })
    }
}
