package me.ghost.ffui.data

import me.ghost.ffui.api.DiscoveredPrinter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure decision tests for discovery-first connect resolution: exact serial match wins, misses
 * fall back (null), and duplicate answers resolve deterministically to the last one seen.
 */
class ConnectionResolverTest {

    private fun printer(serial: String, ip: String) = DiscoveredPrinter(
        ipAddress = ip, name = "Adventurer 5M", serialNumber = serial, isModern = true,
        commandPort = 8899, httpPort = 8898
    )

    @Test
    fun `exact serial match resolves the discovered address`() {
        val found = listOf(
            printer("SN-OTHER", "192.168.1.40"),
            printer("SN-TARGET", "192.168.1.55")
        )
        assertEquals("192.168.1.55", ConnectionResolver.resolve("SN-TARGET", found))
    }

    @Test
    fun `no match returns null so the caller falls back to the saved address`() {
        val found = listOf(printer("SN-OTHER", "192.168.1.40"))
        assertNull(ConnectionResolver.resolve("SN-TARGET", found))
    }

    @Test
    fun `empty scan returns null`() {
        assertNull(ConnectionResolver.resolve("SN-TARGET", emptyList()))
    }

    @Test
    fun `multiple packets for the same serial resolve to the last one seen`() {
        val found = listOf(
            printer("SN-TARGET", "192.168.1.55"),
            printer("SN-OTHER", "192.168.1.60"),
            printer("SN-TARGET", "192.168.1.61")
        )
        assertEquals("192.168.1.61", ConnectionResolver.resolve("SN-TARGET", found))
    }

    @Test
    fun `blank serials never resolve - legacy printers broadcast without one`() {
        val found = listOf(printer("", "192.168.1.55"))
        assertNull(ConnectionResolver.resolve("", found))
    }
}
