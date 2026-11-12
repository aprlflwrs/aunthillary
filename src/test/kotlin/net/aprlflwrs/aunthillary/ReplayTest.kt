package net.aprlflwrs.aunthillary

import net.aprlflwrs.aunthillary.core.Action
import net.aprlflwrs.aunthillary.core.ActionLog
import net.aprlflwrs.aunthillary.core.AntColony
import net.aprlflwrs.aunthillary.core.Replayer
import net.aprlflwrs.aunthillary.core.World
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Two runs with the same seed and action log must match exactly. The first run is driven the
 * way the server drives it (including parameter changes made straight through `params.set`,
 * which the colony must notice and log by itself); the second is a replay of the log the
 * first one recorded. Kept small (a 128² grid, about 800 ants) so it runs in a few seconds.
 */
class ReplayTest {
    private fun live(seed: Long): Pair<AntColony, Long> {
        val c = AntColony(seed, gridSize = 128, terrainSize = 64)
        var steps = 0L
        fun run(seconds: Int) = repeat(seconds * World.TICKS_PER_SECOND) { c.step(); steps++ }

        c.params.set("nest.initialAnts", 800.0) // the server's way: straight into the registry
        c.reset()
        c.placeFood(380.0, 300.0)
        c.placeFood(150.0, 360.0)
        run(20)
        c.params.set("pheromone.evaporation", 0.1)
        c.setParam("ants.wander", 1.5)
        run(5)
        c.flood()
        c.params.set("food.pileAmount", 500.0) // changed in the same batch as the next placeFood: order matters
        c.placeFood(300.0, 140.0)
        run(10)
        c.removeFood(1)
        c.flattenHeadings()
        run(5)
        c.reset()
        c.placeFood(200.0, 200.0)
        run(10)
        c.params.set("ants.speed", 15.0) // logged when the next step starts
        run(5)
        return c to steps
    }

    @Test
    fun `a replay of the action log matches the live run exactly`() {
        val (original, steps) = live(seed = 42)
        val replayed = AntColony(42, gridSize = 128, terrainSize = 64)
        Replayer(replayed, original.log.actions).run(steps)

        assertEquals(original.tick, replayed.tick)
        assertEquals(original.log.toText(), replayed.log.toText(), "the replay should record the same log")
        assertEquals(original.population, replayed.population)
        assertTrue(original.population > 0)
        val n = original.population
        assertContentEquals(original.ants.x.copyOf(n), replayed.ants.x.copyOf(n))
        assertContentEquals(original.ants.y.copyOf(n), replayed.ants.y.copyOf(n))
        assertContentEquals(original.ants.heading.copyOf(n), replayed.ants.heading.copyOf(n))
        assertContentEquals(original.ants.flags.copyOf(n), replayed.ants.flags.copyOf(n))
        assertContentEquals(original.grid.food, replayed.grid.food)
        assertContentEquals(original.grid.home, replayed.grid.home)
        assertEquals(original.store, replayed.store)
        assertEquals(original.state().copy(voices = emptyList()), replayed.state().copy(voices = emptyList()))
        assertEquals(original.fingerprint(), replayed.fingerprint())
        // The pheromone wasn't trivially empty.
        assertTrue(original.grid.home.any { it > 0f })
    }

    @Test
    fun `the log records every kind of action with its tick, and round-trips through text`() {
        val (original, _) = live(seed = 42)
        val actions = original.log.actions
        val kinds = actions.map { it.action::class }.toSet()
        for (k in listOf(Action.PlaceFood::class, Action.SetParam::class, Action.Flood::class, Action.Reset::class,
            Action.RemoveFood::class, Action.FlattenHeadings::class)) {
            assertTrue(k in kinds, "no ${k.simpleName} in the log")
        }
        // The pile-size change was logged before the placeFood it preceded.
        val sizeChange = actions.indexOfFirst { (it.action as? Action.SetParam)?.key == "food.pileAmount" }
        val thirdPile = actions.indexOfFirst { it.action == Action.PlaceFood(300.0, 140.0) }
        assertTrue(sizeChange in 0 until thirdPile)
        assertEquals(20L * World.TICKS_PER_SECOND, actions.first { (it.action as? Action.SetParam)?.key == "pheromone.evaporation" }.tick)
        assertEquals(actions, ActionLog.parse(original.log.toText()))
    }

    @Test
    fun `the same log with a different seed gives a different colony`() {
        val (a, steps) = live(seed = 42)
        val b = AntColony(43, gridSize = 128, terrainSize = 64)
        Replayer(b, a.log.actions).run(steps)
        assertNotEquals(a.fingerprint(), b.fingerprint())
    }
}
