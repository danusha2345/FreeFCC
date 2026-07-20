package com.freefcc.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DumlPortSessionLockTest {
    @Test
    fun samePortIsExclusiveButDifferentPortsAreIndependent() {
        val first = DumlPortSessionLock.tryBegin(40_009)
        assertNotNull(first)
        assertNull(DumlPortSessionLock.tryBegin(40_009))

        val otherPort = DumlPortSessionLock.tryBegin(40_007)
        assertNotNull(otherPort)

        first!!.close()
        val replacement = DumlPortSessionLock.tryBegin(40_009)
        assertNotNull(replacement)

        replacement!!.close()
        otherPort!!.close()
    }

    @Test
    fun leaseCloseIsIdempotent() {
        val lease = DumlPortSessionLock.tryBegin(8_901)
        assertNotNull(lease)

        lease!!.close()
        lease.close()

        DumlPortSessionLock.tryBegin(8_901)!!.close()
    }
}
