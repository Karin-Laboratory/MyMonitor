package com.karinlab.mymonitor

import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Collections

/**
 * Receiver discovery V2, verified in TWO independent stages.
 *
 * 1. An actual receiver echoes the unpredictable nonce in the UDP reply.
 * 2. It then answers MCR1 to a direct TCP MCP1 probe on its advertised IP.
 *
 * A broadcast relay, DHCP host, stale preferences, or an unrelated UDP device
 * cannot be selected simply because its address appeared in discovery traffic.
 * The TCP probe must reach the Windows process itself, not a saved IP.
 */
object ReceiverDiscovery {
    data class Receiver(val host: String, val port: Int, val name: String = "Windows")

    private const val UDP_PORT = 57008
    private const val VIDEO_PORT = 57007
    private const val REQUEST_PREFIX = "MYMONITOR_DISCOVER_V2|"
    private const val RESPONSE_PREFIX = "MYCAST_RECEIVER_V2|"
    private const val PROBE_TIMEOUT_MS = 1100
    private const val DISCOVERY_WINDOW_NS = 1_400_000_000L

    fun probe(host: String, port: Int = VIDEO_PORT): Boolean {
        if (port !in 1..65535 || host.isBlank()) return false
        return try {
            Socket().use { socket ->
                socket.soTimeout = PROBE_TIMEOUT_MS
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                socket.getOutputStream().write(byteArrayOf(0x4d, 0x43, 0x50, 0x31)) // MCP1
                socket.getOutputStream().flush()
                val reply = ByteArray(4)
                DataInputStream(socket.getInputStream()).readFully(reply)
                reply.contentEquals(byteArrayOf(0x4d, 0x43, 0x52, 0x31)) // MCR1
            }
        } catch (_: Exception) { false }
    }

    fun scan(): List<Receiver> {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val request = (REQUEST_PREFIX + nonce).toByteArray(Charsets.US_ASCII)
        val replyPrefix = RESPONSE_PREFIX + nonce + "|" + VIDEO_PORT + "|"
        val broadcastAddresses = linkedSetOf<InetAddress>()
        val interfaces = NetworkInterface.getNetworkInterfaces()
        if (interfaces != null) {
            for (network in Collections.list(interfaces)) {
                if (!network.isUp || network.isLoopback || network.isPointToPoint) continue
                for (item in network.interfaceAddresses) {
                    val address = item.address
                    if (address is Inet4Address && !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress) {
                        item.broadcast?.let(broadcastAddresses::add)
                    }
                }
            }
        }
        if (broadcastAddresses.isEmpty()) return emptyList()

        val candidates = linkedMapOf<String, Receiver>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 200
            // 2 sends mitigate occasional Wi-Fi multicast/broadcast loss.
            for (target in broadcastAddresses) {
                try { socket.send(DatagramPacket(request, request.size, target, UDP_PORT)) }
                catch (_: Exception) { }
            }
            val deadline = System.nanoTime() + DISCOVERY_WINDOW_NS
            while (System.nanoTime() < deadline) {
                val packet = DatagramPacket(ByteArray(256), 256)
                try {
                    socket.receive(packet)
                    if (packet.address !is Inet4Address ||
                        packet.address.isLoopbackAddress || packet.address.isLinkLocalAddress) continue
                    val response = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
                    if (!response.startsWith(replyPrefix)) continue
                    val name = response.removePrefix(replyPrefix)
                    if (name.isEmpty() || name.length > 64 || name.any { it.code !in 33..126 || it == '|' }) continue
                    val host = packet.address.hostAddress ?: continue
                    candidates[host] = Receiver(host, VIDEO_PORT, name)
                } catch (_: SocketTimeoutException) { }
            }
        }
        // TCP verification is deliberately outside the UDP receive loop.
        // Never store/auto-select a device based solely on a UDP sender address.
        return candidates.values.filter { probe(it.host, it.port) }.sortedWith(
            compareBy(Receiver::name, Receiver::host))
    }
}
