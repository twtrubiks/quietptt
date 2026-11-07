package com.twtrubiks.quietptt.core.ptt

/** 我的最愛或看板列表上的一個看板。[category] 是「綜合」「八卦」這種分類；目錄（資料夾）的 [isFolder] 為 true。 */
data class Board(
    val index: Int,
    val name: String,
    val category: String,
    val title: String,
    val unread: Boolean = false,
    val isFolder: Boolean = false,
)

/** 文章列表上的一行。[index] 是序號（置底文是 null）；[pushCount] 是推文數字（「爆」是 100、「X1」是 -10，類推）。 */
data class ArticleSummary(
    val index: Int?,
    val mark: Char?,
    val pushCount: Int?,
    val date: String,
    val author: String,
    val title: String,
)

/** 一篇讀完的文章：翻完所有頁拼起來的內文（每一行就是終端上的一列）。 */
data class Article(
    val board: String,
    val lines: List<String>,
)

/** 文章頁最下面那一列的進度。 */
data class ArticleProgress(
    val page: Int,
    val pages: Int,
    val firstLine: Int,
    val lastLine: Int,
)
