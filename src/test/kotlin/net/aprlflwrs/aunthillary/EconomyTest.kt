package net.aprlflwrs.aunthillary

import net.aprlflwrs.aunthillary.core.AntColony
import net.aprlflwrs.aunthillary.core.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Small, fast checks of the hunger economy and the fixed-population mode (128² grid). */
class EconomyTest {
    private fun colony(seed: Long = 5) = AntColony(seed, gridSize = 128, terrainSize = 64)

    private fun AntColony.run(seconds: Int, each: () -> Unit = {}) =
        repeat(seconds * World.TICKS_PER_SECOND) { step(); each() }

    @Test
    fun `without food the colony starves to its floor`() {
        val c = colony()
        c.run(300)
        assertTrue(c.population <= 1.05 * c.p.populationFloor.value, "population ${c.population}")
        assertTrue(c.totalStarved > 2000, "only ${c.totalStarved} starved")
        assertEquals(0.0, c.store, 1e-9)
    }

    @Test
    fun `fixed-population mode holds the size exactly, through starvation and with following off`() {
        val c = colony()
        c.setParam("nest.fixedPopulation", 2000.0)
        c.setParam("ants.followTrails", 0.0)
        c.placeFood(380.0, 300.0)
        var min = Int.MAX_VALUE; var max = 0
        c.run(240) { min = minOf(min, c.population); max = maxOf(max, c.population) }
        assertEquals(2000, min)
        assertEquals(2000, max)
        // Ants still starved (and were replaced): the economy runs, it just doesn't set the size.
        assertTrue(c.totalStarved > 0)

        // Lowering it culls at once.
        c.setParam("nest.fixedPopulation", 500.0)
        c.step()
        assertEquals(500, c.population)
    }

    @Test
    fun `a hungry ant eats at the nest, and births come only from surplus`() {
        val c = colony()
        c.setParam("nest.initialStore", 0.0)
        c.setParam("nest.initialAnts", 1000.0)
        c.reset()
        // An empty store: nobody hatches beyond the floor, and nobody eats.
        c.run(30)
        assertEquals(1000L, c.totalHatched)
        assertEquals(0.0, c.totalEaten, 1e-9)
    }
}
