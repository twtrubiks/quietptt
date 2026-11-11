package com.twtrubiks.quietptt.core.transport

import com.twtrubiks.quietptt.core.terminal.Big5Uao
import com.twtrubiks.quietptt.core.terminal.Terminal
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `WebSocketTransport` 對著假的 ws.ptt.cc（[FakePtt]）：帶 Origin、binary frame 進出、對方關掉時 read 回 null。 */
class WebSocketTransportTest {
    private val client = OkHttpClient()
    private val fakes = mutableListOf<FakePtt>()

    private fun fake(first: String, onInput: FakePtt.(String) -> Unit = {}) = FakePtt(first, onInput).start().also { fakes += it }

    @After
    fun tearDown() {
        fakes.forEach { it.close() }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun sendsTheOriginThatWsPttCcRequires() = runBlocking {
        val ptt = fake("hello")
        val transport = WebSocketTransport(client, url = ptt.url)
        transport.connect()
        assertEquals("https://term.ptt.cc", ptt.takeRequest().headers["Origin"])
        transport.close()
    }

    @Test
    fun bytesComeInAsBig5AndKeysGoOutAsTyped() = runBlocking {
        val ptt = fake("請輸入代號") { input -> if (input == "guest\r") show("歡迎") }
        val transport = WebSocketTransport(client, url = ptt.url)
        transport.connect()

        val terminal = Terminal()
        terminal.feed(withTimeout(5_000) { checkNotNull(transport.read()) })
        assertEquals("請輸入代號", terminal.snapshot().line(0).trimEnd())

        transport.write(Big5Uao.encode("guest\r"))
        terminal.feed(withTimeout(5_000) { checkNotNull(transport.read()) })
        assertEquals("歡迎", terminal.snapshot().line(0).trimEnd())
        assertEquals(listOf("guest\r"), ptt.received)
        transport.close()
    }

    @Test
    fun aCharacterSplitAcrossTwoFramesStillDecodes() = runBlocking {
        val ptt = fake("")
        val transport = WebSocketTransport(client, url = ptt.url)
        transport.connect()
        val terminal = Terminal()
        terminal.feed(withTimeout(5_000) { checkNotNull(transport.read()) })

        // 「中」= A4 A4：lead byte 在第一個 frame 的最後，trail byte 在下一個 frame
        ptt.sendRaw(byteArrayOf(0xA4.toByte()))
        ptt.sendRaw(byteArrayOf(0xA4.toByte()))
        terminal.feed(withTimeout(5_000) { checkNotNull(transport.read()) })
        terminal.feed(withTimeout(5_000) { checkNotNull(transport.read()) })
        assertEquals("中", terminal.snapshot().line(0).trimEnd())
        transport.close()
    }

    @Test
    fun readReturnsNullWhenTheServerHangsUp() = runBlocking {
        val ptt = fake("bye")
        val transport = WebSocketTransport(client, url = ptt.url)
        transport.connect()
        assertArrayEquals(FakePtt.render("bye"), withTimeout(5_000) { transport.read() })
        ptt.hangUp()
        assertNull(withTimeout(5_000) { transport.read() })
    }
}
