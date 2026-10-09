package com.karinlab.mymonitor

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** UDP broadcast discovery restricted to directly attached IPv4 interfaces. */
object ReceiverDiscovery {
    data class Receiver(val host: String, val port: Int)
    private val request = "MYMONITOR_DISCOVER_V1".toByteArray(Charsets.US_ASCII)
    private val response = "MYCAST_RECEIVER_V1|57007".toByteArray(Charsets.US_ASCII)

    fun scan(): List<Receiver> {
        val found = linkedMapOf<String, Receiver>()
        val targets = linkedSetOf<InetAddress>()
        val interfaces = NetworkInterface.getNetworkInterfaces()
        if (interfaces != null) {
            for (network in java.util.Collections.list(interfaces)) {
                if (!network.isUp || network.isLoopback) continue
                for (address in network.interfaceAddresses) {
                    if (address.address is Inet4Address && !address.address.isLoopbackAddress) {
                        address.broadcast?.let(targets::add)
                    }
                }
            }
        }
        if (targets.isEmpty()) return emptyList()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (target in targets) {
                try { socket.send(DatagramPacket(request, request.size, target, 57008)) }
                catch (_: Exception) { }
            }
            val deadline = System.nanoTime() + 1_500_000_000L
            while (System.nanoTime() < deadline) {
                socket.soTimeout = 200
                val packet = DatagramPacket(ByteArray(128), 128)
                try {
                    socket.receive(packet)
                    if (packet.length != response.size) continue
                    if (!packet.data.copyOfRange(0, packet.length).contentEquals(response)) continue
                    val host = packet.address.hostAddress ?: continue
                    if (packet.address !is Inet4Address) continue
                    found[host] = Receiver(host, 57007)
                } catch (_: SocketTimeoutException) { }
            }
        }
        return found.values.sortedBy { it.host }
    }
}
