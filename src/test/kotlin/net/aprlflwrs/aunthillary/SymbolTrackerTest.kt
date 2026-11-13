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
    fun `successes build a peak and empty-handed returns wear it down`() {
        val h = HeadingDistribution()
        val bin = 5
        val angle = HeadingDistribution.binCenter(bin)
        repeat(500) { h.recordDelivery(angle, capacity = 600.0) }
        val before = h.shares(randomShare = 0.3, prior = 200.0)
        assertTrue(before[bin] > 3 * even)
        assertEquals(1.0, before.sum(), 1e-9)
        repeat(300) { h.recordEmpty(angle, emptyWeight = 1.0) }
        val after = h.shares(0.3, 200.0)
        assertTrue(after[bin] < before[bin])

        // Newborns follow the distribution: most of the non-random share lands in the peak's bin.
        val g = HeadingDistribution()
        repeat(1000) { g.recordDelivery(angle, 600.0) }
        val rng = Random(1)
        val draws = List(10_000) { g.sample(rng, 0.3, 0.0) }
        val inBin = draws.count { angleBetween(it, angle) <= HeadingDistribution.BIN_WIDTH / 2 }
        assertEquals(0.7 + 0.3 / n, inBin / 10_000.0, 0.02)
    }

    @Test
    fun `the distribution changes only with evidence, and each direction only with its own`() {
        val capacity = 600.0
        val h = HeadingDistribution()
        // However many deliveries, a bin approaches the cap and never passes it.
        repeat(20_000) { h.recordDelivery(HeadingDistribution.binCenter(2), capacity) }
        val full = h.weightsCopy()[2]
        assertTrue(full <= capacity && full > 0.99 * capacity, "a bin should fill to just under the cap, got $full")
        // A delivery slightly off a bin's center still goes wholly into the nearest bin, so a
        // direction holds at most one cap wherever the bin boundaries fall.
        h.recordDelivery(HeadingDistribution.binCenter(7) + 0.4 * HeadingDistribution.BIN_WIDTH, capacity)
        assertEquals(1.0, h.weightsCopy()[7], 1e-12)
        assertEquals(0.0, h.weightsCopy()[8])

        // Only evidence about its own direction changes a bin (and there is no fade with time):
        // deliveries and failures in other directions leave this one exactly as it was.
        val before = h.weightsCopy()
        repeat(5_000) { h.recordDelivery(HeadingDistribution.binCenter(10), capacity) }
        repeat(5_000) { h.recordEmpty(HeadingDistribution.binCenter(12), emptyWeight = 0.2) }
        assertEquals(before[2], h.weightsCopy()[2])
        assertEquals(0.0, h.weightsCopy()[12], "a bin never goes below zero")

        // capacity / emptyWeight failures take even a full bin all the way down.
        repeat((capacity / 0.2).toInt()) { h.recordEmpty(HeadingDistribution.binCenter(2), 0.2) }
        assertEquals(0.0, h.weightsCopy()[2], 1e-9)
    }

    @Test
    fun `a direction rests where its deliveries and failures balance`() {
        // w = C · (1 − emptyWeight · failures / deliveries): here 10 deliveries to 5 failures at weight 0.4.
        val h = HeadingDistribution()
        val angle = HeadingDistribution.binCenter(3)
        repeat(4_000) {
            repeat(10) { h.recordDelivery(angle, 600.0) }
            repeat(5) { h.recordEmpty(angle, 0.4) }
        }
        assertEquals(600.0 * (1 - 0.4 * 5 / 10), h.weightsCopy()[3], 5.0)
    }
}
