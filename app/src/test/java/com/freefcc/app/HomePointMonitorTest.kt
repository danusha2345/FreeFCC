package com.freefcc.app

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePointMonitorTest {
    @Test
    fun recordedSnapshotCompletesWithoutPriorFalse() {
        val gate = HomePointSessionGate()

        assertTrue(gate.observe(true))
        assertFalse(gate.observe(true))
        assertFalse(gate.observe(false))
    }

    @Test
    fun detectsRecordedBitInFragmentedWrappedStream() {
        val before = homePointFrame(0x0046)
        val after = homePointFrame(0x0047)
        val parser = WrappedDumlFrameParser()
        val wire = Profiles.wrapFrame(before) + Profiles.wrapFrame(after)

        val frames = mutableListOf<ByteArray>()
        frames += parser.feed(wire.copyOfRange(0, 7))
        frames += parser.feed(wire.copyOfRange(7, 41))
        frames += parser.feed(wire.copyOfRange(41, wire.size))

        assertEquals(2, frames.size)
        assertFalse(HomePointProtocol.isRecorded(frames[0])!!)
        assertTrue(HomePointProtocol.isRecorded(frames[1])!!)
    }

    @Test
    fun detectsRecordedBitInFragmentedDirectStream() {
        val before = homePointFrame(0x0046)
        val after = homePointFrame(0x0047)
        val parser = WrappedDumlFrameParser()
        val wire = before + after

        val frames = mutableListOf<ByteArray>()
        frames += parser.feed(wire.copyOfRange(0, 3))
        frames += parser.feed(wire.copyOfRange(3, 73))
        frames += parser.feed(wire.copyOfRange(73, wire.size))

        assertEquals(2, frames.size)
        assertFalse(HomePointProtocol.isRecorded(frames[0])!!)
        assertTrue(HomePointProtocol.isRecorded(frames[1])!!)
    }

    @Test
    fun parsesMixedDirectAndWrappedFramesWithoutDuplicates() {
        val direct = homePointFrame(0x0046)
        val wrapped = Profiles.wrapFrame(homePointFrame(0x0047))
        val frames = WrappedDumlFrameParser().feed(byteArrayOf(0x01, 0x02) + direct + wrapped)

        assertEquals(2, frames.size)
        assertFalse(HomePointProtocol.isRecorded(frames[0])!!)
        assertTrue(HomePointProtocol.isRecorded(frames[1])!!)
    }

    @Test
    fun skipsCorruptOuterCandidateAndResynchronizes() {
        val corrupt = Profiles.wrapFrame(homePointFrame(0x0047)).also {
            it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte()
        }
        val valid = Profiles.wrapFrame(homePointFrame(0x0047))
        val frames = WrappedDumlFrameParser().feed(corrupt + valid)

        assertEquals(1, frames.size)
        assertTrue(HomePointProtocol.isRecorded(frames.single())!!)
    }

    @Test
    fun rejectsResponseAndAckRequestWithPushLayoutBytes() {
        assertEquals(null, HomePointProtocol.isRecorded(homePointFrame(0x0047, cmdType = 0x80)))
        assertEquals(null, HomePointProtocol.isRecorded(homePointFrame(0x0047, cmdType = 0xC0)))
        assertEquals(null, HomePointProtocol.isRecorded(homePointFrame(0x0047, cmdType = 0x40)))
        assertTrue(HomePointProtocol.isRecorded(homePointFrame(0x0047, cmdType = 0x00))!!)
    }

    @Test
    fun monitorUsesOneConnectionAndStopsAfterRecordedBit() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverError = AtomicReference<Throwable>()
        val thread = Thread {
            try {
                server.use { listener ->
                    listener.accept().use { socket ->
                        val before = Profiles.wrapFrame(homePointFrame(0x0046))
                        val after = Profiles.wrapFrame(homePointFrame(0x0047))
                        socket.getOutputStream().apply {
                            write(before)
                            write(after, 0, 5)
                            flush()
                            write(after, 5, after.size - 5)
                            flush()
                        }
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
            }
        }
        thread.start()

        val result = HomePointMonitor(port = server.localPort).waitUntilRecorded { true }

        thread.join(2_000)
        serverError.get()?.let { throw AssertionError("server failed", it) }
        assertEquals(HomePointWaitResult.RECORDED, result)
    }

    @Test
    fun passiveMonitorAcceptsDirectTelemetryWithoutWriting() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverError = AtomicReference<Throwable>()
        val clientWriteObserved = AtomicBoolean(false)
        val thread = Thread {
            try {
                server.use { listener ->
                    listener.accept().use { socket ->
                        socket.soTimeout = 200
                        try {
                            clientWriteObserved.set(socket.getInputStream().read() >= 0)
                        } catch (_: SocketTimeoutException) {
                            // Expected: passive monitor never writes a primer.
                        }
                        socket.getOutputStream().apply {
                            write(nonHomePointFrame())
                            write(homePointFrame(0x0047))
                            flush()
                        }
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
            }
        }
        thread.start()

        val result = HomePointMonitor(port = server.localPort).waitUntilRecorded { true }

        thread.join(2_000)
        serverError.get()?.let { throw AssertionError("server failed", it) }
        assertEquals(HomePointWaitResult.RECORDED, result)
        assertFalse(clientWriteObserved.get())
    }

    @Test
    fun directNonHomePointTelemetryThenEofRemainsDisconnected() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            server.use { listener ->
                listener.accept().use { socket ->
                    socket.getOutputStream().apply {
                        write(nonHomePointFrame())
                        flush()
                    }
                }
            }
        }
        thread.start()

        val result = HomePointMonitor(port = server.localPort).waitUntilRecorded { true }

        thread.join(2_000)
        assertEquals(HomePointWaitResult.STREAM_DISCONNECTED, result)
    }

    @Test
    fun passiveMonitorNeverWritesToTelemetrySocket() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverError = AtomicReference<Throwable>()
        val clientWriteObserved = AtomicBoolean(false)
        val thread = Thread {
            try {
                server.use { listener ->
                    listener.accept().use { socket ->
                        socket.soTimeout = 1_200
                        try {
                            clientWriteObserved.set(socket.getInputStream().read() >= 0)
                        } catch (_: SocketTimeoutException) {
                            // Expected: the production listener is read-only.
                        }
                        socket.getOutputStream().apply {
                            write(Profiles.wrapFrame(homePointFrame(0x0047)))
                            flush()
                        }
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
            }
        }
        thread.start()

        val result = HomePointMonitor(port = server.localPort).waitUntilRecorded { true }

        thread.join(2_000)
        assertFalse(thread.isAlive)
        serverError.get()?.let { throw AssertionError("server failed", it) }
        assertEquals(HomePointWaitResult.RECORDED, result)
        assertFalse(clientWriteObserved.get())
    }

    @Test
    fun passiveMonitorSurvivesReadTimeoutOnSameConnection() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverError = AtomicReference<Throwable>()
        val thread = Thread {
            try {
                server.use { listener ->
                    listener.accept().use { socket ->
                        Thread.sleep(400)
                        socket.getOutputStream().apply {
                            write(Profiles.wrapFrame(homePointFrame(0x0047)))
                            flush()
                        }
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
            }
        }
        thread.start()

        val result = HomePointMonitor(port = server.localPort).waitUntilRecorded { true }

        thread.join(2_000)
        assertFalse(thread.isAlive)
        serverError.get()?.let { throw AssertionError("server failed", it) }
        assertEquals(HomePointWaitResult.RECORDED, result)
    }

    @Test
    fun stoppedBeforeMonitorStartDoesNotConnectOrWrite() {
        val result = HomePointMonitor(port = 0).waitUntilRecorded { false }

        assertEquals(HomePointWaitResult.STOPPED, result)
    }

    @Test
    fun stopClosesPassiveSocketWithoutWriting() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val keepRunning = AtomicBoolean(true)
        val bytesAfterStop = AtomicReference<Int>()
        val serverError = AtomicReference<Throwable>()
        val thread = Thread {
            try {
                server.use { listener ->
                    listener.accept().use { socket ->
                        socket.soTimeout = 2_000
                        Thread.sleep(100)
                        bytesAfterStop.set(socket.getInputStream().read(ByteArray(21)))
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
            }
        }
        thread.start()
        Thread {
            Thread.sleep(50)
            keepRunning.set(false)
        }.start()

        val result = HomePointMonitor(port = server.localPort).waitUntilRecorded { keepRunning.get() }

        thread.join(2_000)
        assertFalse(thread.isAlive)
        serverError.get()?.let { throw AssertionError("server failed", it) }
        assertEquals(HomePointWaitResult.STOPPED, result)
        assertEquals(-1, bytesAfterStop.get())
    }

    @Test
    fun initialRecordedSnapshotCompletesBeforeEof() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverError = AtomicReference<Throwable>()
        val thread = Thread {
            try {
                server.use { listener ->
                    listener.accept().use { socket ->
                        socket.getOutputStream().apply {
                            write(Profiles.wrapFrame(homePointFrame(0x0047)))
                            flush()
                        }
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
            }
        }
        thread.start()

        val result = HomePointMonitor(port = server.localPort).waitUntilRecorded { true }

        thread.join(2_000)
        serverError.get()?.let { throw AssertionError("server failed", it) }
        assertEquals(HomePointWaitResult.RECORDED, result)
    }

    @Test
    fun armedGateAcceptsRecordedSnapshotAfterOneStreamReconnect() {
        val gate = HomePointSessionGate()
        val firstServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val firstThread = Thread {
            firstServer.use { listener ->
                listener.accept().use { socket ->
                    socket.getOutputStream().apply {
                        write(Profiles.wrapFrame(homePointFrame(0x0046)))
                        flush()
                    }
                }
            }
        }
        firstThread.start()

        val firstResult = HomePointMonitor(port = firstServer.localPort)
            .waitUntilRecorded(gate) { true }
        firstThread.join(2_000)
        assertEquals(HomePointWaitResult.STREAM_DISCONNECTED, firstResult)
        assertTrue(gate.isArmedForRecordedEdge())

        val secondServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val secondThread = Thread {
            secondServer.use { listener ->
                listener.accept().use { socket ->
                    socket.getOutputStream().apply {
                        write(Profiles.wrapFrame(homePointFrame(0x0047)))
                        flush()
                    }
                }
            }
        }
        secondThread.start()

        val secondResult = HomePointMonitor(port = secondServer.localPort)
            .waitUntilRecorded(gate) { true }
        secondThread.join(2_000)
        assertEquals(HomePointWaitResult.RECORDED, secondResult)
        assertFalse(gate.isArmedForRecordedEdge())
    }

    @Test
    fun eofAfterStopRequestIsCooperativeStop() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            server.use { listener ->
                listener.accept().use { socket ->
                }
            }
        }
        thread.start()
        var continuationChecks = 0

        val result = HomePointMonitor(port = server.localPort)
            .waitUntilRecorded { ++continuationChecks == 1 }

        thread.join(2_000)
        assertEquals(HomePointWaitResult.STOPPED, result)
    }

    @Test
    fun sessionGateSurvivesCooperativeReopenAndAcceptsCurrentTrue() {
        val firstServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val firstThread = Thread {
            firstServer.use { listener ->
                listener.accept().use { socket ->
                    socket.getOutputStream().apply {
                        write(Profiles.wrapFrame(homePointFrame(0x0047)))
                        flush()
                    }
                }
            }
        }
        firstThread.start()
        val gate = HomePointSessionGate()
        var continuationChecks = 0

        val firstResult = HomePointMonitor(port = firstServer.localPort)
            .waitUntilRecorded(gate) { ++continuationChecks == 1 }
        firstThread.join(2_000)
        assertEquals(HomePointWaitResult.STOPPED, firstResult)

        val secondServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val secondThread = Thread {
            secondServer.use { listener ->
                listener.accept().use { socket ->
                    socket.getOutputStream().apply {
                        write(Profiles.wrapFrame(homePointFrame(0x0047)))
                        flush()
                    }
                }
            }
        }
        secondThread.start()

        val secondResult = HomePointMonitor(port = secondServer.localPort)
            .waitUntilRecorded(gate) { true }
        secondThread.join(2_000)
        assertEquals(HomePointWaitResult.RECORDED, secondResult)
    }

    @Test
    fun monitorStopsWithoutOpeningAnotherConnection() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val keepRunning = AtomicBoolean(true)
        val thread = Thread {
            server.use { listener ->
                listener.accept().use { socket ->
                    Thread.sleep(400)
                }
            }
        }
        thread.start()
        Thread {
            Thread.sleep(50)
            keepRunning.set(false)
        }.start()

        val result = HomePointMonitor(port = server.localPort)
            .waitUntilRecorded { keepRunning.get() }

        thread.join(2_000)
        assertEquals(HomePointWaitResult.STOPPED, result)
    }

    private fun homePointFrame(homeState: Int, cmdType: Int = 0x00): ByteArray {
        val payload = ByteArray(102)
        payload[20] = (homeState and 0xFF).toByte()
        payload[21] = ((homeState shr 8) and 0xFF).toByte()
        return DumlBuilder().buildFrame(
            DumlFrame(
                sender = 0x0E,
                cmdType = cmdType,
                cmdSet = 0x03,
                cmdId = 0x44,
                dst = 0x02,
                payload = payload
            )
        )
    }

    private fun nonHomePointFrame(): ByteArray = DumlBuilder().buildFrame(
        DumlFrame(
            sender = 0x0E,
            cmdType = 0x00,
            cmdSet = 0x03,
            cmdId = 0x43,
            dst = 0x02,
            payload = ByteArray(85)
        )
    )

}
