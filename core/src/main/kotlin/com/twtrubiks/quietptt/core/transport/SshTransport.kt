package com.twtrubiks.quietptt.core.transport

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * SSH 連線（預設）：`bbs@ptt.cc:22`，伺服器接受 `none` 驗證，登入是在 BBS 畫面上做的。
 *
 * - Host key 用 [PttHost.KNOWN_HOSTS] pin 住，`StrictHostKeyChecking=yes`
 * - 演算法只挑 Android 內建 JCE 就有的（ECDSA host key、DH group14、AES-CTR、HMAC-SHA2），不帶 BouncyCastle。
 *   ptt.cc 的 bbs-sshd 兩邊都有提供。連不上時先看是不是演算法協商失敗，再考慮加 BouncyCastle
 * - 不送 server-alive（keep-alive）
 */
class SshTransport(
    private val host: String = PttHost.NAME,
    private val port: Int = PttHost.SSH_PORT,
    private val knownHosts: String = PttHost.KNOWN_HOSTS,
) : Transport {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var output: OutputStream? = null

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        val jsch = JSch().apply { setKnownHosts(knownHosts.byteInputStream()) }
        val s = jsch.getSession(PttHost.SSH_USER, host, port).apply {
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", "none")
            setConfig("kex", "diffie-hellman-group14-sha256,curve25519-sha256")
            setConfig("server_host_key", "ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-ed25519")
            setConfig("cipher.c2s", "aes128-ctr,aes256-ctr")
            setConfig("cipher.s2c", "aes128-ctr,aes256-ctr")
            setConfig("mac.c2s", "hmac-sha2-256,hmac-sha2-512")
            setConfig("mac.s2c", "hmac-sha2-256,hmac-sha2-512")
            serverAliveInterval = 0
            connect(Transport.CONNECT_TIMEOUT_MS)
        }
        session = s
        val ch = (s.openChannel("shell") as ChannelShell).apply {
            setPtyType("vt100", COLS, ROWS, 0, 0)
        }
        channel = ch
        val input = ch.inputStream
        output = ch.outputStream
        ch.connect(Transport.CONNECT_TIMEOUT_MS)
        thread(name = "ssh-reader", isDaemon = true) { pump(input) }
    }

    private fun pump(input: InputStream) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) incoming.trySend(buf.copyOf(n))
            }
            incoming.close()
        } catch (e: Exception) {
            incoming.close(e)
        }
    }

    override suspend fun read(): ByteArray? = incoming.receiveCatching().getOrNull()

    override suspend fun write(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val out = checkNotNull(output) { "還沒連線" }
        out.write(bytes)
        out.flush()
    }

    override fun close() {
        channel?.disconnect()
        session?.disconnect()
        incoming.close()
    }

    private companion object {
        const val COLS = 80
        const val ROWS = 24
    }
}
