package net.aprlflwrs.aunthillary.trails

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Turns a route (a chain of cell centers, hub end first) into a smooth curve with points at a
 * fixed spacing:
 *
 * 1. Resample the staircase of cell centers at a fine, fixed arc-length step from the hub end.
 * 2. Smooth with a Gaussian of standard deviation [sigma] world units, holding the ends in place
 *    (indices past either end read the end point).
 * 3. Resample the smooth curve at [spacing] from the hub end, always keeping the food end.
 *
 * Why this order: two routes that share a trunk share the same cells from the hub outward, so
 * step 1 gives them identical samples along the trunk, step 2 keeps them identical except within
 * about 3σ of the junction (the kernel only looks that far), and step 3, walking from the same
 * start over the same curve, puts their points in identical places. The two voices therefore
 * start with exactly the same points and only diverge near where their trails split, which is
 * what lets them play the same opening phrase.
 */
internal object Curve {
    /** The fine resampling step, in world units. */
    private const val FINE = 0.5

    fun smooth(xs: DoubleArray, ys: DoubleArray, sigma: Double, spacing: Double): Pair<DoubleArray, DoubleArray> {
        if (xs.size < 2) return xs.copyOf() to ys.copyOf()
        val (fx, fy) = resample(xs, ys, FINE, keepEnd = true)
        val (sx, sy) = if (sigma > 0) gaussian(fx, fy, sigma / FINE) else fx to fy
        return resample(sx, sy, spacing, keepEnd = true)
    }

    /**
     * Points every [step] along the polyline, starting at its first point. With [keepEnd], the
     * last point is added too unless the previous sample is already within a quarter step of it.
     */
    fun resample(xs: DoubleArray, ys: DoubleArray, step: Double, keepEnd: Boolean): Pair<DoubleArray, DoubleArray> {
        val outX = ArrayList<Double>()
        val outY = ArrayList<Double>()
        outX += xs[0]; outY += ys[0]
        // Distance still to walk before the next sample.
        var need = step
        for (k in 1 until xs.size) {
            var px = xs[k - 1]
            var py = ys[k - 1]
            val qx = xs[k]
            val qy = ys[k]
            var segment = hypot(qx - px, qy - py)
            while (segment >= need) {
                val t = need / segment
                px += (qx - px) * t
                py += (qy - py) * t
                outX += px; outY += py
                segment -= need
                need = step
            }
            need -= segment
        }
        if (keepEnd) {
            val lx = xs[xs.size - 1]
            val ly = ys[ys.size - 1]
            if (hypot(lx - outX.last(), ly - outY.last()) > step * 0.25) {
                outX += lx; outY += ly
            }
        }
        return outX.toDoubleArray() to outY.toDoubleArray()
    }

    /** Gaussian smoothing with standard deviation [sigmaSamples] (in samples), ends clamped. */
    private fun gaussian(xs: DoubleArray, ys: DoubleArray, sigmaSamples: Double): Pair<DoubleArray, DoubleArray> {
        val radius = ceil(3 * sigmaSamples).toInt()
        val weights = DoubleArray(2 * radius + 1) { k ->
            val d = (k - radius).toDouble()
            exp(-d * d / (2 * sigmaSamples * sigmaSamples))
        }
        val total = weights.sum()
        val n = xs.size
        val ox = DoubleArray(n)
        val oy = DoubleArray(n)
        for (i in 0 until n) {
            var sx = 0.0
            var sy = 0.0
            for (k in -radius..radius) {
                val j = (i + k).coerceIn(0, n - 1)
                val w = weights[k + radius]
                sx += w * xs[j]; sy += w * ys[j]
            }
            ox[i] = sx / total; oy[i] = sy / total
        }
        // Pin the ends exactly, so the curve still starts at the hub's edge and ends at the food.
        ox[0] = xs[0]; oy[0] = ys[0]
        ox[n - 1] = xs[n - 1]; oy[n - 1] = ys[n - 1]
        return ox to oy
    }

    /**
     * Bilinear sample of a square grid at world position (x, y), cell centers at
     * `(i + 0.5) * cell`. Positions past the outer cell centers read the edge cells.
     */
    fun sample(grid: FloatArray, size: Int, cell: Double, x: Double, y: Double): Double {
        val gx = (x / cell - 0.5).coerceIn(0.0, (size - 1).toDouble())
        val gy = (y / cell - 0.5).coerceIn(0.0, (size - 1).toDouble())
        val i0 = floor(gx).toInt()
        val j0 = floor(gy).toInt()
        val i1 = minOf(i0 + 1, size - 1)
        val j1 = minOf(j0 + 1, size - 1)
        val tx = gx - i0
        val ty = gy - j0
        val a = grid[j0 * size + i0] * (1 - tx) + grid[j0 * size + i1] * tx
        val b = grid[j1 * size + i0] * (1 - tx) + grid[j1 * size + i1] * tx
        return a * (1 - ty) + b * ty
    }

    /** The median of [values] (the mean of the middle two for an even count); 0 if empty. */
    fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
