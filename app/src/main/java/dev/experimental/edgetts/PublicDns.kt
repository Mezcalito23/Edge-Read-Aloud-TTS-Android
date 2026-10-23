package dev.experimental.edgetts

import okhttp3.Dns
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * El DNS de algunos e-readers responde en pocos milisegundos y sin
 * direcciones para speech.platform.bing.com. Si el del sistema falla,
 * se pregunta por UDP a resolutores públicos. El token no interviene:
 * sin una IP no hay handshake.
 */
internal class PublicDns : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        try {
            val system = Dns.SYSTEM.lookup(hostname)
            if (system.isNotEmpty()) return system
        } catch (_: UnknownHostException) {
        }
        for (server in SERVERS) {
            val found = resolve(hostname, server)
            if (found.isNotEmpty()) return found
        }
        throw UnknownHostException(hostname)
    }

    private fun resolve(hostname: String, server: String): List<InetAddress> {
        val out = ArrayList<InetAddress>(2)
        for (type in intArrayOf(TYPE_A, TYPE_AAAA)) {
            val packet = query(hostname, server, type) ?: continue
            for (raw in answers(packet, type)) {
                try {
                    out.add(InetAddress.getByAddress(hostname, raw))
                } catch (_: UnknownHostException) {
                }
            }
            if (out.isNotEmpty()) return out
        }
        return out
    }

    private fun query(hostname: String, server: String, type: Int): ByteArray? {
        val payload = encodeQuery(hostname, type)
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                val dest = InetAddress.getByName(server)
                socket.send(DatagramPacket(payload, payload.size, dest, 53))
                val buf = ByteArray(512)
                val reply = DatagramPacket(buf, buf.size)
                socket.receive(reply)
                buf.copyOf(reply.length)
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val SERVERS = arrayOf("1.1.1.1", "8.8.8.8")
        private const val TIMEOUT_MS = 1500
        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28

        fun encodeQuery(hostname: String, type: Int): ByteArray {
            val labels = ArrayList<Byte>(64)
            for (part in hostname.split('.')) {
                if (part.isEmpty()) continue
                val raw = part.toByteArray(Charsets.US_ASCII)
                labels.add(raw.size.toByte())
                for (b in raw) labels.add(b)
            }
            labels.add(0)
            val out = ByteArray(12 + labels.size + 4)
            out[0] = 0x1e
            out[1] = 0xd6.toByte()
            out[2] = 0x01
            out[5] = 1
            var i = 12
            for (b in labels) out[i++] = b
            out[i++] = (type ushr 8).toByte()
            out[i++] = type.toByte()
            out[i] = 1
            return out
        }

        fun answers(packet: ByteArray, type: Int): List<ByteArray> {
            if (packet.size < 12) return emptyList()
            val count = u16(packet, 6)
            var i = skipName(packet, 12)
            if (i < 0 || i + 4 > packet.size) return emptyList()
            i += 4
            val found = ArrayList<ByteArray>(count)
            for (n in 0 until count) {
                i = skipName(packet, i)
                if (i < 0 || i + 10 > packet.size) break
                val kind = u16(packet, i)
                val len = u16(packet, i + 8)
                val data = i + 10
                if (data + len > packet.size) break
                if (kind == type &&
                    ((type == TYPE_A && len == 4) || (type == TYPE_AAAA && len == 16))
                ) {
                    found.add(packet.copyOfRange(data, data + len))
                }
                i = data + len
            }
            return found
        }

        private fun u16(packet: ByteArray, at: Int): Int =
            ((packet[at].toInt() and 0xFF) shl 8) or (packet[at + 1].toInt() and 0xFF)

        private fun skipName(packet: ByteArray, start: Int): Int {
            var i = start
            var hops = 0
            while (i < packet.size && hops < 16) {
                val len = packet[i].toInt() and 0xFF
                if (len == 0) return i + 1
                if (len and 0xC0 == 0xC0) return if (i + 1 < packet.size) i + 2 else -1
                i += 1 + len
                hops++
            }
            return -1
        }
    }
}
