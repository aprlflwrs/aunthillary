package net.aprlflwrs.aunthillary

import net.aprlflwrs.aunthillary.core.PheromoneGrid
import net.aprlflwrs.aunthillary.core.World
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The grid's promise is that every rate is in world units, so changing the resolution changes
 * the picture, not the behavior. These tests check that at 256², 512² and 1024².
 */
class PheromoneGridTest {
    private val sizes = listOf(256, 512, 1024)

    @Test
    fun `a deposit adds the same total amount and reads back as density at any resolution`() {
        for (n in sizes) {
            val g = PheromoneGrid(n)
            g.deposit(g.food, 100.3, 200.7, 5.0)
            assertEquals(5.0, g.totalAmount(g.food), 1e-4, "total at $n")
            // A wide, flat patch: the sampled density is amount per square unit everywhere inside it.
            val flat = PheromoneGrid(n)
            for (i in 0 until 40) for (j in 0 until 40) flat.deposit(flat.home, 300.0 + i * 0.5, 300.0 + j * 0.5, 0.25)
            assertEquals(1.0, flat.sample(flat.home, 310.0, 310.0), 0.02, "density at $n")
        }
    }

    @Test
    fun `evaporation removes the same share per second at any resolution`() {
        for (n in sizes) {
            val g = PheromoneGrid(n)
            g.deposit(g.food, 256.0, 256.0, 10.0)
            repeat(World.TICKS_PER_SECOND) { g.update(World.DT, evaporation = 0.2, diffusion = 0.0, cutoff = 0.0) }
            assertEquals(10.0 * exp(-0.2), g.totalAmount(g.food), 1e-3, "at $n")
        }
    }

    @Test
    fun `diffusion spreads a spot by the same distance at any resolution`() {
        // A point source diffusing with coefficient D for time T has variance 2·D·T along each axis.
        val d = 2.0
        val seconds = 3
        val variances = sizes.map { n ->
            val g = PheromoneGrid(n)
            g.deposit(g.food, 256.0, 256.0, 1.0)
            repeat(seconds * World.TICKS_PER_SECOND) { g.update(World.DT, evaporation = 0.0, diffusion = d, cutoff = 0.0) }
            assertEquals(1.0, g.totalAmount(g.food), 1e-3, "diffusion must not lose or create pheromone at $n")
            varianceX(g)
        }
        for ((n, v) in sizes.zip(variances)) {
            assertTrue(abs(v - 2 * d * seconds) < 0.1 * 2 * d * seconds, "variance $v at $n, expected ${2 * d * seconds}")
        }
    }

    private fun varianceX(g: PheromoneGrid): Double {
        var m = 0.0; var mx = 0.0; var mxx = 0.0
        for (r in 0 until g.size) for (c in 0 until g.size) {
            val v = g.food[r * g.size + c].toDouble()
            val x = (c + 0.5) * g.cell
            m += v; mx += v * x; mxx += v * x * x
        }
        val mean = mx / m
        return mxx / m - mean * mean
    }

    @Test
    fun `sampling is bilinear between cell centers`() {
        val g = PheromoneGrid(512)
        g.deposit(g.food, 10.5, 10.5, 1.0) // exactly on a cell center: all in one cell
        assertEquals(1.0, g.sample(g.food, 10.5, 10.5), 1e-6)
        assertEquals(0.5, g.sample(g.food, 11.0, 10.5), 1e-6)
        assertEquals(0.25, g.sample(g.food, 11.0, 11.0), 1e-6)
    }
}
