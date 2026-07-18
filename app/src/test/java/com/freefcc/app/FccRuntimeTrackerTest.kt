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

        assertNull(tracker.state.value.lastSuccessfulWriteAtMs)
        assertNull(tracker.state.value.lastAttemptSucceeded)
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
}
