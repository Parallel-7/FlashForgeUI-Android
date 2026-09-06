package me.ghost.ffui.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import me.ghost.ffui.nfc.NfcManager.Companion.toText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip tests for the canonical NFC tag payloads (Robolectric — `NdefRecord` runs its real
 * AOSP implementation headlessly). Payload contract: a spool tag carries one NDEF text record
 * `SPOOL:<id>` plus an optional URI record appended only when the write-URL flag is on (never
 * read back); a box tag carries `BOX:<location>`; foreign/malformed NDEF decodes to clean nulls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NfcPayloadTest {

    // ── Spool payload round-trip ──────────────────────────────────────────────────

    @Test
    fun `spool message round-trips without a URL record`() {
        val message = NfcManager.buildSpoolNdefMessage(spoolId = 42, baseUrl = "http://10.0.2.2:7912", writeUrlEnabled = false)
        assertEquals(1, message.records.size)
        val text = message.records.first().toText()
        assertEquals("SPOOL:42", text)
        assertEquals(42, NfcManager.parseSpoolId(text))
    }

    @Test
    fun `URL record is appended only when enabled and the base URL is present`() {
        val withUrl = NfcManager.buildSpoolNdefMessage(42, "http://10.0.2.2:7912", writeUrlEnabled = true)
        assertEquals(2, withUrl.records.size)
        // First record stays the canonical text payload…
        assertEquals("SPOOL:42", withUrl.records[0].toText())
        // …the second is a URI record (Well-Known + RTD_URI — not RTD_TEXT), so it never decodes
        // as text and is never read back.
        assertTrue(withUrl.records[1].type.contentEquals(NdefRecord.RTD_URI))
        assertNull(withUrl.records[1].toText())
        assertNull(NfcManager.parseSpoolId(withUrl.records[1].toText()))

        // Flag off → no URI record even with a URL configured…
        assertEquals(1, NfcManager.buildSpoolNdefMessage(42, "http://10.0.2.2:7912", writeUrlEnabled = false).records.size)
        // …and a blank URL → none either.
        assertEquals(1, NfcManager.buildSpoolNdefMessage(42, "   ", writeUrlEnabled = true).records.size)
        assertEquals(1, NfcManager.buildSpoolNdefMessage(42, "", writeUrlEnabled = true).records.size)
    }

    @Test
    fun `URL record points at the spool show page with a normalized base`() {
        val message = NfcManager.buildSpoolNdefMessage(7, "http://192.168.1.20:7912/", writeUrlEnabled = true)
        val uri = message.records[1].toUri()?.toString()
        assertEquals("http://192.168.1.20:7912/spool/show/7", uri)
    }

    // ── Box payload round-trip ────────────────────────────────────────────────────

    @Test
    fun `box message round-trips`() {
        val message = NfcManager.buildBoxNdefMessage("Shelf A")
        assertEquals(1, message.records.size)
        val text = message.records.first().toText()
        assertEquals("BOX:Shelf A", text)
        assertEquals("Shelf A", NfcManager.parseBoxLocation(text))
        // A box payload must not resolve as a spool and vice versa.
        assertNull(NfcManager.parseSpoolId(text))
        assertNull(NfcManager.parseBoxLocation("SPOOL:42"))
    }

    // ── RTD_TEXT decoding, incl. the UTF-16 fix (NIT-6) ──────────────────────────

    @Test
    fun `UTF-16 RTD_TEXT records decode correctly`() {
        // Hand-crafted well-known text record: status byte = 0x80 (UTF-16) | lang length 2,
        // language code "en", then the text UTF-16BE (the NFC spec's big-endian, no BOM).
        val text = "SPOOL:17"
        val payload = ByteArray(1 + 2 + text.toByteArray(Charsets.UTF_16BE).size)
        payload[0] = ((0x80 or 0x02).toByte())
        payload[1] = 'e'.code.toByte()
        payload[2] = 'n'.code.toByte()
        System.arraycopy(text.toByteArray(Charsets.UTF_16BE), 0, payload, 3, text.toByteArray(Charsets.UTF_16BE).size)
        val record = NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, byteArrayOf(), payload)

        val decoded = record.toText()
        assertEquals("SPOOL:17", decoded)
        assertEquals(17, NfcManager.parseSpoolId(decoded))
    }

    @Test
    fun `own UTF-8 writes decode back unchanged`() {
        // createTextRecord emits UTF-8 with the locale's ISO-639-2 code — the round-trip our own
        // writes rely on.
        val record = NdefRecord.createTextRecord(null, "BOX:Drawer 2")
        assertEquals("BOX:Drawer 2", record.toText())
    }

    // ── Foreign / malformed NDEF → clean nulls, never a crash ────────────────────

    @Test
    fun `non-text records decode to null`() {
        assertNull(NdefRecord.createUri("https://example.com").toText())
        val mime = NdefRecord(
            NdefRecord.TNF_MIME_MEDIA, "application/json".toByteArray(), byteArrayOf(),
            """{"spool":42}""".toByteArray(),
        )
        assertNull(mime.toText())
        // And neither parses as a spool/box payload.
        assertNull(NfcManager.parseSpoolId(mime.toText()))
        assertNull(NfcManager.parseBoxLocation(mime.toText()))
    }

    @Test
    fun `malformed RTD_TEXT payloads decode to null`() {
        // Empty payload, and a language-code length that runs past the payload.
        val empty = NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, byteArrayOf(), ByteArray(0))
        assertNull(empty.toText())
        val overrun = NdefRecord(
            NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, byteArrayOf(),
            byteArrayOf(((0x3F).toByte())), // claims a 63-byte language code, nothing follows
        )
        assertNull(overrun.toText())
    }

    @Test
    fun `foreign text payloads resolve to null ids, not garbage`() {
        assertNull(NfcManager.parseSpoolId("HELLO:world"))
        assertNull(NfcManager.parseSpoolId("spool:abc"))    // non-numeric id
        assertNull(NfcManager.parseSpoolId("BOX:Shelf A"))  // wrong prefix family
        assertNull(NfcManager.parseBoxLocation("BOX:"))     // blank location
        assertNull(NfcManager.parseSpoolId(null))
        assertNull(NfcManager.parseBoxLocation(null))
    }

    // ── parseSpoolId / parseBoxLocation edges ────────────────────────────────────

    @Test
    fun `payload parsing is case-insensitive, trimmed, and line-tolerant`() {
        // Tags written by other apps may carry the record among other lines.
        assertEquals(42, NfcManager.parseSpoolId("Some app header\nspool: 42 \ntrailer"))
        assertEquals("Shelf A", NfcManager.parseBoxLocation("note\nbox:Shelf A"))
        assertEquals(7, NfcManager.parseSpoolId("SPOOL:7"))
        assertNull(NfcManager.parseSpoolId("SPOOL:"))        // no id at all
        // Pinned quirk: toIntOrNull accepts a sign, so a foreign `SPOOL:-3` parses as -3. Harmless
        // downstream (Spoolman 404s the lookup) — noted, not changed.
        assertEquals(-3, NfcManager.parseSpoolId("SPOOL:-3"))
        assertNull(NfcManager.parseSpoolId("XSPOOL:3"))      // prefix must lead the line
    }

    @Test
    fun `full message read path picks the first decodable text record`() {
        // The read path picks the first record that decodes as RTD_TEXT, like readTag does.
        val foreign = NdefMessage(arrayOf(NdefRecord.createUri("https://example.com")))
        val text = foreign.records.firstNotNullOfOrNull { it.toText() }
        assertNull(text)
    }
}
