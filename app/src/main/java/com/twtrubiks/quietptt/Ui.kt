package com.twtrubiks.quietptt

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.ptt.UnexpectedScreenException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 對話框（`AlertDialog`、`ModalBottomSheet`）是另一個視窗，吃不到 root 開的 `testTagsAsResourceId`，
 * UiAutomator 的 `res("tag")` 找不到裡面的元件：裡面有測試要按的東西時，對話框的 modifier 加這個。
 */
@OptIn(ExperimentalComposeUiApi::class)
val DialogTestTags: Modifier = Modifier.semantics { testTagsAsResourceId = true }

/** 每一頁上面那條：標題（[subtitle] 是標題下面一行小字，例如用哪個帳號發文）、返回鍵（有的話）、右邊的動作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = {
            if (subtitle == null) {
                Text(title)
            } else {
                Column {
                    Text(title)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("app_bar_subtitle"),
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("back_button")) { Text("←") }
            }
        },
        actions = { actions() },
    )
}

/** 小圖示按鈕：有 [icon] 畫圖示（[label] 當 contentDescription），沒有就直接寫 [label]。 */
@Composable
fun TextIconButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    IconButton(onClick = onClick, modifier = modifier) {
        if (icon != null) Icon(icon, contentDescription = label) else Text(label)
    }
}

/** 右上角切到終端畫面的按鈕（每一頁都有）：「>_」圖示（[AppIcons.Terminal]），contentDescription「終端」。 */
@Composable
fun TerminalButton(onClick: () -> Unit) {
    TextIconButton("終端", onClick = onClick, modifier = Modifier.testTag("terminal_button"), icon = AppIcons.Terminal)
}

/** 一頁要 Session 走到的畫面：[error] 是走不到的原因（null 表示還在走或走到了），[done] 是走到了，[retry] 再走一次。 */
@Stable
class Navigation {
    var error by mutableStateOf<String?>(null)
        internal set
    var done by mutableStateOf(false)
        internal set
    internal var attempt by mutableIntStateOf(0)

    fun retry() {
        error = null
        done = false
        attempt++
    }
}

/**
 * 進到這一頁就叫 Session 走到對應的畫面（[go]：`showFavorites`、`showBoard`、`openArticle`）。
 * Session 從現在的畫面出發，已經在那一頁就不按鍵，所以返回、轉螢幕、從終端畫面回來都能用同一招對齊。
 * 走不到（畫面認不出來、等太久）就把原因放在 [Navigation.error]，頁面顯示原因、「重試」與「切到終端畫面」。
 * [enabled] 是 false 時不走（首頁的分頁滑到一半）；變回 true 再走一次。走到一半變 false 就取消（Session 下次從當時的畫面走）。
 */
@Composable
fun rememberNavigation(session: Session, key: Any?, enabled: Boolean = true, go: suspend Session.() -> Unit): Navigation {
    val navigation = remember(session, key) { Navigation() }
    LaunchedEffect(session, key, navigation.attempt, enabled) {
        if (!enabled) return@LaunchedEffect
        // 停用過又啟用（首頁滑走又滑回來）：重新走一次，上一次的結果不算
        navigation.error = null
        navigation.done = false
        try {
            session.go()
            navigation.done = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnexpectedScreenException) {
            // 附上畫面第一列（標題列）：截圖就看得出卡在哪一頁、ScreenParser 該認什麼字
            navigation.error = "${e.message}\n畫面第一列：${e.screen.lines.first().trim().ifEmpty { "（空白）" }}"
        } catch (e: Exception) {
            navigation.error = e.message ?: e::class.simpleName
        }
    }
    return navigation
}

/**
 * 畫面辨識還沒認出預期的頁面時顯示：轉圈加一句話，可以「切到終端畫面」自己看發生什麼事。
 * 走不到的時候（[error]）不轉圈，顯示原因與「重試」。每一頁都用這個當退路，App 不會卡在空白。
 */
@Composable
fun WaitingForPage(message: String, onTerminal: () -> Unit, error: String? = null, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (error == null) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(message)
        } else {
            Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.testTag("navigation_error"))
        }
        Spacer(Modifier.height(12.dp))
        Row {
            if (error != null && onRetry != null) TextButton(onClick = onRetry) { Text("重試") }
            TextButton(onClick = onTerminal) { Text("切到終端畫面") }
        }
    }
}

/** 右上角的「⋮」：點了從下面升起 [items] 的面板（[ActionSheet]，第一行是 [title]）；[MenuItem.enabled] 是 false 的項目變淡不能點。 */
@Composable
fun MoreMenu(title: String, items: List<MenuItem>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.testTag("more_button")) { Text("⋮") }
    if (open) ActionSheet(title, items, onDismiss = { open = false })
}

class MenuItem(val label: String, val tag: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * 長按一列、點一則推文、右上角「⋮」跳出的動作面板（照 BePTT 文章列表的長按）：從下面升起、上緣有拉把；第一行是 [title]（一行，太長加「…」），
 * 下面一列一個 [items]（[MenuItem.enabled] 是 false 的變淡不能點），最下面中間一顆圓的「×」。項目多就在中間捲。
 * 點一項：面板先滑下去、叫 [onDismiss]，再做那件事（開對話框、換頁都在面板收掉之後）；滑下去的途中再點都不算。往下拉、點外面、按「×」、返回鍵都是關掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String, items: List<MenuItem>, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // 面板滑下去要一點時間，這段時間再點一次（同一項或別項、「×」）不算：不然換頁、搜尋會疊兩層
    var closing by remember { mutableStateOf(false) }
    fun close(then: () -> Unit = {}) {
        if (closing) return
        closing = true
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier.testTag("action_sheet")) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp).testTag("action_sheet_title"),
        )
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            for (item in items) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (item.enabled) 1f else DISABLED_ALPHA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("menu_${item.tag}")
                        .clickable(enabled = item.enabled) { close(item.onClick) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
        }
        FilledTonalIconButton(
            onClick = { close() },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 8.dp, bottom = 16.dp)
                .size(56.dp)
                .testTag("action_sheet_close"),
        ) { Icon(Icons.Filled.Close, contentDescription = "關閉") }
    }
}

/** Material 3 不能點的東西的透明度。 */
private const val DISABLED_ALPHA = 0.38f

/**
 * 一個會改到 PTT 那邊的動作（加入最愛、推文、刪文…）：在 App 的 scope 裡跑（離開那一頁也要把 PTT 的流程走完，不能卡在半路），
 * [active] 進行中、[error] 失敗的原因、[done] 做完了（做完一次之後要再做就先 [reset]）。
 */
@Stable
class Action {
    var active by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var done by mutableStateOf(false)

    fun reset() {
        error = null
        done = false
    }
}

/** 在 [container] 的 scope 裡跑 [block]，結果記到 [action]；例外一律接住（那個 scope 沒有例外處理器，丟出去會閃退）。 */
fun Action.launch(container: AppContainer, block: suspend () -> Unit) {
    if (active) return
    active = true
    error = null
    done = false
    container.scope.launch {
        try {
            block()
            done = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName
        } finally {
            active = false
        }
    }
}

/** 短訊息（BePTT 的「已將 X 加入我的最愛」這種）；從哪個執行緒叫都行。 */
fun Context.toast(text: String) {
    val app = applicationContext
    Handler(Looper.getMainLooper()).post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
}
