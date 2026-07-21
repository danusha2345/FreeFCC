package com.freefcc.app

import java.io.File
import java.util.Locale

internal data class ProcInetSocket(
    val protocol: String,
    val localAddressHex: String,
    val port: Int,
    val stateHex: String,
    val uid: Int?,
    val inode: Long?
) {
    fun asMap(): Map<String, Any?> = linkedMapOf(
        "protocol" to protocol,
        "local_address_hex" to localAddressHex,
        "port" to port,
        "state_hex" to stateHex,
        "uid" to uid,
        "inode" to inode
    )
}

internal data class ProcUnixSocket(
    val flagsHex: String,
    val typeHex: String,
    val stateHex: String,
    val inode: Long?,
    val name: String
) {
    fun asMap(): Map<String, Any?> = linkedMapOf(
        "flags_hex" to flagsHex,
        "type_hex" to typeHex,
        "state_hex" to stateHex,
        "inode" to inode,
        "name" to name
    )
}

internal data class LocalSocketInventoryResult(
    val procTcpListeners: List<ProcInetSocket>,
    val procUdpSockets: List<ProcInetSocket>,
    val unixSockets: List<ProcUnixSocket>,
    val readableProcSources: List<String>,
    val errors: List<String>,
    val complete: Boolean,
    val durationMs: Long
) {
    val tcpListenerPorts: List<Int> = procTcpListeners.map { it.port }.distinct().sorted()
    val unixSocketNames: List<String> = unixSockets.map { it.name }.distinct().sorted()

    fun asJsonFields(): Array<Pair<String, Any?>> = arrayOf(
        "ok" to true,
        "command" to "local_socket_inventory",
        "inventory_method" to "proc_net_passive",
        "probe_attempted" to false,
        "probe_host" to null,
        "probe_payload_bytes" to 0,
        "scanned_ports" to 0,
        "scan_complete" to false,
        "inventory_complete" to complete,
        "tcp_listener_ports" to tcpListenerPorts,
        "open_tcp_ports" to tcpListenerPorts,
        "proc_tcp_listeners" to procTcpListeners.map { it.asMap() },
        "proc_udp_sockets" to procUdpSockets.map { it.asMap() },
        "proc_unix_sockets" to unixSockets.map { it.asMap() },
        "unix_socket_names" to unixSocketNames,
        "readable_proc_sources" to readableProcSources,
        "errors" to errors,
        "duration_ms" to durationMs
    )
}

/** One-shot, zero-payload inventory for controller-local sockets. */
internal object LocalSocketInventory {
    private const val MAX_PROC_LINES = 4_096
    private val PROC_SOURCES = listOf("tcp", "tcp6", "udp", "udp6", "unix")

    fun capture(
        procRoot: File = File("/proc/net")
    ): LocalSocketInventoryResult {
        val startedNs = System.nanoTime()
        val errors = mutableListOf<String>()
        val readableSources = mutableListOf<String>()

        fun readProc(name: String): String? {
            return try {
                val file = File(procRoot, name)
                val text = file.bufferedReader().useLines { lines ->
                    lines.take(MAX_PROC_LINES).joinToString("\n")
                }
                readableSources += file.path
                text
            } catch (e: Exception) {
                errors += "${File(procRoot, name).path}: ${e.javaClass.simpleName}"
                null
            }
        }

        val tcp = buildList {
            readProc("tcp")?.let { addAll(parseInetSockets(it, "tcp", listenersOnly = true)) }
            readProc("tcp6")?.let { addAll(parseInetSockets(it, "tcp6", listenersOnly = true)) }
        }.distinctBy { Triple(it.protocol, it.localAddressHex, it.port) }
            .sortedWith(compareBy(ProcInetSocket::port, ProcInetSocket::protocol))

        val udp = buildList {
            readProc("udp")?.let { addAll(parseInetSockets(it, "udp", listenersOnly = false)) }
            readProc("udp6")?.let { addAll(parseInetSockets(it, "udp6", listenersOnly = false)) }
        }.distinctBy { Triple(it.protocol, it.localAddressHex, it.port) }
            .sortedWith(compareBy(ProcInetSocket::port, ProcInetSocket::protocol))

        val unix = readProc("unix")?.let(::parseUnixSockets).orEmpty()

        return LocalSocketInventoryResult(
            procTcpListeners = tcp,
            procUdpSockets = udp,
            unixSockets = unix,
            readableProcSources = readableSources,
            errors = errors,
            complete = PROC_SOURCES.all { name -> File(procRoot, name).path in readableSources },
            durationMs = (System.nanoTime() - startedNs) / 1_000_000L
        )
    }

    internal fun parseInetSockets(
        text: String,
        protocol: String,
        listenersOnly: Boolean
    ): List<ProcInetSocket> = text.lineSequence().drop(1).mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 4) return@mapNotNull null
        val local = fields[1].split(':', limit = 2)
        if (local.size != 2) return@mapNotNull null
        val state = fields[3].uppercase(Locale.US)
        if (listenersOnly && state != "0A") return@mapNotNull null
        val port = local[1].toIntOrNull(16) ?: return@mapNotNull null
        ProcInetSocket(
            protocol = protocol,
            localAddressHex = local[0],
            port = port,
            stateHex = state,
            uid = fields.getOrNull(7)?.toIntOrNull(),
            inode = fields.getOrNull(9)?.toLongOrNull()
        )
    }.toList()

    internal fun parseUnixSockets(text: String): List<ProcUnixSocket> = text.lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"), limit = 8)
            val name = fields.getOrNull(7)?.takeIf { it.isNotBlank() }?.take(1_024)
                ?: return@mapNotNull null
            ProcUnixSocket(
                flagsHex = fields.getOrNull(3).orEmpty(),
                typeHex = fields.getOrNull(4).orEmpty(),
                stateHex = fields.getOrNull(5).orEmpty(),
                inode = fields.getOrNull(6)?.toLongOrNull(),
                name = name
            )
        }
        .distinctBy { it.name }
        .sortedBy { it.name }
        .take(MAX_PROC_LINES)
        .toList()
}
