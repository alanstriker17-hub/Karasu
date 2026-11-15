package eu.kanade.tachiyomi.util.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebtoonGapTrimTest {

    @Test
    fun `finds internal flat bands regardless of background shade`() {
        val shades = IntArray(18) { 40 }
        val noise = IntArray(18) { 10 }
        for (row in 4..6) {
            shades[row] = 250
            noise[row] = 0
        }
        for (row in 11..13) {
            shades[row] = 0
            noise[row] = 0
        }

        assertEquals(
            listOf(4..6, 11..13),
            findWebtoonGapBands(shades, noise, lineLength = 100),
        )
    }

    @Test
    fun `ignores outer bands and oversized flat areas`() {
        val shades = IntArray(70) { 40 }
        val noise = IntArray(70) { 10 }
        for (row in 0..3) noise[row] = 0
        for (row in 5..65) noise[row] = 0
        for (row in 67..69) noise[row] = 0

        assertTrue(findWebtoonGapBands(shades, noise, lineLength = 100).isEmpty())
    }
}
