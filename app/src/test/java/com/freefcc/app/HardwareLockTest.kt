package com.freefcc.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Covers HardwareLock's mutual exclusion — the guarantee S-006 relies on to stop
 * FccKeepaliveService writing DUML frames while FccViewModel holds the lock (or
 * vice versa). The held lock is only released after the concurrent tryBegin()
 * attempt has actually run, via CountDownLatch, so "exactly one wins" can't pass
 * by accident from a lock that was already free by the time the second call ran.
 */
class HardwareLockTest {

    @Test
    fun secondTryBeginFailsWhileFirstHoldsTheLock() {
        val firstLease = HardwareLock.tryBegin()
        try {
            assertNotNull("lock must be free at test start", firstLease)
            assertTrue(HardwareLock.busy.value)

            val secondAttempted = CountDownLatch(1)
            val secondResult = AtomicBoolean(false)

            val secondThread = Thread {
                secondResult.set(HardwareLock.tryBegin() != null)
                secondAttempted.countDown()
            }
            secondThread.start()

            assertTrue(
                "second thread's tryBegin() never ran",
                secondAttempted.await(5, TimeUnit.SECONDS)
            )
            secondThread.join(5000)

            assertFalse("second tryBegin() must fail while the first op still holds the lock", secondResult.get())
        } finally {
            // Only release what this test actually acquired — never unlock on behalf
            // of another op, and never leak the lock into later tests on assertion failure.
            firstLease?.close()
        }
        assertFalse(HardwareLock.busy.value)

        val reacquired = HardwareLock.tryBegin()
        try {
            assertNotNull("lock must be free again after close()", reacquired)
        } finally {
            reacquired?.close()
        }
    }

    @Test
    fun closingOldLeaseAgainCannotUnlockNewOwner() {
        val firstLease = HardwareLock.tryBegin()
        assertNotNull(firstLease)
        firstLease!!.close()

        val secondLease = HardwareLock.tryBegin()
        try {
            assertNotNull(secondLease)
            firstLease.close()
            assertNull("old lease must not unlock the current owner", HardwareLock.tryBegin())
            assertTrue(HardwareLock.busy.value)
        } finally {
            secondLease?.close()
        }
        assertFalse(HardwareLock.busy.value)
    }
}
