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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.twtrubiks.quietptt.core.ptt.MailReply
import com.twtrubiks.quietptt.core.ptt.MailSummary
import com.twtrubiks.quietptt.core.ptt.MailUnconfirmedException
import com.twtrubiks.quietptt.core.ptt.NewMail
import com.twtrubiks.quietptt.core.ptt.Session
import com.twtrubiks.quietptt.core.terminal.Big5Uao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 寫信頁（私人信件分頁的「寫信」、讀信頁的「回信」、文章頁「⋮」的「寄信給作者」）：收件人（回信是寄件人，不能改）、標題、簽名檔、內文，
 * 回信多一個「引用原文」（預設勾，PTT 的預設也是引用）；「寄出後自存底稿」（預設不勾）。右下「寄出」先問一次再送。
 * 寄信給作者預填收件人 [initialTo]（作者）與標題 [initialTitle]（「Re: 文章標題」），都可以改；不引用文章。
 *
 * 寫新的一封用 [Session.sendMail]（先查收件人在不在、PTT 會不會把代號補成別人）；回信用 [Session.replyMail]
 * （先對信箱那一列的寄件人與標題，信箱變了就不回）。標題沒改就用 PTT 給的「Re: 原標題」。
 * 送出走 App 的 scope（離開這一頁也要把 PTT 那邊的流程走完，不能留在編輯器裡），送的時候不給返回。
 * 寄好了 toast 說寄給誰、回上一頁（[onDone]）。失敗顯示原因、「重試」、「切到終端畫面」；
 * 存檔之後才出錯（[MailUnconfirmedException]）可能已經寄出去了，不給重試。
 * 記了兩組以上帳號時，標題下面一行「以 代號 寄出」（[signedAs]）。
 */
@Composable
fun WriteMailScreen(
    session: Session,
    reply: MailSummary?,
    onDone: () -> Unit,
    onTerminal: () -> Unit,
    onBack: () -> Unit,
    initialTo: String? = null,
    initialTitle: String? = null,
    signedAs: String? = null,
) {
    val context = LocalContext.current
    val container = context.container
    val defaultTitle = remember(reply) { reply?.let { replyTitle(it.title) } ?: initialTitle ?: "" }
    var to by rememberSaveable { mutableStateOf(initialTo ?: "") }
    var title by rememberSaveable { mutableStateOf(defaultTitle) }
    var body by rememberSaveable { mutableStateOf("") }
    var signature by rememberSaveable { mutableStateOf("0") }
    var quote by rememberSaveable { mutableStateOf(true) }
    var backup by rememberSaveable { mutableStateOf(false) }
    var signatureOpen by rememberSaveable { mutableStateOf(false) }
    var confirmOpen by rememberSaveable { mutableStateOf(false) }
    val sending = remember(session, reply) { MailSending() }

    val recipient = reply?.author ?: to.trim()
    val toProblem = if (reply == null) recipientProblem(to) else null
    // 回信的標題沒改就交給 PTT（「採用原標題」答 y），不檢查長度
    val titleChanged = reply == null || title.trim() != defaultTitle
    val problem = toProblem ?: if (titleChanged) mailTitleProblem(title) else null

    fun send() {
        if (sending.active || sending.unconfirmed) return
        sending.active = true
        sending.error = null
        val sig = signature.first()
        container.scope.launch {
            try {
                val sentTo = if (reply == null) {
                    session.sendMail(NewMail(to.trim(), title.trim(), body, sig, backup))
                } else {
                    session.replyMail(reply.index, reply, MailReply(body, title.trim().takeIf { titleChanged }, quote, sig, backup))
                    reply.author
                }
                context.toast("已寄給 $sentTo")
                sending.done = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: MailUnconfirmedException) {
                // 已經存檔了才出錯：可能寄出去了，不給重試（會寄兩封）
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
        AppBar(if (reply == null) "寫信" else "回信", onBack = if (sending.active) null else onBack, subtitle = signedAs?.let { "以 $it 寄出" }) {
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
                Text("正在寄信…請不要離開")
            }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            sending.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("mail_error"))
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
            if (reply == null) {
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it.filterNot(Char::isWhitespace) },
                    label = { Text("收件人") },
                    singleLine = true,
                    isError = to.isNotEmpty() && toProblem != null,
                    supportingText = { Text(if (to.isNotEmpty() && toProblem != null) toProblem else "PTT 代號（寄之前會先查有沒有這個人）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag("mail_to"),
                )
            } else {
                Text("收件人：${reply.author}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("mail_to"))
                Spacer(Modifier.height(8.dp))
            }
            val titleError = if (titleChanged) mailTitleProblem(title) else null
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.replace("\n", " ") },
                label = { Text("標題") },
                singleLine = true,
                isError = title.isNotEmpty() && titleError != null,
                supportingText = { if (title.isNotEmpty() && titleError != null) Text(titleError) },
                modifier = Modifier.fillMaxWidth().testTag("mail_title"),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { signatureOpen = true }, modifier = Modifier.testTag("signature_button")) {
                Text(signatureLabel(signature))
            }
            if (reply != null) CheckRow("引用原文", quote, "mail_quote") { quote = it }
            CheckRow("寄出後自存底稿（自己的信箱留一份）", backup, "mail_backup") { backup = it }
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("內文") },
                supportingText = { if (reply != null && quote) Text("原文會引用在內文下面") },
                minLines = 10,
                modifier = Modifier.fillMaxWidth().testTag("mail_body"),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onBack) { Text("取消") }
                TextButton(
                    onClick = { confirmOpen = true },
                    enabled = problem == null && !sending.unconfirmed,
                    modifier = Modifier.testTag("send_mail_button"),
                ) { Text("寄出") }
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
            title = { Text("確定要寄出?") },
            text = { Text(mailSummary(recipient, title.trim(), body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOpen = false
                        send()
                    },
                    modifier = Modifier.testTag("confirm_send_mail"),
                ) { Text("寄出") }
            },
            dismissButton = { TextButton(onClick = { confirmOpen = false }) { Text("取消") } },
        )
    }
}

/** 送出中的狀態：離開這一頁也要留著，所以不放在 composable 的區域變數。 */
private class MailSending {
    var active by mutableStateOf(false)
    var done by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** 已經存檔了才出錯（[MailUnconfirmedException]）：可能寄出去了，不給重試。 */
    var unconfirmed by mutableStateOf(false)
}

/** 一列：勾選框加說明，點字也能切（回文頁也用）。 */
@Composable
internal fun CheckRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

/** PTT 代號：2～12 個英文字母或數字，第一個是字母（pttbbs `is_validuserid`）。 */
private val PTT_ID = Regex("[A-Za-z][A-Za-z0-9]{1,11}")

/** 收件人不能送的原因；可以就是 null。 */
fun recipientProblem(to: String): String? = when {
    to.isBlank() -> "請輸入收件人的代號"
    !PTT_ID.matches(to.trim()) -> "代號是 2～12 個英文字母或數字，第一個是字母"
    else -> null
}

/**
 * 標題不能送的原因；可以就是 null。pttbbs `mail_ui_send` 的「主題：」輸入框最多 [MAIL_TITLE_BYTES] 個 byte（`STRLEN-20` 減結尾，中文字算 2）。
 */
fun mailTitleProblem(title: String): String? {
    val t = title.trim()
    if (t.isEmpty()) return "請輸入標題"
    val bytes = Big5Uao.encode(t).size
    return if (bytes > MAIL_TITLE_BYTES) "標題太長（最多 $MAIL_TITLE_BYTES 個字元，中文算 2 個；現在 $bytes 個）" else null
}

const val MAIL_TITLE_BYTES = 59

/**
 * 回信預設的標題：跟 PTT 一樣「Re: 原標題」（pttbbs `do_reply_title` 用 `subject()` 去掉前面一層層的「Re:」「Fw:」）。
 * 信箱列表上回信顯示成「R: 」、轉寄顯示成「轉 」（`maildoent`），也去掉。
 */
fun replyTitle(listed: String): String {
    var t = listed.trim().removePrefix("◇").trim()
    while (true) {
        val prefix = REPLY_PREFIXES.firstOrNull { t.startsWith(it, ignoreCase = true) } ?: break
        t = t.drop(prefix.length).trimStart()
    }
    return "Re: $t"
}

private val REPLY_PREFIXES = listOf("Re:", "Fw:", "R:", "轉 ")

/** 確認對話框的內容：收件人、標題、內文前 60 字。 */
fun mailSummary(to: String, title: String, body: String): String {
    val preview = body.trim().let { if (it.length > 60) it.take(60) + "…" else it }
    return "收件人：$to\n標題：$title\n內文：${preview.ifEmpty { "（空白）" }}\n\n寄出去就收不回來。送出時不要離開 App。"
}
