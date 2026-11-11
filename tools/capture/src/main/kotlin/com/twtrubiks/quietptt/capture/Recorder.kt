package com.twtrubiks.quietptt.capture

import com.twtrubiks.quietptt.core.ptt.Page
import com.twtrubiks.quietptt.core.record.Recording
import com.twtrubiks.quietptt.core.record.Redactor
import com.twtrubiks.quietptt.core.terminal.Screen
import com.twtrubiks.quietptt.core.transport.Transport
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 錄影：收到的 bytes 與送出的按鍵（各帶毫秒），加上每一步的畫面文字。
 * 每一步都整個重寫兩個檔，中途斷線或當掉也不會丟。存檔時才遮帳號與 IP，帳號是錄到一半才知道的。
 */
class Recorder(val recordingFile: File, val screensFile: File) {
    private val start = System.nanoTime()
    private val events = ArrayList<Recording.Event>()
    private val screens = StringBuilder()

    /** 要遮的字（帳號，以及登入途中分不清是帳號還是密碼的字）；存檔時換成同樣長度的 x。 */
    val secrets: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun now(): Long = (System.nanoTime() - start) / 1_000_000

    @Synchronized
    fun received(bytes: ByteArray) {
        events += Recording.Received(now(), bytes.copyOf())
    }

    /** [bytes] 是 null 表示不留（帳號、密碼）。 */
    @Synchronized
    fun sent(bytes: ByteArray?, label: String) {
        events += Recording.Sent(now(), bytes, label)
    }

    @Synchronized
    fun note(text: String) {
        events += Recording.Note(now(), text)
        save()
    }

    @Synchronized
    fun screen(label: String, page: Page, screen: Screen) {
        screens.append("## %.1f 秒　送出：%s　辨識：%s\n".format(now() / 1000.0, label, page::class.simpleName))
        screens.append(screen.toString()).append("\n\n")
        save()
    }

    @Synchronized
    fun save() {
        recordingFile.writeText(redacted().toString())
        screensFile.writeText(Redactor.redact(screens.toString(), secrets))
    }

    /**
     * 伺服器送來的 bytes 先接成一條再遮（帳號的回顯可能被拆在兩段裡），遮完照原本每段的長度切回去（遮蔽不改長度）。
     */
    private fun redacted(): Recording {
        val received = events.filterIsInstance<Recording.Received>()
        val all = ByteArray(received.sumOf { it.bytes.size })
        var offset = 0
        for (r in received) {
            r.bytes.copyInto(all, offset)
            offset += r.bytes.size
        }
        val clean = Redactor.redact(all, secrets)
        offset = 0
        return Recording(
            events.map { event ->
                when (event) {
                    is Recording.Received -> Recording.Received(event.ms, clean.copyOfRange(offset, offset + event.bytes.size)).also {
                        offset += event.bytes.size
                    }
                    is Recording.Sent -> Recording.Sent(event.ms, event.bytes?.let { Redactor.redact(it, secrets) }, Redactor.redact(event.label, secrets))
                    is Recording.Note -> Recording.Note(event.ms, Redactor.redact(event.text, secrets))
                }
            },
        )
    }
}

/** 包住真的連線：收到的 bytes 原樣交給 [Recorder]，其他照舊。 */
class RecordingTransport(private val inner: Transport, private val recorder: Recorder) : Transport by inner {
    override suspend fun read(): ByteArray? = inner.read()?.also { recorder.received(it) }
}
