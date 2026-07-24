package com.freefcc.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun persistedAutoModeSupportsBothSwitchesBeingOff() {
        assertNull(AutoFccMode.fromPersistedValue(null))
        assertNull(AutoFccMode.fromPersistedValue("unknown"))
        assertEquals(
            AutoFccMode.HOME_POINT_TEXT,
            AutoFccMode.fromPersistedValue("home_point_text")
        )
        assertEquals(
            AutoFccMode.PERIODIC_5S,
            AutoFccMode.fromPersistedValue("periodic_5s")
        )
    }

    @Test
    fun enablingOneAutoModeReplacesTheOtherAndActiveModeCanBeDisabled() {
        assertEquals(
            AutoFccMode.PERIODIC_5S,
            AutoFccSelection.updatedMode(
                AutoFccMode.HOME_POINT_TEXT,
                AutoFccMode.PERIODIC_5S,
                enabled = true
            )
        )
        assertNull(
            AutoFccSelection.updatedMode(
                AutoFccMode.PERIODIC_5S,
                AutoFccMode.PERIODIC_5S,
                enabled = false
            )
        )
    }

    @Test
    fun homePointSignalsAreDebouncedButRearmForLaterFlightSessions() {
        assertFalse(
            HomePointSignalPolicy.shouldAccept(
                lastGeneration = 7L,
                lastSignalAtMs = 1_000L,
                generation = 7L,
                nowMs = 30_999L,
                debounceMs = 30_000L
            )
        )
        assertTrue(
            HomePointSignalPolicy.shouldAccept(
                lastGeneration = 7L,
                lastSignalAtMs = 1_000L,
                generation = 7L,
                nowMs = 31_000L,
                debounceMs = 30_000L
            )
        )
        assertTrue(
            HomePointSignalPolicy.shouldAccept(
                lastGeneration = 7L,
                lastSignalAtMs = 30_000L,
                generation = 8L,
                nowMs = 30_001L,
                debounceMs = 30_000L
            )
        )
    }

    @Test
    fun repeatedIdenticalCountryTicksShareOneLogState() {
        assertEquals(
            FccKeepaliveService.periodicCountryState(alreadyOnTarget),
            FccKeepaliveService.periodicCountryState(alreadyOnTarget.copy())
        )
    }

    @Test
    fun aTickThatHadToRewriteTheCountryProducesANewLogState() {
        assertNotEquals(
            FccKeepaliveService.periodicCountryState(alreadyOnTarget),
            FccKeepaliveService.periodicCountryState(
                alreadyOnTarget.copy(initialCountry = "RU", writeAttempts = 1)
            )
        )
        assertNotEquals(
            FccKeepaliveService.periodicCountryState(alreadyOnTarget),
            FccKeepaliveService.periodicCountryState(alreadyOnTarget.copy(observedCountry = null))
        )
        assertNotEquals(
            FccKeepaliveService.periodicCountryState(alreadyOnTarget),
            FccKeepaliveService.periodicCountryState(alreadyOnTarget.copy(readAckMatched = false))
        )
    }

    private val alreadyOnTarget = FccCountryRegionResult(
        initialCountry = "AU",
        writeAttempts = 0,
        writeCompleted = false,
        writeAckMatched = false,
        readCompleted = true,
        readAckMatched = true,
        observedCountry = "AU"
    )

}
