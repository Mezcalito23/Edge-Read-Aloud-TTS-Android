package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicDnsTest {

    @Test
    fun queryEncodesTheHostAndTypeA() {
        val q = PublicDns.encodeQuery("speech.platform.bing.com", 1)
        assertEquals(0x01, q[2].toInt() and 0xFF)
        assertEquals(1, q[5].toInt() and 0xFF)
        val name = q.copyOfRange(12, q.size - 4).toString(Charsets.US_ASCII)
        assertTrue(name.contains("speech"))
        assertEquals(0, q[q.size - 3].toInt())
        assertEquals(1, q[q.size - 2].toInt() and 0xFF)
        assertEquals(1, q[q.size - 1].toInt() and 0xFF)
    }

    @Test
    fun answersReadsAnAddressAfterACompressedName() {
        val packet = byteArrayOf(
            0, 1, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0,
            3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0,
            0, 1, 0, 1,
            0xC0.toByte(), 12,
            0, 1, 0, 1,
            0, 0, 0, 30,
            0, 4,
            150.toByte(), 171.toByte(), 29, 10
        )
        val ips = PublicDns.answers(packet, 1)
        assertEquals(1, ips.size)
        assertEquals(150, ips[0][0].toInt() and 0xFF)
        assertEquals(10, ips[0][3].toInt() and 0xFF)
    }
}
