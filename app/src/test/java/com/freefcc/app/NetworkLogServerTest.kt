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

    @Test
    fun apiUsesPasswordHeaderAndDispatchesFormCommand() {
        val password = "test_password_123"
        var received: Map<String, String> = emptyMap()
        val server = NetworkLogServer(
            logSnapshot = { emptyList() },
            apiStatusSnapshot = { "{\"ok\":true,\"connected\":true}" },
            apiCommandHandler = { params ->
                received = params
                NetworkApiResponse(200, "{\"ok\":true}")
            },
            addressProvider = { InetAddress.getLoopbackAddress() },
            passwordProvider = { password },
            requestedPort = 0
        )

        try {
            val endpoint = server.start()

            val status = request(
                endpoint.port,
                "GET",
                "/api/status",
                headers = mapOf("X-FreeFCC-Password" to password)
            )
            assertTrue(status.startsWith("HTTP/1.1 200 OK"))
            assertTrue(status.contains("application/json"))
            assertTrue(status.contains("\"connected\":true"))

            val body = "command=duml_request&cmd_set=0x03&cmd_id=0xe2&payload=00000100"
            val command = request(
                endpoint.port,
                "POST",
                "/api/command",
                headers = mapOf(
                    "X-FreeFCC-Password" to password,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                body = body
            )
            assertTrue(command.startsWith("HTTP/1.1 200 OK"))
            assertTrue(received["command"] == "duml_request")
            assertTrue(received["cmd_id"] == "0xe2")
            assertTrue(received["payload"] == "00000100")
        } finally {
            server.close()
        }
    }

    @Test
    fun apiRejectsQueryPasswordWrongContentTypeAndMalformedEncoding() {
        val password = "test_password_123"
        val server = NetworkLogServer(
            logSnapshot = { emptyList() },
            apiCommandHandler = { NetworkApiResponse(200, "{\"ok\":true}") },
            addressProvider = { InetAddress.getLoopbackAddress() },
            passwordProvider = { password },
            requestedPort = 0
        )

        try {
            val endpoint = server.start()
            val body = "command=ping"

            val queryPassword = request(
                endpoint.port,
                "POST",
                "/api/command?password=$password",
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                body = body
            )
            assertTrue(queryPassword.startsWith("HTTP/1.1 403 Forbidden"))

            val wrongContentType = request(
                endpoint.port,
                "POST",
                "/api/command",
                headers = mapOf(
                    "X-FreeFCC-Password" to password,
                    "Content-Type" to "text/plain"
                ),
                body = body
            )
            assertTrue(wrongContentType.startsWith("HTTP/1.1 415 Unsupported Media Type"))

            val malformed = request(endpoint.port, "/logs?password=%")
            assertTrue(malformed.startsWith("HTTP/1.1 400 Bad Request"))
        } finally {
            server.close()
        }
    }

    @Test
    fun startRebindsWhenWifiAddressChanges() {
        var currentAddress = InetAddress.getByName("127.0.0.1")
        val server = NetworkLogServer(
            logSnapshot = { emptyList() },
            addressProvider = { currentAddress },
            passwordProvider = { "test_password_123" },
            requestedPort = 0
        )

        try {
            val first = server.start()
            currentAddress = InetAddress.getByName("127.0.0.2")
            val rebound = server.start()

            assertTrue(first.address == "127.0.0.1")
            assertTrue(rebound.address == "127.0.0.2")
            assertTrue(server.currentEndpoint() == rebound)
        } finally {
            server.close()
        }
    }

    private fun request(port: Int, target: String): String {
        return request(port, "GET", target)
    }

    private fun request(
        port: Int,
        method: String,
        target: String,
        headers: Map<String, String> = emptyMap(),
        body: String = ""
    ): String {
        return Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            val bodyBytes = body.toByteArray(StandardCharsets.US_ASCII)
            val request = buildString {
                append("$method $target HTTP/1.1\r\n")
                append("Host: localhost\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                if (bodyBytes.isNotEmpty()) append("Content-Length: ${bodyBytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            socket.getOutputStream().apply {
                write(request)
                if (bodyBytes.isNotEmpty()) write(bodyBytes)
                flush()
            }
            socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }
    }
}
