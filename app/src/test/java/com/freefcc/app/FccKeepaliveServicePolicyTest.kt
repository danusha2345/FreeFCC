package com.freefcc.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FccKeepaliveServicePolicyTest {
    @Test
    fun onlyExplicitStartRequiresForegroundPromotion() {
        assertTrue(FccKeepaliveService.requiresImmediateForeground(FccKeepaliveService.ACTION_START))
        assertFalse(FccKeepaliveService.requiresImmediateForeground(null))
    }

    @Test
    fun ordinaryStopIntentDoesNotCreateANewForegroundObligation() {
        assertFalse(FccKeepaliveService.requiresImmediateForeground(FccKeepaliveService.ACTION_STOP))
    }

    @Test
    fun actionStartDeliversOnlyItsExactEncodedGeneration() {
        assertEquals(
            7L,
            FccKeepaliveService.deliveredStartGeneration(FccKeepaliveService.ACTION_START, 7L)
        )
        assertNull(
            FccKeepaliveService.deliveredStartGeneration(FccKeepaliveService.ACTION_START, -1L)
        )
        assertNull(
            FccKeepaliveService.deliveredStartGeneration(FccKeepaliveService.ACTION_STOP, 7L)
        )
    }

    @Test
    fun autoModesDecodeFailClosedToHomePointText() {
        assertEquals(
            AutoFccMode.HOME_POINT_TEXT,
            AutoFccMode.fromWireValue("home_point_text")
        )
        assertEquals(AutoFccMode.PERIODIC_5S, AutoFccMode.fromWireValue("periodic_5s"))
        assertEquals(AutoFccMode.HOME_POINT_TEXT, AutoFccMode.fromWireValue("unknown"))
        assertEquals(AutoFccMode.HOME_POINT_TEXT, AutoFccMode.fromWireValue(null))
        assertEquals(
            AutoFccMode.PERIODIC_5S,
            FccKeepaliveService.deliveredAutoMode(
                FccKeepaliveService.ACTION_START,
                "periodic_5s"
            )
        )
        assertNull(
            FccKeepaliveService.deliveredAutoMode(
                FccKeepaliveService.ACTION_STOP,
                "periodic_5s"
            )
        )
    }

    @Test
    fun periodicModeUsesFiveSecondTicks() {
        assertEquals(5_000L, FccKeepaliveService.PERIODIC_INTERVAL_MS)
    }

}
