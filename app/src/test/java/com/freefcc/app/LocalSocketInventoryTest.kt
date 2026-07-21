package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalSocketInventoryTest {

    @Test
    fun parsesListeningTcpAndBoundUdpSockets() {
        val table = """
            sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode
             0: 0100007F:9C47 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 12345
             1: 0100007F:C350 0100007F:1234 01 00000000:00000000 00:00000000 00000000 1001 0 12346
        """.trimIndent()

        val tcp = LocalSocketInventory.parseInetSockets(table, "tcp", listenersOnly = true)
        val udp = LocalSocketInventory.parseInetSockets(table, "udp", listenersOnly = false)

        assertEquals(listOf(40_007), tcp.map { it.port })
        assertEquals(listOf(40_007, 50_000), udp.map { it.port })
        assertEquals(1000, tcp.single().uid)
        assertEquals(12345L, tcp.single().inode)
    }

    @Test
    fun parsesNamedUnixSocketsWithMetadata() {
        val table = """
            Num RefCount Protocol Flags Type St Inode Path
            0001: 00000002 00000000 00010000 0002 01 123 @/duss/mb/0x205
            0002: 00000002 00000000 00000000 0001 01 124
        """.trimIndent()

        val socket = LocalSocketInventory.parseUnixSockets(table).single()

        assertEquals("@/duss/mb/0x205", socket.name)
        assertEquals("00010000", socket.flagsHex)
        assertEquals("0002", socket.typeHex)
        assertEquals("01", socket.stateHex)
        assertEquals(123L, socket.inode)
    }

    @Test
    fun passiveInventoryUsesProcTablesWithoutConnectProbe() {
        val procRoot = Files.createTempDirectory("proc-net-fixture").toFile()
        try {
            File(procRoot, "tcp").writeText(
                "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n" +
                    "0: 0100007F:9C47 00000000:0000 0A 0:0 00:0 0 1000 0 12345\n"
            )
            File(procRoot, "tcp6").writeText("sl local_address rem_address st\n")
            File(procRoot, "udp").writeText("sl local_address rem_address st\n")
            File(procRoot, "udp6").writeText("sl local_address rem_address st\n")
            File(procRoot, "unix").writeText("Num RefCount Protocol Flags Type St Inode Path\n")

            val result = LocalSocketInventory.capture(procRoot = procRoot)

            assertEquals(listOf(40_007), result.tcpListenerPorts)
            assertTrue(result.complete)
            assertTrue(result.errors.isEmpty())
            val json = result.asJsonFields().toMap()
            assertEquals(false, json["probe_attempted"])
            assertEquals(0, json["probe_payload_bytes"])
            assertEquals(0, json["scanned_ports"])
        } finally {
            procRoot.deleteRecursively()
        }
    }
}
