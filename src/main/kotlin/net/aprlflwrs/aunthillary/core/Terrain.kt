package net.aprlflwrs.aunthillary.core

import kotlin.math.PI
import kotlin.math.abs
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
        gateAngles = emptyList()
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

    /**
     * Directions (radians, counterclockwise from east) of the gates in the nest ring, or empty
     * when there is no ring. Set by [addNestRing], cleared by [generate].
     */
    var gateAngles: List<Double> = emptyList()
        private set

    /**
     * Walls the nest in with a ring of rock, broken by [gates] gaps, so that ants leaving or
     * reaching the nest funnel through a few corridors. Call it after [generate].
     *
     * Why: the trail detector starts tracing at the hub's edge, and without obstacles trails to
     * nearby piles split before they get there, so voices never share an opening. Trails
     * heading roughly the same way now have to pass through the same gate, which gives them a
     * common trunk from the hub's edge to the far side of the ring, for a reason that lies in
     * the ground rather than in the detector.
     *
     * The ring is the annulus from [inner] to [inner] + [thickness] around the nest. Each gate
     * is a straight radial channel [gateWidth] wide (parallel-sided, so it is a corridor of the
     * same width all the way through rather than a wedge). Gates are spaced evenly round the
     * circle from a random starting angle, and each is moved by up to [jitter] × half the
     * spacing, so their layout varies with the seed while no direction is ever far from a gate.
     * Each channel is also cleared of the random rocks from [generate] out to [clearTo] from the
     * nest, so a rock lying just outside the ring can never block a gate.
     *
     * A channel is always at least one and a half cells wide, so a gate survives on a coarse
     * terrain grid (the tests use 8-unit cells).
     *
     * With [ridgeHeight] above 0 the ring is a ridge instead of rock: nothing is blocked, and the
     * ring's cells are raised in a tent-shaped profile, [ridgeHeight] high along its middle. Ants
     * cannot steer by slope, but they climb slowly ([ColonyParams.slopePenalty]) and lay less
     * pheromone the longer their trip has taken, so a trail over the ridge should end up weaker
     * than one through a gate. A ridge can funnel only that softly, but it cannot trap an ant.
     *
     * It draws from [rng] only when called, so a colony without the ring uses exactly the same
     * random numbers as before the ring existed, and its terrain and ants are unchanged.
     */
    fun addNestRing(
        rng: Random, nestX: Double, nestY: Double, inner: Double, thickness: Double, gates: Int,
        gateWidth: Double, jitter: Double, clearTo: Double, ridgeHeight: Double = 0.0,
    ) {
        val spacing = 2 * PI / gates.coerceAtLeast(1)
        val start = rng.nextDouble() * 2 * PI
        val angles = List(gates.coerceAtLeast(0)) { k ->
            start + k * spacing + (rng.nextDouble() * 2 - 1) * jitter * spacing / 2
        }
        gateAngles = angles
        val outer = inner + thickness
        val half = maxOf(gateWidth / 2, 0.75 * cell)
        // Only cells within reach of the ring or a cleared channel can change.
        val reach = maxOf(outer, clearTo) + cell
        val c0 = ((nestX - reach) * invCell).toInt().coerceIn(0, size - 1)
        val c1 = ((nestX + reach) * invCell).toInt().coerceIn(0, size - 1)
        val r0 = ((nestY - reach) * invCell).toInt().coerceIn(0, size - 1)
        val r1 = ((nestY + reach) * invCell).toInt().coerceIn(0, size - 1)
        val dirs = angles.map { StrictMath.cos(it) to StrictMath.sin(it) }
        for (r in r0..r1) for (c in c0..c1) {
            val dx = (c + 0.5) * cell - nestX
            val dy = (r + 0.5) * cell - nestY
            val d = StrictMath.sqrt(dx * dx + dy * dy)
            // In a gate's channel: on the gate's side of the nest, and within half its width of
            // the line from the nest through the gate.
            val inGate = dirs.any { (gx, gy) -> dx * gx + dy * gy > 0 && abs(dx * gy - dy * gx) <= half }
            val i = r * size + c
            if (inGate) {
                if (d <= clearTo) blocked[i] = false
            } else if (d in inner..outer) {
                if (ridgeHeight > 0) {
                    val fromMiddle = abs(d - (inner + outer) / 2) / (thickness / 2)
                    hills[i] += (ridgeHeight * (1 - fromMiddle)).toFloat()
                } else {
                    blocked[i] = true
                }
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
