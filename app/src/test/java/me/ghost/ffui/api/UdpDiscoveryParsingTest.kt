package me.ghost.ffui.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Parsing tests for [UdpDiscovery.parseDatagram] against real byte fixtures in
 * `src/test/resources/discovery/` — a 276 B fw-3.x modern packet, a 280 B fw-5.x modern packet
 * (4 trailing bytes after the serial), and a 140 B legacy packet (no serial field). Layout per
 * docs-wiki Discovery-Protocol.md: name at 0x00 (128 B NUL-padded), command port at 0x84,
 * status at 0x8A, HTTP port at 0x8E (modern only), serial at 0x92 (128 B NUL-padded).
 */
class UdpDiscoveryParsingTest {

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader).getResourceAsStream("discovery/$name")!!
            .use { it.readBytes() }

    @Test
    fun `parses a 276-byte fw3 modern packet`() {
        val parsed = UdpDiscovery.parseDatagram(fixture("modern_276.bin"), 276, "192.168.1.50")
        assertNotNull(parsed)
        parsed!!
        assertTrue(parsed.isModern)
        assertEquals("192.168.1.50", parsed.ipAddress)
        assertEquals("FlashForge AD5X", parsed.name)
        assertEquals("SNFFAD5X0485", parsed.serialNumber)
        assertEquals(8899, parsed.commandPort)
        assertEquals(8898, parsed.httpPort)
    }

    @Test
    fun `parses a 280-byte fw5 modern packet with trailing bytes`() {
        // fw 5.x appends 4 bytes after the serial — "≥276 ⇒ modern" must accept both sizes.
        val parsed = UdpDiscovery.parseDatagram(fixture("modern_280.bin"), 280, "192.168.1.51")
        assertNotNull(parsed)
        parsed!!
        assertTrue(parsed.isModern)
        assertEquals("Adventurer 5M Pro", parsed.name)
        assertEquals("SNFF5MPRO12345", parsed.serialNumber)
        assertEquals(8899, parsed.commandPort)
        assertEquals(8898, parsed.httpPort)
    }

    @Test
    fun `legacy 140-byte packet surfaces no serial, not garbage`() {
        // Legacy A3/A4 packets carry no serial field; the SN must come back empty (the discovery
        // screen hints manual serial entry) — never stale buffer bytes read as a serial.
        val parsed = UdpDiscovery.parseDatagram(fixture("legacy_140.bin"), 140, "192.168.1.52")
        assertNotNull(parsed)
        parsed!!
        assertFalse(parsed.isModern)
        assertEquals("Adventurer 4", parsed.name)
        assertEquals("", parsed.serialNumber)
        assertEquals(8899, parsed.commandPort)
        // No HTTP-port field on legacy packets — the 8898 default stands.
        assertEquals(8898, parsed.httpPort)
    }

    @Test
    fun `packets shorter than 140 bytes are skipped`() {
        val short = ByteArray(139)
        assertNull(UdpDiscovery.parseDatagram(short, short.size, "192.168.1.53"))
    }

    @Test
    fun `packets with no resolvable ip are skipped`() {
        assertNull(UdpDiscovery.parseDatagram(fixture("modern_276.bin"), 276, null))
    }

    @Test
    fun `serial is read only up to the first NUL terminator`() {
        // Pad the serial with junk after a NUL — the parser must stop at the NUL, not read 128 raw
        // bytes (which would pick up the next field's trailing zeros as whitespace, or worse).
        val buf = fixture("modern_276.bin")
        buf[146 + 6] = 0 // "SNFFAD" + NUL + junk
        buf[147 + 6] = 'X'.code.toByte()
        val parsed = UdpDiscovery.parseDatagram(buf, buf.size, "192.168.1.54")
        assertEquals("SNFFAD", parsed?.serialNumber)
    }

    // ── Regression: the wave-1 per-receive setLength fix ──────────────────────────

    @Test
    fun `legacy then modern through one reused DatagramPacket parses untruncated`() {
        // The app's receive loop reuses one DatagramPacket over a 512 B buffer for the whole
        // discovery round. Android's Harmony-derived DatagramSocket caps each receive at the
        // packet's *current* length, so a 140 B legacy response first would cap every later
        // modern one at 140 B unless length is reset before EVERY receive (the wave-1 fix).
        // Exercised over real loopback sockets exactly as the loop does.
        val legacy = fixture("legacy_140.bin")
        val modern = fixture("modern_276.bin")
        val loopback = InetAddress.getLoopbackAddress()

        DatagramSocket().use { sender ->
            DatagramSocket(0).use { receiver ->
                receiver.soTimeout = 5_000
                val buf = ByteArray(512)
                val packet = DatagramPacket(buf, buf.size)

                sender.send(DatagramPacket(legacy, legacy.size, loopback, receiver.localPort))
                packet.length = buf.size // the fix: reset before EVERY receive
                receiver.receive(packet)
                val first = UdpDiscovery.parseDatagram(buf, packet.length, packet.address.hostAddress)
                assertNotNull(first)
                assertFalse(first!!.isModern)
                assertEquals("", first.serialNumber)

                sender.send(DatagramPacket(modern, modern.size, loopback, receiver.localPort))
                packet.length = buf.size // reset again
                receiver.receive(packet)
                val second = UdpDiscovery.parseDatagram(buf, packet.length, packet.address.hostAddress)
                assertNotNull(second)
                assertTrue(second!!.isModern)
                assertEquals("SNFFAD5X0485", second.serialNumber)
            }
        }
    }

    @Test
    fun `a truncated modern receive is misread as legacy without the reset`() {
        // Documents the failure mode the reset guards against. Host JDKs grow the packet length
        // back on receive, so the Android/Harmony semantics (receive caps writes at the previous
        // packet.getLength()) are simulated: only the first 140 bytes of the modern packet land
        // in the shared buffer, the rest stays zeroed from the legacy read, length stays 140.
        val modern = fixture("modern_276.bin")
        val buf = ByteArray(512)
        System.arraycopy(modern, 0, buf, 0, 140) // Harmony-capped write
        val parsed = UdpDiscovery.parseDatagram(buf, 140, "192.168.1.55")
        assertNotNull(parsed)
        assertFalse(parsed!!.isModern)
        assertEquals("", parsed.serialNumber) // serial field (offset 146+) never arrived
    }
}
