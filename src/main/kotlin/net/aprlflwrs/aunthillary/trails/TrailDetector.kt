package net.aprlflwrs.aunthillary.trails

import net.aprlflwrs.aunthillary.core.FoodPile
import net.aprlflwrs.aunthillary.core.Params
import net.aprlflwrs.aunthillary.core.Voice
import net.aprlflwrs.aunthillary.core.World
import java.io.File
import java.util.TreeMap
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The signal level: finds trails in a snapshot of the food-pheromone layer and turns them into
 * voices. See "TrailDetector" in ProjectPlan.md. Each call to [detect]:
 *
 * 1. **Threshold.** Converts every cell to a strength, `min(food / displayMax, 1)`. The bar
 *    (`trails.threshold`) is only used in step 4, to accept or reject finished routes; the search
 *    itself may cross weak cells, just at a higher price.
 * 2. **Hub mask.** Cells within the hub radius of the nest are impassable, and the search starts
 *    from the cells just outside them, so routes begin at the hub's edge instead of crowding
 *    through the hub.
 * 3. **Search.** One multi-source shortest-path search ([PathSearch]) from the hub's edge over the
 *    whole grid. A cell costs `(1 + floor) / (strength + floor)` per world unit (1 on a full-
 *    strength trail, about 21 on bare ground with the default floor of 0.05), blocked cells cannot
 *    be crossed, and cells on last call's voices cost `trails.routeDiscount` less, so a voice does
 *    not flicker between two nearly equal routes.
 * 4. **Routes.** From each food pile (live or ghost), the strongest reachable cell within
 *    `trails.pileRadius` is traced back through the search tree to the hub. The route is accepted
 *    only if no stretch longer than `trails.maxGap` falls below the bar and at least half of it is
 *    above the bar. Living voices are held to a lower bar (`threshold × keepRatio`) so a voice
 *    near the threshold doesn't flicker on and off.
 * 5. **Voices and slots.** A voice is named by its food pile. At most four voices exist, in slots
 *    0–3. A living voice whose route is still accepted keeps its id and slot; one whose route is
 *    rejected has faded and is gone. Free slots then go to new piles with accepted routes,
 *    strongest first. A living voice is never pushed out by a stronger newcomer. A pile that
 *    disappears from the input stays behind as an invisible target (a ghost) until its voice
 *    fades; a ghost never starts a new voice.
 * 6. **Smoothing.** Each voice's route becomes a smooth curve ([Curve]) with points every
 *    `trails.pointSpacing` world units from the hub's edge outward, each `[x, y, height, strength]`.
 *    The voice's strength is the median of its points' strengths.
 * 7. **Debug images.** If [debugDir] is set, each call writes PNGs of every stage there
 *    ([DebugImages]).
 *
 * The detector keeps state between calls (living voices, their routes, ghosts, the id counter),
 * so calls must be made one at a time, in tick order. It is deterministic: the same sequence of
 * inputs gives the same outputs. It reads nothing from the live colony.
 *
 * @param params the registry to declare the `trails.*` parameters in (normally the colony's).
 * @param debugDir where to write debug images, or null for none.
 */
class TrailDetector(params: Params, var debugDir: File? = null) {
    private val trailParams = TrailParams(params)

    /** A voice that is alive between calls. Its route is replaced on every call it survives. */
    private class LiveVoice(val id: Int, val slot: Int, val foodId: Int, val bornTick: Long) {
        var route = IntArray(0)
    }

    private val living = ArrayList<LiveVoice>()

    /**
     * The pile of every living voice, by food id, at its last known position. Those missing from
     * the latest input are the ghosts: invisible targets kept until their voice fades.
     */
    private val voicedPiles = TreeMap<Int, FoodPile>()
    private var nextVoiceId = 1

    /** Cells on the routes of the voices returned last call; they get the route discount. */
    private var previousRoutes: BooleanArray? = null

    // Buffers reused between calls of the same grid size.
    private var search: PathSearch? = null
    private var strength = FloatArray(0)
    private var ridge = FloatArray(0)
    private var scratch = FloatArray(0)
    private var cost = DoubleArray(0)
    private var impassable = BooleanArray(0)
    private var hub = BooleanArray(0)

    /** How long the last call's shortest-path search took, in nanoseconds. */
    var lastSearchNanos = 0L
        private set

    /** How long the whole of the last call took (excluding debug images), in nanoseconds. */
    var lastDetectNanos = 0L
        private set

    /** Forgets every voice, ghost and route, for a colony reset or flood. Ids restart at 1. */
    fun reset() {
        living.clear()
        voicedPiles.clear()
        nextVoiceId = 1
        previousRoutes = null
    }

    fun detect(input: DetectorInput): List<Voice> {
        val started = System.nanoTime()
        val cfg = trailParams.snapshot()
        val size = input.size
        val n = size * size
        val cell = input.cell
        ensureBuffers(size)

        // Step 1: strengths.
        val food = input.food
        val inv = 1.0 / input.displayMax
        for (i in 0 until n) strength[i] = (food[i] * inv).coerceIn(0.0, 1.0).toFloat()

        // Step 2: blocked cells (sampled from the terrain grid by world position) and the hub.
        markImpassable(input)
        val sources = markHub(input)

        // Step 3: cost field (from the blurred ridge) and the search.
        Blur.gaussian(strength, ridge, scratch, size, cfg.ridgeBlur / cell)
        val discountMask = previousRoutes
        val keepShare = 1.0 - cfg.routeDiscount
        for (i in 0 until n) {
            cost[i] = if (impassable[i] || hub[i]) {
                Double.POSITIVE_INFINITY
            } else {
                val c = (1.0 + cfg.costFloor) / (ridge[i] + cfg.costFloor)
                if (discountMask != null && discountMask[i]) c * keepShare else c
            }
        }
        val search = search!!
        val searchStarted = System.nanoTime()
        search.run(cost, sources, cell)
        lastSearchNanos = System.nanoTime() - searchStarted

        // Step 4: routes. Targets are the live piles plus the ghosts of living voices, by food id.
        val livePiles = TreeMap<Int, FoodPile>()
        for (p in input.piles) livePiles[p.id] = p
        val targets = TreeMap<Int, FoodPile>(voicedPiles)
        targets.putAll(livePiles)
        val routes = TreeMap<Int, IntArray>()
        // Living voices are traced first (in slot order), so a newcomer merges onto an existing
        // voice's lane rather than the other way round.
        val traceOrder = living.map { it.foodId }.filter { it in targets } +
            targets.keys.filter { id -> living.none { it.foodId == id } }
        val tracer = RouteTracer(size, cell, search.dist, search.parent, strength, cost, cfg)
        for (id in traceOrder) routes[id] = tracer.trace(targets.getValue(id)) ?: continue

        val newBar = cfg.threshold
        val keepBar = cfg.threshold * cfg.keepRatio
        val accepted = HashMap<Int, Boolean>()

        // Step 5a: living voices keep their slot while their route holds the lower bar.
        val survivors = ArrayList<LiveVoice>()
        for (v in living) {
            val route = routes[v.foodId]
            val ok = route != null && accepts(route, keepBar, cfg, size, cell)
            accepted[v.foodId] = ok
            if (ok) {
                v.route = route!!
                survivors += v
            }
        }
        living.clear()
        living += survivors

        // Step 5b: free slots go to new live piles, strongest first. Ghosts never start a voice.
        val voiced = living.mapTo(HashSet()) { it.foodId }
        val freeSlots = (0 until MAX_VOICES).filter { s -> living.none { it.slot == s } }.toMutableList()
        val candidates = ArrayList<Candidate>()
        for ((id, _) in livePiles) {
            if (id in voiced) continue
            val route = routes[id] ?: continue
            val ok = accepts(route, newBar, cfg, size, cell)
            accepted[id] = ok
            if (ok) candidates += Candidate(id, route, buildPoints(route, input, cfg))
        }
        candidates.sortWith(compareByDescending<Candidate> { it.points.strength }.thenBy { it.foodId })
        val built = HashMap<Int, Points>()
        for (c in candidates) {
            if (freeSlots.isEmpty()) break
            val v = LiveVoice(nextVoiceId++, freeSlots.removeAt(0), c.foodId, input.tick)
            v.route = c.route
            living += v
            built[v.foodId] = c.points
        }
        living.sortBy { it.slot }

        // Remember every living voice's pile; a dead voice's ghost is dropped here.
        voicedPiles.clear()
        for (v in living) voicedPiles[v.foodId] = targets.getValue(v.foodId)
        val ghosts = voicedPiles.filterKeys { it !in livePiles }.values.toList()

        // Step 6: curves.
        val voices = living.map { v ->
            val pts = built[v.foodId] ?: buildPoints(v.route, input, cfg)
            Voice(v.id, v.slot, v.foodId, v.bornTick, pts.strength, pts.points)
        }

        val mask = BooleanArray(n)
        for (v in living) for (c in v.route) mask[c] = true
        previousRoutes = mask
        lastDetectNanos = System.nanoTime() - started

        // Step 7: debug images.
        debugDir?.let { dir ->
            DebugImages.write(
                dir,
                DebugImages.Frame(
                    tick = input.tick, size = size, cell = cell,
                    strength = strength, impassable = impassable, hub = hub, sources = sources,
                    threshold = newBar, dist = search.dist,
                    routes = routes.map { (id, r) -> DebugImages.Route(id, r, accepted[id] == true) },
                    voices = voices,
                    piles = input.piles, ghosts = ghosts,
                ),
            )
        }
        return voices
    }

    private class Candidate(val foodId: Int, val route: IntArray, val points: Points)

    private class Points(val points: List<DoubleArray>, val strength: Double)

    private fun ensureBuffers(size: Int) {
        val n = size * size
        if (search?.size == size) return
        search = PathSearch(size)
        strength = FloatArray(n)
        ridge = FloatArray(n)
        scratch = FloatArray(n)
        cost = DoubleArray(n)
        impassable = BooleanArray(n)
        hub = BooleanArray(n)
        // Last call's routes were on a different grid; drop their discount.
        previousRoutes = null
    }

    private fun markImpassable(input: DetectorInput) {
        val size = input.size
        val cell = input.cell
        val ts = input.terrainSize
        val tcell = World.SIZE / ts
        for (j in 0 until size) {
            val tj = (((j + 0.5) * cell) / tcell).toInt().coerceIn(0, ts - 1)
            for (i in 0 until size) {
                val ti = (((i + 0.5) * cell) / tcell).toInt().coerceIn(0, ts - 1)
                impassable[j * size + i] = input.blocked[tj * ts + ti]
            }
        }
    }

    /**
     * Marks the hub (cell centers within the hub radius, plus the nest's own cell so there is
     * always one) and returns the search's sources: passable cells outside the hub that touch it.
     */
    private fun markHub(input: DetectorInput): IntArray {
        val size = input.size
        val cell = input.cell
        hub.fill(false)
        for (j in 0 until size) {
            val dy = (j + 0.5) * cell - input.nestY
            for (i in 0 until size) {
                val dx = (i + 0.5) * cell - input.nestX
                if (hypot(dx, dy) < input.hubRadius) hub[j * size + i] = true
            }
        }
        val ni = (input.nestX / cell).toInt().coerceIn(0, size - 1)
        val nj = (input.nestY / cell).toInt().coerceIn(0, size - 1)
        hub[nj * size + ni] = true

        val sources = ArrayList<Int>()
        for (j in 0 until size) for (i in 0 until size) {
            val c = j * size + i
            if (hub[c] || impassable[c]) continue
            val touches = (i > 0 && hub[c - 1]) || (i < size - 1 && hub[c + 1]) ||
                (j > 0 && hub[c - size]) || (j < size - 1 && hub[c + size])
            if (touches) sources += c
        }
        return sources.toIntArray()
    }

    /**
     * Whether a route counts as a trail at strength [bar]: no stretch longer than `maxGap` world
     * units below the bar, and at least half its length at or above it (so a short route that is
     * all gap, say to a pile right beside the hub, is still rejected).
     */
    private fun accepts(route: IntArray, bar: Double, cfg: TrailConfig, size: Int, cell: Double): Boolean {
        var gap = 0.0
        var above = 0.0
        var total = 0.0
        for (k in route.indices) {
            val c = route[k]
            val step = if (k == 0) cell else stepLength(route[k - 1], c, size, cell)
            total += step
            if (strength[c] >= bar) {
                above += step
                gap = 0.0
            } else {
                gap += step
                if (gap > cfg.maxGap) return false
            }
        }
        return above * 2 >= total
    }

    private fun stepLength(a: Int, b: Int, size: Int, cell: Double): Double {
        val diagonal = a % size != b % size && a / size != b / size
        return if (diagonal) cell * SQRT2 else cell
    }

    private fun buildPoints(route: IntArray, input: DetectorInput, cfg: TrailConfig): Points {
        val size = input.size
        val cell = input.cell
        val xs = DoubleArray(route.size) { (route[it] % size + 0.5) * cell }
        val ys = DoubleArray(route.size) { (route[it] / size + 0.5) * cell }
        val (sx, sy) = Curve.smooth(xs, ys, cfg.smoothing, cfg.pointSpacing)
        val tcell = World.SIZE / input.terrainSize
        val strengths = DoubleArray(sx.size)
        val points = List(sx.size) { k ->
            val h = Curve.sample(input.heights, input.terrainSize, tcell, sx[k], sy[k])
            val s = Curve.sample(strength, size, cell, sx[k], sy[k]).coerceIn(0.0, 1.0)
            strengths[k] = s
            doubleArrayOf(sx[k], sy[k], h, s)
        }
        return Points(points, Curve.median(strengths))
    }

    companion object {
        /** The plan caps the fugue at four voices; slots 0–3 pick color and instrument. */
        const val MAX_VOICES = 4
        private val SQRT2 = sqrt(2.0)
    }
}
