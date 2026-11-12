package net.aprlflwrs.aunthillary

import net.aprlflwrs.aunthillary.core.AntColony
import net.aprlflwrs.aunthillary.core.TrailMetrics
import net.aprlflwrs.aunthillary.core.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 1A's "done when", checked on headless metrics rather than by eye.
 *
 * One colony (seed 1, the full 512² grid, default parameters) gets two food piles at the
 * start, in directions about 110° apart, and runs until both piles are empty and the colony
 * has had time to forget them. Every simulated second it records the metrics below; each test
 * reads them. The run is shared (it takes under a minute) and nothing is scaled down,
 * because the population target, the trail fade and the symbol wear-down are all about the
 * default parameters' real time scales.
 *
 * To try another seed: `SCENARIO_SEED=2 ./gradlew test --tests '*ColonyScenarioTest' --rerun`.
 *
 * Time windows are taken from the metrics (when each pile ran out), not hard-coded.
 */
class ColonyScenarioTest {
    class Sample(
        val seconds: Double,
        val population: Int,
        val amounts: List<Double>,
        /** Per pile, whether food pheromone links the nest to it. */
        val linked: List<Boolean>,
        /** Per control probe (directions with no food), the same. */
        val controlLinked: List<Boolean>,
        /** Directions and ids of the symbols. */
        val symbols: List<Pair<Double, Int>>,
        val foodTotal: Double,
    )

    companion object {
        /** East-north-east (0.30 rad) and north-west (2.23 rad), both about 150 units out. */
        val PILES = listOf(400.0 to 300.0, 160.0 to 380.0)
        /** Same distance, in directions with no food: south-west and south-east. */
        val CONTROLS = listOf(256.0 - 106 to 256.0 - 106, 256.0 + 106 to 256.0 - 106)

        /** A symbol counts as "toward" a pile if its direction is within this of the pile's (2/3 of a bin). */
        const val ANGLE_TOLERANCE = 0.26

        val SEED = System.getenv("SCENARIO_SEED")?.toLong() ?: 1L

        const val MAX_SECONDS = 900.0
        /** After both piles are empty, keep going this long so trails and symbols can wear down. */
        const val AFTER_EMPTY_SECONDS = 200.0

        val run: List<Sample> by lazy { simulate() }
        lateinit var colony: AntColony

        private fun simulate(): List<Sample> {
            val c = AntColony(seed = SEED)
            colony = c
            for ((x, y) in PILES) c.placeFood(x, y)
            val samples = mutableListOf<Sample>()
            var bothEmptyAt = Double.NaN
            while (c.tick * World.DT < MAX_SECONDS) {
                repeat(World.TICKS_PER_SECOND) { c.step() }
                val links = TrailMetrics.linked(
                    c.grid.food, c.grid.size, c.nestX, c.nestY, c.p.hubRadius.value,
                    PILES + CONTROLS, c.p.scentRadius.value, TRAIL_THRESHOLD,
                )
                val seconds = c.tick * World.DT
                samples += Sample(
                    seconds = seconds,
                    population = c.population,
                    amounts = c.piles.map { it.amount },
                    linked = links.take(PILES.size),
                    controlLinked = links.drop(PILES.size),
                    symbols = c.symbols().map { it.direction to it.id },
                    foodTotal = TrailMetrics.total(c.grid.food, c.grid.size),
                )
                if (bothEmptyAt.isNaN() && c.piles.all { it.amount <= 0 }) bothEmptyAt = seconds
                if (seconds - bothEmptyAt >= AFTER_EMPTY_SECONDS) break
            }
            return samples
        }

        fun pileDirection(i: Int) = StrictMath.atan2(PILES[i].second - World.SIZE / 2, PILES[i].first - World.SIZE / 2)

        /** Seconds at which pile [i] ran out. */
        fun emptiedAt(i: Int): Double {
            val s = run.firstOrNull { it.amounts[i] <= 0 }
            assertNotNull(s, "pile $i never ran out within the run")
            return s.seconds
        }

        fun symbolsToward(s: Sample, i: Int) = s.symbols.filter { angleBetween(it.first, pileDirection(i)) < ANGLE_TOLERANCE }
    }

    @Test
    fun `both piles were placed`() {
        run
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
        // A fed colony settles about one lifespan after food starts coming in, so the window runs from
        // one and a half lifespans in (225 s) until the last pile runs out.
        run // runs the scenario (and sets colony) if no other test has yet
        val settled = 1.5 * colony.p.lifespan.value
        val window = run.filter { it.seconds >= settled && it.seconds <= maxOf(emptiedAt(0), emptiedAt(1)) }
        assertTrue(window.size >= 60)
        val pops = window.map { it.population }
        val min = pops.min(); val max = pops.max(); val mean = pops.average()
        assertTrue(min >= 5_000 && max <= 30_000, "population ranged $min–$max, outside 5,000–30,000")
        assertTrue((max - min) / mean < 0.1, "population not settled: ranged $min–$max")
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
    fun `a symbol forms toward each pile and wears down after its food is gone`() {
        for (i in PILES.indices) {
            // It forms while the pile has food, and holds for at least half a minute. (Not necessarily all
            // the time: the symbols compete for newborns, so a busier pile's symbol can crowd out a
            // quieter one's for a while, as happens with seed 4.)
            val fed = run.filter { it.amounts[i] > 0 }.map { symbolsToward(it, i).isNotEmpty() }
            val longest = fed.fold(0 to 0) { (best, cur), present -> if (present) maxOf(best, cur + 1) to cur + 1 else best to 0 }.first
            assertTrue(longest >= 30, "symbol toward pile $i held for at most $longest seconds while it had food")
            // While it lasts it keeps one identity: from one second to the next, the id never changes.
            for ((a, b) in run.zipWithNext()) {
                val before = symbolsToward(a, i).map { it.second }.toSet()
                val after = symbolsToward(b, i).map { it.second }.toSet()
                assertTrue(before.isEmpty() || after.isEmpty() || before.intersect(after).isNotEmpty(),
                    "the symbol toward pile $i changed identity at ${b.seconds}s: $before -> $after")
            }
            // Gone within 200 s of the food, and it stays gone.
            val lastSeen = run.last { symbolsToward(it, i).isNotEmpty() }.seconds
            assertTrue(lastSeen > emptiedAt(i), "the symbol should outlive its food a little")
            assertTrue(lastSeen < emptiedAt(i) + 200, "the symbol toward pile $i lasted until ${lastSeen}s; food ran out at ${emptiedAt(i)}s")
        }
        // At some moment both symbols are there together.
        assertTrue(run.any { s -> PILES.indices.all { symbolsToward(s, it).isNotEmpty() } })
    }
}
