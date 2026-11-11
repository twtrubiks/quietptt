package com.twtrubiks.quietptt.core.ptt

import com.twtrubiks.quietptt.core.terminal.Keys
import com.twtrubiks.quietptt.core.transport.FakePtt
import com.twtrubiks.quietptt.core.transport.WebSocketTransport
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分組討論區、即時熱門看板、信箱（`Session.showClasses`／`showClass`／`hotBoards`／`showMails`／`wholeMail`）送什麼鍵，標題列的「你有新信件」（`Session.newMail`）。
 * 畫面是真機錄的：【分類看板】、即時熱門看板（第 14 項）、「戰略高手」裡的子分類、子分類「CompScience」的看板、私人信件區；
 * 信箱的列表與信是真機的版面配假的內容（私人信件不進 repo）。按鍵的反應照真機：
 * 打序號 Enter 只移游標、再 Enter 進去；子分類先跳進板畫面（任意鍵）；分類裡按 `y` 什麼都不做；左鍵一層一層退回去。
 * 沒寫到的組合不回應（測試就會等到逾時而失敗）。
 */
class ClassAndMailTest {
    private val client = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fakes = mutableListOf<FakePtt>()
    private val sessions = mutableListOf<Session>()

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
        fakes.forEach { it.close() }
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /**
     * [noMail]：信箱是空的（按 R 跳「您沒有來信」，任意鍵按掉回選單）。
     * [mailList]：按 R 進信箱時看到的那頁。[MAIL_FULL] 是 25 封、一進來是最後一頁（第 6～25 封排滿 20 列）、游標在最新的第 25 封；
     * [MAIL_FULL_ON_10] 是同一個信箱停在第 1～20 封、游標在第 10 封（讀完舊信退回來）；[MAIL_TWENTY] 是剛好 20 封、游標在第 20 封。
     * [newMail]：PTT 有新信，有標題列的畫面都畫成「你有新信件」（[withNewMail]）。離開信箱（信箱列表按左鍵回私人信件區）時
     * PTT 再看一次最新 5 封（pttbbs `setmailalert`），這裡當作都讀過了，之後就不寫。
     */
    private fun bbs(first: String, noMail: Boolean = false, mailList: String = MAIL_LIST, newMail: Boolean = false): FakePtt {
        var at = first
        var alert = newMail
        fun drawn(screen: String) = if (alert) withNewMail(screen) else screen
        return FakePtt(drawn(first)) { input ->
            Thread.sleep(LAG_MS)
            val next = when {
                at == MAIN_MENU && input == "C\r" -> CLASS_ROOT
                at == MAIN_MENU && input == "f\r" -> FAVORITES
                at == MAIN_MENU && input == "M\r" -> MAIL_MENU
                at == CLASS_ROOT && input == "14\r" -> CLASS_ROOT_ON_HOT
                at == CLASS_ROOT && input == "7\r" -> CLASS_ROOT_ON_7
                at == CLASS_ROOT_ON_HOT && input == "\r" -> HOT
                at == CLASS_ROOT_ON_7 && input == "\r" -> FOLDERS
                at in setOf(CLASS_ROOT, CLASS_ROOT_ON_HOT, CLASS_ROOT_ON_7) && input == Keys.LEFT -> MAIN_MENU
                at == HOT && input == Keys.PAGE_DOWN -> HOT_2
                at in setOf(HOT, HOT_2) && input == Keys.LEFT -> CLASS_ROOT_ON_HOT
                at == FOLDERS && input == "2\r" -> FOLDERS_ON_2
                at == FOLDERS_ON_2 && input == "\r" -> CLASS_NOTES
                at == CLASS_NOTES && input == "\r" -> BOARDS
                at in setOf(FOLDERS, FOLDERS_ON_2) && input == Keys.LEFT -> CLASS_ROOT_ON_7
                at == BOARDS && input == Keys.PAGE_DOWN -> BOARDS_2
                at in setOf(BOARDS, BOARDS_2) && input == Keys.LEFT -> FOLDERS_ON_2
                // 真機：分類裡按 y（pttbbs `fav_cmd_yank` 的 `IN_CLASS()`）畫面一個字都不變
                at == MAIL_MENU && input == "R\r" -> if (noMail) MAIL_MENU_EMPTY else mailList
                // 真機：游標已經在最後一封時按 End 一個字都不回（MAIL_FULL、MAIL_TWENTY 按 End 沒寫，就是不回應）
                at == MAIL_FULL && input == Keys.UP -> MAIL_FULL_ON_24
                at in setOf(MAIL_FULL_ON_24, MAIL_FULL_ON_10) && input == Keys.END -> MAIL_FULL
                at == MAIL_FULL && input == Keys.PAGE_UP -> MAIL_FULL_TOP
                at == MAIL_TWENTY && input == Keys.UP -> MAIL_TWENTY_ON_19
                at == MAIL_TWENTY_ON_19 && input == Keys.END -> MAIL_TWENTY
                at == MAIL_MENU_EMPTY && input == "\r" -> MAIL_MENU
                at == MAIL_MENU && input == Keys.LEFT -> MAIN_MENU
                at == MAIL_LIST && input == "1\r" -> MAIL_LIST_ON_1
                at == MAIL_LIST && input == "2\r" -> MAIL_LIST_ON_2
                at == MAIL_LIST_ON_1 && input == "\r" -> MAIL_SYSTEM
                at == MAIL_LIST_ON_2 && input == "\r" -> MAIL_READ
                at == MAIL_SYSTEM && input == Keys.LEFT -> MAIL_LIST_ON_1
                at == MAIL_READ && input == Keys.LEFT -> MAIL_LIST_ON_2
                at in setOf(MAIL_LIST, MAIL_LIST_ON_1, MAIL_LIST_ON_2) && input == Keys.LEFT -> MAIL_MENU
                else -> null
            }
            if (next != null) {
                if (next == MAIL_MENU && input == Keys.LEFT) alert = false
                at = next
                show(drawn(next))
            }
        }.also { fakes += it.start() }
    }

    private suspend fun connect(ptt: FakePtt): Session {
        val session = Session(WebSocketTransport(client, url = ptt.url), scope)
        sessions += session
        session.connect()
        return session
    }

    private fun <T> run(ptt: FakePtt, block: suspend Session.() -> T): T = runBlocking { withTimeout(TEST_TIMEOUT_MS) { connect(ptt).block() } }

    @Test
    fun classesFromTheMainMenu() {
        val ptt = bbs(MAIN_MENU)
        val classes = run(ptt) { showClasses() }
        assertEquals((1..14).toList(), classes.map { it.index })
        assertTrue("${classes.last()}", classes.last().isHot)
        assertEquals(listOf("C\r"), ptt.received)
    }

    @Test
    fun hotBoardsIsTheLastClassAndReadsEveryPage() {
        val ptt = bbs(MAIN_MENU)
        val boards = run(ptt) { hotBoards() }
        assertEquals((1..25).toList(), boards.map { it.index })
        assertEquals("Gossiping", boards.first().name)
        assertEquals(listOf("C\r", "14\r", "\r", Keys.PAGE_DOWN), ptt.received)
    }

    @Test
    fun aSubClassIsOneMoreNumberAndItsWelcomeScreenIsDismissed() {
        val ptt = bbs(MAIN_MENU)
        val boards = run(ptt) { showClass(listOf(7, 2)) }
        assertEquals((1..25).toList(), boards.map { it.index })
        assertEquals("Ajax", boards.first().name)
        assertTrue(boards.none { it.isFolder })
        assertEquals(listOf("C\r", "7\r", "\r", "2\r", "\r", "\r", Keys.PAGE_DOWN), ptt.received)
    }

    @Test
    fun theClassItselfListsItsSubClassesAsFolders() {
        val ptt = bbs(MAIN_MENU)
        val boards = run(ptt) { showClass(listOf(7)) }
        assertEquals(15, boards.size)
        assertTrue(boards.all { it.isFolder })
        assertEquals(Board(2, "CompScience", "程式", "程設 資科 資工", isFolder = true), boards[1])
    }

    @Test
    fun favoritesFromASubClassGoesBackWithLeftNeverY() {
        // 分類的列表第 1 列也是「[y]只列最愛」：以前會先按 y，PTT 不理，等到逾時
        val ptt = bbs(BOARDS)
        val page = run(ptt) { showFavorites() }
        assertTrue(!page.inClass && !page.allBoards)
        assertEquals(listOf(Keys.LEFT, Keys.LEFT, Keys.LEFT, "f\r"), ptt.received)
    }

    @Test
    fun favoritesFromTheHotBoards() {
        val ptt = bbs(HOT)
        run(ptt) { showFavorites() }
        assertEquals(listOf(Keys.LEFT, Keys.LEFT, "f\r"), ptt.received)
    }

    @Test
    fun mailsFromTheMainMenu() {
        val ptt = bbs(MAIN_MENU)
        val mails = run(ptt) { showMails() }
        assertEquals((1..5).toList(), mails.map { it.index })
        assertEquals(listOf("M\r", "R\r"), ptt.received)
    }

    @Test
    fun aFullMailboxWithTheCursorOnTheNewestGoesUpBeforeEnd() {
        val ptt = bbs(MAIN_MENU, mailList = MAIL_FULL)
        val mails = run(ptt) { showMails() }
        assertEquals((1..25).toList(), mails.map { it.index })
        assertEquals(listOf("M\r", "R\r", Keys.UP, Keys.END, Keys.PAGE_UP), ptt.received)
    }

    @Test
    fun aFullPageWithTheCursorOnAnOlderMailPressesEndOnly() {
        val ptt = bbs(MAIN_MENU, mailList = MAIL_FULL_ON_10)
        val mails = run(ptt) { showMails() }
        assertEquals((1..25).toList(), mails.map { it.index })
        assertEquals(listOf("M\r", "R\r", Keys.END, Keys.PAGE_UP), ptt.received)
    }

    @Test
    fun exactlyTwentyMailsGoUpAndBackWithoutTurningThePage() {
        val ptt = bbs(MAIN_MENU, mailList = MAIL_TWENTY)
        val mails = run(ptt) { showMails() }
        assertEquals((1..20).toList(), mails.map { it.index })
        assertEquals(listOf("M\r", "R\r", Keys.UP, Keys.END), ptt.received)
    }

    @Test
    fun noMailDismissesTheMessageSoTheNextKeyIsNotEaten() {
        val ptt = bbs(MAIN_MENU, noMail = true)
        val (mails, after) = run(ptt) { showMails() to ScreenParser.parse(screens.value) }
        assertEquals(emptyList<MailSummary>(), mails)
        assertEquals(listOf("M\r", "R\r", "\r"), ptt.received)
        assertTrue("$after", after is Page.MailMenu && !after.noMail)
    }

    @Test
    fun aSystemMailWithoutLineNumbersIsReadAndGoesBackToTheList() {
        val ptt = bbs(MAIL_LIST)
        val text = run(ptt) { wholeMail(1) }
        assertTrue(text.complete)
        assertEquals(7, text.lastLine)
        assertTrue(text.lines.any { it.contains("系統寄的範例") })
        assertEquals(listOf("1\r", "\r", Keys.LEFT), ptt.received)
    }

    @Test
    fun aMailHeaderNamesTheReceiverWhereTheBoardWouldBe() {
        val ptt = bbs(MAIL_LIST)
        val text = run(ptt) { wholeMail(2) }
        val header = text.header!!
        assertEquals("someone", header.author)
        assertEquals("reader", header.board)
        assertEquals("R: 回你的信", header.title)
        assertEquals(listOf("2\r", "\r", Keys.LEFT), ptt.received)
    }

    @Test
    fun favoritesFromTheMailbox() {
        val ptt = bbs(MAIL_LIST)
        run(ptt) { showFavorites() }
        assertEquals(listOf(Keys.LEFT, Keys.LEFT, "f\r"), ptt.received)
    }

    /** 標題列寫「你有新信件」：等到那個畫面之後 [Session.newMail] 是 true。連上之後、還沒等到畫面之前是 null（看不出來）。 */
    @Test
    fun theTitleLineTellsThereIsNewMail() {
        val ptt = bbs(MAIN_MENU, newMail = true)
        run(ptt) {
            assertEquals(null, newMail.value)
            showFavorites()
            assertEquals(true, newMail.value)
        }
        assertEquals(listOf("f\r"), ptt.received)
    }

    @Test
    fun noNewMailWhenTheTitleLineSaysTheSiteName() {
        val ptt = bbs(MAIN_MENU)
        run(ptt) {
            showFavorites()
            assertEquals(false, newMail.value)
        }
    }

    /**
     * 讀信那頁（`more`）第 0 列是「作者」不是標題列：[Session.newMail] 不動，退回信箱列表還是 true；
     * 離開信箱時 PTT 再看一次（`setmailalert`），都讀過了就不寫，變回 false（App 照這個收掉 Snackbar 與藍點）。中間不會閃一下 false 又變回 true。
     */
    @Test
    fun newMailStaysWhileReadingAndClearsAfterLeavingTheMailbox() {
        val ptt = bbs(MAIN_MENU, newMail = true)
        val seen = Collections.synchronizedList(mutableListOf<Boolean?>())
        run(ptt) {
            val watching = scope.launch(start = CoroutineStart.UNDISPATCHED) { newMail.collect { seen += it } }
            showMails()
            assertEquals(true, newMail.value)
            wholeMail(2)
            assertEquals(true, newMail.value)
            showFavorites()
            assertEquals(false, newMail.value)
            while (seen.lastOrNull() != false) delay(10)
            watching.cancel()
        }
        assertEquals(listOf(null, true, false), seen.toList())
        assertEquals(listOf("M\r", "R\r", "2\r", "\r", Keys.LEFT, Keys.LEFT, Keys.LEFT, "f\r"), ptt.received)
    }

    /** 有新信時的畫面：標題列（第 0 列）中間那格的「批踢踢實業坊」換成「你有新信件」（pttbbs `redraw_title`）；讀信那頁沒有標題列，不變。 */
    private fun withNewMail(screen: String): String {
        val lines = screen.lines()
        return (listOf(lines.first().replaceFirst("批踢踢實業坊", "你有新信件")) + lines.drop(1)).joinToString("\n")
    }

    private companion object {
        const val LAG_MS = 100L
        const val TEST_TIMEOUT_MS = 15_000L

        val MAIN_MENU = Fixtures.text("main-menu")
        val FAVORITES = Fixtures.text("favorites")
        val CLASS_ROOT = Fixtures.text("class-root")
        val CLASS_ROOT_ON_HOT = classCursor(CLASS_ROOT, 14)
        val CLASS_ROOT_ON_7 = classCursor(CLASS_ROOT, 7)
        val HOT = Fixtures.text("hot-boards")
        val HOT_2 = secondPage(HOT)
        val FOLDERS = Fixtures.text("class-folders")
        val FOLDERS_ON_2 = listCursor(FOLDERS, 2)
        val CLASS_NOTES = Fixtures.text("board-welcome")
        val BOARDS = Fixtures.text("class-boards")
        val BOARDS_2 = secondPage(BOARDS)
        val MAIL_MENU = Fixtures.text("mail-menu")
        val MAIL_MENU_EMPTY = Fixtures.text("mail-menu-empty")
        val MAIL_LIST = Fixtures.text("mail-list")
        val MAIL_LIST_ON_1 = listCursor(MAIL_LIST, 1)
        val MAIL_LIST_ON_2 = listCursor(MAIL_LIST, 2)
        val MAIL_SYSTEM = Fixtures.text("mail-system")
        val MAIL_READ = Fixtures.text("mail-read")
        val MAIL_FULL = mailPage(from = 6, cursor = 25)
        val MAIL_FULL_ON_24 = mailPage(from = 6, cursor = 24)
        val MAIL_FULL_TOP = mailPage(from = 1, cursor = 5)
        val MAIL_FULL_ON_10 = mailPage(from = 1, cursor = 10)
        val MAIL_TWENTY = mailPage(from = 1, cursor = 20)
        val MAIL_TWENTY_ON_19 = mailPage(from = 1, cursor = 19)

        private fun rows(screen: String) = screen.lines().take(24).toMutableList()

        /** 排滿 20 列的信箱（第 [from]～[from]+19 封），游標在 [cursor]：每一列拿信箱第 4 封那列換序號（「%5d」在第 1～5 欄）。 */
        fun mailPage(from: Int, cursor: Int): String {
            val lines = rows(MAIL_LIST)
            val rest = lines[6].substring(6)
            for (row in 3 until 23) {
                val n = from + row - 3
                lines[row] = (if (n == cursor) ">" else " ") + "%5d".format(n) + rest
            }
            return lines.joinToString("\n")
        }

        /** 【分類看板】的游標畫在第 10 欄，蓋掉序號前面的空白；第 7 列是第 1 項。 */
        fun classCursor(screen: String, index: Int): String {
            val lines = rows(screen)
            for (row in 7 until 23) {
                if (lines[row].length > 10) lines[row] = lines[row].substring(0, 10) + (if (row == 6 + index) ">" else " ") + lines[row].substring(11)
            }
            return lines.joinToString("\n")
        }

        /** 看板列表、信箱的游標在第 0 欄；第 3 列是第 1 項。 */
        fun listCursor(screen: String, index: Int): String {
            val lines = rows(screen)
            for (row in 3 until 23) {
                if (lines[row].isNotEmpty()) lines[row] = (if (row == 2 + index) ">" else " ") + lines[row].substring(1)
            }
            return lines.joinToString("\n")
        }

        /** 第 2 頁（第 21～25 項）：拿第 1 頁的前 5 列換序號（「%6d」在第 1～6 欄），游標在第 21 項，其他列空著。 */
        fun secondPage(screen: String): String {
            val lines = rows(screen)
            for (row in 3 until 23) {
                val n = row - 2 + 20
                lines[row] = if (n <= 25) (if (n == 21) ">" else " ") + "%6d".format(n) + lines[row].substring(7) else ""
            }
            return lines.joinToString("\n")
        }
    }
}
