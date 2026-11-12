package net.aprlflwrs.aunthillary.trails

import net.aprlflwrs.aunthillary.core.FoodPile
import net.aprlflwrs.aunthillary.core.Voice
import net.aprlflwrs.aunthillary.core.World
import kotlin.math.hypot
import kotlin.random.Random

/**
 * A hand-painted world for detector tests: trails are painted as polylines of food pheromone,
 * with optional noise, gaps and blocked rectangles. Coordinates are world units, like the colony's.
 * The nest sits in the middle with a hub radius of 12, as in the fake colony.
 */
class SyntheticGrid(val size: Int = 512) {
    val cell = World.SIZE / size
    val food = FloatArray(size * size)
    val blocked = BooleanArray(size * size)
    val heights = FloatArray(size * size)
    val nestX = 256.0
    val nestY = 256.0
    val hubRadius = 12.0

    /** Paints a trail of [value] along [points]: full strength within [width]/2, fading to 0 at [width]. */
    fun trail(vararg points: Pair<Double, Double>, value: Float = 0.6f, width: Double = 4.0): SyntheticGrid {
        for (k in 1 until points.size) segment(points[k - 1], points[k], value, width)
        return this
    }

    private fun segment(a: Pair<Double, Double>, b: Pair<Double, Double>, value: Float, width: Double) {
        val (ax, ay) = a
        val (bx, by) = b
        val i0 = ((minOf(ax, bx) - width) / cell).toInt().coerceIn(0, size - 1)
        val i1 = ((maxOf(ax, bx) + width) / cell).toInt().coerceIn(0, size - 1)
        val j0 = ((minOf(ay, by) - width) / cell).toInt().coerceIn(0, size - 1)
        val j1 = ((maxOf(ay, by) + width) / cell).toInt().coerceIn(0, size - 1)
        val lx = bx - ax
        val ly = by - ay
        val len2 = lx * lx + ly * ly
        for (j in j0..j1) for (i in i0..i1) {
            val x = (i + 0.5) * cell
            val y = (j + 0.5) * cell
            val t = if (len2 == 0.0) 0.0 else (((x - ax) * lx + (y - ay) * ly) / len2).coerceIn(0.0, 1.0)
            val d = hypot(x - (ax + t * lx), y - (ay + t * ly))
            val v = when {
                d <= width / 2 -> value
                d < width -> (value * (width - d) / (width / 2)).toFloat()
                else -> continue
            }
            val c = j * size + i
            if (v > food[c]) food[c] = v
        }
    }

    /** Adds uniform noise in [0, amplitude) to every cell. */
    fun noise(seed: Long, amplitude: Float = 0.05f): SyntheticGrid {
        val rng = Random(seed)
        for (c in food.indices) food[c] += rng.nextFloat() * amplitude
        return this
    }

    /** Clears pheromone within [radius] of (x, y), leaving a gap in whatever trail runs there. */
    fun gap(x: Double, y: Double, radius: Double): SyntheticGrid {
        forCells(x - radius, y - radius, x + radius, y + radius) { c, cx, cy ->
            if (hypot(cx - x, cy - y) <= radius) food[c] = 0f
        }
        return this
    }

    /** Blocks the rectangle [x0, x1] × [y0, y1]. Pheromone under it is left alone on purpose. */
    fun block(x0: Double, y0: Double, x1: Double, y1: Double): SyntheticGrid {
        forCells(x0, y0, x1, y1) { c, _, _ -> blocked[c] = true }
        return this
    }

    /** Multiplies all pheromone by [factor], like evaporation. */
    fun scale(factor: Float): SyntheticGrid {
        for (c in food.indices) food[c] *= factor
        return this
    }

    private inline fun forCells(x0: Double, y0: Double, x1: Double, y1: Double, f: (Int, Double, Double) -> Unit) {
        for (j in 0 until size) {
            val y = (j + 0.5) * cell
            if (y < y0 || y > y1) continue
            for (i in 0 until size) {
                val x = (i + 0.5) * cell
                if (x < x0 || x > x1) continue
                f(j * size + i, x, y)
            }
        }
    }

    fun input(tick: Long, piles: List<FoodPile>, displayMax: Double = 1.0) = DetectorInput(
        tick = tick, size = size, food = food.copyOf(),
        terrainSize = size, heights = heights.copyOf(), blocked = blocked.copyOf(),
        nestX = nestX, nestY = nestY, hubRadius = hubRadius,
        piles = piles, displayMax = displayMax,
    )
}

fun pile(id: Int, x: Double, y: Double) = FoodPile(id, x, y, 500.0)

/** Voices compared by content: [Voice.points] holds arrays, whose equals compares references. */
fun sameVoices(a: List<Voice>, b: List<Voice>): Boolean =
    a.size == b.size && a.zip(b).all { (u, v) ->
        u.copy(points = emptyList()) == v.copy(points = emptyList()) &&
            u.points.size == v.points.size &&
            u.points.zip(v.points).all { (p, q) -> p.contentEquals(q) }
    }

/** How many leading points two voices share exactly. */
fun sharedPrefix(a: Voice, b: Voice): Int {
    var k = 0
    while (k < a.points.size && k < b.points.size && a.points[k].contentEquals(b.points[k])) k++
    return k
}
