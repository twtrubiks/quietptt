package com.twtrubiks.quietptt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 設定：一列一個項目（標題、下面一行灰色的說明），分成「外觀」「連線與登入」「發文」「資料」「帳號」「關於」六段。
 * 開關的在右邊放 Switch（點整列都算）；幾個選一個的右邊寫現在選的那個，點了跳對話框挑；「預設內文」點了跳對話框打。
 * 登入著的時候（[account] 不是 null）「帳號」那段多一列「登出」，說明寫目前登入的帳號。
 * 這裡沒有、以後也不會有的：遙測開關（因為沒有遙測）、背景通知、前景服務。
 */
@Composable
fun SettingsScreen(account: String?, onBack: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val uriHandler = LocalUriHandler.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    var editingTemplate by rememberSaveable { mutableStateOf(false) }
    var confirmForget by rememberSaveable { mutableStateOf(false) }

    fun open(url: String) {
        runCatching { uriHandler.openUri(url) }.onFailure { context.toast("打不開連結：$url") }
    }

    Column(Modifier.fillMaxSize()) {
        AppBar("設定", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionHeader("外觀")
            ChoiceRow("深色模式", null, "theme", ThemeMode.entries, container.theme.value, { it.label }, container.theme::set)
            ChoiceRow(
                "文章字級", "文章與信的字；文章頁「⋮」也能改", "text_size",
                ArticleTextSize.entries, container.articleTextSize.value, { it.label }, container.articleTextSize::set,
            )
            ChoiceRow(
                "滑動回上一頁", "往這個方向滑過畫面三成，或快速一甩", "swipe_back",
                SwipeBackMode.entries, container.swipeBack.value, { it.label }, container.swipeBack::set,
            )

            SectionHeader("連線與登入")
            ChoiceRow(
                "連線方式", "切換後下次連線生效", "transport",
                TransportKind.entries, container.transport.value, { it.label }, container.transport::set,
                shortLabel = { if (it == TransportKind.SSH) "SSH" else "WebSocket" },
            )
            SwitchRow("記住帳號與密碼", "關掉只記帳號，存著的密碼馬上丟掉", "remember_switch", container.rememberPassword.value) {
                container.rememberPassword.set(it)
                // 關掉就馬上把存著的密碼（每一組）丟掉，帳號留著
                if (!it) container.credentials.forgetPasswords()
            }
            SwitchRow("自動登入", "打開 App 時用上次的帳號直接登入（要記住密碼）", "auto_login_switch", container.autoLogin.value, container.autoLogin::set)
            SwitchRow(
                "重複登入時刪除其他連線", "開著會把別處的連線踢下線；關掉就留著，別處滿 3 條時登入頁再問",
                "kick_other_logins_switch", container.kickOtherLogins.value, container.kickOtherLogins::set,
            )

            SectionHeader("發文")
            SwitchRow(
                "文末加上「Sent from QuietPTT」", "發文、回文時加在內文最後面；發文頁也能勾",
                "sent_from_switch", container.sentFrom.value, container.sentFrom::set,
            )
            val template = container.bodyTemplate.value
            SettingRow(
                "預設內文",
                if (template.isBlank()) "發文、回文時先帶進內文欄位；現在沒有設" else template.trim().lineSequence().first(),
                "body_template_row",
                onClick = { editingTemplate = true },
            )

            SectionHeader("資料")
            SettingRow(
                "清除搜尋紀錄",
                if (container.searchHistory.isEmpty) "現在沒有紀錄" else "看板裡搜過的標題、作者、推文數（只存在這支手機）",
                "clear_search_history_button",
                enabled = !container.searchHistory.isEmpty,
                onClick = { container.searchHistory.clearAll() },
            )

            SectionHeader("帳號")
            if (account != null) {
                SettingRow("登出", "目前登入：$account", "logout_button", onClick = onLogout)
            }
            SettingRow(
                "忘記所有帳號與密碼", "這支手機記著的每一組都刪掉", "forget_button",
                titleColor = MaterialTheme.colorScheme.error,
                onClick = { confirmForget = true },
            )

            SectionHeader("關於")
            SettingRow("QuietPTT $version", "沒有廣告、沒有遙測、不在背景做事，對外只連 ptt.cc", "about_version")
            SettingRow("GitHub 原始碼", GITHUB_URL.removePrefix("https://"), "about_github", onClick = { open(GITHUB_URL) })
            SettingRow("問題回報、建議", "$GITHUB_URL/issues".removePrefix("https://"), "about_issues", onClick = { open("$GITHUB_URL/issues") })
        }
    }

    if (editingTemplate) {
        BodyTemplateDialog(
            initial = container.bodyTemplate.value,
            onSave = {
                container.bodyTemplate.set(it)
                editingTemplate = false
            },
            onDismiss = { editingTemplate = false },
        )
    }
    if (confirmForget) {
        AlertDialog(
            modifier = DialogTestTags,
            onDismissRequest = { confirmForget = false },
            title = { Text("忘記所有帳號與密碼？") },
            text = { Text("這支手機記著的帳號與密碼都會刪掉，並且登出。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmForget = false
                        container.credentials.clear()
                        onLogout()
                    },
                    modifier = Modifier.testTag("forget_confirm"),
                ) { Text("忘記") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("取消") } },
        )
    }
}

/** 一段的小標題（主色的小字）；上面一條分隔線。 */
@Composable
private fun SectionHeader(title: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** 一列的字：[title] 與下面灰色的 [subtitle]（最多兩行）。[enabled] 是 false 時整列變淡。 */
@Composable
private fun RowText(title: String, subtitle: String?, enabled: Boolean, titleColor: Color, modifier: Modifier = Modifier) {
    val fade = if (enabled) 1f else 0.38f
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor.copy(alpha = fade))
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = fade),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 設定頁每一列共用的外框：至少 56dp 高、左右 16dp。 */
private val rowPadding = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp)

/** 一列：標題、說明，右邊可以有一段字 [value]（現在選的那個）。[onClick] 是 null 就只是顯示。 */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    tag: String,
    value: String? = null,
    enabled: Boolean = true,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .testTag(tag)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .then(rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, subtitle, enabled, titleColor, Modifier.weight(1f))
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

/** 開關的一列：右邊一個 Switch，點整列都會切。 */
@Composable
private fun SwitchRow(title: String, subtitle: String?, tag: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .testTag(tag)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .then(rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, subtitle, true, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp))
    }
}

/**
 * 幾個選一個的一列：右邊寫現在選的（[shortLabel]，沒給就用 [label]），點了跳對話框，一個選項一列（testTag 是 `[tag]_名字小寫`），點了就存、關掉對話框。
 */
@Composable
private fun <E : Enum<E>> ChoiceRow(
    title: String,
    subtitle: String?,
    tag: String,
    entries: List<E>,
    selected: E,
    label: (E) -> String,
    onSelect: (E) -> Unit,
    shortLabel: (E) -> String = label,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingRow(title, subtitle, tag, value = shortLabel(selected), onClick = { open = true })
    if (open) {
        AlertDialog(
            modifier = DialogTestTags,
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    for (entry in entries) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .testTag("${tag}_${entry.name.lowercase()}")
                                .clickable {
                                    onSelect(entry)
                                    open = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = entry == selected, onClick = null, modifier = Modifier.padding(8.dp))
                            Text(label(entry))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("取消") } },
        )
    }
}

/** 打「預設內文」的對話框：多行輸入框，按「儲存」才存（留空就是不帶），「取消」或點外面不存。 */
@Composable
private fun BodyTemplateDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        modifier = DialogTestTags,
        onDismissRequest = onDismiss,
        title = { Text("預設內文") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().testTag("body_template"),
                )
                Text(
                    "發文、回文時先幫你打在內文欄位裡，送出前可以改；留空就不帶。只存在這支手機",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, modifier = Modifier.testTag("body_template_save")) { Text("儲存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
