package net.aprlflwrs.aunthillary.server

import net.aprlflwrs.aunthillary.core.AntBuffer
import net.aprlflwrs.aunthillary.core.Colony
import net.aprlflwrs.aunthillary.core.ColonyState
import net.aprlflwrs.aunthillary.core.FoodPile
import net.aprlflwrs.aunthillary.core.Nest
import net.aprlflwrs.aunthillary.core.Params
import net.aprlflwrs.aunthillary.core.PheromoneView
import net.aprlflwrs.aunthillary.core.Symbol
import net.aprlflwrs.aunthillary.core.TerrainView
import net.aprlflwrs.aunthillary.core.Voice
import net.aprlflwrs.aunthillary.core.World
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A stand-in colony with no simulation behind it: ants walk along a few hand-made trails that
 * drift and bend, voices appear and fade on a cycle, and symbols rise with their voices and
 * linger after them. It speaks exactly the same messages as the real colony will, so the
 * renderer and the sound engine can be built and tested before the simulation exists.
 *
 * Nothing here is emergent, and none of it should be copied into the real colony.
 */
class FakeColony(private val seed: Long) : Colony {
    override val params = Params()
    private val antCount = params.declare("fake.antCount", "Ants", "Fake colony", 0.0, 100_000.0, 1000.0, 30_000.0)
    private val drift = params.declare("fake.drift", "Trail drift (cycles per minute)", "Fake colony", 0.0, 10.0, 0.1, 1.5)
    private val voiceCycle = params.declare("fake.voiceCycle", "Voice life cycle (seconds)", "Fake colony", 10.0, 300.0, 1.0, 60.0)
    private val displayMax = params.declare("pheromone.displayMax", "Display max", "Pheromone", 0.01, 10.0, 0.01, 1.0)

    override var tick = 0L
        private set
    override var terrainVersion = 0
        private set

    private val nestX = World.SIZE / 2
    private val nestY = World.SIZE / 2
    private val hubRadius = 12.0
    private var moundHeight = 4.0

    private lateinit var rng: Random
    private val trails = mutableListOf<FakeTrail>()
    private val food = mutableListOf<FoodPile>()
    private var nextVoiceId = 1
    private var nextSymbolId = 1
    private var nextFoodId = 1

    // Per-ant state. An ant on a trail walks back and forth along it; the rest wander.
    private var antTrail = IntArray(0)    // index into trails, or -1 for a wanderer
    private var antS = DoubleArray(0)     // distance along the trail
    private var antDir = IntArray(0)      // +1 outward, -1 homeward
    private var antOffset = DoubleArray(0)
    private val ants = AntBuffer()

    private val terrainSize = 128
    private val heights = FloatArray(terrainSize * terrainSize)
    private val blocked = BooleanArray(terrainSize * terrainSize)

    init {
        reset()
    }

    override fun reset() {
        rng = Random(seed)
        tick = 0
        moundHeight = 4.0
        trails.clear(); food.clear()
        nextVoiceId = 1; nextSymbolId = 1; nextFoodId = 1
        // Three trails; the second branches off the first, so two voices share a trunk.
        val a = addTrail(angle = 0.7, distance = 190.0, parent = null, branchAt = 0.0, lifePhase = 0.0)
        addTrail(angle = 1.25, distance = 170.0, parent = a, branchAt = 70.0, lifePhase = 1.2)
        addTrail(angle = 3.6, distance = 160.0, parent = null, branchAt = 0.0, lifePhase = 2.7)
        antTrail = IntArray(0)
        buildTerrain()
        terrainVersion++
        updateTrails()
    }

    override fun step() {
        tick++
        updateTrails()
        moveAnts()
    }

    override fun placeFood(x: Double, y: Double) {
        val angle = atan2(y - nestY, x - nestX)
        val distance = hypot(x - nestX, y - nestY)
        if (distance <= hubRadius * 2 || trails.size >= 4) {
            // Too close to the nest, or every slot is taken: just show the pile.
            food += FoodPile(nextFoodId++, x, y, 1000.0)
            return
        }
        // Start the new voice's cycle right at its rising edge.
        val t = tick * World.DT
        val lifePhase = -2 * PI * t / voiceCycle.value
        addTrail(angle, distance, parent = null, branchAt = 0.0, lifePhase = lifePhase)
        updateTrails()
    }

    override fun flood() {
        // Every voice dies at once and returns later on a staggered cycle; the mound erodes.
        val t = tick * World.DT
        // Phase π + i·0.9 puts every trail in the silent half of its cycle, each returning a little later.
        trails.forEachIndexed { i, trail -> trail.lifePhase = PI - 2 * PI * t / voiceCycle.value + i * 0.9 }
        moundHeight /= 2
        buildTerrain()
        terrainVersion++
    }

    override fun writeAnts(out: AntBuffer) {
        out.ensureCapacity(ants.count)
        out.count = ants.count
        ants.x.copyInto(out.x, endIndex = ants.count)
        ants.y.copyInto(out.y, endIndex = ants.count)
        ants.heading.copyInto(out.heading, endIndex = ants.count)
        ants.flags.copyInto(out.flags, endIndex = ants.count)
    }

    override fun pheromone(): PheromoneView {
        val n = terrainSize
        val cell = World.SIZE / n
        val foodLayer = FloatArray(n * n)
        val homeLayer = FloatArray(n * n)
        // Home scent: strongest at the nest, falling off with distance.
        for (row in 0 until n) for (col in 0 until n) {
            val d = hypot((col + 0.5) * cell - nestX, (row + 0.5) * cell - nestY)
            homeLayer[row * n + col] = (0.6 * exp(-d / 90.0)).toFloat()
        }
        // Food scent: a soft line along every living trail.
        for (trail in trails) {
            if (trail.strength <= 0) continue
            for (p in trail.points) splat(foodLayer, n, p[0] / cell, p[1] / cell, (trail.strength * p[3]).toFloat())
        }
        return PheromoneView(n, foodLayer, homeLayer)
    }

    override fun terrain() = TerrainView(terrainSize, heights.copyOf(), blocked.copyOf())

    override fun state(): ColonyState {
        val t = tick * World.DT
        val voices = trails.filter { it.voiceAlive }.map { trail ->
            Voice(
                id = trail.voiceId, slot = trail.slot, foodId = trail.foodId, bornTick = trail.voiceBornTick,
                strength = median(trail.points.map { it[3] * trail.strength }),
                points = trail.points.map { doubleArrayOf(it[0], it[1], it[2], it[3] * trail.strength) },
            )
        }
        val symbols = trails.filter { it.symbolAlive }.map { trail ->
            Symbol(
                id = trail.symbolId, direction = trail.angle, width = 0.5, strength = trail.symbolStrength,
                bornTick = trail.symbolBornTick, voiceIds = if (trail.voiceAlive) listOf(trail.voiceId) else emptyList(),
            )
        }
        return ColonyState(
            tick = tick,
            nest = Nest(nestX, nestY, hubRadius, store = 50 + 30 * sin(t / 20), population = ants.count, moundHeight = moundHeight),
            food = food.toList(),
            voices = voices,
            symbols = symbols,
            headings = headingBins(),
        )
    }

    // ---- trails ----

    private fun addTrail(angle: Double, distance: Double, parent: FakeTrail?, branchAt: Double, lifePhase: Double): FakeTrail {
        val foodPile = FoodPile(nextFoodId++, nestX + distance * cos(angle), nestY + distance * sin(angle), 1000.0)
        food += foodPile
        val trail = FakeTrail(slot = freeSlot(), foodId = foodPile.id, foodX = foodPile.x, foodY = foodPile.y,
            angle = angle, parent = parent, branchAt = branchAt, lifePhase = lifePhase,
            bendPhase = rng.nextDouble(2 * PI))
        trails += trail
        return trail
    }

    private fun freeSlot(): Int = (0..3).first { s -> trails.none { it.slot == s } }

    private fun updateTrails() {
        val t = tick * World.DT
        for (trail in trails) {
            // Life: half of every cycle alive, the other half silent.
            trail.strength = max(0.0, sin(2 * PI * t / voiceCycle.value + trail.lifePhase))
            val alive = trail.strength > 0.05
            if (alive && !trail.voiceAlive) { trail.voiceId = nextVoiceId++; trail.voiceBornTick = tick }
            trail.voiceAlive = alive

            // Symbol: rises quickly with its voice, then lingers for a while after the voice is gone.
            trail.symbolStrength = if (trail.strength > trail.symbolStrength)
                trail.symbolStrength + (trail.strength - trail.symbolStrength) * min(1.0, 2.0 * World.DT)
            else
                max(0.0, trail.symbolStrength - 0.04 * World.DT)
            val symbolAlive = trail.symbolStrength > 0.05
            if (symbolAlive && !trail.symbolAlive) { trail.symbolId = nextSymbolId++; trail.symbolBornTick = tick }
            trail.symbolAlive = symbolAlive

            trail.points = trailPoints(trail, t)
        }
    }

    /** Points every 2 world units: [x, y, height, strength along the trail]. Parents are computed first. */
    private fun trailPoints(trail: FakeTrail, t: Double): List<DoubleArray> {
        val start: DoubleArray
        val trunk: List<DoubleArray>
        if (trail.parent != null) {
            trunk = trail.parent.points.take((trail.branchAt / 2).toInt())
            start = trunk.lastOrNull() ?: doubleArrayOf(nestX, nestY, 0.0, 1.0)
        } else {
            trunk = emptyList()
            start = doubleArrayOf(nestX + hubRadius * cos(trail.angle), nestY + hubRadius * sin(trail.angle), 0.0, 1.0)
        }
        // A quadratic curve to the food, its middle swinging slowly from side to side.
        val ex = trail.foodX; val ey = trail.foodY
        val mx = (start[0] + ex) / 2; val my = (start[1] + ey) / 2
        val len = hypot(ex - start[0], ey - start[1])
        val swing = 0.35 * len * sin(2 * PI * drift.value / 60 * t + trail.bendPhase)
        val cx = mx - (ey - start[1]) / len * swing
        val cy = my + (ex - start[0]) / len * swing
        val samples = max(2, (len / 0.5).toInt())
        val out = ArrayList<DoubleArray>(trunk.size + (len / 2).toInt() + 2)
        out += trunk
        var px = start[0]; var py = start[1]; var walked = 0.0
        for (i in 1..samples) {
            val u = i.toDouble() / samples
            val x = (1 - u) * (1 - u) * start[0] + 2 * (1 - u) * u * cx + u * u * ex
            val y = (1 - u) * (1 - u) * start[1] + 2 * (1 - u) * u * cy + u * u * ey
            walked += hypot(x - px, y - py); px = x; py = y
            if (walked >= 2.0 || i == samples) {
                walked = 0.0
                val s = (out.size * 2.0)
                out += doubleArrayOf(x, y, heightAt(x, y), 0.8 + 0.2 * sin(s / 25 + t))
            }
        }
        return out
    }

    // ---- ants ----

    private fun moveAnts() {
        val target = antCount.value.toInt()
        if (antTrail.size != target) resizeAnts(target)
        val speed = 10.0 // world units per second
        for (i in 0 until target) {
            val trailIndex = antTrail[i]
            val trail = trails.getOrNull(trailIndex)
            if (trail != null && trail.strength > 0.15 && trail.points.size >= 2) {
                val pts = trail.points
                val length = (pts.size - 1) * 2.0
                antS[i] += antDir[i] * speed * World.DT
                if (antS[i] >= length) { antS[i] = length; antDir[i] = -1 }
                if (antS[i] <= 0) { antS[i] = 0.0; antDir[i] = 1 }
                val f = antS[i] / 2.0
                val k = min(floor(f).toInt(), pts.size - 2)
                val frac = f - k
                val ax = pts[k][0] + (pts[k + 1][0] - pts[k][0]) * frac
                val ay = pts[k][1] + (pts[k + 1][1] - pts[k][1]) * frac
                val dx = pts[k + 1][0] - pts[k][0]; val dy = pts[k + 1][1] - pts[k][1]
                val dl = max(1e-9, hypot(dx, dy))
                ants.x[i] = (ax - dy / dl * antOffset[i]).toFloat()
                ants.y[i] = (ay + dx / dl * antOffset[i]).toFloat()
                ants.heading[i] = (atan2(dy, dx) + if (antDir[i] < 0) PI else 0.0).toFloat()
                ants.flags[i] = if (antDir[i] < 0) (AntBuffer.RETURNING or AntBuffer.CARRYING_FOOD).toByte() else 0
            } else {
                // Wander: small random turns, bouncing off the world's edges.
                var h = ants.heading[i] + (rng.nextDouble() - 0.5) * 0.6
                var x = ants.x[i] + speed * World.DT * cos(h)
                var y = ants.y[i] + speed * World.DT * sin(h)
                if (x < 1 || x > World.SIZE - 1) { h = PI - h; x = x.coerceIn(1.0, World.SIZE - 1) }
                if (y < 1 || y > World.SIZE - 1) { h = -h; y = y.coerceIn(1.0, World.SIZE - 1) }
                ants.x[i] = x.toFloat(); ants.y[i] = y.toFloat(); ants.heading[i] = h.toFloat()
                ants.flags[i] = if (trailIndex < 0) AntBuffer.SCOUT.toByte() else 0
                if (trail != null && trail.points.isNotEmpty()) antS[i] = 0.0
            }
        }
        ants.count = target
    }

    private fun resizeAnts(n: Int) {
        val old = antTrail.size
        antTrail = antTrail.copyOf(n); antS = antS.copyOf(n); antDir = antDir.copyOf(n); antOffset = antOffset.copyOf(n)
        ants.ensureCapacity(n)
        for (i in old until n) {
            // About a fifth of the ants wander; the rest are spread over the trail slots.
            antTrail[i] = if (rng.nextDouble() < 0.2) -1 else rng.nextInt(4)
            antS[i] = rng.nextDouble(0.0, 150.0)
            antDir[i] = if (rng.nextBoolean()) 1 else -1
            antOffset[i] = (rng.nextDouble() - 0.5) * 3.0
            val a = rng.nextDouble(2 * PI); val r = rng.nextDouble(hubRadius, 60.0)
            ants.x[i] = (nestX + r * cos(a)).toFloat(); ants.y[i] = (nestY + r * sin(a)).toFloat()
            ants.heading[i] = a.toFloat()
        }
    }

    // ---- terrain, headings, helpers ----

    private fun buildTerrain() {
        val n = terrainSize
        val cell = World.SIZE / n
        val rocks = Random(seed + 1)
        val rockCenters = List(12) { doubleArrayOf(rocks.nextDouble(World.SIZE), rocks.nextDouble(World.SIZE), rocks.nextDouble(3.0, 9.0)) }
        for (row in 0 until n) for (col in 0 until n) {
            val x = (col + 0.5) * cell; val y = (row + 0.5) * cell
            val hills = 2.5 * (sin(x / 60) + cos(y / 75) + 2) + 1.5 * sin((x + y) / 33)
            val mound = moundHeight * exp(-(hypot(x - nestX, y - nestY) / 18).let { it * it })
            heights[row * n + col] = max(0.0, hills + mound).toFloat()
            blocked[row * n + col] = rockCenters.any { hypot(x - it[0], y - it[1]) < it[2] } &&
                hypot(x - nestX, y - nestY) > 40
        }
    }

    private fun heightAt(x: Double, y: Double): Double {
        val n = terrainSize
        val col = (x / World.SIZE * n).toInt().coerceIn(0, n - 1)
        val row = (y / World.SIZE * n).toInt().coerceIn(0, n - 1)
        return heights[row * n + col].toDouble()
    }

    private fun headingBins(): List<Double> {
        val bins = DoubleArray(16) { 0.3 / 16 } // an even spread underneath
        for (trail in trails) {
            if (trail.symbolStrength <= 0) continue
            for (b in 0 until 16) {
                val center = b * 2 * PI / 16
                var d = abs(center - ((trail.angle % (2 * PI)) + 2 * PI) % (2 * PI))
                d = min(d, 2 * PI - d)
                bins[b] += trail.symbolStrength * exp(-(d / 0.35).let { it * it })
            }
        }
        val total = bins.sum()
        return bins.map { it / total }
    }

    private fun splat(layer: FloatArray, n: Int, cx: Double, cy: Double, amount: Float) {
        val c0 = floor(cx).toInt(); val r0 = floor(cy).toInt()
        for (r in r0 - 1..r0 + 1) for (c in c0 - 1..c0 + 1) {
            if (r !in 0 until n || c !in 0 until n) continue
            val d2 = (c + 0.5 - cx).let { it * it } + (r + 0.5 - cy).let { it * it }
            val i = r * n + c
            layer[i] = max(layer[i], amount * exp(-d2).toFloat())
        }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private class FakeTrail(
        val slot: Int,
        val foodId: Int,
        val foodX: Double,
        val foodY: Double,
        val angle: Double,
        val parent: FakeTrail?,
        val branchAt: Double,
        var lifePhase: Double,
        val bendPhase: Double,
    ) {
        var strength = 0.0
        var points: List<DoubleArray> = emptyList()
        var voiceAlive = false
        var voiceId = 0
        var voiceBornTick = 0L
        var symbolStrength = 0.0
        var symbolAlive = false
        var symbolId = 0
        var symbolBornTick = 0L
    }
}
