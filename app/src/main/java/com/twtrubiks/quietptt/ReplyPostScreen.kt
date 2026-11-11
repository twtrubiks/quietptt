package com.twtrubiks.quietptt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.twtrubiks.quietptt.core.ptt.ArticleSearch
import com.twtrubiks.quietptt.core.ptt.ArticleSummary
import com.twtrubiks.quietptt.core.ptt.PostReply
import com.twtrubiks.quietptt.core.ptt.PostUnconfirmedException
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.terminal.Big5Uao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 回文頁（文章頁下面工具列的「回文」，PTT 的 `y` → (F)看板）：上面一列說回的是誰的哪一篇，標題（預填「Re: 原標題」，可以改）、
 * 簽名檔、「引用原文」（預設勾，PTT 的預設也是引用；引言接在內文下面）、內文。「回文」先問一次再送。只回到看板，不寄給作者。
 *
 * 送出用 [Session.replyPost]：列表上游標移到那篇，先對那一列的作者與標題（[original]，從看板點進來才有），看板變了就不回。
 * 標題沒改就用 PTT 給的（「採用原標題」答 y）。走 App 的 scope（離開這一頁也要把 PTT 那邊的流程走完，不能留在編輯器裡），送的時候不給返回。
 * 發好了 toast、回文章（[onDone]）。失敗顯示原因、「重試」、「切到終端畫面」；存檔之後才出錯（[PostUnconfirmedException]）可能已經發出去了，
 * 只給「回上一頁」（回看板就看得到有沒有發出去）。
 * 記了兩組以上帳號時，標題下面一行「以 代號 回文」（[signedAs]）。
 */
@Composable
fun ReplyPostScreen(
    session: Session,
    board: String,
    index: Int,
    search: ArticleSearch?,
    title: String,
    author: String,
    original: ArticleSummary?,
    onDone: () -> Unit,
    onTerminal: () -> Unit,
    onBack: () -> Unit,
    signedAs: String? = null,
) {
    val context = LocalContext.current
    val container = context.container
    val defaultTitle = remember(title) { replyTitle(title) }
    var newTitle by rememberSaveable { mutableStateOf(defaultTitle) }
    var body by rememberSaveable { mutableStateOf("") }
    var signature by rememberSaveable { mutableStateOf("0") }
    var quote by rememberSaveable { mutableStateOf(true) }
    var signatureOpen by rememberSaveable { mutableStateOf(false) }
    var confirmOpen by rememberSaveable { mutableStateOf(false) }
    val sending = remember(session, board, index) { ReplySending() }

    // 標題沒改就交給 PTT（「採用原標題」答 y），不檢查長度
    val titleChanged = newTitle.trim() != defaultTitle
    val titleError = if (titleChanged) replyTitleProblem(newTitle) else null
    val bodyError = replyBodyProblem(body)
    val problem = titleError ?: bodyError

    fun send() {
        if (sending.active || sending.unconfirmed) return
        sending.active = true
        sending.error = null
        val reply = PostReply(body, newTitle.trim().takeIf { titleChanged }, quote, signature.first(), if (container.sentFrom.value) SENT_FROM else "")
        container.scope.launch {
            try {
                session.replyPost(board, index, original, reply, search)
                context.toast("回文發好了")
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
        AppBar("回文", onBack = if (sending.active) null else onBack, subtitle = signedAs?.let { "以 $it 回文" }) {
            TerminalButton(onTerminal)
        }
        if (sending.active) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在回文…請不要離開")
            }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            sending.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("reply_error"))
                Row {
                    if (sending.unconfirmed) {
                        TextButton(onClick = onDone) { Text("回上一頁") }
                    } else {
                        TextButton(onClick = ::send) { Text("重試") }
                    }
                    TextButton(onClick = onTerminal) { Text("切到終端畫面") }
                }
                Spacer(Modifier.height(8.dp))
            }
            Text("回應 $author 的文章（$board 板）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("reply_to"))
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it.replace("\n", " ") },
                label = { Text("標題") },
                singleLine = true,
                isError = newTitle.isNotEmpty() && titleError != null,
                supportingText = { if (newTitle.isNotEmpty() && titleError != null) Text(titleError) },
                modifier = Modifier.fillMaxWidth().testTag("reply_title"),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { signatureOpen = true }, modifier = Modifier.testTag("signature_button")) {
                Text(signatureLabel(signature))
            }
            CheckRow("引用原文", quote, "reply_quote") { quote = it }
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("內文") },
                supportingText = { if (quote) Text("原文會引用在內文下面；PTT 規定引言不能超過八成") },
                minLines = 10,
                modifier = Modifier.fillMaxWidth().testTag("reply_body"),
            )
            CheckRow("文末加上「Sent from QuietPTT」", container.sentFrom.value, "reply_sent_from", container.sentFrom::set)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onBack) { Text("取消") }
                TextButton(
                    onClick = { confirmOpen = true },
                    enabled = problem == null && !sending.unconfirmed,
                    modifier = Modifier.testTag("send_reply_button"),
                ) { Text("回文") }
            }
        }
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
            title = { Text("確定要回文?") },
            text = { Text("回應 $author 的文章（$board 板）\n" + postSummary(null, newTitle.trim(), body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOpen = false
                        send()
                    },
                    modifier = Modifier.testTag("confirm_send_reply"),
                ) { Text("回文") }
            },
            dismissButton = { TextButton(onClick = { confirmOpen = false }) { Text("取消") } },
        )
    }
}

/** 送出中的狀態：離開這一頁也要留著，所以不放在 composable 的區域變數。 */
private class ReplySending {
    var active by mutableStateOf(false)
    var done by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** 已經存檔了才出錯（[PostUnconfirmedException]）：可能發出去了，不給重試。 */
    var unconfirmed by mutableStateOf(false)
}

/**
 * 改過的回文標題不能送的原因；可以就是 null。pttbbs `do_reply_title` 的「新標題：」最多 [REPLY_TITLE_BYTES] 個 byte
 * （`TTLEN` 減結尾，中文字算 2）。
 */
fun replyTitleProblem(title: String): String? {
    val t = title.trim()
    if (t.isEmpty()) return "請輸入標題"
    val bytes = Big5Uao.encode(t).size
    return if (bytes > REPLY_TITLE_BYTES) "標題太長（最多 $REPLY_TITLE_BYTES 個字元，中文算 2 個；現在 $bytes 個）" else null
}

const val REPLY_TITLE_BYTES = 63

/** 回文的內文不能是空的（只有引言 PTT 也會說引言太多）。 */
fun replyBodyProblem(body: String): String? = if (body.isBlank()) "請輸入內文" else null
