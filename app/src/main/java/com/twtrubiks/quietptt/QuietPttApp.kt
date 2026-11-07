package com.twtrubiks.quietptt

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.twtrubiks.quietptt.core.ptt.Page
import com.twtrubiks.quietptt.core.ptt.ScreenParser
import com.twtrubiks.quietptt.core.ptt.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 登入後的畫面。首頁是我的最愛，看板、文章從它往裡走；終端畫面是認不出來時的退路。設定不在 stack 裡，是蓋在上面的一頁。 */
sealed interface Route {
    val key: String

    data object Favorites : Route {
        override val key = "favorites"
    }

    data class Board(val name: String) : Route {
        override val key get() = "board-$name"
    }

    /** [board] 裡游標所在的那篇（進文章前先把游標移到 [index]）。 */
    data class Article(val board: String, val index: Int) : Route {
        override val key get() = "article-$board-$index"
    }

    data object Terminal : Route {
        override val key = "terminal"
    }
}

/** 轉螢幕、切到背景再回來時，走到哪一頁要留著。 */
private val StackSaver = listSaver<List<Route>, String>(
    save = { stack ->
        stack.map {
            when (it) {
                Route.Favorites -> "favorites"
                is Route.Board -> "board|${it.name}"
                is Route.Article -> "article|${it.board}|${it.index}"
                Route.Terminal -> "terminal"
            }
        }
    },
    restore = { saved ->
        saved.map {
            val parts = it.split("|")
            when (parts[0]) {
                "board" -> Route.Board(parts[1])
                "article" -> Route.Article(parts[1], parts[2].toInt())
                "terminal" -> Route.Terminal
                else -> Route.Favorites
            }
        }
    },
)

/**
 * 整個 App。導覽是 sealed interface 的 [Route] ＋ rememberSaveable 的 stack，不另外引入 Navigation 套件。
 * 沒登入時只有登入頁（與設定）；登入後照 stack 顯示。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun QuietPttApp() {
    val container = LocalContext.current.container
    val state by container.connection.state.collectAsState()
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf<Route>(Route.Favorites)) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()

    fun push(route: Route) {
        stack = stack + route
    }
    fun pop() {
        val top = stack.last()
        stack = stack.dropLast(1).ifEmpty { listOf(Route.Favorites) }
        holder.removeState(top.key)
    }

    QuietPttTheme(container.theme.value) {
        Surface(
            Modifier
                .fillMaxSize()
                // 讓 UiAutomator 用 By.res(...) 找得到 testTag
                .semantics { testTagsAsResourceId = true },
        ) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                when {
                    settingsOpen -> SettingsScreen(
                        online = state is ConnectionState.Online,
                        onBack = { settingsOpen = false },
                        onLogout = {
                            container.connection.disconnect(null)
                            settingsOpen = false
                        },
                    )
                    state is ConnectionState.Online -> {
                        val session = (state as ConnectionState.Online).session
                        val page by remember(session) { session.screens.map(ScreenParser::parse).flowOn(Dispatchers.Default) }
                            .collectAsState(initial = session.page)
                        val route = stack.last()
                        holder.SaveableStateProvider(route.key) {
                            OnlineScreens(
                                session = session,
                                page = page,
                                route = route,
                                onPush = ::push,
                                onPop = ::pop,
                                onSettings = { settingsOpen = true },
                            )
                        }
                    }
                    else -> LoginScreen(
                        state = state,
                        savedAccount = container.credentials.load(),
                        rememberPassword = container.rememberPassword.value,
                        onRememberChange = container.rememberPassword::set,
                        onLogin = { account, password ->
                            container.scope.launch {
                                container.connection.login(account, password, remember = container.rememberPassword.value)
                            }
                        },
                        onSettings = { settingsOpen = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun OnlineScreens(
    session: Session,
    page: Page,
    route: Route,
    onPush: (Route) -> Unit,
    onPop: () -> Unit,
    onSettings: () -> Unit,
) {
    when (route) {
        Route.Favorites -> FavoritesScreen(
            session = session,
            page = page,
            onOpenBoard = { onPush(Route.Board(it)) },
            onTerminal = { onPush(Route.Terminal) },
            onSettings = onSettings,
        )
        is Route.Board -> BoardScreen(
            session = session,
            page = page,
            board = route.name,
            onOpenArticle = { onPush(Route.Article(route.name, it)) },
            onTerminal = { onPush(Route.Terminal) },
            onBack = onPop,
        )
        is Route.Article -> ArticleScreen(
            session = session,
            page = page,
            board = route.board,
            onTerminal = { onPush(Route.Terminal) },
            onBack = onPop,
        )
        Route.Terminal -> TerminalScreen(session = session, onBack = onPop)
    }
}
