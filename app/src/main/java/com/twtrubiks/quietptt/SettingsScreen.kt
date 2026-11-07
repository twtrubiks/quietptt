package com.twtrubiks.quietptt

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * 設定：連線方式（預設 SSH，下次連線生效）、深色模式、記住帳號與密碼、登出／忘記帳號。
 * 這裡沒有、以後也不會有的：遙測開關（因為沒有遙測）、背景通知、前景服務。
 */
@Composable
fun SettingsScreen(online: Boolean, onBack: () -> Unit, onLogout: () -> Unit) {
    val container = LocalContext.current.container
    Column(Modifier.fillMaxSize()) {
        AppBar("設定", onBack = onBack)
        Column(Modifier.padding(16.dp)) {
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

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = container.rememberPassword.value,
                    onCheckedChange = {
                        container.rememberPassword.set(it)
                        // 關掉就馬上把存著的密碼丟掉，帳號留著
                        if (!it) container.credentials.load()?.let { saved -> container.credentials.save(saved.name, null) }
                    },
                    modifier = Modifier.testTag("remember_switch"),
                )
                Text("記住帳號與密碼", modifier = Modifier.padding(start = 8.dp))
            }

            if (online) {
                Button(onClick = onLogout, modifier = Modifier.padding(top = 24.dp).testTag("logout_button")) { Text("登出") }
            }
            Button(
                onClick = {
                    container.credentials.clear()
                    onLogout()
                },
                modifier = Modifier.padding(top = 8.dp).testTag("forget_button"),
            ) {
                Text("忘記帳號與密碼")
            }
        }
    }
}
