package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FccRuntimeTrackerTest {

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
        tracker.recordWrite(success = true, timestampMs = 1234L)
        tracker.recordWrite(success = false, timestampMs = 5678L)

        assertEquals(1234L, tracker.state.value.lastSuccessfulWriteAtMs)
        assertFalse(tracker.state.value.lastAttemptSucceeded!!)
    }

    @Test
    fun newHardwareSessionClearsStaleWriteEvidence() {
        val tracker = FccRuntimeTracker()
        tracker.recordWrite(success = true, timestampMs = 1234L)

        tracker.beginHardwareSession()

        assertTrue(tracker.state.value.controllerSessionEstablished)
        assertNull(tracker.state.value.lastSuccessfulWriteAtMs)
        assertNull(tracker.state.value.lastAttemptSucceeded)
    }

    @Test
    fun monitorLifecyclePreservesExplicitControllerSession() {
        val tracker = FccRuntimeTracker()
        tracker.beginHardwareSession()

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
        freshTracker.recordWrite(success = true, timestampMs = 1234L)
        freshTracker.clearWriteEvidence()
        assertFalse(freshTracker.state.value.controllerSessionEstablished)
        assertNull(freshTracker.state.value.lastSuccessfulWriteAtMs)

        val connectedTracker = FccRuntimeTracker()
        connectedTracker.beginHardwareSession()
        connectedTracker.recordWrite(success = true, timestampMs = 5678L)
        connectedTracker.clearWriteEvidence()
        assertTrue(connectedTracker.state.value.controllerSessionEstablished)
        assertNull(connectedTracker.state.value.lastSuccessfulWriteAtMs)
    }

    @Test
    fun failedExplicitProbeClearsOnlyControllerSessionEvidence() {
        val tracker = FccRuntimeTracker()
        tracker.beginHardwareSession()
        tracker.recordWrite(success = true, timestampMs = 1234L)

        tracker.controllerSessionLost()

        assertFalse(tracker.state.value.controllerSessionEstablished)
        assertEquals(1234L, tracker.state.value.lastSuccessfulWriteAtMs)
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
