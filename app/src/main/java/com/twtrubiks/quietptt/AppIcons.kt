package com.twtrubiks.quietptt

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * material-icons-core 裡沒有、這個 App 自己畫的幾個 Material 圖示（路徑照 Material Design icons，Apache 2.0；24×24）。
 * 不拉 material-icons-extended：那包很大、debug 版整包進 APK。
 */
object AppIcons {
    /** 對話框（BePTT 文章頁的「推文」，`ic_mode_comment`）。 */
    val Comment: ImageVector by lazy { icon("Comment", "M21.99 4c0-1.1-.89-2-1.99-2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h14l4 4-.01-18z") }

    /** 往左彎的箭頭（文章頁的「回文」，`ic_reply`）。 */
    val Reply: ImageVector by lazy { icon("Reply", "M10 9V5l-7 7 7 7v-4.1c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11z") }

    /** 箭頭指到底線（BePTT 的「到底」，`ic_vertical_align_bottom`）。 */
    val AlignBottom: ImageVector by lazy { icon("AlignBottom", "M16 13h-3V3h-2v10H8l4 4 4-4zM4 19v2h16v-2H4z") }

    /** 箭頭指到頂線（「到頂」）。 */
    val AlignTop: ImageVector by lazy { icon("AlignTop", "M8 11h3v10h2V11h3l-4-4-4 4zM4 3v2h16V3H4z") }

    /** 小視窗裡一個「>_」（右上角的「終端」，`ic_terminal`）。 */
    val Terminal: ImageVector by lazy {
        icon("Terminal", "M20 4H4c-1.11 0-2 .9-2 2v12c0 1.1.89 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.89-2-2-2zm0 14H4V8h16v10zm-2-1h-6v-2h6v2zM7.5 17l-1.41-1.41L8.67 13l-2.59-2.59L7.5 9l4 4-4 4z")
    }

    /** 眼睛（登入頁「顯示密碼」，`ic_visibility`）。 */
    val Visibility: ImageVector by lazy {
        icon("Visibility", "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z")
    }

    /** 眼睛劃一條線（登入頁「隱藏密碼」，`ic_visibility_off`）。 */
    val VisibilityOff: ImageVector by lazy {
        icon(
            "VisibilityOff",
            "M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z",
        )
    }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(addPathNodes(pathData), fill = SolidColor(Color.Black))
            .build()
}
