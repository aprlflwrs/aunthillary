package net.aprlflwrs.aunthillary.core

import kotlin.random.Random

/**
 * The ground: generated hills, blocked cells (rocks), and the nest's mound on top.
 *
 * Heights are in world units. Ants read two things from here: whether a point is blocked,
 * and the grade (rise over run) along their heading, which slows them on slopes. The mound is
 * kept separate from the generated hills so it can grow and erode without regenerating them.
 *
 * Layout matches [TerrainView]: row-major, rows south to north, [size] × [size] cells.
 */
class Terrain(val size: Int) {
    val cell = World.SIZE / size
    private val invCell = 1.0 / cell

    private val hills = FloatArray(size * size)
    val heights = FloatArray(size * size)
    val blocked = BooleanArray(size * size)

    /** Height change per world unit east and north, per cell, recomputed with the heights. */
    private val gradX = FloatArray(size * size)
    private val gradY = FloatArray(size * size)

    /**
     * Draws new hills and rocks from [rng]. Hills are two octaves of smoothed value noise
     * (random heights on a coarse lattice, blended with smoothstep), scaled to [hillHeight].
     * Rocks are discs, kept at least [clearance] from the nest so the colony is never walled in.
     */
    fun generate(
        rng: Random, hillHeight: Double, hillScale: Double, rockCount: Int, rockRadius: Double,
        nestX: Double, nestY: Double, clearance: Double,
    ) {
        val octave1 = Lattice(rng, (World.SIZE / hillScale).toInt().coerceAtLeast(1))
        val octave2 = Lattice(rng, (2 * World.SIZE / hillScale).toInt().coerceAtLeast(1))
        for (r in 0 until size) for (c in 0 until size) {
            val x = (c + 0.5) * cell
            val y = (r + 0.5) * cell
            val v = (octave1.at(x, y) * 2 + octave2.at(x, y)) / 3
            hills[r * size + c] = (v * hillHeight).toFloat()
        }

        blocked.fill(false)
        repeat(rockCount) {
            val rx = rng.nextDouble(World.SIZE)
            val ry = rng.nextDouble(World.SIZE)
            val rr = rng.nextDouble(rockRadius * 0.3, rockRadius)
            val dx = rx - nestX
            val dy = ry - nestY
            if (StrictMath.sqrt(dx * dx + dy * dy) < clearance + rr) return@repeat
            val c0 = ((rx - rr) * invCell).toInt().coerceIn(0, size - 1)
            val c1 = ((rx + rr) * invCell).toInt().coerceIn(0, size - 1)
            val r0 = ((ry - rr) * invCell).toInt().coerceIn(0, size - 1)
            val r1 = ((ry + rr) * invCell).toInt().coerceIn(0, size - 1)
            for (r in r0..r1) for (c in c0..c1) {
                val ex = (c + 0.5) * cell - rx
                val ey = (r + 0.5) * cell - ry
                if (ex * ex + ey * ey <= rr * rr) blocked[r * size + c] = true
            }
        }
    }

    /** Recomputes heights as hills plus a cone of [height] and [radius] at the nest. */
    fun applyMound(nestX: Double, nestY: Double, height: Double, radius: Double) {
        for (r in 0 until size) for (c in 0 until size) {
            val dx = (c + 0.5) * cell - nestX
            val dy = (r + 0.5) * cell - nestY
            val d = StrictMath.sqrt(dx * dx + dy * dy)
            val cone = if (d < radius) height * (1 - d / radius) else 0.0
            val i = r * size + c
            heights[i] = (hills[i] + cone).toFloat()
        }
        // Central differences (one-sided at the edges), in height per world unit.
        for (r in 0 until size) for (c in 0 until size) {
            val i = r * size + c
            val cl = if (c == 0) c else c - 1
            val cr = if (c == size - 1) c else c + 1
            val rd = if (r == 0) r else r - 1
            val ru = if (r == size - 1) r else r + 1
            gradX[i] = ((heights[r * size + cr] - heights[r * size + cl]) / ((cr - cl) * cell)).toFloat()
            gradY[i] = ((heights[ru * size + c] - heights[rd * size + c]) / ((ru - rd) * cell)).toFloat()
        }
    }

    private fun index(x: Double, y: Double): Int {
        val c = (x * invCell).toInt().coerceIn(0, size - 1)
        val r = (y * invCell).toInt().coerceIn(0, size - 1)
        return r * size + c
    }

    fun isBlocked(x: Double, y: Double) = blocked[index(x, y)]

    /** Rise over run when moving from (x, y) in the direction (dirX, dirY), a unit vector. */
    fun grade(x: Double, y: Double, dirX: Double, dirY: Double): Double {
        val i = index(x, y)
        return gradX[i] * dirX + gradY[i] * dirY
    }

    /** Bilinear height at (x, y). */
    fun heightAt(x: Double, y: Double): Double {
        val gx = (x * invCell - 0.5).coerceIn(0.0, size - 1.0)
        val gy = (y * invCell - 0.5).coerceIn(0.0, size - 1.0)
        val c0 = gx.toInt().coerceAtMost(size - 2).coerceAtLeast(0)
        val r0 = gy.toInt().coerceAtMost(size - 2).coerceAtLeast(0)
        val fx = gx - c0
        val fy = gy - r0
        val c1 = (c0 + 1).coerceAtMost(size - 1)
        val r1 = (r0 + 1).coerceAtMost(size - 1)
        val a = heights[r0 * size + c0]; val b = heights[r0 * size + c1]
        val c = heights[r1 * size + c0]; val d = heights[r1 * size + c1]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    fun view() = TerrainView(size, heights.copyOf(), blocked.copyOf())

    /** Random values in [0, 1] on an (n+1) × (n+1) lattice over the world, smoothly interpolated. */
    private class Lattice(rng: Random, private val n: Int) {
        private val v = DoubleArray((n + 1) * (n + 1)) { rng.nextDouble() }
        private val spacing = World.SIZE / n

        fun at(x: Double, y: Double): Double {
            val gx = (x / spacing).coerceIn(0.0, n.toDouble())
            val gy = (y / spacing).coerceIn(0.0, n.toDouble())
            val i = gx.toInt().coerceAtMost(n - 1)
            val j = gy.toInt().coerceAtMost(n - 1)
            val fx = smooth(gx - i)
            val fy = smooth(gy - j)
            val w = n + 1
            val a = v[j * w + i]; val b = v[j * w + i + 1]
            val c = v[(j + 1) * w + i]; val d = v[(j + 1) * w + i + 1]
            return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
        }

        private fun smooth(t: Double) = t * t * (3 - 2 * t)
    }
}
