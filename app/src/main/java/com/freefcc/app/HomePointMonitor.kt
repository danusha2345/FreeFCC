package com.freefcc.app

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

internal enum class HomePointWaitResult {
    RECORDED,
    CONNECT_FAILED,
    STREAM_DISCONNECTED,
    STOPPED
}

/** Accepts current recorded state; an observed false only arms safe recovery. */
internal class HomePointSessionGate {
    private var armed = false
    private var triggered = false

    @Synchronized
    fun observe(recorded: Boolean): Boolean {
        if (triggered) return false
        if (!recorded) {
            armed = true
            return false
        }
        triggered = true
        return true
    }

    @Synchronized
    fun isArmedForRecordedEdge(): Boolean = armed && !triggered
}

internal object HomePointProtocol {
    fun isRecorded(frame: ByteArray): Boolean? {
        if (frame.size < 35 || frame[0] != 0x55.toByte()) return null
        val length = (frame[1].toInt() and 0xFF) or
            ((frame[2].toInt() and 0x03) shl 8)
        if (length != frame.size) return null
        if (DumlBuilder.crc8(frame, 0, 3) != (frame[3].toInt() and 0xFF)) return null
        val expectedCrc = DumlBuilder.crc16(frame, 0, frame.size - 2)
        val actualCrc = (frame[frame.lastIndex - 1].toInt() and 0xFF) or
            ((frame[frame.lastIndex].toInt() and 0xFF) shl 8)
        if (expectedCrc != actualCrc) return null

        val senderType = frame[4].toInt() and 0x1F
        val destinationType = frame[5].toInt() and 0x1F
        if (senderType !in setOf(0x03, 0x0E) || destinationType != 0x02) return null
        // The live-confirmed Avata/O4 Home Point layout is an unencrypted
        // passive telemetry push (cmdType=0x00). The production listener sends
        // no request frames and parses only this fixed push layout.
        if (frame[8] != 0x00.toByte()) return null
        if (frame[9] != 0x03.toByte() || frame[10] != 0x44.toByte()) return null

        val payloadLength = frame.size - 13
        if (payloadLength < 22) return null
        val homeState = (frame[31].toInt() and 0xFF) or
            ((frame[32].toInt() and 0xFF) shl 8)
        return (homeState and 0x01) != 0
    }
}

/** Parses CRC-valid DUML from either a direct stream or the 40007 envelope. */
internal class WrappedDumlFrameParser {
    private var pending = ByteArray(0)

    fun feed(bytes: ByteArray, length: Int = bytes.size): List<ByteArray> {
        if (length <= 0) return emptyList()
        val combined = ByteArray(pending.size + length)
        pending.copyInto(combined)
        bytes.copyInto(combined, pending.size, 0, length)
        pending = combined

        val frames = mutableListOf<ByteArray>()
        while (pending.size >= 4) {
            val marker = pending.indexOfFirst { it == 0x55.toByte() }
            if (marker < 0) {
                pending = ByteArray(0)
                break
            }
            if (marker > 0) pending = pending.copyOfRange(marker, pending.size)

            if (hasOuterMagic(pending)) {
                if (pending.size < 8) break
                val innerLength = (pending[4].toInt() and 0xFF) or
                    ((pending[5].toInt() and 0xFF) shl 8) or
                    ((pending[6].toInt() and 0xFF) shl 16) or
                    ((pending[7].toInt() and 0xFF) shl 24)
                if (innerLength !in 13..1023) {
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }
                val outerLength = 8 + innerLength
                if (pending.size < outerLength) break
                val candidate = pending.copyOfRange(8, outerLength)
                if (!isValidDuml(candidate)) {
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }
                frames += candidate
                pending = pending.copyOfRange(outerLength, pending.size)
                continue
            }

            val directLength = (pending[1].toInt() and 0xFF) or
                ((pending[2].toInt() and 0x03) shl 8)
            if (directLength !in 13..1023) {
                pending = pending.copyOfRange(1, pending.size)
                continue
            }
            if (pending.size < directLength) break
            val candidate = pending.copyOfRange(0, directLength)
            if (!isValidDuml(candidate)) {
                pending = pending.copyOfRange(1, pending.size)
                continue
            }
            frames += candidate
            pending = pending.copyOfRange(directLength, pending.size)
        }
        return frames
    }

    private fun hasOuterMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x55.toByte() &&
            bytes[1] == 0xCC.toByte() &&
            bytes[2] == 0x30.toByte() &&
            bytes[3] == 0x75.toByte()

    private fun isValidDuml(frame: ByteArray): Boolean {
        if (frame.size < 13 || frame[0] != 0x55.toByte()) return false
        val encodedLength = (frame[1].toInt() and 0xFF) or
            ((frame[2].toInt() and 0x03) shl 8)
        if (encodedLength != frame.size) return false
        if (DumlBuilder.crc8(frame, 0, 3) != (frame[3].toInt() and 0xFF)) return false
        val expectedCrc = DumlBuilder.crc16(frame, 0, frame.size - 2)
        val actualCrc = (frame[frame.lastIndex - 1].toInt() and 0xFF) or
            ((frame[frame.lastIndex].toInt() and 0xFF) shl 8)
        return expectedCrc == actualCrc
    }
}

/** One connection that remains open only until Home Point is observed. */
internal class HomePointMonitor(
    private val host: String = "127.0.0.1",
    private val port: Int = DumlTransport.PORT_LED
) {
    fun waitUntilRecorded(
        sessionGate: HomePointSessionGate = HomePointSessionGate(),
        shouldContinue: () -> Boolean
    ): HomePointWaitResult {
        if (!shouldContinue()) return HomePointWaitResult.STOPPED
        var socket: Socket? = null
        var connected = false
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), 2_000)
            connected = true
            socket.tcpNoDelay = true
            socket.soTimeout = 250

            val parser = WrappedDumlFrameParser()
            val buffer = ByteArray(4_096)
            while (shouldContinue()) {
                val count = try {
                    socket.getInputStream().read(buffer)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (count <= 0) {
                    return if (shouldContinue()) {
                        HomePointWaitResult.STREAM_DISCONNECTED
                    } else {
                        HomePointWaitResult.STOPPED
                    }
                }
                for (frame in parser.feed(buffer, count)) {
                    val recorded = HomePointProtocol.isRecorded(frame) ?: continue
                    if (sessionGate.observe(recorded)) {
                        return HomePointWaitResult.RECORDED
                    }
                }
            }
            HomePointWaitResult.STOPPED
        } catch (_: IOException) {
            if (!shouldContinue()) {
                HomePointWaitResult.STOPPED
            } else if (connected) {
                HomePointWaitResult.STREAM_DISCONNECTED
            } else {
                HomePointWaitResult.CONNECT_FAILED
            }
        } finally {
            try { socket?.close() } catch (_: IOException) {}
        }
    }
}
