package com.twtrubiks.quietptt.core.ptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 推文列照 pttbbs 的欄位切；被 PTT 切成多列的推文接回一則。列都是真機畫面抄的（`screens/article-no-total-pages.txt`，與用 `capture --e2e` 讀的 Soft_Job、MobileComm）。 */
class PushTest {
    private fun items(vararg lines: String) = lines.toList().toItems()

    private fun comment(type: PushType, user: String, text: String, time: String, floor: Int, nth: Int, ip: String? = null) =
        ArticleItem.Comment(Push(type, user, text, time, ip), floor, nth)

    @Test
    fun pushBooAndArrowAreSplitIntoFields() {
        assertEquals(
            listOf(
                comment(PushType.PUSH, "sivhd", "一樓帥哥", "07/18 09:54", floor = 1, nth = 1),
                comment(PushType.BOO, "se2422", "經過幾次水桶手機板會很冷清吧", "07/18 10:45", floor = 2, nth = 1),
                comment(PushType.ARROW, "yoriko6406", "年好像沒啥看點？", "07/18 11:16", floor = 3, nth = 1),
            ),
            items(
                "推 sivhd       : 一樓帥哥                                          07/18 09:54",
                "噓 se2422      : 經過幾次水桶手機板會很冷清吧                      07/18 10:45",
                "→ yoriko6406  : 年好像沒啥看點？                                  07/18 11:16",
            ),
        )
    }

    @Test
    fun floorsCountEveryPushAndNthCountsEachKind() {
        // BePTT 的「12樓/推5」：樓是全部一起數，推／噓／→ 各自再數一遍
        val floors = items(
            "推 a           : 1                                                 07/18 09:54",
            "推 b           : 2                                                 07/18 09:54",
            "噓 c           : 3                                                 07/18 09:54",
            "→ d           : 4                                                 07/18 09:54",
            "推 e           : 5                                                 07/18 09:54",
        ).filterIsInstance<ArticleItem.Comment>().map { it.floor to it.nth }
        assertEquals(listOf(1 to 1, 2 to 2, 3 to 1, 4 to 1, 5 to 3), floors)
    }

    @Test
    fun aLongPushSplitByPttIsJoinedBackIntoOne() {
        // 第一列填滿了 50 欄、第二列是「→」同一個人同一個時間：是同一則
        assertEquals(
            listOf(
                comment(PushType.ARROW, "yoriko6406", "下半年新機都大概幾月啊,三星從9月移到7月發，下半年好像沒啥看點？", "07/18 11:16", floor = 1, nth = 1),
                comment(PushType.PUSH, "fish10606", "718三星蝦皮的S24沒有特別的優惠，似乎直接買1.6K的S23就好了？", "07/18 11:41", floor = 2, nth = 1),
            ),
            items(
                "→ yoriko6406  : 下半年新機都大概幾月啊,三星從9月移到7月發，下半   07/18 11:16",
                "→ yoriko6406  : 年好像沒啥看點？                                  07/18 11:16",
                "推 fish10606   : 718三星蝦皮的S24沒有特別的優惠，似乎直接買1.6K的  07/18 11:41",
                "→ fish10606   : S23就好了？                                       07/18 11:41",
            ),
        )
    }

    @Test
    fun boardsWithoutAlignedIdsPutTheColonRightAfterTheId() {
        // 真機（Soft_Job）：這個板沒開對齊，「:」緊接代號；內容一樣補到第 66 欄，時間一樣從第 67 欄起
        assertEquals(
            listOf(
                comment(PushType.PUSH, "CaptPlanet", "不用了，謝謝", "10/21 14:50", floor = 1, nth = 1),
                comment(PushType.ARROW, "wuyiulin", "陋習在哪？AI生成程式碼跟井噴一樣，你一個沒注意就踩坑，不用自己看？", "10/21 17:53", floor = 2, nth = 1),
            ),
            items(
                "推 CaptPlanet: 不用了，謝謝                                        10/21 14:50",
                "→ wuyiulin: 陋習在哪？AI生成程式碼跟井噴一樣，你一個沒注意就踩    10/21 17:53",
                "→ wuyiulin: 坑，不用自己看？                                      10/21 17:53",
            ),
        )
    }

    @Test
    fun aCutLineCanEndAFewColumnsShortOfFull() {
        // 真機：被切開的那列常常差 3 欄才滿（對齊與沒對齊的板都是），三列也接成一則
        assertEquals(
            listOf(
                comment(PushType.ARROW, "alihue", "問題在於軟體工程師加上膨風vibe coder供過於求的時候怎麼有效率地篩選，而不是面試內容是否有真正實務上的直接幫助", "10/21 18:01", floor = 1, nth = 1),
                comment(PushType.ARROW, "gary8442", "@tacitus 我很喜歡X300Ultra這顆2億大底主鏡頭 超廣角也碾壓X500ProMax小底", "11/07 13:35", floor = 2, nth = 2),
            ),
            items(
                "→ alihue: 問題在於軟體工程師加上膨風vibe coder供過於求的時候怎    10/21 18:01",
                "→ alihue: 麼有效率地篩選，而不是面試內容是否有真正實務上的直接    10/21 18:01",
                "→ alihue: 幫助                                                    10/21 18:01",
                "→ gary8442    : @tacitus 我很喜歡X300Ultra這顆2億大底主鏡頭 超    11/07 13:35",
                "→ gary8442    : 廣角也碾壓X500ProMax小底                          11/07 13:35",
            ),
        )
    }

    @Test
    fun twoShortPushesInTheSameMinuteStaySeparate() {
        assertEquals(
            listOf(
                comment(PushType.ARROW, "sivhd", "一樓", "07/18 09:54", floor = 1, nth = 1),
                comment(PushType.ARROW, "sivhd", "帥哥", "07/18 09:54", floor = 2, nth = 2),
            ),
            items(
                "→ sivhd       : 一樓                                              07/18 09:54",
                "→ sivhd       : 帥哥                                              07/18 09:54",
            ),
        )
    }

    @Test
    fun boardsShowingIpHaveAShorterTextColumn() {
        assertEquals(
            listOf(comment(PushType.PUSH, "sivhd", "一樓帥哥", "07/18 09:54", floor = 1, nth = 1, ip = "140.112.1.2")),
            items("推 sivhd       : 一樓帥哥                              140.112.1.2 07/18 09:54"),
        )
    }

    @Test
    fun veryOldPushesOnlyHaveADate() {
        assertEquals(
            listOf(comment(PushType.PUSH, "sivhd", "一樓帥哥", "07/18", floor = 1, nth = 1)),
            items("推 sivhd       : 一樓帥哥                                                07/18"),
        )
    }

    @Test
    fun bodyLinesThatLookLikePushesStayText() {
        val lines = listOf(
            "推 sivhd       : 一樓帥哥",                       // 沒有時間：本文裡引用的推文
            "推文 很多",
            "→ 這是本文裡的箭頭",
            "",
        )
        assertEquals(lines.map { ArticleItem.Line(it) }, items(*lines.toTypedArray()))
    }

    @Test
    fun signatureLinesAreMarked() {
        // 真機（article-no-total-pages.txt）：「--」之後是「※ 發信站」「※ 文章網址」；編輯過的文章還有「※ 編輯」
        assertEquals(
            listOf(
                ArticleItem.Line("--"),
                ArticleItem.Signature("※ 發信站: 批踢踢實業坊(ptt.cc), 來自: 1.2.3.4 (臺灣)"),
                ArticleItem.Signature("※ 文章網址: https://www.ptt.cc/bbs/MobileComm/M.1721266183.A.DEA.html"),
                ArticleItem.Signature("※ 編輯: chanifa (1.2.3.4 臺灣), 11/06/2026 09:30:00"),
                ArticleItem.Line("※ 引用本文的人要注意"),
            ),
            items(
                "--",
                "※ 發信站: 批踢踢實業坊(ptt.cc), 來自: 1.2.3.4 (臺灣)",
                "※ 文章網址: https://www.ptt.cc/bbs/MobileComm/M.1721266183.A.DEA.html",
                "※ 編輯: chanifa (1.2.3.4 臺灣), 11/06/2026 09:30:00",
                "※ 引用本文的人要注意",
            ),
        )
    }

    @Test
    fun headerComesFromTheFirstThreeLines() {
        // 真機（article.txt）
        val text = ArticleText(
            listOf(
                " 作者  chanifa (股師)                                        看板  MobileComm",
                " 標題  [討論] s24u 綠線案例",
                " 時間  Fri Nov  6 09:19:28 2026",
                "───────────────────────────────────────",
                "",
                "去reddit隨便搜一下s24u green line",
            ),
            6,
            false,
        )
        assertEquals(ArticleHeader("chanifa", "股師", "MobileComm", "[討論] s24u 綠線案例", "Fri Nov  6 09:19:28 2026"), text.header)
        assertEquals("chanifa", text.author)
        // 標頭與分隔線不當本文
        assertEquals(listOf(ArticleItem.Line(""), ArticleItem.Line("去reddit隨便搜一下s24u green line")), text.items())
    }

    @Test
    fun headerWithoutNicknameOrBoardStillParses() {
        assertEquals(ArticleHeader("someone"), ArticleText(listOf(" 作者  someone"), 1, false).header)
        assertEquals(
            ArticleHeader("someone", null, "Test", "hi", null),
            ArticleText(listOf(" 作者  someone                                        看板  Test", " 標題  hi"), 2, false).header,
        )
        assertEquals(null, ArticleText.EMPTY.header)
        assertEquals(null, ArticleText(listOf("推 sivhd       : 一樓帥哥"), 1, false).header)
        // 沒有標頭的文章從第一列就是本文
        assertEquals(listOf(ArticleItem.Line("hello")), ArticleText(listOf("hello"), 1, false).items())
    }

    private fun art(vararg lines: String): List<Boolean> = lines.toList().toItems().map { (it as ArticleItem.Line).art }

    @Test
    fun rightAlignedColumnsAreOneTable() {
        // 數字靠右對齊：起點差一欄，結尾在同一欄
        assertEquals(
            listOf(true, true, true, true),
            art(
                "代號  名稱      收盤    漲跌",
                "2330  台積電    1050     +15",
                "2317  鴻海       180      -2",
                "2454  聯發科    1320    +40",
            ),
        )
    }

    @Test
    fun tableReachesRowsPushedOutOfLine() {
        // 第三列的字太長，欄位都對不上：緊貼著表格、自己有 3 個以上空白，跟著算；第四列沒有就不算，空行隔開的也不算
        assertEquals(
            listOf(true, true, true, false, false, false),
            art(
                "項目     價格     數量",
                "手機     30000    2",
                "超長的品項名稱   5   1",
                "以上  含稅",
                "",
                "上班就少說話  多摸魚   摸啊摸啊摸  真爽",
            ),
        )
    }

    @Test
    fun artAndTablesAreTold() {
        assertTrue(looksLikeArt("┌──────┬──────┐"))
        assertTrue(looksLikeArt("│ 型號 │ 價格 │"))
        assertTrue(looksLikeArt("███▓▓▒▒░░"))
        // 用空白排的欄位：緊鄰的列有字從同一欄開始才算
        assertTrue(looksLikeArt("項目     價格     數量", below = "手機     30000    2"))
        assertTrue(looksLikeArt("手機     30000    2", above = "項目     價格     數量"))
        assertTrue(looksLikeArt("iPhone 15  30000", above = "項目       價格")) // 這列只隔 2 個空白，上一列隔得夠開
        assertFalse(looksLikeArt("項目     價格     數量")) // 只有自己一列
        // 拿空白當標點的句子（八卦板常見）：欄位對不上，是一般文字
        assertFalse(looksLikeArt("我們處男還是乖一點   一口開可能就被說噁", below = "上班就少說話  多摸魚   摸啊摸啊摸  真爽"))
        assertFalse(looksLikeArt("上班就少說話  多摸魚   摸啊摸啊摸  真爽", above = "我們處男還是乖一點   一口開可能就被說噁"))
        assertFalse(looksLikeArt("我們處男還是乖一點   一口開可能就被說噁", above = "", below = ""))
        // 開頭的詞一樣長、後面接空白：第一欄的結尾不算
        assertFalse(looksLikeArt("我覺得   不行", below = "你覺得  可以"))
        assertFalse(looksLikeArt("去reddit隨便搜一下s24u green line ，開始爆發了"))
        assertFalse(looksLikeArt("這次不只垂直綠線，還有垂直紫線。"))
        assertFalse(looksLikeArt("a  b")) // 兩個空白不算
        assertFalse(looksLikeArt(""))
    }

    @Test
    fun urlsAreFoundWithoutTrailingPunctuation() {
        assertEquals(listOf(0..28 to "https://i.mopix.cc/T870Tr.jpg"), findUrls("https://i.mopix.cc/T870Tr.jpg"))
        assertEquals(
            listOf(3..22 to "https://ptt.cc/a?b=1"),
            findUrls("看這 https://ptt.cc/a?b=1。然後"),
        )
        assertEquals(listOf("http://a.b/c", "https://d.e/f"), findUrls("http://a.b/c (https://d.e/f)").map { it.second })
        assertEquals(emptyList<Pair<IntRange, String>>(), findUrls("沒有網址 https://"))
        // 範圍要對得上原文的位置（App 用它標可點的範圍）
        val line = "圖 https://i.mopix.cc/b88jNw.jpg 圖"
        val (range, url) = findUrls(line).single()
        assertEquals(url, line.substring(range))
    }
}
