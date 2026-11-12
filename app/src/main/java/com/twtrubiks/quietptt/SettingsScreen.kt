package com.twtrubiks.quietptt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * 設定：連線方式（預設 SSH，下次連線生效）、深色模式、文章字級、滑動回上一頁的方向、記住帳號與密碼、自動登入、重複登入時刪除其他連線、
 * 文末加上「Sent from QuietPTT」、清除搜尋紀錄、登出／忘記帳號，最下面「關於」（版本、GitHub 的網址）。
 * 登入著的時候（[account] 不是 null）「登出」上面寫目前登入的帳號。
 * 這裡沒有、以後也不會有的：遙測開關（因為沒有遙測）、背景通知、前景服務。
 */
@Composable
fun SettingsScreen(account: String?, onBack: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    Column(Modifier.fillMaxSize()) {
        AppBar("設定", onBack = onBack)
        // 項目多了，小手機放不下：整頁可以捲
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("連線方式", style = MaterialTheme.typography.titleMedium)
            for (kind in TransportKind.entries) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = container.transport.value == kind,
                        onClick = { container.transport.set(kind) },
                        modifier = Modifier.testTag("transport_${kind.name.lowercase()}"),
                    )
                    Text(kind.label)
                }
            }
            Text("切換後下次連線生效", style = MaterialTheme.typography.bodySmall)

            Text("深色模式", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            for (mode in ThemeMode.entries) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = container.theme.value == mode, onClick = { container.theme.set(mode) })
                    Text(mode.label)
                }
            }

            Text("文章字級", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            for (size in ArticleTextSize.entries) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = container.articleTextSize.value == size,
                        onClick = { container.articleTextSize.set(size) },
                        modifier = Modifier.testTag("text_size_${size.name.lowercase()}"),
                    )
                    Text(size.label)
                }
            }
            Text("文章與信的字，跟著系統的字體大小再放大縮小；文章頁「⋮」也能改", style = MaterialTheme.typography.bodySmall)

            Text("滑動回上一頁", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            for (mode in SwipeBackMode.entries) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = container.swipeBack.value == mode,
                        onClick = { container.swipeBack.set(mode) },
                        modifier = Modifier.testTag("swipe_back_${mode.name.lowercase()}"),
                    )
                    Text(mode.label)
                }
            }
            Text("往那個方向滑過畫面寬度三成、或快速一甩就回上一頁（終端畫面除外）", style = MaterialTheme.typography.bodySmall)

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = container.rememberPassword.value,
                    onCheckedChange = {
                        container.rememberPassword.set(it)
                        // 關掉就馬上把存著的密碼（每一組）丟掉，帳號留著
                        if (!it) container.credentials.forgetPasswords()
                    },
                    modifier = Modifier.testTag("remember_switch"),
                )
                Text("記住帳號與密碼", modifier = Modifier.padding(start = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = container.autoLogin.value,
                    onCheckedChange = container.autoLogin::set,
                    modifier = Modifier.testTag("auto_login_switch"),
                )
                Text("自動登入", modifier = Modifier.padding(start = 8.dp))
            }
            Text("打開 App 時用上次登入的帳號直接登入（要記住密碼）；登出後這次就停在登入頁", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = container.kickOtherLogins.value,
                    onCheckedChange = container.kickOtherLogins::set,
                    modifier = Modifier.testTag("kick_other_logins_switch"),
                )
                Text("重複登入時刪除其他連線", modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                "這個帳號在別的地方（電腦、其他 App）也登入著時，PTT 會問要不要刪除其他連線。開著就刪除（那邊會被踢下線）；" +
                    "關掉就留著，但別的地方已經有 3 條時登不進去，登入頁會給「刪除其他連線再登入」",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = container.sentFrom.value,
                    onCheckedChange = container.sentFrom::set,
                    modifier = Modifier.testTag("sent_from_switch"),
                )
                Text("文末加上「Sent from QuietPTT」", modifier = Modifier.padding(start = 8.dp))
            }
            Text("發文、回文時在內文最後面加兩列「-----」「Sent from QuietPTT」（不寫手機型號）；發文頁、回文頁也能勾", style = MaterialTheme.typography.bodySmall)

            OutlinedTextField(
                value = container.bodyTemplate.value,
                onValueChange = container.bodyTemplate::set,
                label = { Text("預設內文") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("body_template"),
            )
            Text("發文、回文時先幫你打在內文欄位裡，送出前可以改；留空就不帶。只存在這支手機", style = MaterialTheme.typography.bodySmall)

            Button(
                onClick = { container.searchHistory.clearAll() },
                enabled = !container.searchHistory.isEmpty,
                modifier = Modifier.padding(top = 16.dp).testTag("clear_search_history_button"),
            ) {
                Text("清除搜尋紀錄")
            }
            Text("看板裡搜過的標題、作者、推文數（只存在這支手機）；一筆一筆刪在搜尋對話框", style = MaterialTheme.typography.bodySmall)

            if (account != null) {
                Text("目前登入：$account", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp).testTag("current_account"))
                Button(onClick = onLogout, modifier = Modifier.padding(top = 8.dp).testTag("logout_button")) { Text("登出") }
                Text("登出後在登入頁可以挑記住的另一個帳號，或打新的帳號登入", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    container.credentials.clear()
                    onLogout()
                },
                modifier = Modifier.padding(top = 8.dp).testTag("forget_button"),
            ) {
                Text("忘記所有帳號與密碼")
            }

            Text("關於", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            Text("QuietPTT $version", modifier = Modifier.padding(top = 4.dp).testTag("about_version"))
            Text("沒有廣告、沒有遙測、不在背景做事，對外只連 ptt.cc。原始碼公開：", style = MaterialTheme.typography.bodySmall)
            AboutLink("GitHub 原始碼", GITHUB_URL, "about_github")
            AboutLink("問題回報、建議", "$GITHUB_URL/issues", "about_issues")
        }
    }
}

/** 「關於」裡的一列連結：[label] 與下面小字的網址，點了交給外部瀏覽器（手機上沒有瀏覽器就跳一句話）。 */
@Composable
private fun AboutLink(label: String, url: String, tag: String) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { runCatching { uriHandler.openUri(url) }.onFailure { context.toast("打不開連結：$url") } }
            .padding(vertical = 8.dp)
            .testTag(tag),
    ) {
        Text(label, color = MaterialTheme.colorScheme.primary)
        Text(url.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
