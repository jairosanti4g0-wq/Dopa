package com.dopa.cleaner.config

import com.dopa.cleaner.core.NsfwScores
import com.dopa.cleaner.core.RiskCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanOptionsTest {
    @Test
    fun exclusionMatchesSubstringIgnoringCase() {
        val o = ScanOptions(excludedPaths = listOf("whatsapp/media", "  "))
        assertTrue(o.isExcluded("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/x.jpg"))
        assertFalse(o.isExcluded("/storage/emulated/0/DCIM/Camera/x.jpg"))
    }

    @Test
    fun riskRespectsCategories() {
        val s = NsfwScores(drawings = 0f, hentai = 0.2f, neutral = 0.2f, porn = 0.3f, sexy = 0.3f)
        assertEquals(0.8f, s.risk(RiskCategories()), 1e-5f)
        assertEquals(0.3f, s.risk(RiskCategories(porn = true, hentai = false, sexy = false)), 1e-5f)
        assertEquals(0.5f, s.risk(RiskCategories(porn = true, hentai = true, sexy = false)), 1e-5f)
        assertFalse(RiskCategories(false, false, false).any)
    }
}
