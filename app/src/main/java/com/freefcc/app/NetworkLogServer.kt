package com.freefcc.app

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

internal data class NetworkLogEndpoint(
    val url: String,
    val address: String,
    val port: Int
)

/**
 * Small opt-in HTTP server that exposes the in-app activity log to a trusted
 * client on the same Wi-Fi network. It never binds to cellular or wildcard
 * interfaces, and every request requires the fixed project password. A small
 * UDP beacon lets a trusted client find the controller without copying a URL.
 */
internal class NetworkLogServer(
    private val logSnapshot: () -> List<String>,
    private val addressProvider: () -> InetAddress? = ::findPrivateWifiAddress,
    private val passwordProvider: () -> String = { FIXED_ACCESS_PASSWORD },
    private val requestedPort: Int = DEFAULT_PORT
) : AutoCloseable {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var endpoint: NetworkLogEndpoint? = null
    @Volatile private var accessPassword: String? = null
    private var worker: Thread? = null
    private var beaconWorker: Thread? = null

    @Synchronized
    @Throws(IOException::class)
    fun start(): NetworkLogEndpoint {
        endpoint?.let { return it }

        val address = addressProvider()
            ?: throw IOException("No private Wi-Fi IPv4 address found")
        val password = passwordProvider()
        require(password.matches(Regex("[A-Za-z0-9_-]{12,64}"))) {
            "LAN log password must be 12-64 URL-safe characters"
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
        worker = Thread({ acceptLoop(socket) }, "FreeFCC-LAN-Log").apply {
            isDaemon = true
            start()
        }
        beaconWorker = Thread(
            { beaconLoop(socket, startedEndpoint, address) },
            "FreeFCC-LAN-Beacon"
        ).apply {
            isDaemon = true
            start()
        }
        return startedEndpoint
    }

    @Synchronized
    fun currentEndpoint(): NetworkLogEndpoint? = endpoint

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
            // The HTTP log bridge remains usable even when UDP is unavailable.
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            try {
                server.accept().use(::handleClient)
            } catch (_: IOException) {
                if (!server.isClosed) continue
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 2_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
        val requestLine = reader.readLine()?.take(MAX_REQUEST_LINE) ?: return
        for (ignored in 0 until MAX_HEADER_LINES) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2 || parts[0] != "GET") {
            writeResponse(client, 405, "Method Not Allowed", "GET only\n")
            return
        }

        val target = parts[1]
        if (target.substringBefore('?') != "/logs") {
            writeResponse(client, 404, "Not Found", "Not found\n")
            return
        }

        val expectedPassword = accessPassword
        val suppliedPassword = target.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("password=") }
            ?.substringAfter('=')
        if (expectedPassword == null || suppliedPassword != expectedPassword) {
            writeResponse(client, 403, "Forbidden", "Invalid password\n")
            return
        }

        val logs = logSnapshot().joinToString(separator = "\n", postfix = "\n")
        writeResponse(client, 200, "OK", logs)
    }

    private fun writeResponse(client: Socket, code: Int, reason: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
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
        serverSocket = null
        worker = null
        beaconWorker = null
        endpoint = null
        accessPassword = null
    }

    companion object {
        const val DEFAULT_PORT = 8787
        const val BEACON_PORT = 8788
        const val FIXED_ACCESS_PASSWORD = "c0dec0de8787fcc0"
        const val BEACON_MAGIC = "FREEFCC_LOG_V1"
        private const val MAX_REQUEST_LINE = 2_048
        private const val MAX_HEADER_LINES = 32
        private const val BEACON_INTERVAL_MS = 2_000L

        internal fun beaconPayload(endpoint: NetworkLogEndpoint): String =
            "$BEACON_MAGIC address=${endpoint.address} port=${endpoint.port}\n"

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
}
