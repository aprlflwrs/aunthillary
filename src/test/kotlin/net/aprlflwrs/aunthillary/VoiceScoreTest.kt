package net.aprlflwrs.aunthillary

import net.aprlflwrs.aunthillary.core.Voice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceScoreTest {
    /** A voice whose points run east from (0, 0) every 2 units, then turn by [turn] radians after [trunk] points. */
    private fun voice(id: Int, trunk: Int, turn: Double, total: Int = 20): Voice {
        val pts = mutableListOf<DoubleArray>()
        var x = 0.0; var y = 0.0
        for (k in 0 until total) {
            pts += doubleArrayOf(x, y, 0.0, 1.0)
            val h = if (k + 1 >= trunk) turn else 0.0
            x += 2 * StrictMath.cos(h); y += 2 * StrictMath.sin(h)
        }
        return Voice(id, 0, id, 0, 1.0, pts)
    }

    @Test
    fun `the shared opening is the length of the matching prefix`() {
        // Both run east for 10 points (18 units between the first and the tenth), then split.
        val a = voice(1, trunk = 10, turn = 1.0)
        val b = voice(2, trunk = 10, turn = -1.0)
        assertEquals(18.0, VoiceScore.shared(a, b), 1e-9)
        assertEquals(0.0, VoiceScore.shared(a, voice(3, trunk = 0, turn = 2.0)), 1e-9)
    }

    @Test
    fun `the third number is undefined, not zero, without two voices at once`() {
        val s = VoiceScore()
        s.add(emptyList())
        s.add(listOf(voice(1, 5, 1.0)))
        assertEquals(0.5, s.shareOne)
        assertEquals(0.0, s.shareTwo)
        assertNull(s.meanShared)
        s.add(listOf(voice(1, 10, 1.0), voice(2, 10, -1.0)))
        assertEquals(18.0, s.meanShared!!, 1e-9)
    }
}
