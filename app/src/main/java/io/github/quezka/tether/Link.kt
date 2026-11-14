package io.github.quezka.tether

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One connection to the computer: the handshake, then encrypted JSON both ways. */
class Link(
    private val store: Store,
    private val phoneName: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onConnected: (String) -> Unit,
) {
    private val queue = LinkedBlockingQueue<JSONObject>()
    @Volatile private var socket: Socket? = null
    @Volatile var connected = false
        private set

    fun send(m: JSONObject) { if (connected) queue.offer(m) }

    fun close() { runCatching { socket?.close() } }

    /** Runs until the connection ends. [secret] is the pairing code (pairing) or the long-term key. Returns why it ended. */
    fun run(host: String, port: Int, secret: ByteArray, pairing: Boolean): String {
        val s = Socket()
        socket = s
        try {
            s.connect(InetSocketAddress(host, port), 5000)
            s.soTimeout = 90_000
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            val nonce = Protocol.randomBytes(16)
            val hello = JSONObject().put("t", "hello").put("id", store.phoneId).put("name", phoneName)
                .put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP)).put("pairing", pairing)
            Protocol.writeFrame(out, hello.toString().toByteArray())
            val reply = JSONObject(String(Protocol.readFrame(input) ?: return "refused"))
            val desktopNonce = Base64.decode(reply.getString("nonce"), Base64.NO_WRAP)
            val crypto = Protocol.Crypto(Protocol.sessionKey(secret, nonce, desktopNonce))
            Protocol.writeFrame(out, crypto.seal(JSONObject().put("t", "auth").toString().toByteArray()))
            if (pairing) {
                val paired = crypto.open(Protocol.readFrame(input) ?: return "wrong code")?.let { JSONObject(String(it)) }
                    ?: return "wrong code"
                store.key = Base64.decode(paired.getString("key"), Base64.NO_WRAP)
                store.computerId = paired.getString("id")
                store.computerName = reply.optString("name")
                store.lastHost = host
            }
            store.lastHost = host
            connected = true
            onConnected(reply.optString("name"))
            queue.clear()
            val writer = Thread {
                try {
                    while (!s.isClosed) {
                        val m = queue.poll(30, TimeUnit.SECONDS) ?: JSONObject().put("t", "ping")
                        Protocol.writeFrame(out, crypto.seal(m.toString().toByteArray()))
                    }
                } catch (_: Exception) { runCatching { s.close() } }
            }.apply { isDaemon = true; start() }
            while (true) {
                val sealed = Protocol.readFrame(input) ?: break
                val plain = crypto.open(sealed) ?: break
                runCatching { onMessage(JSONObject(String(plain))) }
            }
            writer.interrupt()
            return "closed"
        } catch (e: Exception) {
            return e.message ?: "failed"
        } finally {
            connected = false
            runCatching { s.close() }
        }
    }
}
