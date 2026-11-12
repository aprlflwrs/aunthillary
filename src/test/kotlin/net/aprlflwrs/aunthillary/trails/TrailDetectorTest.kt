package net.aprlflwrs.aunthillary.trails

import net.aprlflwrs.aunthillary.core.Params
import net.aprlflwrs.aunthillary.core.Voice
import java.io.File
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrailDetectorTest {
    private fun detector() = TrailDetector(Params())

    /**
     * A fork: one trunk east from the nest, splitting at a junction into a north-east and a
     * south-east branch, each ending at a pile. [t] drifts the junction and the branches.
     */
    private fun fork(t: Int, seed: Long = t.toLong()): SyntheticGrid {
        val jx = 320.0 + 4 * t
        val jy = 256.0 + 2 * t
        return SyntheticGrid()
            .trail(256.0 to 256.0, jx to jy)
            .trail(jx to jy, 370.0 to 300.0 + t, 410.0 to 340.0 + t)
            .trail(jx to jy, 370.0 to 210.0 - t, 410.0 to 170.0 - t)
            .noise(seed)
    }

    private fun forkPiles(t: Int) = listOf(pile(1, 410.0, 340.0 + t), pile(2, 410.0, 170.0 - t))

    private fun Voice.closestTo(x: Double, y: Double) = points.minOf { hypot(it[0] - x, it[1] - y) }

    @Test
    fun `a drifting fork keeps both voices' ids and slots, and they share their trunk`() {
        val d = detector()
        var first: List<Voice>? = null
        var last: List<Voice> = emptyList()
        for (t in 0..7) {
            val voices = d.detect(fork(t).input(tick = 15L * t, piles = forkPiles(t)))
            assertEquals(listOf(1, 2), voices.map { it.foodId }.sorted(), "call $t")
            if (first == null) first = voices
            for (v in voices) {
                val was = first.single { it.foodId == v.foodId }
                assertEquals(was.id, v.id, "voice for pile ${v.foodId} kept its id at call $t")
                assertEquals(was.slot, v.slot, "voice for pile ${v.foodId} kept its slot at call $t")
                assertEquals(0L, v.bornTick)
            }
            // The trunk runs about 52–80 world units from the hub's edge to the junction, so at
            // 2 units per point the two voices share at least 20 identical opening points, and
            // then diverge.
            val (a, b) = voices
            val shared = sharedPrefix(a, b)
            assertTrue(shared >= 20, "only $shared shared points at call $t")
            assertTrue(shared < minOf(a.points.size, b.points.size), "voices never diverge at call $t")
            last = voices
        }
        // The routes really moved: the junction drifted 28 units.
        for (v in last) {
            val was = first!!.single { it.foodId == v.foodId }
            assertFalse(sameVoices(listOf(was), listOf(v)))
        }
    }

    @Test
    fun `points follow the interface - from the hub edge, every 2 units, strengths in range, median strength`() {
        val voices = detector().detect(fork(0).input(0, forkPiles(0)))
        val g = SyntheticGrid()
        for (v in voices) {
            assertTrue(v.slot in 0..3)
            val start = v.points.first()
            assertEquals(g.hubRadius, hypot(start[0] - g.nestX, start[1] - g.nestY), 1.5, "starts at the hub's edge")
            val end = v.points.last()
            val pile = forkPiles(0).single { it.id == v.foodId }
            assertTrue(hypot(end[0] - pile.x, end[1] - pile.y) < 5.0, "ends near the food")
            for (k in 1 until v.points.size - 1) {
                val p = v.points[k - 1]
                val q = v.points[k]
                assertEquals(2.0, hypot(q[0] - p[0], q[1] - p[1]), 0.6, "spacing at point $k")
            }
            for (p in v.points) {
                assertEquals(4, p.size)
                assertTrue(p[3] in 0.0..1.0)
            }
            val sorted = v.points.map { it[3] }.sorted()
            val n = sorted.size
            val median = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
            assertEquals(median, v.strength, 1e-12)
            assertTrue(v.strength > 0.5, "a 0.6 trail with noise has a strong median, got ${v.strength}")
        }
    }

    @Test
    fun `a pile with no trail to it gets no voice`() {
        val grid = fork(0)
            // A trail heading for pile 4 that stops 25 units short: a gap far longer than maxGap.
            .trail(256.0 to 256.0, 256.0 to 360.0, value = 0.6f)
        val piles = forkPiles(0) + listOf(
            pile(3, 120.0, 420.0),   // nothing at all near it
            pile(4, 256.0, 385.0),   // trail stops short
            pile(5, 258.0, 254.0),   // inside the hub
            pile(6, -5.0, 600.0),    // outside the world
        )
        grid.block(100.0, 60.0, 140.0, 100.0)
        val withBlocked = piles + pile(7, 120.0, 80.0) // in the middle of a rock
        val voices = detector().detect(grid.input(0, withBlocked))
        assertEquals(listOf(1, 2), voices.map { it.foodId }.sorted())
    }

    @Test
    fun `short gaps are allowed and long gaps are not`() {
        fun voicesWithGap(radius: Double) = detector().detect(
            SyntheticGrid().trail(256.0 to 256.0, 256.0 to 400.0).noise(1).gap(256.0, 330.0, radius)
                .input(0, listOf(pile(1, 256.0, 400.0))),
        )
        assertEquals(1, voicesWithGap(2.0).size, "a 4-unit gap is bridged")
        assertEquals(0, voicesWithGap(8.0).size, "a 16-unit gap breaks the trail")
    }

    @Test
    fun `spurs never become voices`() {
        // A trunk north to a pile, with a dead-end spur off to the west that is even stronger than
        // the trail itself, and a spur that leaves the trunk and rejoins it (a loop).
        val grid = SyntheticGrid()
            .trail(256.0 to 256.0, 256.0 to 420.0, value = 0.5f)
            .trail(256.0 to 320.0, 180.0 to 360.0, value = 0.9f)
            .trail(256.0 to 350.0, 290.0 to 370.0, 256.0 to 390.0, value = 0.5f)
            .noise(3)
        val voices = detector().detect(grid.input(0, listOf(pile(1, 256.0, 420.0))))
        val v = voices.single()
        assertEquals(1, v.foodId)
        assertTrue(v.closestTo(180.0, 360.0) > 40.0, "the voice wandered down the spur")
        assertTrue(v.closestTo(290.0, 370.0) > 20.0, "the voice went round the loop")
    }

    @Test
    fun `routes go around blocked cells, even when pheromone lies on them`() {
        val grid = SyntheticGrid()
            .trail(256.0 to 256.0, 400.0 to 256.0, value = 0.8f)         // straight through the rock
            .trail(290.0 to 256.0, 300.0 to 300.0, 340.0 to 300.0, 350.0 to 256.0, value = 0.4f) // detour
            .block(305.0, 240.0, 335.0, 280.0)
            .noise(4)
        val v = detector().detect(grid.input(0, listOf(pile(1, 400.0, 256.0)))).single()
        for (p in v.points) {
            val inside = p[0] in 308.0..332.0 && p[1] in 243.0..277.0
            assertFalse(inside, "point (${p[0]}, ${p[1]}) is inside the rock")
        }
        assertTrue(v.closestTo(320.0, 300.0) < 3.0, "the voice should take the detour")
    }

    /** Five spokes from the nest, one pile at the end of each, and [values] the trail strengths. */
    private fun spokes(values: List<Float>, seed: Long): SyntheticGrid {
        val g = SyntheticGrid()
        for ((k, v) in values.withIndex()) {
            if (v <= 0f) continue
            val (x, y) = spokeEnd(k)
            g.trail(256.0 to 256.0, x to y, value = v)
        }
        return g.noise(seed)
    }

    private fun spokeEnd(k: Int): Pair<Double, Double> {
        val a = k * 2 * Math.PI / 5
        return (256.0 + 150 * Math.cos(a)) to (256.0 + 150 * Math.sin(a))
    }

    private val spokePiles = (0 until 5).map { k -> spokeEnd(k).let { (x, y) -> pile(k + 1, x, y) } }

    @Test
    fun `a fifth pile never takes a slot from a living voice, but takes a freed one`() {
        val d = detector()
        // Piles 1–4 have ordinary trails; pile 5 has none yet.
        val before = d.detect(spokes(listOf(0.4f, 0.4f, 0.4f, 0.4f, 0f), 1).input(0, spokePiles))
        assertEquals(listOf(1, 2, 3, 4), before.map { it.foodId }.sorted())
        val slotOf = before.associate { it.foodId to it.slot }
        val idOf = before.associate { it.foodId to it.id }

        // Pile 5's trail appears, far stronger than any other. It still gets nothing.
        for (t in 1..3) {
            val voices = d.detect(spokes(listOf(0.4f, 0.4f, 0.4f, 0.4f, 1.0f), 1L + t).input(15L * t, spokePiles))
            assertEquals(listOf(1, 2, 3, 4), voices.map { it.foodId }.sorted())
            for (v in voices) {
                assertEquals(slotOf[v.foodId], v.slot)
                assertEquals(idOf[v.foodId], v.id)
            }
        }

        // Pile 2's trail fades below the keep bar: its voice goes, and pile 5 takes exactly its slot.
        val after = d.detect(spokes(listOf(0.4f, 0.03f, 0.4f, 0.4f, 1.0f), 9).input(75, spokePiles))
        assertEquals(listOf(1, 3, 4, 5), after.map { it.foodId }.sorted())
        val five = after.single { it.foodId == 5 }
        assertEquals(slotOf[2], five.slot)
        assertEquals(75L, five.bornTick)
        assertTrue(five.id !in idOf.values, "a new voice gets a new id")
    }

    @Test
    fun `when five trails appear at once the strongest four get voices`() {
        val voices = detector().detect(spokes(listOf(0.5f, 0.3f, 0.7f, 0.6f, 0.9f), 2).input(0, spokePiles))
        assertEquals(listOf(1, 3, 4, 5), voices.map { it.foodId }.sorted())
        // Slots are handed out strongest first.
        assertEquals(listOf(5, 3, 4, 1), voices.sortedBy { it.slot }.map { it.foodId })
    }

    @Test
    fun `a removed pile's voice persists until its trail fades, and does not come back`() {
        val d = detector()
        val piles = listOf(pile(1, 256.0, 420.0))
        fun trail(value: Float, seed: Long) = SyntheticGrid().trail(256.0 to 256.0, 256.0 to 420.0, value = value).noise(seed)

        val born = d.detect(trail(0.6f, 0).input(0, piles)).single()

        // The pile is used up and leaves the input, but its trail is still there: the voice stays.
        var strength = born.strength
        for ((t, value) in listOf(0.5f, 0.35f, 0.2f).withIndex()) {
            val v = d.detect(trail(value, t + 1L).input(15L * (t + 1), emptyList())).single()
            assertEquals(born.id, v.id)
            assertEquals(born.slot, v.slot)
            assertEquals(1, v.foodId)
            assertTrue(v.strength < strength, "strength falls as the trail fades")
            strength = v.strength
        }

        // The trail fades below the keep bar (0.15 × 0.6 = 0.09): the voice is gone.
        assertTrue(d.detect(trail(0.05f, 10).input(60, emptyList())).isEmpty())

        // With the voice gone the ghost is gone too, so a trail there now has no target.
        assertTrue(d.detect(trail(0.6f, 11).input(75, emptyList())).isEmpty())

        // The pile coming back (say the user puts food there again) starts a new voice.
        val again = d.detect(trail(0.6f, 12).input(90, piles)).single()
        assertTrue(again.id != born.id)
        assertEquals(90L, again.bornTick)
    }

    @Test
    fun `a voice near the threshold does not flicker`() {
        val d = detector()
        val piles = listOf(pile(1, 256.0, 420.0))
        fun trail(value: Float, seed: Long) = SyntheticGrid().trail(256.0 to 256.0, 256.0 to 420.0, value = value).noise(seed, 0.02f)
        // Born above the threshold (0.15), then wobbling just under it: kept, with the same id.
        val id = d.detect(trail(0.2f, 0).input(0, piles)).single().id
        for ((t, value) in listOf(0.13f, 0.16f, 0.12f, 0.14f).withIndex()) {
            assertEquals(id, d.detect(trail(value, t + 1L).input(15L * (t + 1), piles)).single().id)
        }
        // But a new trail that never reached the threshold gets no voice.
        assertTrue(detector().detect(trail(0.13f, 0).input(0, piles)).isEmpty())
    }

    @Test
    fun `same inputs give the same outputs`() {
        fun run(): List<List<Voice>> {
            val d = detector()
            val out = ArrayList<List<Voice>>()
            for (t in 0..5) {
                val g = fork(t).block(300.0, 280.0, 320.0, 300.0)
                val piles = if (t < 4) forkPiles(t) + pile(9, 100.0, 100.0) else forkPiles(t).take(1)
                out += d.detect(g.input(15L * t, piles))
            }
            return out
        }
        val a = run()
        val b = run()
        assertEquals(a.size, b.size)
        for (k in a.indices) assertTrue(sameVoices(a[k], b[k]), "call $k differs")
        assertTrue(a.all { it.isNotEmpty() })
    }

    @Test
    fun `reset forgets every voice and restarts ids`() {
        val d = detector()
        val first = d.detect(fork(0).input(0, forkPiles(0)))
        d.reset()
        val again = d.detect(fork(0).input(0, forkPiles(0)))
        assertTrue(sameVoices(first, again))
    }

    @Test
    fun `works at other grid resolutions, with a coarser terrain grid`() {
        // Pheromone at 256², terrain at 128² with a hill, as the fake colony's terrain is coarser.
        val g = SyntheticGrid(256).trail(256.0 to 256.0, 400.0 to 256.0).noise(5)
        val heights = FloatArray(128 * 128) { c -> (c % 128) / 10f } // rises to the east
        val input = DetectorInput(
            tick = 0, size = 256, food = g.food, terrainSize = 128, heights = heights,
            blocked = BooleanArray(128 * 128), nestX = 256.0, nestY = 256.0, hubRadius = 12.0,
            piles = listOf(pile(1, 400.0, 256.0)), displayMax = 1.0,
        )
        val v = TrailDetector(Params()).detect(input).single()
        assertTrue(v.points.size in 60..80, "about 132 units at 2 per point, got ${v.points.size}")
        // Height is sampled by world position: x / 4 units per terrain cell / 10.
        val p = v.points[v.points.size / 2]
        assertEquals((p[0] / 4 - 0.5) / 10, p[2], 0.05)
    }

    @Test
    fun `displayMax scales strengths`() {
        val g = SyntheticGrid().trail(256.0 to 256.0, 256.0 to 400.0, value = 3.0f)
        val v = detector().detect(g.input(0, listOf(pile(1, 256.0, 400.0)), displayMax = 6.0)).single()
        assertEquals(0.5, v.strength, 0.01)
    }

    @Test
    fun `debug images of every stage are written when a directory is given`() {
        val dir = File("build/trail-debug")
        dir.deleteRecursively()
        val d = TrailDetector(Params(), debugDir = dir)
        val grid = fork(3).block(300.0, 280.0, 320.0, 300.0)
            .trail(256.0 to 256.0, 160.0 to 330.0, value = 0.9f) // a spur, so the images show one
        d.detect(grid.input(45, forkPiles(3) + pile(3, 120.0, 420.0)))
        for (stage in listOf("threshold", "cost", "routes", "voices")) {
            val f = File(dir, "00000045-$stage.png")
            assertTrue(f.isFile && f.length() > 0, "missing $f")
            assertNotNull(javax.imageio.ImageIO.read(f))
        }
    }
}
