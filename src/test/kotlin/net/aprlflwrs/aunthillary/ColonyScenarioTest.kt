package net.aprlflwrs.aunthillary

import net.aprlflwrs.aunthillary.core.AntColony
import net.aprlflwrs.aunthillary.core.HeadingDistribution
import net.aprlflwrs.aunthillary.core.TrailMetrics
import net.aprlflwrs.aunthillary.core.World
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import org.junit.experimental.categories.Category
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 1A's "done when", checked on headless metrics rather than by eye.
 *
 * The main run: one colony (seed 1, default parameters, on a 256² grid to keep `check` under
 * a minute; PheromoneGridTest shows the grid behaves the same at any resolution) gets two piles
 * of 20,000 units at the start, in directions about 110° apart, and runs until both are empty
 * and seven minutes more, as the colony starves to its floor. A second, shorter run gets only the first
 * pile, to show that less food supports fewer ants. The two run at once, on two threads (each
 * colony is still single-threaded and deterministic). Every simulated second each run records
 * the metrics below, and the tests read them.
 *
 * Nothing sets the population's size: it comes from the hunger economy, so the tests check
 * that it settles and that it starves back to the floor, not that it reaches a number.
 * Time windows are taken from the metrics (when each pile ran out), not hard-coded.
 *
 * It takes a few minutes, so it runs in `./gradlew slowTest`, not in `test` or `check`.
 * To try another seed: `SCENARIO_SEED=2 ./gradlew slowTest`.
 */
@Category(SlowTests::class)
class ColonyScenarioTest {
    class Sample(
        val seconds: Double,
        val population: Int,
        val amounts: List<Double>,
        /** Per pile, whether food pheromone links the nest to it. */
        val linked: List<Boolean>,
        /** Per control probe (directions with no food), the same. */
        val controlLinked: List<Boolean>,
        val symbols: List<SymbolSample>,
        /** The heading distribution's weights, one per bin. */
        val weights: DoubleArray,
        val foodTotal: Double,
    )

    class SymbolSample(val direction: Double, val id: Int, val strength: Double)

    class Run(val colony: AntColony, val samples: List<Sample>)

    companion object {
        /** East-north-east (0.30 rad) and north-west (2.23 rad), both about 150 units out. */
        val PILES = listOf(400.0 to 300.0, 160.0 to 380.0)
        /** Same distance, in directions with no food: south-west and south-east. */
        val CONTROLS = listOf(256.0 - 106 to 256.0 - 106, 256.0 + 106 to 256.0 - 106)
        const val PILE_AMOUNT = 20_000.0
        const val GRID = 256

        /** A symbol counts as "toward" a pile if its direction is within this of the pile's (2/3 of a bin). */
        const val ANGLE_TOLERANCE = 0.26

        val SEED = System.getenv("SCENARIO_SEED")?.toLong() ?: 1L

        const val MAX_SECONDS = 1500.0
        /** After both piles are empty, keep going this long so trails fade and the colony starves to its floor. */
        const val AFTER_EMPTY_SECONDS = 420.0
        /** The single-pile run only needs to reach its settled size. */
        const val SINGLE_SECONDS = 450.0

        /** Both runs start together, on first use (not when the class loads). */
        private val runs by lazy {
            val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "scenario").apply { isDaemon = true } }
            CompletableFuture.supplyAsync({ simulate(PILES, MAX_SECONDS, untilForgotten = true) }, pool) to
                CompletableFuture.supplyAsync({ simulate(PILES.take(1), SINGLE_SECONDS, untilForgotten = false) }, pool)
        }

        val run: List<Sample> get() = runs.first.get().samples
        val colony: AntColony get() = runs.first.get().colony
        val single: List<Sample> get() = runs.second.get().samples

        private fun simulate(piles: List<Pair<Double, Double>>, maxSeconds: Double, untilForgotten: Boolean): Run {
            val c = AntColony(seed = SEED, gridSize = GRID)
            c.setParam("food.pileAmount", PILE_AMOUNT)
            for ((x, y) in piles) c.placeFood(x, y)
            val samples = mutableListOf<Sample>()
            var allEmptyAt = Double.NaN
            while (c.tick * World.DT < maxSeconds) {
                repeat(World.TICKS_PER_SECOND) { c.step() }
                val links = TrailMetrics.linked(
                    c.grid.food, c.grid.size, c.nestX, c.nestY, c.p.hubRadius.value,
                    piles + CONTROLS, c.p.scentRadius.value, TRAIL_THRESHOLD,
                )
                val seconds = c.tick * World.DT
                samples += Sample(
                    seconds = seconds,
                    population = c.population,
                    amounts = c.piles.map { it.amount },
                    linked = links.take(piles.size),
                    controlLinked = links.drop(piles.size),
                    symbols = c.symbols().map { SymbolSample(it.direction, it.id, it.strength) },
                    weights = c.headings.weightsCopy(),
                    foodTotal = TrailMetrics.total(c.grid.food, c.grid.size),
                )
                if (allEmptyAt.isNaN() && c.piles.all { it.amount <= 0 }) allEmptyAt = seconds
                if (untilForgotten && seconds - allEmptyAt >= AFTER_EMPTY_SECONDS) break
            }
            return Run(c, samples)
        }

        fun pileDirection(i: Int) = StrictMath.atan2(PILES[i].second - World.SIZE / 2, PILES[i].first - World.SIZE / 2)

        /** Seconds at which pile [i] ran out. */
        fun emptiedAt(i: Int): Double {
            val s = run.firstOrNull { it.amounts[i] <= 0 }
            assertNotNull(s, "pile $i never ran out within the run")
            return s.seconds
        }

        fun symbolsToward(s: Sample, i: Int) = s.symbols.filter { angleBetween(it.direction, pileDirection(i)) < ANGLE_TOLERANCE }

        /** The ids of the symbols that pointed at pile [i] while it had food. */
        fun symbolIds(i: Int) = run.filter { it.amounts[i] > 0 }.flatMap { symbolsToward(it, i) }.map { it.id }.toSet()

        /** The last second at which one of pile [i]'s symbols (by id, wherever it has drifted) was alive. */
        fun symbolLastSeen(i: Int): Double {
            val ids = symbolIds(i)
            return run.last { s -> s.symbols.any { it.id in ids } }.seconds
        }

        fun pileBin(i: Int) = HeadingDistribution.binOf(pileDirection(i))
    }

    @Test
    fun `both piles were placed`() {
        assertEquals(PILES.size, colony.piles.size, "a pile landed on a rock for this seed")
    }

    @Test
    fun `food pheromone links the nest to both piles, and not to directions without food`() {
        val firstEmpty = minOf(emptiedAt(0), emptiedAt(1))
        // From one minute in until the first pile runs out, both piles stay linked almost all the time.
        val fed = run.filter { it.seconds >= 60 && it.seconds < firstEmpty }
        assertTrue(fed.size > 60, "the piles should last well over a minute")
        val bothLinked = fed.count { it.linked.all { l -> l } }
        assertTrue(bothLinked >= 0.9 * fed.size, "both piles linked in only $bothLinked of ${fed.size} seconds")
        // The negative control: the trail network never reaches the empty directions.
        val leaks = run.count { it.controlLinked.any { l -> l } }
        assertEquals(0, leaks, "food pheromone linked the nest to a place with no food")
    }

    @Test
    fun `the population settles in the target range while the colony is fed`() {
        // The last three minutes before the first pile runs out.
        val firstEmpty = minOf(emptiedAt(0), emptiedAt(1))
        val window = run.filter { it.seconds >= firstEmpty - 180 && it.seconds < firstEmpty }
        assertTrue(window.size >= 170, "the piles ran out before the colony could settle")
        val pops = window.map { it.population }
        val min = pops.min(); val max = pops.max(); val mean = pops.average()
        assertTrue(min >= 5_000 && max <= 30_000, "population ranged $min–$max, outside 5,000–30,000")
        assertTrue((max - min) / mean < 0.2, "population not settled: ranged $min–$max")
        // And it got there by growing from food: well above where it started.
        assertTrue(mean > 2 * run.first().population)
    }

    @Test
    fun `the colony starves back to its floor when the food is gone`() {
        val lastEmpty = maxOf(emptiedAt(0), emptiedAt(1))
        val floor = colony.p.populationFloor.value
        val after = run.filter { it.seconds >= lastEmpty + 200 }
        assertTrue(after.isNotEmpty())
        val max = after.maxOf { it.population }
        assertTrue(max <= 1.05 * floor, "population still $max, 200 s after the food ran out (floor $floor)")
    }

    @Test
    fun `more food supports more ants`() {
        // Same seed, same window: one pile against two.
        val window = { s: List<Sample> -> s.filter { it.seconds >= SINGLE_SECONDS - 120 && it.seconds <= SINGLE_SECONDS } }
        val one = window(single).map { it.population }.average()
        val two = window(run).map { it.population }.average()
        assertTrue(window(single).all { it.amounts[0] > 0 } && window(run).all { s -> s.amounts.all { it > 0 } },
            "both colonies should still be fed in the comparison window")
        assertTrue(two > 1.5 * one, "two piles supported $two ants, one pile $one")
    }

    @Test
    fun `trails fade after the food runs out`() {
        for (i in PILES.indices) {
            val gone = emptiedAt(i) + 150
            val after = run.filter { it.seconds >= gone }
            assertTrue(after.isNotEmpty())
            val stillLinked = after.count { it.linked[i] }
            assertEquals(0, stillLinked, "pile $i still linked $stillLinked seconds into the 150 s after it ran out")
        }
        val peak = run.maxOf { it.foodTotal }
        assertTrue(run.last().foodTotal < 0.02 * peak, "food pheromone ${run.last().foodTotal} vs peak $peak at the end")
    }

    @Test
    fun `a symbol forms toward each pile and outlives its food`() {
        for (i in PILES.indices) {
            // It forms while the pile has food, and holds for at least half a minute. (Not necessarily all
            // the time: the symbols compete for newborns, so a busier pile's symbol can crowd out a
            // quieter one's for a while.)
            val fed = run.filter { it.amounts[i] > 0 }.map { symbolsToward(it, i).isNotEmpty() }
            val longest = fed.fold(0 to 0) { (best, cur), present -> if (present) maxOf(best, cur + 1) to cur + 1 else best to 0 }.first
            assertTrue(longest >= 30, "symbol toward pile $i held for at most $longest seconds while it had food")
            // While it lasts it keeps one identity: from one second to the next, the id never changes.
            for ((a, b) in run.zipWithNext()) {
                val before = symbolsToward(a, i).map { it.id }.toSet()
                val after = symbolsToward(b, i).map { it.id }.toSet()
                assertTrue(before.isEmpty() || after.isEmpty() || before.intersect(after).isNotEmpty(),
                    "the symbol toward pile $i changed identity at ${b.seconds}s: $before -> $after")
            }
            // It outlives its food: the colony's belief lingers after the trail is gone. (Followed
            // by id, since a fading peak can shrink onto a neighboring bin.)
            assertTrue(symbolLastSeen(i) > emptiedAt(i) + 30,
                "the symbol toward pile $i should outlive its food; last seen ${symbolLastSeen(i)}s, food ran out at ${emptiedAt(i)}s")
        }
        // At some moment both symbols are there together.
        assertTrue(run.any { s -> PILES.indices.all { symbolsToward(s, it).isNotEmpty() } })
    }

    /**
     * The heading distribution changes only with evidence (deliveries and empty-handed returns),
     * not with time. So after the food is gone, a belief wears down only as fast as ants come
     * home empty-handed from that direction. Most of those come as the colony starves: its
     * foragers keep going back to the piles they knew, and fail. How that wave divides between
     * the two directions depends on the run (with seed 1 the pile that ran out last takes nearly
     * all of it and its symbol is gone within about a minute; with seed 2 the piles run out seven
     * seconds apart, the wave is split, and both symbols outlive it). Once the colony is at its
     * floor of a few hundred ants, it brings home too few reports to wear down what is left within
     * the run, so whatever belief survives the wave lingers. So each belief is only required to
     * have weakened, and to stay still at the floor.
     */
    @Test
    fun `beliefs wear down only with empty-handed returns`() {
        for (i in PILES.indices) {
            val atEmpty = run.first { it.seconds >= emptiedAt(i) }.weights[pileBin(i)]
            val atEnd = run.last().weights[pileBin(i)]
            assertTrue(atEnd < 0.9 * atEmpty, "the weight toward pile $i went from $atEmpty when it ran out to $atEnd at the end")
        }
        // At the floor nothing else changes it: over the last minute, with a few hundred ants, the
        // weights move only as far as a handful of reports can move them.
        val minuteAgo = run[run.size - 61].weights
        val change = run.last().weights.indices.sumOf { kotlin.math.abs(run.last().weights[it] - minuteAgo[it]) }
        assertTrue(change < 0.05 * minuteAgo.sum().coerceAtLeast(1.0) + 30,
            "the weights changed by $change in the last minute at the floor")
    }
}
