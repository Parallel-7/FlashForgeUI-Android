package me.ghost.ffui.ui.discovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the light IPv4 validation behind the address re-entry dialog's Save button: enough to
 * block obvious typos (this is a LAN address typed by the printer's owner, not user input that
 * needs a full IP parser).
 */
class IpAddressValidationTest {

    @Test
    fun `valid addresses pass`() {
        assertTrue(isValidIpv4("192.168.1.50"))
        assertTrue(isValidIpv4("10.0.0.2"))
        assertTrue(isValidIpv4("255.255.255.255"))
        assertTrue(isValidIpv4("0.0.0.0"))
        assertTrue(isValidIpv4(" 192.168.1.50 ")) // surrounding whitespace is trimmed
    }

    @Test
    fun `octet range and shape violations fail`() {
        assertFalse(isValidIpv4("256.1.1.1"))      // octet > 255
        assertFalse(isValidIpv4("1.2.3"))          // too few octets
        assertFalse(isValidIpv4("1.2.3.4.5"))      // too many octets
        assertFalse(isValidIpv4("1.2.3.4."))       // trailing dot = empty octet
        assertFalse(isValidIpv4("192.168.abc.4"))  // non-numeric
        assertFalse(isValidIpv4("192.168..4"))     // empty octet
        assertFalse(isValidIpv4("01.2.3.4"))       // leading zero
        assertFalse(isValidIpv4("192.168.1.4a"))   // trailing garbage
        assertFalse(isValidIpv4(""))
        assertFalse(isValidIpv4("myprinter.local"))
    }
}
