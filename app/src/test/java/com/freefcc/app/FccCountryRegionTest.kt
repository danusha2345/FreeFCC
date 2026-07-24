package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FccCountryRegionTest {

    @Test
    fun `execute sends one country write then one readback`() {
        val requests = mutableListOf<ByteArray>()
        val result = FccCountryRegion.execute { frame, _ ->
            requests += frame
            if (requests.size == 1) {
                exchange(payload = byteArrayOf(0x00, 0x01))
            } else {
                exchange(payload = byteArrayOf(0x00, 0x41, 0x55, 0x00))
            }
        }

        assertEquals(2, requests.size)
        assertEquals(0x07, requests[0][9].toInt() and 0xFF)
        assertEquals(0x30, requests[0][10].toInt() and 0xFF)
        assertEquals(0x41, requests[0][11].toInt() and 0xFF)
        assertEquals(0x55, requests[0][12].toInt() and 0xFF)
        assertEquals(0x07, requests[1][9].toInt() and 0xFF)
        assertEquals(0x19, requests[1][10].toInt() and 0xFF)
        assertEquals(13, requests[1].size)
        assertEquals("AU", result.observedCountry)
        assertTrue(result.verified)
    }

    @Test
    fun `readback parser accepts verified response format and raw alpha2`() {
        assertEquals(
            "AU",
            FccCountryRegion.parseReadback(byteArrayOf(0x00, 0x41, 0x55, 0x00))
        )
        assertEquals(
            "RU",
            FccCountryRegion.parseReadback(byteArrayOf(0x52, 0x55))
        )
    }

    @Test
    fun `readback parser rejects errors and malformed country`() {
        assertNull(FccCountryRegion.parseReadback(null))
        assertNull(FccCountryRegion.parseReadback(byteArrayOf(0x00)))
        assertNull(FccCountryRegion.parseReadback(byteArrayOf(0x00, 0x41, 0x31, 0x00)))
    }

    @Test
    fun `verification reflects observed country even when write ack is absent`() {
        var call = 0
        val result = FccCountryRegion.execute { _, _ ->
            call++
            if (call == 1) {
                exchange(payload = null, matched = false)
            } else {
                exchange(payload = byteArrayOf(0x00, 0x52, 0x55, 0x00))
            }
        }

        assertFalse(result.writeAckMatched)
        assertEquals("RU", result.observedCountry)
        assertFalse(result.verified)
    }

    private fun exchange(
        payload: ByteArray?,
        matched: Boolean = payload != null
    ): DumlRawExchange = DumlRawExchange(
        matchedFrame = if (matched) byteArrayOf(0x55) else null,
        validatedPayload = payload,
        lastCompleteUnmatchedFrame = null,
        partialTail = null,
        writeCompleted = true
    )
}
