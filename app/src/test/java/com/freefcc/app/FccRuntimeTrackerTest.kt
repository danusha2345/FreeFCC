package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FccRuntimeTrackerTest {

    private fun finishAttempt(
        tracker: FccRuntimeTracker,
        success: Boolean,
        finishedAtMs: Long,
        origin: FccApplyOrigin = FccApplyOrigin.MANUAL,
        homePointObservedAtMs: Long? = null
    ) {
        val attempt = tracker.beginApply(
            origin = origin,
            port = 40009,
            expectedWrites = 42,
            homePointObservedAtMs = homePointObservedAtMs,
            startedAtMs = finishedAtMs - 1
        )
        tracker.finishApply(
            startedAtMs = attempt.startedAtMs,
            flushedWrites = if (success) 42 else 17,
            matchingAcks = if (success) 2 else 0,
            outcome = if (success) {
                FccApplyOutcome.ALL_WRITES_FLUSHED
            } else {
                FccApplyOutcome.PARTIAL_WRITE_FAILURE
            },
            finishedAtMs = finishedAtMs
        )
    }

    @Test
    fun newProcessStartsWithUnknownWriteState() {
        val tracker = FccRuntimeTracker()

        assertNull(tracker.state.value.lastSuccessfulWriteAtMs)
        assertNull(tracker.state.value.lastAttemptSucceeded)
        assertFalse(tracker.state.value.controllerSessionEstablished)
        assertEquals(KeepaliveRuntimeStatus.STOPPED, tracker.state.value.keepaliveStatus)
    }

    @Test
    fun failedWriteDoesNotEraseLastSuccessfulWriteEvidence() {
        val tracker = FccRuntimeTracker()
        finishAttempt(tracker, success = true, finishedAtMs = 1234L)
        finishAttempt(tracker, success = false, finishedAtMs = 5678L)

        assertEquals(1234L, tracker.state.value.lastSuccessfulWriteAtMs)
        assertFalse(tracker.state.value.lastAttemptSucceeded!!)
    }

    @Test
    fun newHardwareSessionClearsStaleWriteEvidence() {
        val tracker = FccRuntimeTracker()
        finishAttempt(tracker, success = true, finishedAtMs = 1234L)

        tracker.beginHardwareSession(40009)

        assertTrue(tracker.state.value.controllerSessionEstablished)
        assertEquals(40009, tracker.state.value.controllerPort)
        assertNull(tracker.state.value.lastSuccessfulWriteAtMs)
        assertNull(tracker.state.value.lastAttemptSucceeded)
    }

    @Test
    fun monitorLifecyclePreservesExplicitControllerSession() {
        val tracker = FccRuntimeTracker()
        tracker.beginHardwareSession(40009)

        tracker.serviceStartRequested()
        tracker.serviceRunning()
        tracker.serviceFailed("short passive stream")
        assertTrue(tracker.state.value.controllerSessionEstablished)

        tracker.serviceStopped()
        assertTrue(tracker.state.value.controllerSessionEstablished)
    }

    @Test
    fun clearingWriteEvidenceDoesNotManufactureOrEraseConnection() {
        val freshTracker = FccRuntimeTracker()
        finishAttempt(freshTracker, success = true, finishedAtMs = 1234L)
        freshTracker.clearWriteEvidence()
        assertFalse(freshTracker.state.value.controllerSessionEstablished)
        assertNull(freshTracker.state.value.lastSuccessfulWriteAtMs)

        val connectedTracker = FccRuntimeTracker()
        connectedTracker.beginHardwareSession(40009)
        finishAttempt(connectedTracker, success = true, finishedAtMs = 5678L)
        connectedTracker.clearWriteEvidence()
        assertTrue(connectedTracker.state.value.controllerSessionEstablished)
        assertNull(connectedTracker.state.value.lastSuccessfulWriteAtMs)
    }

    @Test
    fun failedExplicitProbeClearsOnlyControllerSessionEvidence() {
        val tracker = FccRuntimeTracker()
        tracker.beginHardwareSession(40009)
        finishAttempt(tracker, success = true, finishedAtMs = 1234L)

        tracker.controllerSessionLost()

        assertFalse(tracker.state.value.controllerSessionEstablished)
        assertNull(tracker.state.value.controllerPort)
        assertEquals(1234L, tracker.state.value.lastSuccessfulWriteAtMs)
    }

    @Test
    fun manualAttemptDoesNotEraseLastAutomaticAttemptEvidence() {
        val tracker = FccRuntimeTracker()
        finishAttempt(
            tracker,
            success = false,
            finishedAtMs = 2_000L,
            origin = FccApplyOrigin.HOME_POINT_AUTO,
            homePointObservedAtMs = 1_000L
        )
        finishAttempt(tracker, success = true, finishedAtMs = 3_000L)

        val automatic = tracker.state.value.lastAutomaticApplyAttempt!!
        assertEquals(FccApplyOrigin.HOME_POINT_AUTO, automatic.origin)
        assertEquals(1_000L, automatic.homePointObservedAtMs)
        assertEquals(17, automatic.flushedWrites)
        assertEquals(FccApplyOutcome.PARTIAL_WRITE_FAILURE, automatic.outcome)
        assertEquals(FccApplyOrigin.MANUAL, tracker.state.value.lastApplyAttempt!!.origin)
    }

    @Test
    fun allWritesFlushedIsRecordedWithPortAndAckEvidence() {
        val tracker = FccRuntimeTracker()
        tracker.beginHardwareSession(8901)
        val attempt = tracker.beginApply(
            origin = FccApplyOrigin.HOME_POINT_AUTO,
            port = 8901,
            expectedWrites = 42,
            homePointObservedAtMs = 900L,
            startedAtMs = 1_000L
        )
        tracker.finishApply(
            startedAtMs = attempt.startedAtMs,
            flushedWrites = 42,
            matchingAcks = 7,
            outcome = FccApplyOutcome.ALL_WRITES_FLUSHED,
            finishedAtMs = 2_000L
        )

        val finished = tracker.state.value.lastApplyAttempt!!
        assertEquals(8901, finished.port)
        assertEquals(42, finished.flushedWrites)
        assertEquals(7, finished.matchingAcks)
        assertEquals(2_000L, tracker.state.value.lastSuccessfulWriteAtMs)
        assertTrue(tracker.state.value.lastAttemptSucceeded!!)
    }

    @Test
    fun keepaliveStatusTracksActualLifecycle() {
        val tracker = FccRuntimeTracker()

        tracker.serviceStartRequested()
        assertEquals(KeepaliveRuntimeStatus.STARTING, tracker.state.value.keepaliveStatus)
        tracker.serviceRunning()
        assertEquals(KeepaliveRuntimeStatus.RUNNING, tracker.state.value.keepaliveStatus)
        tracker.serviceFailed("broken profile")
        assertEquals(KeepaliveRuntimeStatus.FAILED, tracker.state.value.keepaliveStatus)
        assertEquals("broken profile", tracker.state.value.error)
        tracker.serviceStopped()
        assertEquals(KeepaliveRuntimeStatus.STOPPED, tracker.state.value.keepaliveStatus)
        assertTrue(tracker.state.value.lastSuccessfulWriteAtMs == null)
    }

    @Test
    fun monitorLifecycleNeverManufacturesOrErasesControllerConnection() {
        assertEquals(
            "monitor_failed",
            resolveFccRuntimeStatus("connected", true, KeepaliveRuntimeStatus.FAILED, false)
        )
        assertEquals(
            "disconnected",
            resolveFccRuntimeStatus("disconnected", false, KeepaliveRuntimeStatus.FAILED, false)
        )
        assertEquals(
            "disconnected",
            resolveFccRuntimeStatus("disconnected", false, KeepaliveRuntimeStatus.STARTING, false)
        )
        assertEquals(
            "disconnected",
            resolveFccRuntimeStatus("disconnected", false, KeepaliveRuntimeStatus.RUNNING, false)
        )
    }
}
