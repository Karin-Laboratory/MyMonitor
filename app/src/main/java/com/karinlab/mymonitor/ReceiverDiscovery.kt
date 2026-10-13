package com.karinlab.mymonitor

import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Collections

/**
 * Receiver discovery is NOT allowed to trust an IP address or a UDP string.
 * 1. Remembered address -> direct TCP MCP1/MCR1 proof (works across VLANs and
 *    on Wi-Fi routers blocking broadcasts).
 * 2. If no verified remembered receiver, per-interface and global broadcasts
 *    with a fresh nonce -> independently verify EACH UDP source by direct TCP.
 *
 * A preexisting TCP/video stream occupies the receiver's single accept loop;
 * StreamHub never probes the active endpoint while it is streaming.
 */
object ReceiverDiscovery {
    data class Receiver(val host: String, val port: Int, val name: String = "Windows")
    private const val UDP_PORT = 57008
    private const val VIDEO_PORT = 57007
    private const val PROBE_TIMEOUT_MS = 950
    private const val DISCOVERY_WINDOW_NS = 2_200_000_000L
    private const val REQUEST_PREFIX = "MYMONITOR_DISCOVER_V2|"
    private const val RESPONSE_PREFIX = "MYCAST_RECEIVER_V2|"

    fun probe(host: String, port: Int = VIDEO_PORT): Boolean {
        if (host.isBlank() || port !in 1..65535) return false
        return try {
            Socket().use { s ->
                s.soTimeout = PROBE_TIMEOUT_MS
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                s.getOutputStream().write("MCP1".toByteArray(Charsets.US_ASCII))
                s.getOutputStream().flush()
                val reply = ByteArray(4)
                DataInputStream(s.getInputStream()).readFully(reply)
                reply.contentEquals("MCR1".toByteArray(Charsets.US_ASCII))
            }
        } catch (_: Exception) { false }
    }

    /** Direct-only reconnect (no broadcasts, no stale-IP acceptance). */
    fun remembered(host: String?, port: Int = VIDEO_PORT): Receiver? =
        if (!host.isNullOrBlank() && probe(host, port)) Receiver(host, port, "前回のWindows")
        else null

    /**
     * Returns ONLY verified receiver instances.
     * includeBroadcast=false is useful for quick startup after pairing and
     * for deterministic JVM tests with an in-process fake receiver.
     */
    fun scan(preferredHost: String? = null, preferredPort: Int = VIDEO_PORT,
             includeBroadcast: Boolean = true): List<Receiver> {
        val found = linkedMapOf<String, Receiver>()
        remembered(preferredHost, preferredPort)?.let { found[it.host + ":" + it.port] = it }
        if (!includeBroadcast) return found.values.toList()
        // A successful remembered receiver should be immediately usable.
        // The explicit receiver picker can still call scanAll() to enumerate.
        if (found.isNotEmpty()) return found.values.toList()
        return scanAll()
    }

    fun scanAll(preferredHost: String? = null,
                preferredPort: Int = VIDEO_PORT): List<Receiver> {
        val candidates = linkedMapOf<String, Receiver>()
        remembered(preferredHost, preferredPort)?.let { candidates[it.host + ":" + it.port] = it }
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val request = (REQUEST_PREFIX + nonce).toByteArray(Charsets.US_ASCII)
        val responsePrefix = RESPONSE_PREFIX + nonce + "|" + VIDEO_PORT + "|"

        val targets = linkedSetOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) for (network in Collections.list(interfaces)) {
                if (!network.isUp || network.isLoopback || network.isPointToPoint) continue
                for (item in network.interfaceAddresses) {
                    if (item.address is Inet4Address &&
                        !item.address.isLoopbackAddress && !item.address.isLinkLocalAddress)
                        item.broadcast?.let { targets.add(it) }
                }
            }
        } catch (_: Exception) { }
        // Some Android Wi-Fi drivers do not expose InterfaceAddress.broadcast.
        try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) { }

        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 180
                val startNs = System.nanoTime()
                var sentAgain = false
                for (target in targets) {
                    try { socket.send(DatagramPacket(request, request.size, target, UDP_PORT)) }
                    catch (_: Exception) { }
                }
                while (System.nanoTime() - startNs < DISCOVERY_WINDOW_NS) {
                    if (!sentAgain && System.nanoTime() - startNs >= 550_000_000L) {
                        sentAgain = true
                        for (target in targets) {
                            try { socket.send(DatagramPacket(request, request.size, target, UDP_PORT)) }
                            catch (_: Exception) { }
                        }
                    }
                    try {
                        val packet = DatagramPacket(ByteArray(256), 256)
                        socket.receive(packet)
                        val ip = packet.address
                        if (ip !is Inet4Address || ip.isLoopbackAddress ||
                            ip.isLinkLocalAddress) continue
                        val answer = String(packet.data, packet.offset,
                            packet.length, Charsets.US_ASCII)
                        if (!answer.startsWith(responsePrefix)) continue
                        val name = answer.removePrefix(responsePrefix)
                        if (name.isEmpty() || name.length > 64 ||
                            name.any { it.code !in 33..126 || it == '|' }) continue
                        candidates[ip.hostAddress + ":" + VIDEO_PORT] =
                            Receiver(ip.hostAddress, VIDEO_PORT, name)
                    } catch (_: SocketTimeoutException) { }
                }
            }
        } catch (_: Exception) { }
        // Validate candidate identity directly. Bounced/proxied UDP packets do
        // not confer trust in a DHCP/router address.
        return candidates.values.filter {
            // The remembered address was already probed earlier.
            (it.name == "前回のWindows" && it.host == preferredHost && it.port == preferredPort) ||
                probe(it.host, it.port)
        }.sortedWith(compareBy(Receiver::name, Receiver::host))
    }
}
