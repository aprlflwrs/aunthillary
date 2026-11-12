package net.aprlflwrs.aunthillary.core

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The colony's shared memory: two square layers of pheromone over the whole world, one laid
 * by ants carrying food ("toward food") and one by searching ants ("toward home").
 *
 * Values are **densities** (amount per square world unit), and every rate is in world units
 * and seconds, so the colony behaves the same at any [size]: a deposit of 1 adds a total
 * amount of 1 however many cells it is spread over, and sampling returns the same density.
 *
 * Cells are row-major, rows south to north, each row west to east (the [PheromoneView] layout).
 * Cell (col, row) covers x ∈ [col·cell, (col+1)·cell) and has its value at its center.
 */
class PheromoneGrid(val size: Int) {
    /** Width of one cell in world units. */
    val cell = World.SIZE / size
    private val invCell = 1.0 / cell
    private val invCellArea = invCell * invCell

    var food = FloatArray(size * size); private set
    var home = FloatArray(size * size); private set
    private var scratch = FloatArray(size * size)

    enum class Layer { FOOD, HOME }

    fun layer(which: Layer) = if (which == Layer.FOOD) food else home

    fun clear() {
        food.fill(0f); home.fill(0f)
    }

    /**
     * Adds [amount] of pheromone at (x, y), split over the four nearest cell centers with the
     * same bilinear weights [sample] reads with.
     */
    fun deposit(layer: FloatArray, x: Double, y: Double, amount: Double) {
        val gx = x * invCell - 0.5
        val gy = y * invCell - 0.5
        val c0 = floor(gx).toInt()
        val r0 = floor(gy).toInt()
        val fx = gx - c0
        val fy = gy - r0
        val density = amount * invCellArea
        add(layer, c0, r0, density * (1 - fx) * (1 - fy))
        add(layer, c0 + 1, r0, density * fx * (1 - fy))
        add(layer, c0, r0 + 1, density * (1 - fx) * fy)
        add(layer, c0 + 1, r0 + 1, density * fx * fy)
    }

    private fun add(layer: FloatArray, col: Int, row: Int, v: Double) {
        // Deposits past the edge land on the edge cell, so no pheromone is lost.
        val c = col.coerceIn(0, size - 1)
        val r = row.coerceIn(0, size - 1)
        val i = r * size + c
        layer[i] = (layer[i] + v).toFloat()
    }

    /** Bilinear density at (x, y), so steering stays smooth across cell boundaries. */
    fun sample(layer: FloatArray, x: Double, y: Double): Double {
        val gx = x * invCell - 0.5
        val gy = y * invCell - 0.5
        val c0f = floor(gx)
        val r0f = floor(gy)
        val fx = gx - c0f
        val fy = gy - r0f
        val n1 = size - 1
        val c0 = c0f.toInt().coerceIn(0, n1)
        val r0 = r0f.toInt().coerceIn(0, n1)
        val c1 = (c0f.toInt() + 1).coerceIn(0, n1)
        val r1 = (r0f.toInt() + 1).coerceIn(0, n1)
        val a = layer[r0 * size + c0]
        val b = layer[r0 * size + c1]
        val c = layer[r1 * size + c0]
        val d = layer[r1 * size + c1]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /**
     * One tick of evaporation and diffusion on both layers.
     *
     * Diffusion is the explicit five-point scheme, value += α·(sum of 4 neighbors − 4·value)
     * with α = D·dt / cell². That scheme is only stable for α ≤ 1/4, and α grows 4× each time
     * the resolution doubles, so when α would pass [MAX_ALPHA] the tick is split into enough
     * substeps to stay below it. That keeps diffusion the same in world units at any resolution
     * (instead of silently clamping it at fine grids). The edges are closed: nothing flows out.
     *
     * Evaporation multiplies by exp(−rate·dt), applied once per tick, and anything below
     * [cutoff] becomes 0.
     */
    fun update(dt: Double, evaporation: Double, diffusion: Double, cutoff: Double) {
        val alpha = diffusion * dt * invCellArea
        val substeps = if (alpha <= MAX_ALPHA) 1 else ceil(alpha / MAX_ALPHA).toInt()
        val a = (alpha / substeps).toFloat()
        val keep = StrictMath.exp(-evaporation * dt).toFloat()
        val cut = cutoff.toFloat()
        for (s in 0 until substeps) {
            val last = s == substeps - 1
            food = step(food, a, if (last) keep else 1f, if (last) cut else 0f)
            home = step(home, a, if (last) keep else 1f, if (last) cut else 0f)
        }
    }

    /** Writes one diffusion (and optionally evaporation) step of [src] into the scratch buffer and swaps. */
    private fun step(src: FloatArray, a: Float, keep: Float, cut: Float): FloatArray {
        val dst = scratch
        val n = size
        if (a == 0f) {
            for (i in src.indices) {
                val v = src[i] * keep
                dst[i] = if (v < cut) 0f else v
            }
        } else {
            for (r in 0 until n) {
                val row = r * n
                val up = if (r == n - 1) row else row + n
                val down = if (r == 0) row else row - n
                for (c in 0 until n) {
                    val i = row + c
                    val v = src[i]
                    val l = if (c == 0) v else src[i - 1]
                    val rt = if (c == n - 1) v else src[i + 1]
                    val u = src[up + c]
                    val d = src[down + c]
                    val nv = (v + a * (l + rt + u + d - 4f * v)) * keep
                    dst[i] = if (nv < cut) 0f else nv
                }
            }
        }
        scratch = src
        return dst
    }

    /** Total amount in a layer (density × cell area summed), for tests and metrics. */
    fun totalAmount(layer: FloatArray): Double {
        var sum = 0.0
        for (v in layer) sum += v
        return sum * cell * cell
    }

    fun view() = PheromoneView(size, food.copyOf(), home.copyOf())

    companion object {
        /** Largest diffusion α per substep; below the 1/4 stability limit with some margin. */
        const val MAX_ALPHA = 0.2
    }
}
