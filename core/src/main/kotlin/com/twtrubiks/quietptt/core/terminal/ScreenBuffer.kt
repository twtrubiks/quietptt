package com.twtrubiks.quietptt.core.terminal

/** 一格的顏色。[fg]、[bg] 是 ANSI 的 0～7（黑紅綠黃藍紫青白），PTT 預設是白字黑底。 */
data class Attr(
    val fg: Int = DEFAULT_FG,
    val bg: Int = DEFAULT_BG,
    val bold: Boolean = false,
    val blink: Boolean = false,
    val reverse: Boolean = false,
) {
    companion object {
        const val DEFAULT_FG = 7
        const val DEFAULT_BG = 0
        val DEFAULT = Attr()
    }
}

/**
 * 一列裡顏色不是預設（白字黑底、不粗）的一段：[start]（含）到 [end]（不含）是 [Screen.line] 那個字串裡的字元位置（全形字算一個字，不是欄）。
 * [fg]、[bg] 是 ANSI 的 0～7（反白已經換過來：前景跟背景對調），[bold] 是亮色（PTT 的 `1;3x`）。
 * 文章頁靠它把 PTT 的彩色字帶到 App（`Page.ArticleView.colors`）；閃爍不管。
 */
data class ColorSpan(val start: Int, val end: Int, val fg: Int, val bg: Int, val bold: Boolean) {
    val length: Int get() = end - start
}

/**
 * 終端的一格。全形字佔兩格：第一格放字，第二格是 [wideTail]（字一樣，只是標記），
 * 取整列文字時跳過 tail，照欄位切字串時兩格都算。
 */
data class Cell(val ch: Char, val attr: Attr = Attr.DEFAULT, val wideTail: Boolean = false) {
    companion object {
        val BLANK = Cell(' ')
    }
}

/**
 * 某一刻的整個畫面（不可變），畫面辨識（`ScreenParser`）與 Compose 都吃這個。
 * [lines] 是每列的文字（全形字一個字），[text] 可以照欄位切（全形字算兩欄）。
 */
class Screen(
    val cells: List<List<Cell>>,
    val cursorRow: Int = 0,
    val cursorCol: Int = 0,
) {
    val rows: Int get() = cells.size
    val cols: Int get() = if (cells.isEmpty()) 0 else cells[0].size

    val lines: List<String> by lazy {
        cells.map { row -> buildString { for (cell in row) if (!cell.wideTail) append(cell.ch) } }
    }

    fun line(row: Int): String = lines[row]

    /** 第 [row] 列第 [fromCol] 欄（含）到 [toCol] 欄（不含）的文字，欄位以半形算。全形字只有一半在範圍內時照樣取。 */
    fun text(row: Int, fromCol: Int, toCol: Int = cols): String = buildString {
        val cellsOfRow = cells[row]
        for (col in fromCol.coerceAtLeast(0) until toCol.coerceAtMost(cols)) {
            val cell = cellsOfRow[col]
            if (!cell.wideTail || col == fromCol) append(cell.ch)
        }
    }

    /**
     * 第 [row] 列有顏色的那幾段（[ColorSpan]，照字元位置、跟 [line] 對得上），預設色的字不算；整列預設色就是空的。
     * 連著的同色字併成一段。測試的 fixture（[of]）沒有顏色，永遠是空的。
     */
    fun spans(row: Int): List<ColorSpan> {
        val out = ArrayList<ColorSpan>()
        var index = 0
        var current: ColorSpan? = null
        for (cell in cells[row]) {
            if (cell.wideTail) continue
            val attr = cell.attr
            val fg = if (attr.reverse) attr.bg else attr.fg
            val bg = if (attr.reverse) attr.fg else attr.bg
            val plain = fg == Attr.DEFAULT_FG && bg == Attr.DEFAULT_BG && !attr.bold
            val open = current
            current = when {
                plain -> { if (open != null) out += open; null }
                open != null && open.fg == fg && open.bg == bg && open.bold == attr.bold -> open.copy(end = index + 1)
                else -> { if (open != null) out += open; ColorSpan(index, index + 1, fg, bg, attr.bold) }
            }
            index++
        }
        current?.let { out += it }
        return out
    }

    /** 畫面上有沒有這段文字（任一列）。 */
    fun contains(text: String): Boolean = lines.any { it.contains(text) }

    override fun toString(): String = lines.joinToString("\n") { it.trimEnd() }

    companion object {
        const val ROWS = 24
        const val COLS = 80

        /**
         * 從文字還原畫面（測試的 fixture）：每行一列，不足 [ROWS] 列補空白；非 ASCII 的字當全形、佔兩欄。
         * 顏色會丟掉，只有文字。
         */
        fun of(text: String, rows: Int = ROWS, cols: Int = COLS): Screen {
            val lines = text.lines().take(rows)
            val cells = List(rows) { row ->
                val line = lines.getOrNull(row) ?: ""
                val out = ArrayList<Cell>(cols)
                for (ch in line) {
                    if (out.size >= cols) break
                    out.add(Cell(ch))
                    if (isWide(ch) && out.size < cols) out.add(Cell(ch, wideTail = true))
                }
                while (out.size < cols) out.add(Cell.BLANK)
                out
            }
            return Screen(cells)
        }

        /** Big5 的雙位元組字全是全形；用「不是 ASCII」判斷，跟 [Terminal] 一致。 */
        fun isWide(ch: Char): Boolean = ch.code > 0x7F
    }
}

/**
 * 可寫的畫面：24x80 的格子加游標，[Terminal] 照跳脫序列改它，[snapshot] 拿不可變的 [Screen] 給上層。
 * 一律在同一個執行緒（coroutine）上改。
 */
class ScreenBuffer(val rows: Int = Screen.ROWS, val cols: Int = Screen.COLS) {
    private val cells = Array(rows) { Array(cols) { Cell.BLANK } }

    var row = 0
        private set
    var col = 0
        private set

    private var savedRow = 0
    private var savedCol = 0

    fun put(ch: Char, attr: Attr, wide: Boolean) {
        if (col >= cols) {
            col = 0
            lineFeed()
        }
        if (wide && col == cols - 1) {
            // 全形字放不進最後一欄：那一欄留白，換到下一列
            clearHalf(col)
            cells[row][col] = Cell(' ', attr)
            col = 0
            lineFeed()
        }
        clearHalf(col)
        cells[row][col] = Cell(ch, attr)
        if (wide) {
            if (col + 1 < cols) {
                clearHalf(col + 1)
                cells[row][col + 1] = Cell(ch, attr, wideTail = true)
            }
            col += 2
        } else {
            col++
        }
    }

    /** 蓋掉全形字的一半時，另一半也要清掉，不然會留下半個字。 */
    private fun clearHalf(c: Int) {
        val cell = cells[row][c]
        if (cell.wideTail && c > 0) {
            cells[row][c - 1] = Cell(' ', cells[row][c - 1].attr)
        } else if (!cell.wideTail && c + 1 < cols && cells[row][c + 1].wideTail) {
            cells[row][c + 1] = Cell(' ', cells[row][c + 1].attr)
        }
    }

    fun moveTo(r: Int, c: Int) {
        row = r.coerceIn(0, rows - 1)
        col = c.coerceIn(0, cols - 1)
    }

    fun move(dr: Int, dc: Int) = moveTo(row + dr, col + dc)

    fun carriageReturn() {
        col = 0
    }

    fun lineFeed() {
        if (row == rows - 1) scrollUp() else row++
    }

    fun reverseLineFeed() {
        if (row == 0) scrollDown() else row--
    }

    fun backspace() {
        if (col > 0) col--
    }

    fun tab() {
        col = ((col / 8) + 1) * 8
        if (col >= cols) col = cols - 1
    }

    fun saveCursor() {
        savedRow = row
        savedCol = col
    }

    fun restoreCursor() = moveTo(savedRow, savedCol)

    /** `ESC [ n J`：0 游標到結尾、1 開頭到游標、2 整個畫面。 */
    fun eraseDisplay(mode: Int, attr: Attr) {
        val blank = Cell(' ', attr.copy(bold = false, blink = false, reverse = false))
        when (mode) {
            0 -> {
                eraseLine(0, attr)
                for (r in row + 1 until rows) cells[r].fill(blank)
            }
            1 -> {
                eraseLine(1, attr)
                for (r in 0 until row) cells[r].fill(blank)
            }
            else -> for (r in 0 until rows) cells[r].fill(blank)
        }
    }

    /** `ESC [ n K`：0 游標到列尾、1 列首到游標、2 整列。 */
    fun eraseLine(mode: Int, attr: Attr) {
        val blank = Cell(' ', attr.copy(bold = false, blink = false, reverse = false))
        val line = cells[row]
        when (mode) {
            0 -> for (c in col.coerceAtMost(cols - 1) until cols) line[c] = blank
            1 -> for (c in 0..col.coerceAtMost(cols - 1)) line[c] = blank
            else -> line.fill(blank)
        }
    }

    private fun scrollUp() {
        for (r in 1 until rows) cells[r - 1] = cells[r]
        cells[rows - 1] = Array(cols) { Cell.BLANK }
    }

    private fun scrollDown() {
        for (r in rows - 1 downTo 1) cells[r] = cells[r - 1]
        cells[0] = Array(cols) { Cell.BLANK }
    }

    fun snapshot(): Screen = Screen(cells.map { it.toList() }, row, col)
}
