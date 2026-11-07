package com.twtrubiks.quietptt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 登入頁。帳號、密碼預填記住的（[savedAccount]）；「記住帳號與密碼」預設勾著。
 * 連線中、登入中顯示進度；失敗的原因在 [ConnectionState.Offline.reason]。
 */
@Composable
fun LoginScreen(
    state: ConnectionState,
    savedAccount: Account?,
    rememberPassword: Boolean,
    onRememberChange: (Boolean) -> Unit,
    onLogin: (account: String, password: String) -> Unit,
    onSettings: () -> Unit,
) {
    var account by rememberSaveable { mutableStateOf(savedAccount?.name ?: "") }
    var password by rememberSaveable { mutableStateOf(savedAccount?.password ?: "") }
    val busy = state is ConnectionState.Connecting || state is ConnectionState.LoggingIn

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("QuietPTT", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = account,
            onValueChange = { account = it.trim() },
            label = { Text("帳號") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("account_input"),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密碼") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("password_input"),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = rememberPassword, onCheckedChange = onRememberChange, enabled = !busy, modifier = Modifier.testTag("remember_checkbox"))
            Text("記住帳號與密碼")
        }
        Spacer(Modifier.height(8.dp))
        if (busy) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(if (state is ConnectionState.Connecting) "連線中…" else "登入中…")
        } else {
            Button(
                onClick = { onLogin(account, password) },
                enabled = account.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("login_button"),
            ) {
                Text("登入")
            }
            (state as? ConnectionState.Offline)?.reason?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("login_error"))
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onSettings, modifier = Modifier.testTag("settings_button")) { Text("設定") }
    }
}
