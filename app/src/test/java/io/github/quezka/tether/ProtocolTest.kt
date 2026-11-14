package io.github.quezka.tether

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {
    @Test fun theSessionKeyMatchesTheComputers() {
        // python: sha256(b"tether1 session" + len(b"code").to_bytes(4, "big") + b"code" + b"p" + b"d")
        val hex = Protocol.sessionKey("code".toByteArray(), "p".toByteArray(), "d".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("000533bc2ab04a08bcbeee541892126e748d004b153a9b076deb73465c8aacc9", hex)
    }

    @Test fun sealedFramesOpenOnlyWithTheKey() {
        val k = Protocol.sessionKey("x".toByteArray(), ByteArray(16), ByteArray(16))
        val sealed = Protocol.Crypto(k).seal("hi".toByteArray())
        assertArrayEquals("hi".toByteArray(), Protocol.Crypto(k).open(sealed))
        assertNull(Protocol.Crypto(Protocol.sessionKey("y".toByteArray(), ByteArray(16), ByteArray(16))).open(sealed))
    }

    @Test fun beaconsCodesAndNames() {
        assertEquals(Protocol.Computer("qpc", "10.0.0.2", 47821, "ab"), Protocol.parseBeacon("TETHER1|qpc|47821|ab", "10.0.0.2"))
        assertNull(Protocol.parseBeacon("TETHER1|qpc|x|ab", "h"))
        assertEquals("abcd23", Protocol.normalCode(" AB-cd 23 "))
        assertEquals("passwd", Protocol.safeName("../../etc/passwd"))
    }
}
