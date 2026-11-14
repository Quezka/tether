package io.github.quezka.tether

import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The protocol (docs/PROTOCOL.md), byte for byte the same as the computer's (suite-rs/crates/tether/src/domain). */
object Protocol {
    const val BEACON_PORT = 47820
    const val MAX_FRAME = 4 * 1024 * 1024
    const val FILE_PIECE = 512 * 1024
    private const val MAGIC = "TETHER1"
    private val random = SecureRandom()

    data class Computer(val name: String, val host: String, val port: Int, val id: String)

    fun parseBeacon(text: String, host: String): Computer? {
        val p = text.trim().split('|')
        if (p.size != 4 || p[0] != MAGIC || p[1].isEmpty()) return null
        return Computer(p[1], host, p[2].toIntOrNull() ?: return null, p[3])
    }

    fun sessionKey(secret: ByteArray, phoneNonce: ByteArray, desktopNonce: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        d.update("tether1 session".toByteArray())
        d.update(byteArrayOf((secret.size ushr 24).toByte(), (secret.size ushr 16).toByte(), (secret.size ushr 8).toByte(), secret.size.toByte()))
        d.update(secret)
        d.update(phoneNonce)
        d.update(desktopNonce)
        return d.digest()
    }

    fun normalCode(typed: String) = typed.filter { it.isLetterOrDigit() && it.code < 128 }.lowercase()

    fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    fun writeFrame(out: DataOutputStream, payload: ByteArray) = synchronized(out) {
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    fun readFrame(input: DataInputStream): ByteArray? {
        val n = runCatching { input.readInt() }.getOrNull() ?: return null
        if (n <= 0 || n > MAX_FRAME) return null
        return ByteArray(n).also { input.readFully(it) }
    }

    /** AES-256-GCM: 12-byte random nonce, then the ciphertext with its 16-byte tag (as Rust's aes-gcm). */
    class Crypto(key: ByteArray) {
        private val spec = SecretKeySpec(key, "AES")
        fun seal(plain: ByteArray): ByteArray {
            val iv = randomBytes(12)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, iv)) }
            return iv + c.doFinal(plain)
        }
        fun open(sealed: ByteArray): ByteArray? {
            if (sealed.size < 28) return null
            return runCatching {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                    .apply { init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, sealed.copyOfRange(0, 12))) }
                c.doFinal(sealed, 12, sealed.size - 12)
            }.getOrNull()
        }
    }

    fun safeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').filter { !it.isISOControl() && it != ':' }
            .trimStart('.').trim()
        return if (base.isEmpty()) "file" else base.take(120)
    }
}
