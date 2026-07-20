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
    fun automaticApplyWaitsForPostHomePointRegionSettle() {
        assertEquals(2_000L, FccKeepaliveService.POST_HOME_POINT_SETTLE_DELAY_MS)
    }
}
