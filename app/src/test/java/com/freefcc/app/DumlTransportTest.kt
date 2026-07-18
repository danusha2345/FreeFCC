package com.freefcc.app

import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class DumlTransportTest {

    @Test
    fun oneSuccessfulWriteDoesNotMakeWholeProfileSuccessful() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            server.use {
                it.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(64))
                }
            }
        }
        serverThread.start()

        val success = DumlTransport().sendFrames(
            frames = listOf(byteArrayOf(0x01), byteArrayOf(0x02)),
            rounds = 1,
            interFrameDelayMs = 0,
            readWindowMs = 20,
            port = server.localPort
        )

        serverThread.join(5_000)
        assertFalse("a partial profile write must be reported as failure", success)
    }
}
