package net.aprlflwrs.aunthillary

import net.aprlflwrs.aunthillary.core.HeadingDistribution
import net.aprlflwrs.aunthillary.core.SymbolTracker
import kotlin.math.PI
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SymbolTrackerTest {
    private val n = HeadingDistribution.BINS
    private val even = 1.0 / n

    /** An even spread with [extra] added to the given bins, renormalized to sum to 1. */
    private fun shares(vararg extra: Pair<Int, Double>): DoubleArray {
        val s = DoubleArray(n) { even }
        for ((bin, e) in extra) s[bin] += e
        val total = s.sum()
        return DoubleArray(n) { s[it] / total }
    }

    @Test
    fun `a peak appears only above the appear level and lasts until below the disappear level`() {
        val t = SymbolTracker()
        // Between the two levels: not yet a symbol.
        t.update(shares(4 to 0.03), 0, appearRatio = 1.6, disappearRatio = 1.25)
        assertEquals(0, t.symbols.size)
        // Above the appear level: a symbol.
        t.update(shares(4 to 0.08), 15, 1.6, 1.25)
        assertEquals(1, t.symbols.size)
        val id = t.symbols[0].id
        // Back between the levels: it stays (no flicker), with the same id.
        t.update(shares(4 to 0.03), 30, 1.6, 1.25)
        assertEquals(listOf(id), t.symbols.map { it.id })
        // Below the disappear level: gone.
        t.update(shares(4 to 0.005), 45, 1.6, 1.25)
        assertEquals(0, t.symbols.size)
    }

    @Test
    fun `a drifting peak keeps its id, and two peaks get two ids`() {
        val t = SymbolTracker()
        t.update(shares(3 to 0.1, 10 to 0.1), 0, 1.6, 1.25)
        val ids = t.symbols.map { it.id }.toSet()
        assertEquals(2, ids.size)
        // Each peak spreads onto its neighbor, then moves onto it.
        t.update(shares(3 to 0.06, 4 to 0.06, 10 to 0.06, 11 to 0.06), 15, 1.6, 1.25)
        t.update(shares(4 to 0.1, 11 to 0.1), 30, 1.6, 1.25)
        assertEquals(ids, t.symbols.map { it.id }.toSet())
        val byDirection = t.symbols.sortedBy { it.direction }
        assertEquals(HeadingDistribution.binCenter(4), byDirection[0].direction, 1e-9)
        assertEquals(HeadingDistribution.binCenter(11), byDirection[1].direction, 1e-9)
    }

    @Test
    fun `a peak that wraps past east is one symbol`() {
        val t = SymbolTracker()
        t.update(shares(15 to 0.08, 0 to 0.08), 0, 1.6, 1.25)
        assertEquals(1, t.symbols.size)
        val d = t.symbols[0].direction
        // Halfway between bin 15 and bin 0, that is just below east.
        assertEquals(2 * PI - PI / n, d, 1e-9)
    }

    @Test
    fun `successes build a peak, empty-handed returns and fading wear it down`() {
        val h = HeadingDistribution()
        val bin = 5
        repeat(500) { h.record(HeadingDistribution.binCenter(bin), 1.0) }
        val before = h.shares(randomShare = 0.3, prior = 200.0)
        assertTrue(before[bin] > 3 * even)
        assertEquals(1.0, before.sum(), 1e-9)
        repeat(300) { h.record(HeadingDistribution.binCenter(bin), -1.0) }
        val after = h.shares(0.3, 200.0)
        assertTrue(after[bin] < before[bin])
        h.fade(0.01)
        assertTrue(h.shares(0.3, 200.0)[bin] < 1.2 * even)

        // Newborns follow the distribution: most of the non-random share lands in the peak's bin.
        val g = HeadingDistribution()
        repeat(1000) { g.record(HeadingDistribution.binCenter(bin), 1.0) }
        val rng = Random(1)
        val draws = List(10_000) { g.sample(rng, 0.3, 0.0) }
        val inBin = draws.count { angleBetween(it, HeadingDistribution.binCenter(bin)) <= HeadingDistribution.BIN_WIDTH / 2 }
        assertEquals(0.7 + 0.3 / n, inBin / 10_000.0, 0.02)
    }
}
