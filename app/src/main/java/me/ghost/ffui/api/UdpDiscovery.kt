package me.ghost.ffui.api

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
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

    suspend fun discover(context: Context): List<DiscoveredPrinter> = withContext(Dispatchers.IO) {
        val printers = mutableListOf<DiscoveredPrinter>()
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("FlasherMulticastLock")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()

        var socket: MulticastSocket? = null

        try {
            socket = MulticastSocket(0)
            socket.broadcast = true
            socket.reuseAddress = true
            socket.soTimeout = 1500 // 1.5 seconds per read

            val group = InetAddress.getByName("225.0.0.9")
            try {
                socket.joinGroup(group)
            } catch (e: Exception) {
                e.printStackTrace()
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

            // We do 3 retries
            for (retry in 0..2) {
                targets.forEach { (ip, port) ->
                    try {
                        val addr = InetAddress.getByName(ip)
                        val packet = DatagramPacket(discoverMsg, discoverMsg.size, addr, port)
                        socket.send(packet)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // Listen for up to 3 seconds for this retry round
                val endTime = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < endTime) {
                    try {
                        socket.receive(receivePacket)
                        val len = receivePacket.length
                        val ip = receivePacket.address.hostAddress ?: continue
                        
                        if (len >= 140) {
                            val nameStr = String(receiveBuf, 0, 128, Charsets.UTF_8).trimEnd('\u0000', ' ', '\n', '\r')
                            
                            val isModern = len >= 276
                            
                            val cmdPort = (receiveBuf[0x84].toInt() and 0xFF shl 8) or (receiveBuf[0x85].toInt() and 0xFF)
                            val httpPort = if (isModern) (receiveBuf[0x8E].toInt() and 0xFF shl 8) or (receiveBuf[0x8F].toInt() and 0xFF) else 8898
                            
                            var serial = ""
                            if (isModern) {
                                // Extract the serial up to the first null character since it is null-terminated
                                val rawSerialBytes = receiveBuf.copyOfRange(146, 146 + 128)
                                val nullIndex = rawSerialBytes.indexOf(0.toByte())
                                val lenToRead = if (nullIndex >= 0) nullIndex else 128
                                serial = String(rawSerialBytes, 0, lenToRead, Charsets.UTF_8).trim()
                            }
                            
                            // Avoid duplicates
                            val existing = printers.find { it.ipAddress == ip && it.commandPort == cmdPort }
                            if (existing == null) {
                                printers.add(DiscoveredPrinter(ip, nameStr, serial, isModern, cmdPort, httpPort))
                            } else if (isModern && !existing.isModern) {
                                printers.remove(existing)
                                printers.add(DiscoveredPrinter(ip, nameStr, serial, isModern, cmdPort, httpPort))
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        break
                    } catch (e: Exception) {
                        e.printStackTrace()
                        break
                    }
                }
                
                if (printers.isNotEmpty()) {
                    break // Early exit
                }
                
                if (retry < 2) delay(1000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                socket?.leaveGroup(InetAddress.getByName("225.0.0.9"))
            } catch (e: Exception) {}
            socket?.close()
            if (multicastLock?.isHeld == true) {
                multicastLock.release()
            }
        }
        
        printers
    }
}

