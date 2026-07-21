package com.freefcc.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiFlyHomePointMatcherTest {

    @Test
    fun matchesNormalizedTranslations() {
        val phrases = setOf(
            DjiFlyHomePointMatcher.normalize("Home Point updated"),
            DjiFlyHomePointMatcher.normalize("Домашняя точка обновлена"),
            DjiFlyHomePointMatcher.normalize("返航点已刷新，请留意返航点位置")
        )

        assertTrue(DjiFlyHomePointMatcher.matches("  HOME   POINT updated. ", phrases))
        assertTrue(DjiFlyHomePointMatcher.matches("Домашняя точка обновлена!", phrases))
        assertTrue(DjiFlyHomePointMatcher.matches("返航点已刷新，请留意返航点位置。", phrases))
    }

    @Test
    fun rejectsOtherHomePointMessages() {
        val phrases = setOf(DjiFlyHomePointMatcher.normalize("Home Point updated"))

        assertFalse(DjiFlyHomePointMatcher.matches("Unable to update Home Point", phrases))
        assertFalse(DjiFlyHomePointMatcher.matches("RTH unavailable near Home Point", phrases))
    }
}
