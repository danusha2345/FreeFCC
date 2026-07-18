package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FourGIdentityTest {

    @Test
    fun shortModelCodeIsAcceptedWithoutOffByOne() {
        val identity = FccViewModel.parseFourGIdentity("WA341")

        assertEquals("WA341", identity?.payloadSerial)
        assertEquals("wa341", identity?.modelCode)
        assertTrue(FccViewModel.isSupportedFourGModel(identity!!.modelCode!!))
    }

    @Test
    fun capturedStyleIdentityKeepsPayloadSuffix() {
        val identity = FccViewModel.parseFourGIdentity("wa341test")

        assertEquals("WA341TEST", identity?.payloadSerial)
        assertEquals("wa341", identity?.modelCode)
    }

    @Test
    fun fullFactorySerialIsAcceptedWithoutPretendingPrefixIsModel() {
        val serial = "1581ABCDEF012345"
        val identity = FccViewModel.parseFourGIdentity(serial)

        assertEquals(serial, identity?.payloadSerial)
        assertNull(identity?.modelCode)
    }

    @Test
    fun malformedIdentityIsRejected() {
        assertNull(FccViewModel.parseFourGIdentity(""))
        assertNull(FccViewModel.parseFourGIdentity("1581TOO_SHORT"))
        assertNull(FccViewModel.parseFourGIdentity("not-a-dji-identity"))
    }

    @Test
    fun miniModelIsNotInFourGAllowlist() {
        assertFalse(FccViewModel.isSupportedFourGModel("wa140"))
        assertFalse(FccViewModel.isSupportedFourGModel("wa150"))
    }
}
