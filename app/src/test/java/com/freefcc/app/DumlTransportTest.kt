package com.freefcc.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
        assertArrayEquals(response, exchange.matchedFrame)
        assertArrayEquals(expectedPayload, exchange.validatedPayload)
        assertNull(exchange.lastCompleteUnmatchedFrame)
        assertNull(exchange.partialTail)
    }

    @Test
    fun rawExchangeSkipsUnrelatedTelemetryBeforeMatchingResponse() {
        val request = DumlBuilder().buildFrame(
            DumlFrame(0x2A, 0x40, 0x03, 0xF8, 0x03, byteArrayOf(0xA2.toByte(), 0x59, 0xCE.toByte(), 0xED.toByte()))
        )
        val unrelatedRequest = DumlBuilder().buildFrame(
            DumlFrame(0x2A, 0x40, 0x06, 0xAE, 0x03, ByteArray(0))
        )
        val unrelatedResponse = buildResponse(unrelatedRequest, byteArrayOf(0x04))
        val expectedPayload = byteArrayOf(0, 0xA2.toByte(), 0x59, 0xCE.toByte(), 0xED.toByte(), 0xEF.toByte())
        val expectedResponse = buildResponse(request, expectedPayload)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            server.use {
                it.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(1024))
                    socket.getOutputStream().apply {
                        write(unrelatedResponse)
                        write(expectedResponse)
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
        assertArrayEquals(expectedResponse, exchange.responseFrame)
        assertArrayEquals(expectedResponse, exchange.matchedFrame)
        assertArrayEquals(expectedPayload, exchange.validatedPayload)
        assertArrayEquals(unrelatedResponse, exchange.lastCompleteUnmatchedFrame)
        assertNull(exchange.partialTail)
    }

    @Test
    fun passiveCaptureCollectsMultipleValidFrames() {
        val firstRequest = DumlBuilder().buildFrame(
            DumlFrame(0x2A, 0x40, 0x03, 0xF8, 0x03, ByteArray(0))
        )
        val secondRequest = DumlBuilder().buildFrame(
            DumlFrame(0x2A, 0x40, 0x06, 0xAE, 0x03, ByteArray(0))
        )
        val first = buildResponse(firstRequest, byteArrayOf(0x01))
        val second = buildResponse(secondRequest, byteArrayOf(0x02))
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            server.use {
                it.accept().use { socket ->
                    socket.getOutputStream().apply {
                        write(first)
                        write(second)
                        flush()
                    }
                }
            }
        }
        serverThread.start()

        val frames = DumlTransport().captureFrames(
            durationMs = 1_000,
            maxFrames = 2,
            port = server.localPort
        )

        serverThread.join(5_000)
        assertEquals(2, frames.size)
        assertArrayEquals(first, frames[0])
        assertArrayEquals(second, frames[1])
    }

    @Test
    fun passiveCaptureDeadlineCannotBeExtendedByTrickleHeader() {
        val request = DumlBuilder().buildFrame(
            DumlFrame(0x2A, 0x40, 0x03, 0xF8, 0x03, ByteArray(0))
        )
        val response = buildResponse(request, byteArrayOf(0x01))
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            server.use {
                it.accept().use { socket ->
                    runCatching {
                        for (byte in response) {
                            socket.getOutputStream().apply {
                                write(byte.toInt())
                                flush()
                            }
                            Thread.sleep(80)
                        }
                    }
                }
            }
        }
        serverThread.start()

        val startedAt = System.nanoTime()
        val frames = DumlTransport().captureFrames(
            durationMs = 150,
            maxFrames = 1,
            port = server.localPort
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        serverThread.join(2_000)
        assertTrue("capture exceeded its global deadline: ${elapsedMs}ms", elapsedMs < 600)
        assertTrue(frames.isEmpty())
    }

    @Test
    fun passiveCaptureReturnsPromptlyOnEof() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            server.use { listener ->
                listener.accept().use { /* close without sending */ }
            }
        }
        serverThread.start()

        val startedAt = System.nanoTime()
        val frames = DumlTransport().captureFrames(
            durationMs = 1_000,
            maxFrames = 1,
            port = server.localPort
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        serverThread.join(2_000)
        assertTrue("capture spun after EOF for ${elapsedMs}ms", elapsedMs < 500)
        assertTrue(frames.isEmpty())
    }

    @Test
    fun passiveCaptureWaitsPastLocalReadTimeoutUntilGlobalDeadline() {
        val request = DumlBuilder().buildFrame(
            DumlFrame(0x2A, 0x40, 0x06, 0xAE, 0x03, ByteArray(0))
        )
        val response = buildResponse(request, byteArrayOf(0x04))
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            server.use { listener ->
                listener.accept().use { socket ->
                    socket.getOutputStream().apply {
                        write(0x55)
                        flush()
                    }
                    Thread.sleep(350)
                    socket.getOutputStream().apply {
                        write(response)
                        flush()
                    }
                }
            }
        }
        serverThread.start()

        val frames = DumlTransport().captureFrames(
            durationMs = 1_000,
            maxFrames = 1,
            port = server.localPort
        )

        serverThread.join(2_000)
        assertEquals(1, frames.size)
        assertArrayEquals(response, frames.single())
    }

    @Test
    fun passiveCaptureResynchronizesOneByteAfterFalseMagic() {
        val request = DumlBuilder().buildFrame(
            DumlFrame(0x2A, 0x40, 0x03, 0xF8, 0x03, ByteArray(0))
        )
        val response = buildResponse(request, byteArrayOf(0x01))
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            server.use { listener ->
                listener.accept().use { socket ->
                    socket.getOutputStream().apply {
                        write(byteArrayOf(0x55, 0x00))
                        write(response)
                        flush()
                    }
                }
            }
        }
        serverThread.start()

        val frames = DumlTransport().captureFrames(
            durationMs = 1_000,
            maxFrames = 1,
            port = server.localPort
        )

        serverThread.join(2_000)
        assertEquals(1, frames.size)
        assertArrayEquals(response, frames.single())
    }

    @Test
    fun wireExchangeReturnsExactUnparsedBytesWithinLimit() {
        val request = byteArrayOf(0x55, 0xCC.toByte(), 0x30, 0x75)
        val response = byteArrayOf(0x55, 0xCC.toByte(), 0x30, 0x75, 0x01, 0x02, 0x03)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverError = AtomicReference<Throwable>()
        val serverThread = Thread {
            try {
                server.use {
                    it.accept().use { socket ->
                        val received = ByteArray(request.size)
                        socket.getInputStream().read(received)
                        assertArrayEquals(request, received)
                        socket.getOutputStream().apply {
                            write(response)
                            flush()
                        }
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
            }
        }
        serverThread.start()

        val exchange = DumlTransport().exchangeWire(
            wire = request,
            durationMs = 500,
            maxBytes = 128,
            port = server.localPort
        )

        serverThread.join(5_000)
        serverError.get()?.let { throw AssertionError("server thread failed", it) }
        assertTrue(exchange.writeCompleted)
        assertNull(exchange.failureStage)
        assertArrayEquals(response, exchange.responseBytes)
    }

    @Test
    fun rawExchangeReleasesWriteLeaseAfterFlushBeforeResponse() {
        val request = DumlBuilder().buildFrame(
            DumlFrame(0x2A, 0x40, 0x03, 0xF8, 0x03, ByteArray(0))
        )
        val response = buildResponse(request, byteArrayOf(0x01))
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val requestReceived = CountDownLatch(1)
        val writeReleased = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val exchangeResult = AtomicReference<DumlRawExchange>()
        val exchangeError = AtomicReference<Throwable>()
        val serverThread = Thread {
            server.use { listener ->
                listener.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(request.size))
                    requestReceived.countDown()
                    releaseResponse.await(2, TimeUnit.SECONDS)
                    socket.getOutputStream().apply {
                        write(response)
                        flush()
                    }
                }
            }
        }
        serverThread.start()

        val originalLease = HardwareLock.tryBegin()
        assertNotNull(originalLease)
        val clientThread = Thread {
            try {
                exchangeResult.set(
                    DumlTransport().sendAndReceiveRaw(
                        frame = request,
                        readWindowMs = 1_000,
                        port = server.localPort,
                        autoDetectPort = false,
                        onWriteFlushed = {
                            originalLease!!.close()
                            writeReleased.countDown()
                        }
                    )
                )
            } catch (t: Throwable) {
                exchangeError.set(t)
            }
        }
        clientThread.start()

        assertTrue(requestReceived.await(1, TimeUnit.SECONDS))
        assertTrue(writeReleased.await(1, TimeUnit.SECONDS))
        val keepaliveLease = HardwareLock.tryBegin()
        assertNotNull("write lease remained held during passive response wait", keepaliveLease)
        keepaliveLease!!.close()
        releaseResponse.countDown()

        clientThread.join(2_000)
        serverThread.join(2_000)
        exchangeError.get()?.let { throw AssertionError("exchange thread failed", it) }
        assertArrayEquals(response, exchangeResult.get().matchedFrame)
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
        assertNull(exchange.matchedFrame)
        assertNull(exchange.validatedPayload)
        assertNull(exchange.lastCompleteUnmatchedFrame)
        assertArrayEquals(response.copyOf(partialSize), exchange.partialTail)
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
        assertNull(exchange.matchedFrame)
        assertNull(exchange.validatedPayload)
        assertNull(exchange.lastCompleteUnmatchedFrame)
        assertArrayEquals(response.copyOf(partialSize), exchange.partialTail)
    }

    @Test
    fun rawExchangeKeepsLastUnmatchedFrameSeparateFromPartialTail() {
        val request = DumlBuilder().buildFrame(
            DumlFrame(0x2A, 0x40, 0x03, 0xF8, 0x03, ByteArray(0))
        )
        val unrelatedRequest = DumlBuilder().buildFrame(
            DumlFrame(0x2A, 0x40, 0x06, 0xAE, 0x03, ByteArray(0))
        )
        val unrelatedResponse = buildResponse(unrelatedRequest, byteArrayOf(0x04))
        val matchingResponse = buildResponse(request, byteArrayOf(0x01))
        val partialSize = 8
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            server.use { listener ->
                listener.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(1024))
                    socket.getOutputStream().apply {
                        write(unrelatedResponse)
                        write(matchingResponse, 0, partialSize)
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

        serverThread.join(2_000)
        assertNull(exchange.matchedFrame)
        assertNull(exchange.validatedPayload)
        assertArrayEquals(unrelatedResponse, exchange.lastCompleteUnmatchedFrame)
        assertArrayEquals(matchingResponse.copyOf(partialSize), exchange.partialTail)
        assertArrayEquals(unrelatedResponse, exchange.responseFrame)
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
