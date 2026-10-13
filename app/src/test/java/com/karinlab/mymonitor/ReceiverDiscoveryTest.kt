package com.karinlab.mymonitor

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class ReceiverDiscoveryTest {
    private fun fakeReceiver(reply: String, block: (Int) -> Unit) {
        ServerSocket(0).use { server ->
            server.soTimeout = 2500
            val worker = thread(name = "FakeReceiver", isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 1800
                        val magic = ByteArray(4)
                        var i = 0
                        while (i < 4) {
                            val n = socket.getInputStream().read(magic, i, 4-i)
                            if (n < 0) break
                            i += n
                        }
                        if (i == 4 && String(magic, Charsets.US_ASCII) == "MCP1")
                            socket.getOutputStream().write(reply.toByteArray(Charsets.US_ASCII))
                    }
                } catch (_: SocketTimeoutException) { }
            }
            block(server.localPort)
            worker.join(2600)
            assertFalse("TCP fake receiver thread leaked", worker.isAlive)
        }
    }
    @Test fun validReceiverProvesIdentity() {
        fakeReceiver("MCR1") { port ->
            assertTrue(ReceiverDiscovery.probe("127.0.0.1", port))
        }
    }
    @Test fun unrelatedDhcpTcpServiceIsRejected() {
        fakeReceiver("DHCP") { port ->
            assertFalse(ReceiverDiscovery.probe("127.0.0.1", port))
        }
    }
    @Test fun rememberedReceiverWorksWithoutUdpBroadcast() {
        fakeReceiver("MCR1") { port ->
            val result = ReceiverDiscovery.scan("127.0.0.1", port, includeBroadcast = false)
            assertEquals(1, result.size)
            assertEquals("127.0.0.1", result.single().host)
            assertEquals(port, result.single().port)
        }
    }
    @Test fun invalidSavedIpIsNotAccepted() {
        fakeReceiver("DHCP") { port ->
            assertTrue(ReceiverDiscovery.scan("127.0.0.1", port,
                includeBroadcast = false).isEmpty())
        }
    }
    @Test fun localSubnetFallbackIsBoundedToPrivateLan() {
        val privateAddress = InetAddress.getByName("192.168.0.100") as Inet4Address
        val hosts = ReceiverDiscovery.local24Peers(privateAddress)
        assertEquals(253, hosts.size)
        assertTrue(hosts.contains("192.168.0.27"))
        assertFalse(hosts.contains("192.168.0.100"))
        assertFalse(hosts.contains("192.168.1.1"))
        val publicAddress = InetAddress.getByName("8.8.8.8") as Inet4Address
        assertTrue(ReceiverDiscovery.local24Peers(publicAddress).isEmpty())
    }

}
