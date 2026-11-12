package net.aprlflwrs.aunthillary.core

import kotlin.math.PI
import kotlin.random.Random

/**
 * The colony's caste distribution of preferred headings: the nest's memory of which
 * directions food comes from. No ant holds it; it decides where newborns head.
 *
 * It keeps one weight per direction bin. A forager that brings food home adds 1 to the bin of
 * the direction it found the food in; an ant that gave up and came home empty-handed
 * subtracts [emptyWeight] from the direction it gave up in (never below 0). All weights fade
 * with a time constant (the memory), so the colony can unlearn a direction.
 *
 * A newborn's heading is drawn as: with probability randomShare, uniformly at random;
 * otherwise from the bins in proportion to weight + prior / bins. The prior is a number of
 * evenly spread pseudo-returns, so a few lucky returns don't make a peak. [shares] gives the
 * resulting probability of each bin, which is what the state message's `headings` carries.
 *
 * Bins follow the protocol: [BINS] of them, bin 0 centered on east, counterclockwise.
 */
class HeadingDistribution {
    private val weights = DoubleArray(BINS)

    fun clear() = weights.fill(0.0)

    /** Multiplies every weight by [keep] (= exp(−dt / memory), computed by the caller once per tick). */
    fun fade(keep: Double) {
        for (i in weights.indices) weights[i] *= keep
    }

    /** Adds [amount] (negative for an empty-handed return) at [angle], split between the two nearest bins. */
    fun record(angle: Double, amount: Double) {
        val pos = wrap(angle) / BIN_WIDTH // bin i's center sits at pos = i
        val i0 = StrictMath.floor(pos).toInt()
        val f = pos - i0
        addTo(i0 % BINS, amount * (1 - f))
        addTo((i0 + 1) % BINS, amount * f)
    }

    private fun addTo(bin: Int, amount: Double) {
        weights[bin] = (weights[bin] + amount).coerceAtLeast(0.0)
    }

    /** The probability that a newborn is given each bin's heading. Sums to 1. */
    fun shares(randomShare: Double, prior: Double): DoubleArray {
        val total = weights.sum() + prior
        val out = DoubleArray(BINS)
        for (i in 0 until BINS) {
            val learned = if (total > 0) (weights[i] + prior / BINS) / total else 1.0 / BINS
            out[i] = randomShare / BINS + (1 - randomShare) * learned
        }
        return out
    }

    /** Draws a newborn's preferred heading. */
    fun sample(rng: Random, randomShare: Double, prior: Double): Double {
        if (rng.nextDouble() < randomShare) return rng.nextDouble() * 2 * PI
        val total = weights.sum() + prior
        if (total <= 0) return rng.nextDouble() * 2 * PI
        var pick = rng.nextDouble() * total
        var bin = BINS - 1
        for (i in 0 until BINS) {
            pick -= weights[i] + prior / BINS
            if (pick < 0) { bin = i; break }
        }
        // Anywhere within the chosen bin.
        return wrap((bin + rng.nextDouble() - 0.5) * BIN_WIDTH)
    }

    fun weightsCopy() = weights.copyOf()

    companion object {
        const val BINS = 16
        const val BIN_WIDTH = 2 * PI / BINS

        /** Wraps an angle into [0, 2π). */
        fun wrap(angle: Double): Double {
            val twoPi = 2 * PI
            var a = angle % twoPi
            if (a < 0) a += twoPi
            if (a >= twoPi) a -= twoPi
            return a
        }

        /** Angle of bin [i]'s center. */
        fun binCenter(i: Int) = i * BIN_WIDTH
    }
}
