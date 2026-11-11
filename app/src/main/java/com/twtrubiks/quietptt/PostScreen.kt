package com.twtrubiks.quietptt

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.twtrubiks.quietptt.core.ptt.NewPost
import com.twtrubiks.quietptt.core.ptt.PostOptions
import com.twtrubiks.quietptt.core.ptt.PostUnconfirmedException
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.terminal.Big5Uao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 發文頁（BePTT 的「發表文章」`activity_send_artical`）：「選擇分類」按鈕、標題、說明「分類不會顯示在標題欄，送出時會自動加在標題前」、
 * 簽名檔按鈕（不使用／簽名檔 1～9／隨機）、內文；右上角「發文」先問「確定要發表文章?」（標題與內文前 60 字）再送。
 *
 * 一進來叫 [Session.postOptions]：在列表按 Ctrl-P 讀出這個板的分類（PTT 列的「種類」），再取消掉；不能發文的板在這裡就會知道原因。
 * 送出用 [Session.post]，走 App 的 scope（離開這一頁也要把 PTT 那邊的流程走完，不能留在編輯器裡），送的時候不給返回。
 * 發好了回看板（[onDone]），看板那一頁重新進來就會看到新文章。失敗顯示原因、「重試」、「切到終端畫面」。
 * 記了兩組以上帳號時，標題下面一行「以 代號 發文」（[signedAs]）。
 */
@Composable
fun PostScreen(session: Session, board: String, onDone: () -> Unit, onTerminal: () -> Unit, onBack: () -> Unit, signedAs: String? = null) {
    var options by remember(session, board) { mutableStateOf<PostOptions?>(null) }
    val navigation = rememberNavigation(session, "post-$board") { options = postOptions(board) }
    // -1 是「不選」
    var category by rememberSaveable(board) { mutableIntStateOf(-1) }
    var title by rememberSaveable(board) { mutableStateOf("") }
    var body by rememberSaveable(board) { mutableStateOf("") }
    var signature by rememberSaveable(board) { mutableStateOf("0") }
    var categoryOpen by rememberSaveable { mutableStateOf(false) }
    var signatureOpen by rememberSaveable { mutableStateOf(false) }
    var confirmOpen by rememberSaveable { mutableStateOf(false) }
    val sending = remember(session, board) { Sending() }
    val container = LocalContext.current.container

    val categories = options?.categories ?: emptyList()
    val categoryName = categories.getOrNull(category)
    val problem = titleProblem(title, categoryName)

    fun send() {
        if (sending.active) return
        if (sending.unconfirmed) return // 可能已經發出去了：不再送一次
        sending.active = true
        sending.error = null
        val post = NewPost(category.takeIf { categoryName != null }, title.trim(), body, signature.first(), if (container.sentFrom.value) SENT_FROM else "")
        container.scope.launch {
            try {
                session.post(board, post)
                sending.done = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: PostUnconfirmedException) {
                // 已經存檔了才出錯：可能發出去了，不給重試（會發兩篇）
                sending.unconfirmed = true
                sending.error = e.message
            } catch (e: Exception) {
                sending.error = e.message ?: e::class.simpleName
            } finally {
                sending.active = false
            }
        }
    }
    LaunchedEffect(sending.done) { if (sending.done) onDone() }
    BackHandler(enabled = sending.active) {}

    Column(Modifier.fillMaxSize()) {
        AppBar("發表文章", onBack = if (sending.active) null else onBack, subtitle = signedAs?.let { "以 $it 發文" }) {
            TerminalButton(onTerminal)
        }
        when {
            options == null || navigation.error != null ->
                WaitingForPage("正在讀 $board 的發文分類…", onTerminal, navigation.error, navigation::retry)
            sending.active -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在發表文章…請不要離開")
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                sending.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("post_error"))
                    Row {
                        if (sending.unconfirmed) {
                            TextButton(onClick = onDone, modifier = Modifier.testTag("post_check_board")) { Text("回看板確認") }
                        } else {
                            TextButton(onClick = ::send) { Text("重試") }
                        }
                        TextButton(onClick = onTerminal) { Text("切到終端畫面") }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { categoryOpen = true }, modifier = Modifier.testTag("category_button")) {
                        Text(categoryName ?: "選擇分類")
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = { signatureOpen = true }, modifier = Modifier.testTag("signature_button")) {
                        Text(signatureLabel(signature))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.replace("\n", " ") },
                    label = { Text("標題") },
                    singleLine = true,
                    isError = title.isNotEmpty() && problem != null,
                    supportingText = {
                        Text(if (title.isNotEmpty() && problem != null) problem else "分類不會顯示在標題欄，送出時 PTT 會自動加在標題前")
                    },
                    modifier = Modifier.fillMaxWidth().testTag("post_title"),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("內文") },
                    minLines = 10,
                    modifier = Modifier.fillMaxWidth().testTag("post_body"),
                )
                CheckRow("文末加上「Sent from QuietPTT」", container.sentFrom.value, "post_sent_from", container.sentFrom::set)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onBack) { Text("取消") }
                    TextButton(onClick = { confirmOpen = true }, enabled = problem == null, modifier = Modifier.testTag("send_button")) {
                        Text("發文")
                    }
                }
            }
        }
    }

    if (categoryOpen) {
        ChoiceDialog(
            title = "選擇分類",
            choices = listOf("不選") + categories,
            selected = category + 1,
            onSelect = {
                category = it - 1
                categoryOpen = false
            },
            onDismiss = { categoryOpen = false },
            tag = "category",
        )
    }
    if (signatureOpen) {
        ChoiceDialog(
            title = "簽名檔",
            choices = SIGNATURES.map(::signatureLabel),
            selected = SIGNATURES.indexOf(signature),
            onSelect = {
                signature = SIGNATURES[it]
                signatureOpen = false
            },
            onDismiss = { signatureOpen = false },
            tag = "signature",
        )
    }
    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("確定要發表文章?") },
            text = { Text(postSummary(categoryName, title.trim(), body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOpen = false
                        send()
                    },
                    modifier = Modifier.testTag("confirm_send"),
                ) { Text("發文") }
            },
            dismissButton = { TextButton(onClick = { confirmOpen = false }) { Text("取消") } },
        )
    }
}

/** 送出中的狀態：離開這一頁也要留著，所以不放在 composable 的區域變數。 */
private class Sending {
    var active by mutableStateOf(false)
    var done by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** 已經存檔了才出錯（[PostUnconfirmedException]）：可能發出去了，只給「回看板確認」。 */
    var unconfirmed by mutableStateOf(false)
}

/** 單選的對話框（BePTT 的分類與簽名檔都是這種清單）；寫信頁的簽名檔也用。 */
@Composable
internal fun ChoiceDialog(
    title: String,
    choices: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    tag: String,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                choices.forEachIndexed { i, choice ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(i) }.padding(vertical = 4.dp).testTag("${tag}_$i"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = i == selected, onClick = { onSelect(i) }, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(choice)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
    )
}

/** 簽名檔的選項：PTT 的「0=不加、1～9、x=隨機」。 */
val SIGNATURES: List<String> = listOf("0") + (1..9).map { it.toString() } + "x"

fun signatureLabel(signature: String): String = when (signature) {
    "0" -> "不使用簽名檔"
    "x" -> "隨機簽名檔"
    else -> "簽名檔 $signature"
}

/**
 * 標題不能送的原因；可以就是 null。pttbbs `do_general` 的標題欄最多 [TITLE_BYTES] 個 byte（`DISP_TTLEN`，中文字算 2），
 * 分類「[問卦] 」也算在裡面，超過的會被截掉。
 */
fun titleProblem(title: String, category: String?): String? {
    val t = title.trim()
    if (t.isEmpty()) return "請輸入標題"
    val prefix = category?.let { "[$it] " } ?: ""
    val bytes = Big5Uao.encode(prefix + t).size
    return if (bytes > TITLE_BYTES) "標題太長（含分類最多 $TITLE_BYTES 個字元，中文算 2 個；現在 $bytes 個）" else null
}

const val TITLE_BYTES = 48

/** 確認對話框的內容（BePTT：標題、內文前 60 字）。 */
fun postSummary(category: String?, title: String, body: String): String {
    val fullTitle = (category?.let { "[$it] " } ?: "") + title
    val preview = body.trim().let { if (it.length > 60) it.take(60) + "…" else it }
    return "標題：$fullTitle\n內文：${preview.ifEmpty { "（空白）" }}\n\n請在網路訊號良好的地方發文，送出時不要離開 App。"
}
