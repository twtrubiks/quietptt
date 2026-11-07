package com.twtrubiks.quietptt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** 每一頁上面那條：標題、返回鍵（有的話）、右邊的動作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Text("←") }
            }
        },
        actions = { actions() },
    )
}

/** 小圖示按鈕；圖示先用文字，之後換 drawable。 */
@Composable
fun TextIconButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    IconButton(onClick = onClick, modifier = modifier) {
        if (icon != null) Icon(icon, contentDescription = label) else Text(label)
    }
}

/**
 * 畫面辨識還沒認出預期的頁面時顯示：轉圈加一句話，等太久可以「切到終端畫面」自己看發生什麼事。
 * 每一頁都用這個當退路，App 不會卡在空白。
 */
@Composable
fun WaitingForPage(message: String, onTerminal: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(message)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onTerminal) { Text("切到終端畫面") }
    }
}
