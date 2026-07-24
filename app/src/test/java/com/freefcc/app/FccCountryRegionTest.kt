package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FccCountryRegionTest {

    @Test
    fun `a country that already matches the target sends no write`() {
        val requests = mutableListOf<ByteArray>()
        val result = FccCountryRegion.executeEnsure { frame, _ ->
            requests += frame
            exchange(payload = byteArrayOf(0x00, 0x41, 0x55, 0x00))
        }

        assertEquals(1, requests.size)
        assertEquals(0x07, requests[0][9].toInt() and 0xFF)
        assertEquals(0x19, requests[0][10].toInt() and 0xFF)
        assertEquals(13, requests[0].size)
        assertEquals("AU", result.initialCountry)
        assertEquals(0, result.writeAttempts)
        assertTrue(result.skippedWrite)
        assertTrue(result.verified)
    }

    @Test
    fun `any other country triggers one write and one verifying readback`() {
        val requests = mutableListOf<ByteArray>()
        val result = FccCountryRegion.executeEnsure { frame, _ ->
            requests += frame
            when (requests.size) {
                1 -> exchange(payload = byteArrayOf(0x00, 0x43, 0x4E, 0x00))
                2 -> exchange(payload = byteArrayOf(0x00, 0x01))
                else -> exchange(payload = byteArrayOf(0x00, 0x41, 0x55, 0x00))
            }
        }

        assertEquals(3, requests.size)
        assertEquals(0x30, requests[1][10].toInt() and 0xFF)
        assertEquals(0x41, requests[1][11].toInt() and 0xFF)
        assertEquals(0x55, requests[1][12].toInt() and 0xFF)
        assertEquals(0x19, requests[2][10].toInt() and 0xFF)
        assertEquals("CN", result.initialCountry)
        assertEquals(1, result.writeAttempts)
        assertEquals("AU", result.observedCountry)
        assertTrue(result.verified)
    }

    @Test
    fun `an unreadable country is treated as a mismatch and written`() {
        var exchanges = 0
        val result = FccCountryRegion.executeEnsure { _, _ ->
            exchanges++
            when (exchanges) {
                1 -> exchange(payload = null, matched = false)
                2 -> exchange(payload = byteArrayOf(0x00, 0x01))
                else -> exchange(payload = byteArrayOf(0x00, 0x41, 0x55, 0x00))
            }
        }

        assertNull(result.initialCountry)
        assertEquals(1, result.writeAttempts)
        assertTrue(result.verified)
    }

    @Test
    fun `an unverified readback repeats the country write`() {
        var exchanges = 0
        val result = FccCountryRegion.executeEnsure { _, _ ->
            exchanges++
            when (exchanges) {
                1, 3 -> exchange(payload = byteArrayOf(0x00, 0x52, 0x55, 0x00))
                2, 4 -> exchange(payload = byteArrayOf(0x00, 0x01))
                else -> exchange(payload = byteArrayOf(0x00, 0x41, 0x55, 0x00))
            }
        }

        assertEquals("RU", result.initialCountry)
        assertEquals(2, result.writeAttempts)
        assertEquals("AU", result.observedCountry)
        assertTrue(result.verified)
    }

    @Test
    fun `a country that never changes stops after the attempt limit`() {
        var exchanges = 0
        val result = FccCountryRegion.executeEnsure { _, _ ->
            exchanges++
            if (exchanges % 2 == 0) {
                exchange(payload = byteArrayOf(0x00, 0x01))
            } else {
                exchange(payload = byteArrayOf(0x00, 0x52, 0x55, 0x00))
            }
        }

        assertEquals(FccCountryRegion.MAX_ATTEMPTS, result.writeAttempts)
        assertEquals(1 + 2 * FccCountryRegion.MAX_ATTEMPTS, exchanges)
        assertEquals("RU", result.observedCountry)
        assertFalse(result.verified)
    }

    @Test
    fun `a cancelled run does not start another country write`() {
        var exchanges = 0
        val result = FccCountryRegion.executeEnsure(shouldContinue = { false }) { _, _ ->
            exchanges++
            if (exchanges % 2 == 0) {
                exchange(payload = byteArrayOf(0x00, 0x01))
            } else {
                exchange(payload = byteArrayOf(0x00, 0x52, 0x55, 0x00))
            }
        }

        assertEquals(1, result.writeAttempts)
        assertEquals(3, exchanges)
        assertFalse(result.verified)
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
