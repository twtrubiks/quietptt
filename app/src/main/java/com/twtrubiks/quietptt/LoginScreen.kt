package com.twtrubiks.quietptt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.twtrubiks.quietptt.core.terminal.Screen
import kotlinx.coroutines.flow.drop
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 登入頁的哪一層（照 PttX）。 */
private enum class LoginStep { LAST, LIST, FORM }

/**
 * 登入頁，照 PttX 分三層：
 * - [LoginStep.LAST]：最後一次登入的帳號一張卡片（代號、上次登入時間），點了就登入；下面「使用其他帳號」到列表。
 * - [LoginStep.LIST]：最上面「新增帳號」，接著每個記住的帳號一張卡片（最後一次登入的、其他照上次登入時間新的在前），
 *   點了登入，右邊垃圾桶忘掉那一組（先問）。
 * - [LoginStep.FORM]：帳號、密碼（眼睛可以顯示）、「記住帳號與密碼」、登入；有記住的帳號時下面「使用其他帳號」回列表。
 *
 * 首頁帳號選單的「切換帳號」登出過來的（[startAtList]）直接從 LIST 開始。
 * 沒記任何帳號就只有 FORM、沒有 ✕。只記帳號沒記密碼的卡片點了到 FORM，帳號填好、打密碼。從卡片登入但密碼不對
 * （[ConnectionState.Offline.badPassword]，例如在別的地方改過密碼）也換到 FORM 重打。左上的 ✕ 與系統返回鍵回上一層。
 * 連線中、登入中顯示進度、什麼都不能按；失敗的原因在 [ConnectionState.Offline.reason]，有當時的畫面（[ConnectionState.Offline.screen]）就給「看當時的畫面」。
 * 重複登入答了「不刪除」、其他連線已經有 3 條被擋下（[ConnectionState.Offline.tooManyLogins]）：多一顆「刪除其他連線再登入」（[onKickOthers]）。
 */
@Composable
fun LoginScreen(
    state: ConnectionState,
    savedAccounts: List<Account>,
    lastLogin: (String) -> Long?,
    onForget: (String) -> Unit,
    rememberPassword: Boolean,
    onRememberChange: (Boolean) -> Unit,
    onLogin: (account: String, password: String) -> Unit,
    onKickOthers: () -> Unit,
    onSettings: () -> Unit,
    startAtList: Boolean = false,
) {
    var step by rememberSaveable { mutableStateOf(if (startAtList) LoginStep.LIST else LoginStep.LAST) }
    // FORM 的 ✕ 回哪一層（從列表的「新增帳號」來的回列表）
    var formBack by rememberSaveable { mutableStateOf(LoginStep.LAST) }
    var account by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    // 最近一次從卡片登入的帳號：密碼不對時帶到 FORM
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var forgetting by rememberSaveable { mutableStateOf<String?>(null) }
    var failedScreen by remember { mutableStateOf<Screen?>(null) }
    val shown = if (savedAccounts.isEmpty()) LoginStep.FORM else step
    val busy = state is ConnectionState.Connecting || state is ConnectionState.LoggingIn

    fun openForm(name: String, from: LoginStep) {
        account = name
        password = ""
        formBack = from
        step = LoginStep.FORM
    }

    /** 點了一張帳號卡片：記了密碼就登入，沒記到 FORM 打密碼。 */
    fun pick(saved: Account, from: LoginStep) {
        val remembered = saved.password
        if (remembered == null) {
            openForm(saved.name, from)
        } else {
            picked = saved.name
            onLogin(saved.name, remembered)
        }
    }

    val back: (() -> Unit)? = when {
        shown == LoginStep.LIST -> { { step = LoginStep.LAST } }
        shown == LoginStep.FORM && savedAccounts.isNotEmpty() -> { { step = formBack } }
        else -> null
    }
    BackHandler(enabled = back != null && !busy) { back?.invoke() }

    // 從卡片登入（或打開 App 自動登入）密碼不對：換到 FORM 重打那個帳號的密碼。
    // 只看「變成」密碼不對的那一下（drop 掉進來時的狀態）：從設定頁回來、狀態還是同一個失敗時不要又跳過去
    val latestState by rememberUpdatedState(state)
    val latestStep by rememberUpdatedState(shown)
    val latestFirst by rememberUpdatedState(savedAccounts.firstOrNull()?.name)
    LaunchedEffect(Unit) {
        snapshotFlow { latestState }.drop(1).collect { now ->
            if (now is ConnectionState.Offline && now.badPassword && latestStep != LoginStep.FORM) {
                openForm(picked ?: latestFirst ?: "", latestStep)
            }
        }
    }

    failedScreen?.let { screen ->
        AlertDialog(
            onDismissRequest = { failedScreen = null },
            confirmButton = { TextButton(onClick = { failedScreen = null }) { Text("關閉") } },
            text = { TerminalText(screen) },
        )
    }
    forgetting?.let { name ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("忘記 $name？") },
            text = { Text("這支手機會忘掉這個帳號和它的密碼，之後要登入得重打。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onForget(name)
                        forgetting = null
                    },
                    modifier = Modifier.testTag("forget_confirm"),
                ) { Text("忘記") }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("取消") } },
        )
    }

    // 小手機打字時鍵盤會蓋住下面：整頁可以捲
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
        // 上面一列：能回上一層就是 ✕（PttX），不然是 App 的名字
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
            if (back != null) {
                IconButton(onClick = back, enabled = !busy, modifier = Modifier.testTag("login_close")) {
                    Icon(Icons.Filled.Close, contentDescription = "回上一層")
                }
            } else {
                Text("QuietPTT", style = MaterialTheme.typography.headlineSmall)
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (shown) {
                LoginStep.LAST -> {
                    val last = savedAccounts.first()
                    AccountCard(last, lastLogin(last.name), enabled = !busy, tag = "last_account", onClick = { pick(last, LoginStep.LAST) })
                    WideButton("使用其他帳號", "other_account", enabled = !busy) { step = LoginStep.LIST }
                }
                LoginStep.LIST -> {
                    Card(
                        onClick = { openForm("", LoginStep.LIST) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("add_account"),
                    ) {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text("新增帳號", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    for (saved in savedAccounts) {
                        AccountCard(
                            saved,
                            lastLogin(saved.name),
                            enabled = !busy,
                            tag = "saved_account_${saved.name}",
                            onClick = { pick(saved, LoginStep.LIST) },
                            onForget = { forgetting = saved.name },
                        )
                    }
                }
                LoginStep.FORM -> {
                    val canLogin = !busy && account.isNotBlank() && password.isNotEmpty()
                    OutlinedTextField(
                        value = account,
                        onValueChange = { account = it.trim() },
                        label = { Text("帳號") },
                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().testTag("account_input"),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密碼") },
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        trailingIcon = {
                            // 跟 PttX 一樣：藏著的時候是劃線的眼睛
                            IconButton(onClick = { showPassword = !showPassword }, modifier = Modifier.testTag("password_visibility")) {
                                Icon(
                                    if (showPassword) AppIcons.Visibility else AppIcons.VisibilityOff,
                                    contentDescription = if (showPassword) "隱藏密碼" else "顯示密碼",
                                )
                            }
                        },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (canLogin) onLogin(account, password) }),
                        modifier = Modifier.fillMaxWidth().testTag("password_input"),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberPassword, onCheckedChange = onRememberChange, enabled = !busy, modifier = Modifier.testTag("remember_checkbox"))
                        Text("記住帳號與密碼")
                    }
                    Button(
                        onClick = { onLogin(account, password) },
                        enabled = canLogin,
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("login_button"),
                    ) {
                        Text("登入")
                    }
                    if (savedAccounts.isNotEmpty()) WideButton("使用其他帳號", "other_account", enabled = !busy) { step = LoginStep.LIST }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(if (state is ConnectionState.Connecting) "連線中…" else "登入中…")
            }
        } else {
            (state as? ConnectionState.Offline)?.reason?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("login_error"))
            }
            // 設定是「不刪除」、其他連線已經有 3 條：這次改成刪除再登一次（設定不變）
            if ((state as? ConnectionState.Offline)?.tooManyLogins == true) {
                Button(onClick = onKickOthers, modifier = Modifier.padding(top = 8.dp).testTag("kick_others_button")) {
                    Text("刪除其他連線再登入")
                }
            }
            // 過載、等太久這種失敗：看得到 PTT 當時的畫面寫了什麼
            (state as? ConnectionState.Offline)?.screen?.let { screen ->
                TextButton(onClick = { failedScreen = screen }, modifier = Modifier.testTag("failed_screen_button")) { Text("看當時的畫面") }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSettings, modifier = Modifier.testTag("settings_button")) { Text("設定") }
    }
}

/** 一個記住的帳號：代號、上次登入時間（沒記到就不寫）、沒記密碼的話說一聲；[onForget] 有的話右邊一個垃圾桶。 */
@Composable
private fun AccountCard(saved: Account, lastLogin: Long?, enabled: Boolean, tag: String, onClick: () -> Unit, onForget: (() -> Unit)? = null) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(saved.name, style = MaterialTheme.typography.titleMedium)
                if (lastLogin != null) Text("上次登入時間：${LAST_LOGIN_FORMAT.format(Instant.ofEpochMilli(lastLogin).atZone(ZoneId.systemDefault()))}")
                if (saved.password == null) {
                    Text("沒記密碼，點了打密碼", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onForget != null) {
                IconButton(onClick = onForget, enabled = enabled, modifier = Modifier.testTag("forget_${saved.name}")) {
                    Icon(Icons.Filled.Delete, contentDescription = "忘記 ${saved.name}")
                }
            }
        }
    }
}

/** 整排寬的按鈕（「使用其他帳號」）。 */
@Composable
private fun WideButton(text: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp).testTag(tag)) {
        Text(text)
    }
}

private val LAST_LOGIN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
