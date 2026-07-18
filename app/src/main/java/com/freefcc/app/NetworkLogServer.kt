package com.freefcc.app

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class NetworkLogEndpoint(
    val url: String,
    val address: String,
    val port: Int
)

internal data class NetworkApiResponse(
    val code: Int,
    val body: String
)

/**
 * Small opt-in HTTP server that exposes the in-app activity log to a trusted
 * client on the same Wi-Fi network. It never binds to cellular or wildcard
 * interfaces, and every request requires the fixed project password. A small
 * UDP beacon lets a trusted client find the controller without copying a URL.
 */
internal class NetworkLogServer(
    private val logSnapshot: () -> List<String>,
    private val apiStatusSnapshot: () -> String = { "{\"ok\":false,\"error\":\"api_not_ready\"}" },
    private val apiCommandHandler: (Map<String, String>) -> NetworkApiResponse = {
        NetworkApiResponse(503, "{\"ok\":false,\"error\":\"api_not_ready\"}")
    },
    private val addressProvider: () -> InetAddress? = ::findPrivateWifiAddress,
    private val passwordProvider: () -> String = { FIXED_ACCESS_PASSWORD },
    private val requestedPort: Int = DEFAULT_PORT
) : AutoCloseable {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var endpoint: NetworkLogEndpoint? = null
    @Volatile private var accessPassword: String? = null
    private var worker: Thread? = null
    private var beaconWorker: Thread? = null
    private var clientExecutor: ExecutorService? = null

    @Synchronized
    @Throws(IOException::class)
    fun start(): NetworkLogEndpoint {
        val address = addressProvider()
            ?: throw IOException("No private Wi-Fi IPv4 address found")
        val currentEndpoint = endpoint
        val currentSocket = serverSocket
        if (currentEndpoint != null && currentSocket != null && !currentSocket.isClosed &&
            currentEndpoint.address == address.hostAddress) {
            return currentEndpoint
        }
        if (currentEndpoint != null || currentSocket != null) close()

        val password = passwordProvider()
        require(password.matches(Regex("[A-Za-z0-9_-]{12,64}"))) {
            "LAN control password must be 12-64 URL-safe characters"
        }

        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(address, requestedPort), 4)
        }
        val startedEndpoint = NetworkLogEndpoint(
            url = "http://${address.hostAddress}:${socket.localPort}/logs?password=$password",
            address = address.hostAddress.orEmpty(),
            port = socket.localPort
        )

        serverSocket = socket
        accessPassword = password
        endpoint = startedEndpoint
        clientExecutor = ThreadPoolExecutor(
            MAX_CLIENTS,
            MAX_CLIENTS,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(MAX_PENDING_CLIENTS),
            { task -> Thread(task, "FreeFCC-LAN-Client").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()
        )
        worker = Thread({ acceptLoop(socket) }, "FreeFCC-LAN-Log").apply {
            isDaemon = true
            start()
        }
        if (!address.isLoopbackAddress) {
            beaconWorker = Thread(
                { beaconLoop(socket, startedEndpoint, address) },
                "FreeFCC-LAN-Beacon"
            ).apply {
                isDaemon = true
                start()
            }
        }
        return startedEndpoint
    }

    @Synchronized
    fun currentEndpoint(): NetworkLogEndpoint? {
        val current = endpoint ?: return null
        val socket = serverSocket ?: return null
        if (socket.isClosed) return null
        val currentAddress = try { addressProvider()?.hostAddress } catch (_: Exception) { null }
        return current.takeIf { it.address == currentAddress }
    }

    private fun beaconLoop(
        server: ServerSocket,
        endpoint: NetworkLogEndpoint,
        boundAddress: InetAddress
    ) {
        val targets = broadcastAddresses(boundAddress)
        val payload = beaconPayload(endpoint).toByteArray(StandardCharsets.US_ASCII)
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                while (!server.isClosed && !Thread.currentThread().isInterrupted) {
                    targets.forEach { target ->
                        try {
                            socket.send(DatagramPacket(payload, payload.size, target, BEACON_PORT))
                        } catch (_: IOException) {
                            // Keep announcing to any remaining interface target.
                        }
                    }
                    try {
                        Thread.sleep(BEACON_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        } catch (_: IOException) {
            // The HTTP control bridge remains usable even when UDP is unavailable.
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            try {
                val client = server.accept()
                val executor = clientExecutor
                if (executor == null || executor.isShutdown) {
                    try { client.close() } catch (_: IOException) {}
                } else {
                    try {
                        executor.execute {
                            client.use(::handleClient)
                        }
                    } catch (_: RejectedExecutionException) {
                        try { client.close() } catch (_: IOException) {}
                    }
                }
            } catch (_: IOException) {
                if (!server.isClosed) continue
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 2_000
        try {
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readAsciiLine(input, MAX_REQUEST_LINE) ?: return
            val headers = linkedMapOf<String, String>()
            var headersComplete = false
            for (ignored in 0 until MAX_HEADER_LINES) {
                val line = readAsciiLine(input, MAX_HEADER_LINE) ?: break
                if (line.isEmpty()) {
                    headersComplete = true
                    break
                }
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }
            if (!headersComplete) throw HttpRequestException(431, "Request Header Fields Too Large", "Too many headers\n")

            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 3 || !parts[2].startsWith("HTTP/")) {
                throw HttpRequestException(400, "Bad Request", "Bad request\n")
            }

            val method = parts[0]
            val target = parts[1]
            val path = target.substringBefore('?')
            if (path !in API_PATHS) {
                writeResponse(client, 404, "Not Found", "Not found\n")
                return
            }

            val expectedPassword = accessPassword
            val suppliedPassword = headers[AUTH_HEADER] ?: if (path == "/logs") {
                parseUrlEncoded(target.substringAfter('?', ""))["password"]
            } else {
                null
            }
            if (expectedPassword == null || suppliedPassword == null ||
                !MessageDigest.isEqual(
                    expectedPassword.toByteArray(StandardCharsets.UTF_8),
                    suppliedPassword.toByteArray(StandardCharsets.UTF_8)
                )) {
                writeResponse(client, 403, "Forbidden", "Invalid password\n")
                return
            }

            when (path) {
                "/logs" -> {
                    if (method != "GET") {
                        writeResponse(client, 405, "Method Not Allowed", "GET only\n")
                        return
                    }
                    val logs = logSnapshot().joinToString(separator = "\n", postfix = "\n")
                    writeResponse(client, 200, "OK", logs)
                }
                "/api/status" -> {
                    if (method != "GET") {
                        writeResponse(client, 405, "Method Not Allowed", "GET only\n")
                        return
                    }
                    writeResponse(client, 200, "OK", apiStatusSnapshot(), JSON_CONTENT_TYPE)
                }
                "/api/commands" -> {
                    if (method != "GET") {
                        writeResponse(client, 405, "Method Not Allowed", "GET only\n")
                        return
                    }
                    val response = apiCommandHandler(mapOf("command" to "help"))
                    writeApiResponse(client, response)
                }
                "/api/command" -> {
                    if (method != "POST") {
                        writeResponse(client, 405, "Method Not Allowed", "POST only\n")
                        return
                    }
                    val contentType = headers["content-type"]
                        ?.substringBefore(';')
                        ?.trim()
                        ?.lowercase()
                    if (contentType != FORM_CONTENT_TYPE) {
                        writeResponse(client, 415, "Unsupported Media Type", "Form content type required\n")
                        return
                    }
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    if (contentLength !in 1..MAX_BODY_LENGTH) {
                        writeResponse(client, 400, "Bad Request", "Invalid request body length\n")
                        return
                    }
                    val body = readBody(input, contentLength)
                        ?: throw HttpRequestException(400, "Bad Request", "Incomplete request body\n")
                    val params = parseUrlEncoded(body)
                    val response = try {
                        apiCommandHandler(params)
                    } catch (_: Exception) {
                        NetworkApiResponse(500, "{\"ok\":false,\"error\":\"command_handler_failed\"}")
                    }
                    writeApiResponse(client, response)
                }
            }
        } catch (e: IllegalArgumentException) {
            try { writeResponse(client, 400, "Bad Request", "Invalid form encoding\n") } catch (_: IOException) {}
        } catch (e: HttpRequestException) {
            try { writeResponse(client, e.code, e.reason, e.responseBody) } catch (_: IOException) {}
        } catch (_: IOException) {
            // Client disconnected or timed out while the bounded request was read.
        }
    }

    private fun writeApiResponse(client: Socket, response: NetworkApiResponse) {
        writeResponse(
            client,
            response.code,
            when (response.code) {
                200 -> "OK"
                202 -> "Accepted"
                400 -> "Bad Request"
                409 -> "Conflict"
                412 -> "Precondition Failed"
                415 -> "Unsupported Media Type"
                502 -> "Bad Gateway"
                503 -> "Service Unavailable"
                504 -> "Gateway Timeout"
                else -> "Error"
            },
            response.body,
            JSON_CONTENT_TYPE
        )
    }

    private fun writeResponse(
        client: Socket,
        code: Int,
        reason: String,
        body: String,
        contentType: String = "text/plain; charset=utf-8"
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        client.getOutputStream().apply {
            write(headers)
            write(bytes)
            flush()
        }
    }

    @Synchronized
    override fun close() {
        try { serverSocket?.close() } catch (_: IOException) {}
        worker?.interrupt()
        beaconWorker?.interrupt()
        clientExecutor?.shutdownNow()
        serverSocket = null
        worker = null
        beaconWorker = null
        clientExecutor = null
        endpoint = null
        accessPassword = null
    }

    companion object {
        const val DEFAULT_PORT = 8787
        const val BEACON_PORT = 8788
        const val FIXED_ACCESS_PASSWORD = "c0dec0de8787fcc0"
        const val BEACON_MAGIC = "FREEFCC_LOG_V1"
        private const val AUTH_HEADER = "x-freefcc-password"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
        private const val MAX_REQUEST_LINE = 4_096
        private const val MAX_HEADER_LINE = 4_096
        // A 4 KiB wire payload expands to 8 KiB of hex plus form field names.
        private const val MAX_BODY_LENGTH = 16_384
        private const val MAX_HEADER_LINES = 32
        private const val MAX_CLIENTS = 4
        private const val MAX_PENDING_CLIENTS = 8
        private const val BEACON_INTERVAL_MS = 2_000L
        private val API_PATHS = setOf("/logs", "/api/status", "/api/commands", "/api/command")

        internal fun beaconPayload(endpoint: NetworkLogEndpoint): String =
            "$BEACON_MAGIC address=${endpoint.address} port=${endpoint.port}\n"

        private fun readAsciiLine(input: InputStream, maxLength: Int): String? {
            val output = ByteArrayOutputStream(minOf(maxLength, 256))
            while (true) {
                val value = input.read()
                if (value < 0) return if (output.size() == 0) null else output.toString(StandardCharsets.US_ASCII.name())
                if (value == '\n'.code) return output.toString(StandardCharsets.US_ASCII.name())
                if (value == '\r'.code) continue
                if (output.size() >= maxLength) {
                    throw HttpRequestException(431, "Request Header Fields Too Large", "Request line too long\n")
                }
                output.write(value)
            }
        }

        private fun readBody(input: InputStream, contentLength: Int): String? {
            val bytes = ByteArray(contentLength)
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) return null
                offset += read
            }
            return String(bytes, StandardCharsets.UTF_8)
        }

        internal fun parseUrlEncoded(value: String): Map<String, String> {
            if (value.isEmpty()) return emptyMap()
            return value.split('&').mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8.name())
                val decodedValue = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8.name())
                key to decodedValue
            }.toMap()
        }

        private fun broadcastAddresses(boundAddress: InetAddress): List<InetAddress> {
            val network = NetworkInterface.getByInetAddress(boundAddress)
            val addresses = network?.interfaceAddresses
                ?.mapNotNull { it.broadcast }
                ?.distinct()
                .orEmpty()
            return addresses.ifEmpty { listOf(InetAddress.getByName("255.255.255.255")) }
        }

        private fun findPrivateWifiAddress(): InetAddress? {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                val name = network.name.lowercase()
                if (!network.isUp || network.isLoopback ||
                    !(name.startsWith("wlan") || name.startsWith("wifi"))) {
                    continue
                }
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && address.isSiteLocalAddress) return address
                }
            }
            return null
        }
    }

    private class HttpRequestException(
        val code: Int,
        val reason: String,
        val responseBody: String
    ) : IOException(reason)
}
