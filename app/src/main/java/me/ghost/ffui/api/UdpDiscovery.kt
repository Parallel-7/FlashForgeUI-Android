package me.ghost.ffui.api

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

data class DiscoveredPrinter(
    val ipAddress: String,
    val name: String,
    val serialNumber: String,
    val isModern: Boolean,
    val commandPort: Int,
    val httpPort: Int
)

object UdpDiscovery {

    private const val TAG = "UdpDiscovery"

    /**
     * Parses one received discovery datagram out of [buf] (the shared 512-byte receive buffer;
     * only the first [len] bytes are valid). Returns `null` when [ip] is missing or the packet is
     * too short (< 140 B) to be a discovery response.
     *
     * Wire layout (docs-wiki Discovery-Protocol.md): machine name at 0x00 (128 B, NUL-terminated),
     * command port at 0x84 (uint16 BE), status at 0x8A, HTTP port at 0x8E (modern only), serial at
     * 0x92 (128 B, NUL-terminated). Packets ≥ 276 B are modern (fw 3.x sends 276, fw 5.x 280 —
     * accept "276 or more"); legacy 140 B packets carry no serial.
     */
    internal fun parseDatagram(buf: ByteArray, len: Int, ip: String?): DiscoveredPrinter? {
        if (ip == null) return null
        if (len < 140) return null
        val nameStr = String(buf, 0, 128, Charsets.UTF_8).trimEnd('\u0000', ' ', '\n', '\r')

        val isModern = len >= 276

        val cmdPort = (buf[0x84].toInt() and 0xFF shl 8) or (buf[0x85].toInt() and 0xFF)
        val httpPort = if (isModern) (buf[0x8E].toInt() and 0xFF shl 8) or (buf[0x8F].toInt() and 0xFF) else 8898

        var serial = ""
        if (isModern) {
            // Extract the serial up to the first null character since it is null-terminated
            val rawSerialBytes = buf.copyOfRange(146, 146 + 128)
            val nullIndex = rawSerialBytes.indexOf(0.toByte())
            val lenToRead = if (nullIndex >= 0) nullIndex else 128
            serial = String(rawSerialBytes, 0, lenToRead, Charsets.UTF_8).trim()
        }

        return DiscoveredPrinter(ip, nameStr, serial, isModern, cmdPort, httpPort)
    }

    /**
     * Full network scan: up to 3 probe rounds of ~3 s each (1 s pause between), returning early
     * once anything answers. Used for the "refresh" action on the Printers tab.
     */
    suspend fun discover(context: Context): List<DiscoveredPrinter> = withContext(Dispatchers.IO) {
        scan(context, retries = 2, listenMsPerRound = 3000, targetSerial = null)
    }

    /**
     * One short (~1.5 s) probe round for connect-time address resolution: sends the same probe
     * burst once, listens for [listenMs], and returns early the moment [targetSerial] answers (a
     * printer that is actually on the network typically replies in well under a second). Returns
     * an empty list when the target wasn't seen — the caller then falls back to the saved address.
     * Same probes and parser as [discover]; only the retry/window policy differs.
     */
    suspend fun discoverQuick(
        context: Context,
        targetSerial: String,
        listenMs: Long = 1500
    ): List<DiscoveredPrinter> = withContext(Dispatchers.IO) {
        scan(context, retries = 0, listenMsPerRound = listenMs, targetSerial = targetSerial)
    }

    private suspend fun scan(
        context: Context,
        retries: Int,
        listenMsPerRound: Long,
        targetSerial: String?
    ): List<DiscoveredPrinter> {
        val printers = mutableListOf<DiscoveredPrinter>()
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("FlasherMulticastLock")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()

        var socket: MulticastSocket? = null

        try {
            // Bind explicitly so SO_REUSEADDR is set BEFORE the bind (it's a no-op afterwards —
            // MulticastSocket(0) binds in the constructor). Ephemeral port via InetSocketAddress(0).
            socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
                broadcast = true
                soTimeout = 1500 // 1.5 seconds per read
            }
            val group = InetAddress.getByName("225.0.0.9")
            try {
                socket.joinGroup(group)
            } catch (e: Exception) {
                // Not fatal — the broadcast probes below still work without the multicast group.
                Log.w(TAG, "joinGroup failed; falling back to broadcast only", e)
            }

            val discoverMsg = ByteArray(0)
            val targets = listOf(
                Pair("225.0.0.9", 19000),
                Pair("225.0.0.9", 8899),
                Pair("255.255.255.255", 48899),
                Pair("255.255.255.255", 19000),
                Pair("255.255.255.255", 8899)
            )

            val receiveBuf = ByteArray(512)
            val receivePacket = DatagramPacket(receiveBuf, receiveBuf.size)

            // We do 3 retries for the full scan; the quick connect-time scan does one round
            for (retry in 0..retries) {
                targets.forEach { (ip, port) ->
                    try {
                        val addr = InetAddress.getByName(ip)
                        val packet = DatagramPacket(discoverMsg, discoverMsg.size, addr, port)
                        socket.send(packet)
                    } catch (e: Exception) {
                        Log.w(TAG, "probe to $ip:$port failed", e)
                    }
                }

                // Listen for up to listenMsPerRound for this retry round
                val endTime = System.currentTimeMillis() + listenMsPerRound
                var targetSeen = false
                while (!targetSeen && System.currentTimeMillis() < endTime) {
                    try {
                        // Reset the packet length before EVERY receive: receive() caps writes at
                        // packet.getLength() (the PREVIOUS packet's size) and never grows it back,
                        // so without this a short (legacy ~140 B) response first would truncate
                        // every later modern (~276/280 B) one — misread as legacy with stale bytes.
                        receivePacket.length = receiveBuf.size
                        socket.receive(receivePacket)
                        val len = receivePacket.length
                        val ip = receivePacket.address.hostAddress

                        val parsed = parseDatagram(receiveBuf, len, ip) ?: continue

                        // Avoid duplicates
                        val existing = printers.find { it.ipAddress == parsed.ipAddress && it.commandPort == parsed.commandPort }
                        if (existing == null) {
                            printers.add(parsed)
                        } else if (parsed.isModern && !existing.isModern) {
                            printers.remove(existing)
                            printers.add(parsed)
                        }
                        // Quick-scan early exit: stop the moment the printer being resolved answers.
                        if (targetSerial != null && parsed.serialNumber == targetSerial) {
                            targetSeen = true
                        }
                    } catch (e: SocketTimeoutException) {
                        break
                    } catch (e: Exception) {
                        // One bad packet (e.g. a transient ICMP port-unreachable) doesn't kill the
                        // socket — skip it and keep listening; the window's time bound still exits.
                        Log.w(TAG, "dropping malformed discovery packet", e)
                        continue
                    }
                }
                
                if (printers.isNotEmpty() || targetSeen) {
                    break // Early exit
                }

                if (retry < retries) delay(1000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "discovery round failed", e)
        } finally {
            try {
                socket?.leaveGroup(InetAddress.getByName("225.0.0.9"))
            } catch (_: Exception) {
                // Never joined (or already closed) — nothing to leave.
            }
            socket?.close()
            if (multicastLock?.isHeld == true) {
                multicastLock.release()
            }
        }

        return printers
    }
}

