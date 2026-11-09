package com.dopa.cleaner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun unsafeSumsExplicitClasses() {
        val s = NsfwScores.fromArray(floatArrayOf(0.05f, 0.10f, 0.15f, 0.50f, 0.20f))
        assertEquals(0.80f, s.unsafe, 1e-5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongArity() {
        NsfwScores.fromArray(floatArrayOf(0.1f, 0.2f))
    }

    @Test
    fun maxOfPicksHighestRisk() {
        val low = NsfwScores(0f, 0f, 0.9f, 0.05f, 0.05f)
        val high = NsfwScores(0f, 0.1f, 0.1f, 0.7f, 0.1f)
        assertEquals(high, NsfwScores.maxOf(listOf(low, high)))
        assertNull(NsfwScores.maxOf(emptyList()))
    }

    @Test
    fun mediaTypesByExtension() {
        assertEquals("image/jpeg", MediaTypes.fromName("IMG_001.JPG"))
        assertEquals("video/mp4", MediaTypes.fromName("clip.mp4"))
        assertNull(MediaTypes.fromName("notes.txt"))
        assertNull(MediaTypes.fromName("noext"))
        assertTrue(MediaTypes.isSupported("image/png"))
        assertFalse(MediaTypes.isSupported("image/svg+xml"))
        assertFalse(MediaTypes.isSupported(null))
    }

    @Test
    fun refKeyAndVideoFlag() {
        val r = MediaRef("local", "/sdcard/a.mp4", "a.mp4", "video/mp4", 10, "x")
        assertEquals("local|/sdcard/a.mp4", r.key)
        assertTrue(r.isVideo)
    }
}
