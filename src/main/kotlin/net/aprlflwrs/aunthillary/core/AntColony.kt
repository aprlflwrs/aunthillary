package net.aprlflwrs.aunthillary.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random

/**
 * The real colony.
 *
 * One tick ([step]), in order:
 *  1. Pick up any parameter changes the server made directly, and log them.
 *  2. **Ants** (oldest index last, swap-removing the dead): each ant ages, moves one step
 *     along its heading (slower on slopes, never into a blocked cell), then reacts where it
 *     lands: a searcher picks up food, homes in on a nearby pile's scent, or steers by food
 *     pheromone, and gives up after a while; a returning ant steers by home pheromone and a
 *     weak pull toward the nest, and delivers its load (or its empty-handed report) on arrival.
 *     Then a random turn, and the ant lays pheromone: home pheromone while searching, food
 *     pheromone while carrying, fading with the time since its trip began.
 *  3. **Pheromone**: both layers evaporate and diffuse.
 *  4. **Everything else**: the nest hatches newborns from its store (heading drawn from the
 *     heading distribution), tops the population up to its floor, the distribution fades,
 *     the mound's terrain is rebuilt once a second if it grew, and twice a second the symbol
 *     tracker reads the distribution.
 *
 * Determinism: all randomness comes from [rng], seeded from [seed] on every reset; every tick
 * is exactly [World.DT]; nothing runs on another thread; and every sin, cos, exp, atan2 and
 * sqrt goes through StrictMath, which gives bit-identical results everywhere (Math's versions
 * may differ between the interpreter and JIT-compiled code). With the same seed and the same
 * [log], a colony replays exactly (see [Replayer]).
 *
 * @param gridSize pheromone grid resolution (cells per side). Every rate is in world units,
 *   so changing it should change how the colony looks, not how it behaves.
 * @param terrainSize terrain grid resolution, which is also what the terrain frame sends.
 */
class AntColony(
    val seed: Long,
    val gridSize: Int = 512,
    val terrainSize: Int = 256,
) : Colony {
    override val params = Params()
    val p = ColonyParams(params)

    /** Every outside change, with its tick. Kept across resets so a whole session replays. */
    val log = ActionLog()
    val profile = StageProfile()

    override var tick = 0L
        private set
    override var terrainVersion = 0
        private set

    val nestX = World.SIZE / 2
    val nestY = World.SIZE / 2

    private var rng = Random(seed)
    val grid = PheromoneGrid(gridSize)
    val terrain = Terrain(terrainSize)
    val ants = Ants()
    val headings = HeadingDistribution()
    private val tracker = SymbolTracker()

    class Pile(val id: Int, val x: Double, val y: Double, var amount: Double, val placedTick: Long) {
        /** Units carried away from the pile, and units of those that reached the nest. */
        var pickedUp = 0L
        var delivered = 0L
        /** Tick the pile ran out (or was removed), or −1. */
        var emptiedTick = -1L
    }

    private val pileList = mutableListOf<Pile>()
    /** Every pile placed since the last reset, empty or not, in the order placed. */
    val piles: List<Pile> get() = pileList
    private var nextPileId = 1

    var store = 0.0
        private set
    var moundHeight = 0.0
        private set
    private var appliedMound = 0.0
    private var hatchBudget = 0.0

    /** Running totals since the last reset, for metrics. */
    var totalDelivered = 0L; private set
    var totalEmptyReturns = 0L; private set
    var totalHatched = 0L; private set
    var totalDied = 0L; private set

    /**
     * **Phase 2 hook.** The trail detector's latest voices. The colony never reads them except
     * to fill in each symbol's `voiceIds` and to pass them through in [state]; nothing they
     * contain feeds back into the simulation, so setting them can't affect a replay.
     */
    var voices: List<Voice> = emptyList()

    // Parameter values as of the last check, so changes made straight through [params] (as the
    // server's tick loop does) are noticed and logged before anything else happens.
    private var seenVersion = -1
    private val seenValues = HashMap<String, Double>()

    init {
        markParamsSeen()
        resetInternal()
    }

    // ---- outside actions (each logged with the tick it was applied on) ----

    override fun placeFood(x: Double, y: Double) {
        record(Action.PlaceFood(x, y))
        // Food off the map or on a rock would be unreachable, so it's ignored.
        if (x !in 0.0..World.SIZE || y !in 0.0..World.SIZE || terrain.isBlocked(x, y)) return
        pileList += Pile(nextPileId++, x, y, p.pileAmount.value, tick)
    }

    override fun flood() {
        record(Action.Flood)
        grid.clear()
        val scatter = p.floodScatter.value
        for (i in 0 until ants.count) {
            val a = rng.nextDouble() * 2 * PI
            val d = rng.nextDouble() * scatter
            val nx = (ants.x[i] + d * StrictMath.cos(a)).coerceIn(EDGE, World.SIZE - EDGE)
            val ny = (ants.y[i] + d * StrictMath.sin(a)).coerceIn(EDGE, World.SIZE - EDGE)
            if (!terrain.isBlocked(nx, ny)) { ants.x[i] = nx.toFloat(); ants.y[i] = ny.toFloat() }
            // Everyone heads home, dropping any load, and reports nothing on arrival.
            ants.flags[i] = (Ants.RETURNING or (ants.flags[i].toInt() and Ants.SCOUT)).toByte()
            ants.eventAngle[i] = Float.NaN
            ants.pile[i] = 0
            ants.tripTime[i] = 0f
            ants.strength[i] = 0f
        }
        moundHeight *= p.floodKeep.value
        rebuildTerrain()
    }

    override fun reset() {
        record(Action.Reset)
        resetInternal()
    }

    /** Takes pile [id] away at once. */
    fun removeFood(id: Int) {
        record(Action.RemoveFood(id))
        pileList.firstOrNull { it.id == id }?.let { pile ->
            if (pile.amount > 0) { pile.amount = 0.0; pile.emptiedTick = tick }
        }
    }

    /** The lesion: forgets the heading distribution, so newborns head evenly until it relearns. */
    fun flattenHeadings() {
        record(Action.FlattenHeadings)
        headings.clear()
    }

    /** Same as `params.set`, but logged at once rather than at the next step. */
    fun setParam(key: String, value: Double) {
        syncParams()
        params.set(key, value)
        syncParams()
    }

    fun apply(action: Action) = when (action) {
        is Action.PlaceFood -> placeFood(action.x, action.y)
        Action.Flood -> flood()
        Action.Reset -> reset()
        is Action.SetParam -> setParam(action.key, action.value)
        is Action.RemoveFood -> removeFood(action.id)
        Action.FlattenHeadings -> flattenHeadings()
    }

    private fun record(action: Action) {
        syncParams() // a parameter changed before this action must be logged before it
        log.add(tick, action)
    }

    private fun syncParams() {
        if (params.version == seenVersion) return
        for (info in params.specs()) {
            if (seenValues[info.key] != info.value) {
                log.add(tick, Action.SetParam(info.key, info.value))
                seenValues[info.key] = info.value
            }
        }
        seenVersion = params.version
    }

    private fun markParamsSeen() {
        for (info in params.specs()) seenValues[info.key] = info.value
        seenVersion = params.version
    }

    private fun resetInternal() {
        rng = Random(seed)
        tick = 0
        grid.clear()
        ants.clear()
        headings.clear()
        tracker.clear()
        pileList.clear()
        nextPileId = 1
        store = p.initialStore.value
        moundHeight = 0.0
        hatchBudget = 0.0
        totalDelivered = 0; totalEmptyReturns = 0; totalHatched = 0; totalDied = 0
        voices = emptyList()
        terrain.generate(
            rng, p.hillHeight.value, p.hillScale.value, p.rockCount.value.toInt(), p.rockRadius.value,
            nestX, nestY, p.rockClearance.value,
        )
        rebuildTerrain()
        // The first ants have random ages, so they don't all die in the same moment.
        repeat(p.initialAnts.value.toInt().coerceAtMost(p.populationCap.value.toInt())) {
            val i = hatch()
            ants.age[i] = (rng.nextDouble() * ants.lifespan[i]).toFloat()
        }
    }

    private fun rebuildTerrain() {
        terrain.applyMound(nestX, nestY, moundHeight, p.moundRadius.value)
        appliedMound = moundHeight
        terrainVersion++
    }

    // ---- the tick ----

    override fun step() {
        syncParams()
        val t0 = System.nanoTime()
        stepAnts()
        val t1 = System.nanoTime()
        grid.update(World.DT, p.evaporation.value, p.diffusion.value, p.cutoff.value)
        val t2 = System.nanoTime()
        stepNest()
        tick++
        if (tick % SYMBOL_EVERY == 0L) {
            tracker.update(headingShares(), tick, p.symbolAppear.value, p.symbolDisappear.value)
        }
        val t3 = System.nanoTime()
        profile.ticks++
        profile.antsNanos += t1 - t0
        profile.pheromoneNanos += t2 - t1
        profile.otherNanos += t3 - t2
    }

    private fun stepAnts() {
        val dt = World.DT
        val speedDt = p.speed.value * dt
        // Uniform noise in ±a has standard deviation a/√3; this makes it wander·√dt per tick.
        val wanderAmp = p.wander.value * StrictMath.sqrt(3 * dt)
        val turn = p.turnRate.value * dt
        val sd = p.sensorDistance.value
        val ca = StrictMath.cos(p.sensorAngle.value)
        val sa = StrictMath.sin(p.sensorAngle.value)
        val follow = p.followTrails.value >= 0.5
        val threshold = p.followThreshold.value
        val headingPull = p.headingPull.value * dt
        val nestPull = p.nestPull.value * dt
        val giveUp = p.giveUpTime.value.toFloat()
        val fadeKeep = StrictMath.exp(-dt / p.depositFade.value).toFloat()
        val homeDep = p.homeDeposit.value * dt
        val foodDep = p.foodDeposit.value * dt
        val slope = p.slopePenalty.value
        val hub2 = p.hubRadius.value * p.hubRadius.value
        val nest2 = p.nestRadius.value * p.nestRadius.value
        val pickup2 = p.pickupRadius.value * p.pickupRadius.value
        val scent2 = p.scentRadius.value * p.scentRadius.value
        val scentTurn = p.scentTurnRate.value * dt
        val emptyWeight = p.emptyWeight.value
        val foodLayer = grid.food
        val homeLayer = grid.home
        val active = pileList.filter { it.amount > 0 }

        var i = ants.count - 1
        while (i >= 0) {
            val age = ants.age[i] + dt.toFloat()
            if (age >= ants.lifespan[i]) {
                ants.remove(i) // the last ant (already updated) moves into slot i
                totalDied++
                i--
                continue
            }
            ants.age[i] = age

            var h = ants.heading[i].toDouble()
            val c = StrictMath.cos(h)
            val s = StrictMath.sin(h)
            var flags = ants.flags[i].toInt()

            // 1. Move one step along the heading.
            val x0 = ants.x[i].toDouble()
            val y0 = ants.y[i].toDouble()
            val step = speedDt / (1 + slope * abs(terrain.grade(x0, y0, c, s)))
            var x = x0 + c * step
            var y = y0 + s * step
            if (x < EDGE || x > World.SIZE - EDGE) { h = PI - h; x = x.coerceIn(EDGE, World.SIZE - EDGE) }
            if (y < EDGE || y > World.SIZE - EDGE) { h = -h; y = y.coerceIn(EDGE, World.SIZE - EDGE) }
            if (terrain.isBlocked(x, y)) {
                // Bumped into a rock: stay put and turn somewhere between a right angle and right round.
                x = x0; y = y0
                val side = if (rng.nextBoolean()) 1 else -1
                h += side * (PI / 2 + rng.nextDouble() * PI / 2)
            }

            val tripTime = ants.tripTime[i] + dt.toFloat()
            ants.tripTime[i] = tripTime
            ants.strength[i] *= fadeKeep
            val toNestX = nestX - x
            val toNestY = nestY - y
            val nestD2 = toNestX * toNestX + toNestY * toNestY

            // 2. React to where it is.
            if (flags and Ants.RETURNING == 0) {
                // Searching. The scent of a nearby pile beats everything else.
                var near: Pile? = null
                var nearD2 = scent2
                for (pile in active) {
                    val dx = pile.x - x
                    val dy = pile.y - y
                    val d2 = dx * dx + dy * dy
                    if (d2 < nearD2 && pile.amount > 0) { near = pile; nearD2 = d2 }
                }
                if (near != null && nearD2 < pickup2) {
                    val load = minOf(1.0, near.amount)
                    near.amount -= load
                    near.pickedUp++
                    if (near.amount <= 0) { near.amount = 0.0; near.emptiedTick = tick }
                    flags = (flags and Ants.SCOUT) or Ants.RETURNING or Ants.CARRYING
                    ants.pile[i] = near.id
                    ants.eventAngle[i] = StrictMath.atan2(near.y - nestY, near.x - nestX).toFloat()
                    ants.tripTime[i] = 0f
                    ants.strength[i] = 1f
                    h += PI // turn round, back the way it came
                } else if (near != null) {
                    val tx = near.x - x
                    val ty = near.y - y
                    val diff = StrictMath.atan2(c * ty - s * tx, c * tx + s * ty)
                    h += diff.coerceIn(-scentTurn, scentTurn)
                } else {
                    if (follow && nestD2 > hub2) {
                        h += steer(foodLayer, x, y, c, s, ca, sa, sd, threshold, turn, ants.fidelity[i].toDouble())
                    }
                    // A gentle drift back toward its preferred heading: sin(preferred − heading).
                    h += headingPull * (ants.prefSin[i] * c - ants.prefCos[i] * s)
                    if (tripTime > giveUp) {
                        flags = (flags and Ants.SCOUT) or Ants.RETURNING
                        ants.eventAngle[i] = StrictMath.atan2(y - nestY, x - nestX).toFloat()
                        ants.tripTime[i] = 0f
                        h += PI
                    }
                }
            } else if (nestD2 < nest2) {
                // Home. Deliver (or report an empty-handed trip), then head straight back out.
                val angle = ants.eventAngle[i].toDouble()
                if (flags and Ants.CARRYING != 0) {
                    deliver(ants.pile[i])
                    if (!angle.isNaN()) headings.record(angle, 1.0)
                } else if (!angle.isNaN()) {
                    headings.record(angle, -emptyWeight)
                    totalEmptyReturns++
                }
                flags = flags and Ants.SCOUT
                ants.pile[i] = 0
                ants.eventAngle[i] = Float.NaN
                ants.tripTime[i] = 0f
                ants.strength[i] = 1f
                h += PI
            } else {
                // Returning: follow home pheromone, with a weak pull toward the nest as a backup.
                if (follow && nestD2 > hub2) {
                    h += steer(homeLayer, x, y, c, s, ca, sa, sd, threshold, turn, 1.0)
                }
                h += nestPull * (c * toNestY - s * toNestX) / StrictMath.sqrt(nestD2) // sin(toward nest − heading)
            }

            // 3. A small random turn, then lay pheromone: each layer is laid by the ants not following it.
            h += (rng.nextDouble() * 2 - 1) * wanderAmp
            if (flags and Ants.RETURNING == 0) {
                grid.deposit(homeLayer, x, y, homeDep * ants.strength[i])
            } else if (flags and Ants.CARRYING != 0) {
                grid.deposit(foodLayer, x, y, foodDep * ants.strength[i])
            }

            ants.x[i] = x.toFloat()
            ants.y[i] = y.toFloat()
            ants.heading[i] = HeadingDistribution.wrap(h).toFloat()
            ants.flags[i] = flags.toByte()
            i--
        }
    }

    /**
     * Samples [layer] at the center of three forward cones (straight ahead, and [ca]/[sa]'s
     * angle to either side, [sd] ahead) and returns the turn toward the strongest, taken with
     * probability [chance]. No turn if ahead is strongest or nothing passes [threshold].
     */
    private fun steer(
        layer: FloatArray, x: Double, y: Double, c: Double, s: Double,
        ca: Double, sa: Double, sd: Double, threshold: Double, turn: Double, chance: Double,
    ): Double {
        val ahead = grid.sample(layer, x + sd * c, y + sd * s)
        val left = grid.sample(layer, x + sd * (c * ca - s * sa), y + sd * (s * ca + c * sa))
        val right = grid.sample(layer, x + sd * (c * ca + s * sa), y + sd * (s * ca - c * sa))
        if (maxOf(ahead, left, right) <= threshold) return 0.0
        if (chance < 1.0 && rng.nextDouble() >= chance) return 0.0
        return when {
            left > ahead && left >= right -> turn
            right > ahead && right > left -> -turn
            else -> 0.0
        }
    }

    private fun deliver(pileId: Int) {
        totalDelivered++
        pileList.firstOrNull { it.id == pileId }?.let { it.delivered++ }
        store = minOf(store + 1, p.storeCap.value)
        moundHeight = minOf(moundHeight + p.moundGrowth.value, p.moundMax.value)
    }

    private fun stepNest() {
        val dt = World.DT
        // Hatch from the store, no faster than the queen can lay. The budget never banks more
        // than one tick's worth, so a colony that was starving doesn't burst when food returns.
        val rate = p.maxHatchRate.value * dt
        hatchBudget = minOf(hatchBudget + rate, rate + 1)
        val cap = p.populationCap.value.toInt()
        val cost = p.hatchCost.value
        while (hatchBudget >= 1 && store >= cost && ants.count < cap) {
            hatch()
            store -= cost
            hatchBudget -= 1
        }
        // The floor: a colony that has shrunk this far is topped up for free, so it never dies out.
        val floor = minOf(p.populationFloor.value.toInt(), cap)
        while (ants.count < floor) hatch()

        headings.fade(StrictMath.exp(-dt / p.headingMemory.value))

        // The mound's terrain is rebuilt at most once a second (on whole-second ticks).
        if ((tick + 1) % World.TICKS_PER_SECOND == 0L && moundHeight != appliedMound) rebuildTerrain()
    }

    /** Adds one newborn at the nest and returns its index. */
    private fun hatch(): Int {
        val i = ants.add()
        val pref = headings.sample(rng, p.randomShare.value, p.headingPrior.value)
        val scout = rng.nextDouble() < p.scoutShare.value
        val base = if (scout) p.scoutFidelity.value else p.foragerFidelity.value
        val fidelity = (base + (rng.nextDouble() * 2 - 1) * p.fidelityJitter.value).coerceIn(0.0, 1.0)
        val r = rng.nextDouble() * p.nestRadius.value
        val a = rng.nextDouble() * 2 * PI
        val jitter = p.lifespanJitter.value
        ants.x[i] = (nestX + r * StrictMath.cos(a)).toFloat()
        ants.y[i] = (nestY + r * StrictMath.sin(a)).toFloat()
        ants.heading[i] = pref.toFloat()
        ants.prefCos[i] = StrictMath.cos(pref).toFloat()
        ants.prefSin[i] = StrictMath.sin(pref).toFloat()
        ants.fidelity[i] = fidelity.toFloat()
        ants.flags[i] = (if (scout) Ants.SCOUT else 0).toByte()
        ants.tripTime[i] = 0f
        ants.strength[i] = 1f
        ants.age[i] = 0f
        ants.lifespan[i] = (p.lifespan.value * (1 + (rng.nextDouble() * 2 - 1) * jitter)).toFloat()
        ants.eventAngle[i] = Float.NaN
        ants.pile[i] = 0
        totalHatched++
        return i
    }

    // ---- reading the colony ----

    val population get() = ants.count

    fun headingShares(): DoubleArray = headings.shares(p.randomShare.value, p.headingPrior.value)

    override fun writeAnts(out: AntBuffer) {
        val n = ants.count
        out.ensureCapacity(n)
        out.count = n
        ants.x.copyInto(out.x, endIndex = n)
        ants.y.copyInto(out.y, endIndex = n)
        ants.heading.copyInto(out.heading, endIndex = n)
        ants.flags.copyInto(out.flags, endIndex = n)
    }

    override fun pheromone(): PheromoneView = grid.view()

    override fun terrain(): TerrainView = terrain.view()

    fun symbols(): List<Symbol> = tracker.symbols.map { t ->
        Symbol(
            id = t.id, direction = t.direction, width = t.width, strength = t.strength, bornTick = t.bornTick,
            // A voice belongs to a symbol when its food pile lies in one of the symbol's bins, seen from the nest.
            voiceIds = voices.filter { v ->
                val pile = pileList.firstOrNull { it.id == v.foodId } ?: return@filter false
                binOf(StrictMath.atan2(pile.y - nestY, pile.x - nestX)) in t.bins
            }.map { it.id },
        )
    }

    private fun binOf(angle: Double) =
        (StrictMath.floor(HeadingDistribution.wrap(angle) / HeadingDistribution.BIN_WIDTH + 0.5).toInt()) % HeadingDistribution.BINS

    override fun state(): ColonyState {
        val linger = (p.emptyLinger.value * World.TICKS_PER_SECOND).toLong()
        return ColonyState(
            tick = tick,
            nest = Nest(nestX, nestY, p.hubRadius.value, store, ants.count, moundHeight),
            food = pileList.filter { it.amount > 0 || tick - it.emptiedTick <= linger }
                .map { FoodPile(it.id, it.x, it.y, it.amount) },
            voices = voices,
            symbols = symbols(),
            headings = headingShares().toList(),
        )
    }

    /**
     * A hash of the colony's whole state (ants, both pheromone layers, the store, mound, piles,
     * heading weights and symbols), bit for bit. Two colonies with the same fingerprint at the
     * same tick are, for every practical purpose, identical.
     */
    fun fingerprint(): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a offset basis
        fun mix(v: Long) { h = (h xor v) * 0x100000001b3L }
        fun mixF(v: Float) = mix(java.lang.Float.floatToRawIntBits(v).toLong())
        fun mixD(v: Double) = mix(java.lang.Double.doubleToRawLongBits(v))
        mix(tick); mix(ants.count.toLong())
        for (i in 0 until ants.count) {
            mixF(ants.x[i]); mixF(ants.y[i]); mixF(ants.heading[i]); mix(ants.flags[i].toLong())
            mixF(ants.fidelity[i]); mixF(ants.prefCos[i]); mixF(ants.tripTime[i]); mixF(ants.strength[i])
            mixF(ants.age[i]); mixF(ants.lifespan[i]); mixF(ants.eventAngle[i]); mix(ants.pile[i].toLong())
        }
        for (v in grid.food) mixF(v)
        for (v in grid.home) mixF(v)
        for (v in terrain.heights) mixF(v)
        mixD(store); mixD(moundHeight); mixD(hatchBudget)
        for (pile in pileList) { mix(pile.id.toLong()); mixD(pile.amount); mix(pile.delivered) }
        for (w in headings.weightsCopy()) mixD(w)
        for (s in tracker.symbols) { mix(s.id.toLong()); mixD(s.direction); mixD(s.strength) }
        return h
    }

    companion object {
        /** The symbol tracker runs every this many ticks (twice a second). */
        const val SYMBOL_EVERY = 15L

        /** Ants keep this far inside the world's edge. */
        private const val EDGE = 0.5
    }
}
