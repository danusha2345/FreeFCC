package com.freefcc.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class DumlTransportTest {

    @Test
    fun emptyProfileIsFailure() {
        val success = DumlTransport().sendFrames(
            frames = emptyList(),
            rounds = 1,
            interFrameDelayMs = 0
        )

        assertFalse("an empty profile must not be reported as successful", success)
    }

    @Test
    fun allSuccessfulWritesMakeWholeProfileSuccessful() {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            server.use { listener ->
                repeat(2) {
                    listener.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(64))
                    }
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
        assertTrue("all completed writes must be reported as successful", success)
    }

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

    @Test
    fun rawExchangeReturnsValidatedPayloadAndRawFrame() {
        val request = DumlBuilder().buildFrame(
            DumlFrame(
                sender = 0x2A,
                cmdType = 0x40,
                cmdSet = 0x03,
                cmdId = 0xE2,
                dst = 0x03,
                payload = byteArrayOf(0, 0, 1, 0)
            )
        )
        val expectedPayload = byteArrayOf(0, 0, 0, 0, 1, 0, 0xEF.toByte())
        val response = buildResponse(request, expectedPayload)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            server.use {
                it.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(1024))
                    socket.getOutputStream().apply {
                        write(response)
                        flush()
                    }
                }
            }
        }
        serverThread.start()

        val exchange = DumlTransport().sendAndReceiveRaw(
            frame = request,
            readWindowMs = 1_000,
            port = server.localPort,
            autoDetectPort = false
        )

        serverThread.join(5_000)
        assertNotNull(exchange.responseFrame)
        assertArrayEquals(response, exchange.responseFrame)
        assertArrayEquals(expectedPayload, exchange.validatedPayload)
    }

    @Test
    fun rawExchangeRetainsPartialResponseOnBodyTimeout() {
        val request = DumlBuilder().buildFrame(
            DumlFrame(0x2A, 0x40, 0x03, 0xF8, 0x03, byteArrayOf(0xA2.toByte(), 0x59, 0xCE.toByte(), 0xED.toByte()))
        )
        val response = buildResponse(request, byteArrayOf(0, 0xA2.toByte(), 0x59, 0xCE.toByte(), 0xED.toByte(), 0xEF.toByte()))
        val partialSize = 14
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            server.use {
                it.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(1024))
                    socket.getOutputStream().apply {
                        write(response, 0, partialSize)
                        flush()
                    }
                    Thread.sleep(300)
                }
            }
        }
        serverThread.start()

        val exchange = DumlTransport().sendAndReceiveRaw(
            frame = request,
            readWindowMs = 100,
            port = server.localPort,
            autoDetectPort = false
        )

        serverThread.join(5_000)
        assertArrayEquals(response.copyOf(partialSize), exchange.responseFrame)
        assertNull(exchange.validatedPayload)
    }

    @Test
    fun rawExchangeStopsCleanlyOnPartialHeaderTimeout() {
        val request = DumlBuilder().buildFrame(
            DumlFrame(0x2A, 0x40, 0x03, 0xF8, 0x03, byteArrayOf(0xA2.toByte(), 0x59, 0xCE.toByte(), 0xED.toByte()))
        )
        val response = buildResponse(request, byteArrayOf(0, 0xA2.toByte(), 0x59, 0xCE.toByte(), 0xED.toByte(), 0xEF.toByte()))
        val partialSize = 6
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            server.use {
                it.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(1024))
                    socket.getOutputStream().apply {
                        write(response, 0, partialSize)
                        flush()
                    }
                    Thread.sleep(300)
                }
            }
        }
        serverThread.start()

        val exchange = DumlTransport().sendAndReceiveRaw(
            frame = request,
            readWindowMs = 100,
            port = server.localPort,
            autoDetectPort = false
        )

        serverThread.join(5_000)
        assertArrayEquals(response.copyOf(partialSize), exchange.responseFrame)
        assertNull(exchange.validatedPayload)
    }

    private fun buildResponse(request: ByteArray, payload: ByteArray): ByteArray {
        val totalLength = payload.size + 13
        val response = ByteArray(totalLength)
        response[0] = 0x55
        response[1] = (totalLength and 0xFF).toByte()
        response[2] = (((totalLength shr 8) and 0x03) or 0x04).toByte()
        response[3] = DumlBuilder.crc8(response, 0, 3).toByte()
        response[4] = request[5]
        response[5] = request[4]
        response[6] = request[6]
        response[7] = request[7]
        response[8] = (request[8].toInt() or 0x80).toByte()
        response[9] = request[9]
        response[10] = request[10]
        payload.copyInto(response, destinationOffset = 11)
        val crc = DumlBuilder.crc16(response, 0, totalLength - 2)
        response[totalLength - 2] = (crc and 0xFF).toByte()
        response[totalLength - 1] = ((crc shr 8) and 0xFF).toByte()
        return response
    }
}
