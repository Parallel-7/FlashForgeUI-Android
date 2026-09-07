package me.ghost.ffui.data

import me.ghost.ffui.api.DiscoveredPrinter

/**
 * Pure decision for discovery-first connects: given the printers seen in one discovery window,
 * resolve a saved printer's current address by exact serial-number match.
 *
 * - Exact match wins; any other serial in the scan is ignored.
 * - No match returns `null` — the caller falls back to the saved address.
 * - A serial answering more than once (multiple packets for the same printer) resolves
 *   deterministically to the last packet seen.
 */
object ConnectionResolver {

    /**
     * @param serialNumber the saved printer's serial; blank serials never resolve (legacy
     *   printers broadcast without one, so they can only connect via their saved address).
     * @param discovered printers parsed from one UDP discovery window, in arrival order.
     * @return the discovered address, or `null` when the window didn't see the serial.
     */
    fun resolve(serialNumber: String, discovered: List<DiscoveredPrinter>): String? =
        discovered.lastOrNull { serialNumber.isNotBlank() && it.serialNumber == serialNumber }?.ipAddress
}
