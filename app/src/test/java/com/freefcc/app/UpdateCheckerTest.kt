package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun releaseBodyRestoresLineBreaksAndHidesDigestMetadata() {
        val raw = "- First change\\n- Second change\\n\\nAPK SHA-256: deadbeef\\nSigning certificate SHA-256: cafe"

        val normalized = UpdateChecker.normalizeReleaseBody(raw)

        assertEquals("- First change\n- Second change", normalized)
        assertFalse(normalized.contains("SHA-256"))
    }
}
