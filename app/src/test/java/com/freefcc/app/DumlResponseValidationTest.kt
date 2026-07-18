package com.freefcc.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers DumlBuilder.validateResponse() against the checklist Codex required in
 * plan review: valid response, CRC-8, CRC-16, length, sequence, routing, command
 * set/ID, and the response bit — each isolated so only that one check fails.
 *
 * Wire layout: [4] sender, [5] dst, [6-7] seq, [8] cmdType, [9] cmdSet, [10] cmdId
 * A response reverses [4]<->[5] (sender<->receiver) and sets bit 7 of [8].
 */
class DumlResponseValidationTest {

    @Test
    fun `builder uses receiver at byte 5 and command type at byte 8`() {
        val frame = DumlBuilder().buildFrame(
            DumlFrame(
                sender = 0x82,
                dst = 0x06,
                cmdType = 0x20,
                cmdSet = 0x06,
                cmdId = 0x72,
                payload = byteArrayOf(0x01, 0x02)
            )
        )

        assertEquals(0x82, frame[4].toInt() and 0xFF)
        assertEquals(0x06, frame[5].toInt() and 0xFF)
        assertEquals(0x20, frame[8].toInt() and 0xFF)
        assertEquals(0x06, frame[9].toInt() and 0xFF)
        assertEquals(0x72, frame[10].toInt() and 0xFF)
        assertEquals(DumlBuilder.crc8(frame, 0, 3), frame[3].toInt() and 0xFF)

        val expectedCrc = DumlBuilder.crc16(frame, 0, frame.size - 2)
        val actualCrc = (frame[frame.size - 2].toInt() and 0xFF) or
            ((frame[frame.size - 1].toInt() and 0xFF) shl 8)
        assertEquals(expectedCrc, actualCrc)
    }

    private fun buildRawFrame(
        sender: Int,
        dst: Int,
        seq: Int,
        cmdType: Int,
        cmdSet: Int,
        cmdId: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLength = payload.size + 13
        val out = ByteArray(totalLength)
        out[0] = 0x55
        out[1] = (totalLength and 0xFF).toByte()
        out[2] = (((totalLength shr 8) and 0x03) or 0x04).toByte()
        out[3] = DumlBuilder.crc8(out, 0, 3).toByte()
        out[4] = sender.toByte()
        out[5] = dst.toByte()
        out[6] = (seq and 0xFF).toByte()
        out[7] = ((seq shr 8) and 0xFF).toByte()
        out[8] = cmdType.toByte()
        out[9] = cmdSet.toByte()
        out[10] = cmdId.toByte()
        System.arraycopy(payload, 0, out, 11, payload.size)
        val crc = DumlBuilder.crc16(out, 0, 11 + payload.size)
        out[totalLength - 2] = (crc and 0xFF).toByte()
        out[totalLength - 1] = ((crc shr 8) and 0xFF).toByte()
        return out
    }

    private val request = buildRawFrame(
        sender = 0x01, dst = 0x28, seq = 1234,
        cmdType = 0x00, cmdSet = 0x02, cmdId = 0x03,
        payload = byteArrayOf(1, 2, 3)
    )

    private fun validResponse(payload: ByteArray = byteArrayOf(9, 8, 7, 6)) = buildRawFrame(
        sender = 0x28, dst = 0x01, seq = 1234,
        cmdType = 0x80, cmdSet = 0x02, cmdId = 0x03,
        payload = payload
    )

    @Test
    fun `valid response yields payload`() {
        val payload = byteArrayOf(9, 8, 7, 6)
        val response = validResponse(payload)
        assertArrayEquals(payload, DumlBuilder.validateResponse(request, response))
    }

    @Test
    fun `crc8 mismatch is rejected`() {
        val response = validResponse()
        response[3] = (response[3] + 1).toByte()
        assertNull(DumlBuilder.validateResponse(request, response))
    }

    @Test
    fun `crc16 mismatch is rejected`() {
        val response = validResponse()
        response[response.size - 1] = (response[response.size - 1] + 1).toByte()
        assertNull(DumlBuilder.validateResponse(request, response))
    }

    @Test
    fun `length mismatch is rejected`() {
        val response = validResponse() + byteArrayOf(0x00)
        assertNull(DumlBuilder.validateResponse(request, response))
    }

    @Test
    fun `sequence mismatch is rejected`() {
        val response = buildRawFrame(
            sender = 0x28, dst = 0x01, seq = 9999,
            cmdType = 0x80, cmdSet = 0x02, cmdId = 0x03,
            payload = byteArrayOf(9, 8, 7, 6)
        )
        assertNull(DumlBuilder.validateResponse(request, response))
    }

    @Test
    fun `routing mismatch is rejected`() {
        val response = buildRawFrame(
            sender = 0x01, dst = 0x28, seq = 1234, // not reversed
            cmdType = 0x80, cmdSet = 0x02, cmdId = 0x03,
            payload = byteArrayOf(9, 8, 7, 6)
        )
        assertNull(DumlBuilder.validateResponse(request, response))
    }

    @Test
    fun `command set mismatch is rejected`() {
        val response = buildRawFrame(
            sender = 0x28, dst = 0x01, seq = 1234,
            cmdType = 0x80, cmdSet = 0x99, cmdId = 0x03,
            payload = byteArrayOf(9, 8, 7, 6)
        )
        assertNull(DumlBuilder.validateResponse(request, response))
    }

    @Test
    fun `command id mismatch is rejected`() {
        val response = buildRawFrame(
            sender = 0x28, dst = 0x01, seq = 1234,
            cmdType = 0x80, cmdSet = 0x02, cmdId = 0x99,
            payload = byteArrayOf(9, 8, 7, 6)
        )
        assertNull(DumlBuilder.validateResponse(request, response))
    }

    @Test
    fun `missing response bit is rejected`() {
        val response = buildRawFrame(
            sender = 0x28, dst = 0x01, seq = 1234,
            cmdType = 0x00, cmdSet = 0x02, cmdId = 0x03, // response bit not set
            payload = byteArrayOf(9, 8, 7, 6)
        )
        assertNull(DumlBuilder.validateResponse(request, response))
    }
}
