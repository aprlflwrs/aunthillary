package net.aprlflwrs.aunthillary.core

import kotlin.math.PI
import kotlin.random.Random

/**
 * The colony's caste distribution of preferred headings: the nest's memory of which
 * directions food comes from. No ant holds it; it decides where newborns and recruited ants head.
 *
 * It keeps one weight per direction bin, the evidence that food lies that way. The weights
 * change only with evidence, never with time alone, so an idle colony keeps its belief:
 *
 *  - a forager that brings food home moves the bin it found the food in a fraction 1/C of
 *    the way up to the cap C (the capacity):   w ← w + (1 − w / C)
 *  - an ant that gave up and came home empty-handed takes emptyWeight off the bin it gave up
 *    in, never below 0:                         w ← max(0, w − emptyWeight)
 *
 * Each event goes wholly into the bin nearest its angle. (Splitting it between the two nearest
 * bins, as an earlier version did, would let a pile that sits between two bin centers fill both
 * to the cap, so how much a direction could be believed would depend on where the bin
 * boundaries happen to fall.) A pile is at a fixed angle, so all its deliveries land in one bin.
 *
 * Why this shape. Each bin is bounded on its own, by 0 and C, and only evidence about its own
 * direction moves it: food brought home from the south-west says nothing about the north-east,
 * so it doesn't erode a belief there. (Moving the whole distribution toward each delivery's bin,
 * or rescaling to a total budget, would: a busy pile elsewhere would wear down every other
 * belief at the rate it delivers, which is a fade with time under another name.) The bins
 * compete only through [shares], which divides by the total: a growing peak still shrinks the
 * others' shares. The cap is what keeps the colony from becoming stubborn: however long a
 * direction has fed it, a belief holds at most C, so at most C / emptyWeight empty-handed
 * returns undo it. Deliveries approach the cap smoothly rather than hitting it, and a bin's
 * resting level, where deliveries and failures balance, is
 *
 *     w = C · (1 − emptyWeight · failures / deliveries)     (per second, in that direction)
 *
 * so a direction that is reliable rests near C, and one that fails often rests lower. A busy
 * colony sends many ants to a dead direction and unlearns it quickly; a colony at its floor
 * sends few, and keeps its belief longer.
 *
 * A heading is drawn as: with probability randomShare, uniformly at random; otherwise from the
 * bins in proportion to weight + prior / bins. The prior is a number of evenly spread
 * pseudo-returns, so a few lucky returns don't make a peak. [shares] gives the resulting
 * probability of each bin, which is what the state message's `headings` carries.
 *
 * Bins follow the protocol: [BINS] of them, bin 0 centered on east, counterclockwise.
 */
class HeadingDistribution {
    private val weights = DoubleArray(BINS)

    fun clear() = weights.fill(0.0)

    /** A food delivery from [angle]: its bin moves 1/[capacity] of the way up to [capacity]. */
    fun recordDelivery(angle: Double, capacity: Double) {
        val bin = binOf(angle)
        weights[bin] += 1 - weights[bin] / capacity
    }

    /** An empty-handed return from [angle]: takes [emptyWeight] off its bin, never below 0. */
    fun recordEmpty(angle: Double, emptyWeight: Double) {
        val bin = binOf(angle)
        weights[bin] = (weights[bin] - emptyWeight).coerceAtLeast(0.0)
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

        /** The bin whose center is nearest [angle]. */
        fun binOf(angle: Double) = StrictMath.floor(wrap(angle) / BIN_WIDTH + 0.5).toInt() % BINS
    }
}
