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

/** One connection to one computer: the handshake, then encrypted JSON both ways. */
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
    /** Which computer (known before connecting, or learned when pairing). */
    @Volatile var computerId: String? = null
    @Volatile var viaRelay = false

    fun send(m: JSONObject) { if (connected) queue.offer(m) }

    fun close() { runCatching { closer?.invoke() ?: socket?.close() } }

    /** Runs until the connection ends. [secret] is the pairing code (pairing) or the long-term key. Returns why it ended. */
    fun run(host: String, port: Int, secret: ByteArray, pairing: Boolean, relayRoom: String? = null): String {
        val s = Socket()
        socket = s
        closer = { s.close() }
        try {
            s.connect(InetSocketAddress(host, port), 5000)
            if (relayRoom != null) {
                // relay: say our room, wait for the computer's waiting line to be joined ("ok\n"), then link as usual
                s.getOutputStream().apply { write(Protocol.relayHello("phone", relayRoom).toByteArray()); flush() }
                s.soTimeout = 15_000
                val ok = ByteArray(3)
                java.io.DataInputStream(s.getInputStream()).readFully(ok)
                if (String(ok) != "ok\n") return "relay refused"
            }
            s.soTimeout = 90_000
            return talk(s.getInputStream(), s.getOutputStream(), secret, pairing, host, relayRoom != null)
        } catch (e: Exception) {
            return e.message ?: "failed"
        } finally {
            connected = false
            runCatching { s.close() }
        }
    }

    /** Same link over Bluetooth (RFCOMM, fixed channel like the computer); only for an already paired computer. */
    @android.annotation.SuppressLint("MissingPermission")
    fun runBluetooth(device: android.bluetooth.BluetoothDevice, secret: ByteArray): String {
        // fixed channel -> hidden api (no sdp record on the computer); insecure = no second os pairing prompt
        val s = runCatching {
            device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType).invoke(device, Protocol.RFCOMM_CHANNEL)
                as android.bluetooth.BluetoothSocket
        }.getOrElse { return "no bluetooth" }
        closer = { s.close() }
        try {
            s.connect()
            return talk(s.inputStream, s.outputStream, secret, false, null, false)
        } catch (e: Exception) {
            return e.message ?: "failed"
        } finally {
            connected = false
            runCatching { s.close() }
        }
    }

    @Volatile var viaBluetooth = false
    /** got past the handshake at least once (Bluetooth: this was the right computer) */
    @Volatile var wasConnected = false
    @Volatile private var closer: (() -> Unit)? = null

    private fun talk(rawIn: java.io.InputStream, rawOut: java.io.OutputStream, secret: ByteArray, pairing: Boolean,
                     host: String?, relay: Boolean): String {
            val input = DataInputStream(BufferedInputStream(rawIn))
            val out = DataOutputStream(BufferedOutputStream(rawOut))
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
                computerId = paired.getString("id")
                store.put(Store.Pairing(computerId!!, reply.optString("name", "?"), host,
                    Base64.decode(paired.getString("key"), Base64.NO_WRAP)))
            }
            if (host != null && !relay) computerId?.let { store.setHost(it, host) }
            connected = true
            wasConnected = true
            viaRelay = relay
            onConnected(reply.optString("name"))
            queue.clear()
            val writer = Thread {
                try {
                    while (connected) {
                        val m = queue.poll(30, TimeUnit.SECONDS) ?: JSONObject().put("t", "ping")
                        Protocol.writeFrame(out, crypto.seal(m.toString().toByteArray()))
                    }
                } catch (_: Exception) { runCatching { closer?.invoke() } }
            }.apply { isDaemon = true; start() }
            while (true) {
                val sealed = Protocol.readFrame(input) ?: break
                val plain = crypto.open(sealed) ?: break
                runCatching { onMessage(JSONObject(String(plain))) }
            }
            connected = false
            writer.interrupt()
            return "closed"
    }
}
