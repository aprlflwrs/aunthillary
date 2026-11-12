package net.aprlflwrs.aunthillary.core

import kotlin.math.PI

/**
 * The symbol level: finds the persistent peaks in the heading distribution and gives each a
 * stable identity.
 *
 * A peak is a run of neighboring bins (wrapping round the ring) whose share stands above the
 * disappear level. A new peak becomes a symbol only once its highest bin passes the higher
 * appear level, and a symbol lives for as long as some run above the disappear level still
 * overlaps the bins it had last time; the gap between the two levels keeps symbols from
 * flickering. Both levels are multiples of the even share (1 / bins), the level an evenly
 * spread colony would have.
 *
 * Nothing here says what a peak means. "Food to the north-east" is a reading of it.
 */
class SymbolTracker {
    class Tracked(
        val id: Int,
        val bornTick: Long,
        var bins: Set<Int>,
        var direction: Double,
        var width: Double,
        var strength: Double,
    )

    private var nextId = 1
    private val live = mutableListOf<Tracked>() // oldest first

    val symbols: List<Tracked> get() = live

    fun clear() {
        live.clear()
        nextId = 1
    }

    /** Updates the symbols from [shares] (one per bin, summing to 1). */
    fun update(shares: DoubleArray, tick: Long, appearRatio: Double, disappearRatio: Double) {
        val n = shares.size
        val even = 1.0 / n
        val low = even * maxOf(1.0, disappearRatio)
        val high = even * maxOf(appearRatio, disappearRatio)
        val peaks = findPeaks(shares, low)

        // Existing symbols, oldest first, claim the peak they overlap most. Oldest first means
        // that when two symbols' peaks merge, the older one keeps its identity.
        val claimed = BooleanArray(peaks.size)
        val survivors = mutableListOf<Tracked>()
        for (symbol in live) {
            var best = -1
            var bestOverlap = 0
            for ((k, peak) in peaks.withIndex()) {
                if (claimed[k]) continue
                val overlap = peak.bins.count { it in symbol.bins }
                if (overlap > bestOverlap || (overlap == bestOverlap && overlap > 0 && peak.strength > peaks[best].strength)) {
                    best = k; bestOverlap = overlap
                }
            }
            if (best < 0) continue // its peak sank below the disappear level: the symbol is gone
            claimed[best] = true
            val peak = peaks[best]
            symbol.bins = peak.bins; symbol.direction = peak.direction
            symbol.width = peak.width; symbol.strength = peak.strength
            survivors += symbol
        }
        // Unclaimed peaks become new symbols only if they rise above the appear level.
        for ((k, peak) in peaks.withIndex()) {
            if (claimed[k] || peak.max < high) continue
            survivors += Tracked(nextId++, tick, peak.bins, peak.direction, peak.width, peak.strength)
        }
        live.clear()
        live += survivors
    }

    private class Peak(val bins: Set<Int>, val max: Double, val direction: Double, val width: Double, val strength: Double)

    /** Maximal runs of bins above [level], wrapping round the ring. */
    private fun findPeaks(shares: DoubleArray, level: Double): List<Peak> {
        val n = shares.size
        val even = 1.0 / n
        val above = BooleanArray(n) { shares[it] > level }
        if (above.none { it }) return emptyList()
        // Start scanning just after a bin that is below the level, so no run is split by the wrap.
        // (Since shares sum to 1 and level > even, at least one bin is below it.)
        val start = (0 until n).firstOrNull { !above[it] } ?: return emptyList()
        val peaks = mutableListOf<Peak>()
        var run = mutableListOf<Int>()
        for (k in 1..n) {
            val i = (start + k) % n
            if (above[i]) run += i
            if ((!above[i] || k == n) && run.isNotEmpty()) {
                peaks += makePeak(run, shares, even)
                run = mutableListOf()
            }
        }
        return peaks
    }

    private fun makePeak(run: List<Int>, shares: DoubleArray, even: Double): Peak {
        // Direction: mean of the bin centers weighted by how far each stands above even.
        var sx = 0.0; var sy = 0.0; var excess = 0.0; var max = 0.0
        for (i in run) {
            val e = shares[i] - even
            val a = HeadingDistribution.binCenter(i)
            sx += e * StrictMath.cos(a); sy += e * StrictMath.sin(a)
            excess += e
            max = maxOf(max, shares[i])
        }
        val direction = HeadingDistribution.wrap(StrictMath.atan2(sy, sx))
        val width = run.size * 2 * PI / shares.size
        // Strength: the share of newborns the peak holds beyond an even spread, which runs 0–1.
        return Peak(run.toSet(), max, direction, width, excess.coerceIn(0.0, 1.0))
    }
}
