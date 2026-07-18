package com.freefcc.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class NetworkLogServerTest {

    @Test
    fun endpointRequiresTokenAndReturnsLogSnapshot() {
        val password = "test_password_123"
        val server = NetworkLogServer(
            logSnapshot = { listOf("[12:00:00] first", "[12:00:01] second") },
            addressProvider = { InetAddress.getLoopbackAddress() },
            passwordProvider = { password },
            requestedPort = 0
        )

        try {
            val endpoint = server.start()

            val forbidden = request(endpoint.port, "/logs")
            assertTrue(forbidden.startsWith("HTTP/1.1 403 Forbidden"))

            val allowed = request(endpoint.port, "/logs?password=$password")
            assertTrue(allowed.startsWith("HTTP/1.1 200 OK"))
            assertTrue(allowed.contains("[12:00:00] first\n[12:00:01] second\n"))
            assertTrue(allowed.contains("Cache-Control: no-store"))
        } finally {
            server.close()
        }
    }

    @Test
    fun beaconContainsOnlyDiscoveryCoordinates() {
        val payload = NetworkLogServer.beaconPayload(
            NetworkLogEndpoint("ignored", "192.168.1.42", 8787)
        )

        assertTrue(payload == "FREEFCC_LOG_V1 address=192.168.1.42 port=8787\n")
        assertTrue(!payload.contains(NetworkLogServer.FIXED_ACCESS_PASSWORD))
    }

    private fun request(port: Int, target: String): String {
        return Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.getOutputStream().apply {
                write("GET $target HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
            socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }
    }
}
