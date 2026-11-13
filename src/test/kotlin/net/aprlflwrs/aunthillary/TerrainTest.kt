package net.aprlflwrs.aunthillary

import net.aprlflwrs.aunthillary.core.AntColony
import net.aprlflwrs.aunthillary.core.Terrain
import net.aprlflwrs.aunthillary.core.World
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The nest ring (`terrain.nestRing`): deterministic, open through its gates, and fully switchable off. */
class TerrainTest {
    private fun colony(seed: Long, ring: Boolean, terrainSize: Int = 256) =
        AntColony(seed, gridSize = 128, terrainSize = terrainSize).apply {
            if (ring) { setParam("terrain.nestRing", 1.0); apply(net.aprlflwrs.aunthillary.core.Action.Reset) }
        }

    /** Cells reachable from the nest's cell over unblocked cells (4-neighbour flood fill). */
    private fun reachable(t: Terrain, nestX: Double, nestY: Double): BooleanArray {
        val n = t.size
        val seen = BooleanArray(n * n)
        val start = (nestY / t.cell).toInt() * n + (nestX / t.cell).toInt()
        val queue = ArrayDeque<Int>()
        seen[start] = true; queue.add(start)
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val r = i / n; val c = i % n
            for ((nr, nc) in listOf(r - 1 to c, r + 1 to c, r to c - 1, r to c + 1)) {
                if (nr !in 0 until n || nc !in 0 until n) continue
                val j = nr * n + nc
                if (!seen[j] && !t.blocked[j]) { seen[j] = true; queue.add(j) }
            }
        }
        return seen
    }

    @Test
    fun `the ring is the same for the same seed and differs between seeds`() {
        val a = colony(7, ring = true).terrain
        val b = colony(7, ring = true).terrain
        val c = colony(8, ring = true).terrain
        assertContentEquals(a.blocked, b.blocked)
        assertContentEquals(a.heights, b.heights)
        assertEquals(a.gateAngles, b.gateAngles)
        assertFalse(a.gateAngles == c.gateAngles)
        assertEquals(5, a.gateAngles.size)
    }

    @Test
    fun `the ring blocks the annulus except at its gates`() {
        val col = colony(3, ring = true)
        val t = col.terrain
        val inner = col.p.ringRadius.value; val outer = inner + col.p.ringThickness.value
        val mid = (inner + outer) / 2
        // Walk round the middle of the ring: blocked, except within a gate's half width of a gate line.
        var open = 0
        for (k in 0 until 360) {
            val a = k * StrictMath.PI / 180
            if (!t.isBlocked(col.nestX + mid * StrictMath.cos(a), col.nestY + mid * StrictMath.sin(a))) open++
        }
        // Five 8-unit gates on a circle of radius 34 cover about 5 × 8 / (2π × 34) ≈ 19% of it.
        assertTrue(open in 30..110, "open degrees $open")
        for (g in t.gateAngles) {
            assertFalse(t.isBlocked(col.nestX + mid * StrictMath.cos(g), col.nestY + mid * StrictMath.sin(g)))
        }
    }

    @Test
    fun `as a ridge the ring blocks nothing near the nest and raises the ground`() {
        val rock = colony(3, ring = true)
        val ridge = AntColony(3, gridSize = 128, terrainSize = 256).apply {
            setParam("terrain.nestRing", 1.0); setParam("terrain.ringRidgeHeight", 12.0)
            apply(net.aprlflwrs.aunthillary.core.Action.Reset)
        }
        val mid = ridge.p.ringRadius.value + ridge.p.ringThickness.value / 2
        // Same gates as the rock ring (the same random numbers are drawn).
        assertEquals(rock.terrain.gateAngles, ridge.terrain.gateAngles)
        val between = (ridge.terrain.gateAngles[0] + ridge.terrain.gateAngles[1]) / 2
        val x = ridge.nestX + mid * StrictMath.cos(between); val y = ridge.nestY + mid * StrictMath.sin(between)
        assertTrue(rock.terrain.isBlocked(x, y))
        assertFalse(ridge.terrain.isBlocked(x, y))
        assertTrue(ridge.terrain.heightAt(x, y) > rock.terrain.heightAt(x, y) + 8)
    }

    @Test
    fun `with the switch off the colony is exactly as without the ring`() {
        // The ring's own parameters must change nothing while it is off: the same terrain and,
        // since no random numbers are drawn for it, the same ants.
        val plain = colony(11, ring = false)
        val tweaked = AntColony(11, gridSize = 128, terrainSize = 256).apply {
            setParam("terrain.ringGates", 3.0); setParam("terrain.ringRadius", 40.0)
            apply(net.aprlflwrs.aunthillary.core.Action.Reset)
        }
        repeat(60) { plain.step(); tweaked.step() }
        assertEquals(plain.fingerprint(), tweaked.fingerprint())
        assertTrue(plain.terrain.gateAngles.isEmpty())

        // And outside the ring and its gates, the ring changes nothing about the ground.
        val ringed = colony(11, ring = true)
        val reach = ringed.p.ringRadius.value + ringed.p.ringThickness.value + 2 * ringed.p.rockRadius.value + 2 * ringed.terrain.cell
        val t0 = plain.terrain; val t1 = ringed.terrain
        for (r in 0 until t0.size) for (c in 0 until t0.size) {
            val dx = (c + 0.5) * t0.cell - plain.nestX; val dy = (r + 0.5) * t0.cell - plain.nestY
            if (StrictMath.hypot(dx, dy) > reach) assertEquals(t0.blocked[r * t0.size + c], t1.blocked[r * t0.size + c])
        }
        assertContentEquals(t0.heights, t1.heights)
    }

    @Test
    fun `every direction is reachable through the gates, for many seeds and grid sizes`() {
        for (size in listOf(64, 256)) for (seed in 1L..20L) {
            val col = colony(seed, ring = true, terrainSize = size)
            val t = col.terrain
            val seen = reachable(t, col.nestX, col.nestY)
            // 16 directions, 150 units out: some unblocked cell within 12 units of the target is reachable.
            for (k in 0 until 16) {
                val a = k * 2 * StrictMath.PI / 16
                val tx = col.nestX + 150 * StrictMath.cos(a); val ty = col.nestY + 150 * StrictMath.sin(a)
                var ok = false
                for (r in 0 until t.size) for (c in 0 until t.size) {
                    if (!seen[r * t.size + c]) continue
                    if (StrictMath.hypot((c + 0.5) * t.cell - tx, (r + 0.5) * t.cell - ty) <= 12) { ok = true; break }
                }
                assertTrue(ok, "seed $seed size $size: direction $k cut off")
            }
        }
    }

    @Test
    fun `food beyond the ring, between two gates, is found and brought home`() {
        val col = AntColony(4, gridSize = 128, terrainSize = 64)
        col.setParam("terrain.nestRing", 1.0)
        col.setParam("terrain.rockCount", 0.0)
        col.apply(net.aprlflwrs.aunthillary.core.Action.Reset)
        // Halfway between the first two gates, so no gate points at it.
        val g = col.terrain.gateAngles
        val a = (g[0] + g[1]) / 2
        col.placeFood(col.nestX + 120 * StrictMath.cos(a), col.nestY + 120 * StrictMath.sin(a))
        val pile = col.piles.single()
        repeat(240 * World.TICKS_PER_SECOND) { col.step() }
        assertTrue(pile.delivered > 200, "delivered ${pile.delivered}")
    }
}
