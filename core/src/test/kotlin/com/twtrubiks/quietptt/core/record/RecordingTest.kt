package com.twtrubiks.quietptt.core.record

import com.twtrubiks.quietptt.core.terminal.Big5Uao
import com.twtrubiks.quietptt.core.terminal.Terminal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTest {
    @Test
    fun writeThenParseKeepsEveryEvent() {
        val big5 = Big5Uao.encode("\u001b[1;33m請輸入代號\u001b[m")
        val recording = Recording(
            listOf(
                Recording.Received(0, big5),
                Recording.Note(800, "接下來打帳號"),
                Recording.Sent(1_200, null, "<帳號> ENTER"),
                Recording.Sent(2_500, "s".toByteArray(), "\"s\""),
            ),
        )
        val parsed = Recording.parse(recording.toString())
        assertEquals(4, parsed.events.size)
        val received = parsed.events[0] as Recording.Received
        assertArrayEquals(big5, received.bytes)
        val note = parsed.events[1] as Recording.Note
        assertEquals(800L to "接下來打帳號", note.ms to note.text)
        val account = parsed.events[2] as Recording.Sent
        assertNull(account.bytes)
        assertEquals("<帳號> ENTER", account.label)
        val key = parsed.events[3] as Recording.Sent
        assertEquals(2_500L, key.ms)
        assertArrayEquals("s".toByteArray(), key.bytes)
    }

    @Test
    fun accountAndIpAreReplacedWithTheSameLength() {
        val text = "我是 SomeOne  上次從 203.0.113.45 登入；someone2 不是我、ptt.cc 也不是"
        assertEquals(
            "我是 xxxxxxx  上次從 000.0.000.00 登入；someone2 不是我、ptt.cc 也不是",
            Redactor.redact(text, "someone"),
        )
    }

    @Test
    fun accountInsideTheByteStreamIsReplacedEvenWhenColored() {
        // 狀態列裡帳號中間夾了顏色：跳脫序列當透明的
        val bytes = Big5Uao.encode("\u001b[2;1H我是 some\u001b[1;37mone\u001b[m 你好")
        val redacted = Redactor.redact(bytes, "someone")
        val terminal = Terminal().apply { feed(redacted) }
        assertEquals("我是 xxxxxxx 你好", terminal.snapshot().line(1).trimEnd())
        assertEquals(bytes.size, redacted.size)
    }

    @Test
    fun accountRightAfterAColorCodeIsReplaced() {
        // 編輯器把文章裡的 ESC 顯示成「*」：自己的推文列是「*[1;31m→ *[33m帳號*[m*[33m: …」（真機），帳號前面緊接著「m」
        val editor = "*[1;31m→ *[33mSomeOne*[m*[33m: 推文測試"
        assertEquals("*[1;31m→ *[33mxxxxxxx*[m*[33m: 推文測試", Redactor.redact(editor, "someone"))
        // bytes 版本：那些「*[33m」是看得到的字，不是跳脫序列
        val terminal = Terminal().apply { feed(Redactor.redact(Big5Uao.encode(editor), "someone")) }
        assertEquals("*[1;31m→ *[33mxxxxxxx*[m*[33m: 推文測試", terminal.snapshot().line(0).trimEnd())
        // 文字裡留著真的跳脫序列也一樣
        assertEquals("\u001b[33mxxxxxxx\u001b[m", Redactor.redact("\u001b[33mSomeOne\u001b[m", "someone"))
        // 一般的英數字接在前面還是不換
        assertEquals("msomeone 33msomeone", Redactor.redact("msomeone 33msomeone", "someone"))
    }

    @Test
    fun big5TrailBytesThatLookLikeLettersAreNotTouched() {
        // 「乙」是 A4 41，後半剛好是 'A'：帳號 abc 不能把「乙bc」的 A 換掉
        val bytes = Big5Uao.encode("乙bc 乙 abc")
        val terminal = Terminal().apply { feed(Redactor.redact(bytes, "abc")) }
        assertEquals("乙bc 乙 xxx", terminal.snapshot().line(0).trimEnd())
    }

    @Test
    fun ipSplitAcrossNothingButDigitsAndDots() {
        val bytes = "from 10.20.30.40\r\nversion 1.2.3".toByteArray()
        val terminal = Terminal().apply { feed(Redactor.redact(bytes, null)) }
        assertEquals("from 00.00.00.00", terminal.snapshot().line(0).trimEnd())
        assertTrue(terminal.snapshot().line(1).startsWith("version 1.2.3"))
    }
}
