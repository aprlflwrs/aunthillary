package net.aprlflwrs.aunthillary.core

import kotlinx.serialization.Serializable

/**
 * What the server needs from a colony. The fake colony (server/FakeColony.kt) implements it
 * now; the real simulation implements it in Phase 1. The server never knows which it has.
 *
 * Everything here is called from the tick loop's single thread only.
 */
interface Colony {
    val params: Params

    /** Ticks since the colony started (or was last reset). */
    val tick: Long

    /** Advances the colony by exactly one tick ([World.DT] seconds of simulated time). */
    fun step()

    fun placeFood(x: Double, y: Double)

    fun flood()

    /** Restarts the colony from its seed. Parameter values are kept. */
    fun reset()

    /** Copies every ant into [out], growing it if needed. */
    fun writeAnts(out: AntBuffer)

    /** The two pheromone layers at the colony's own grid resolution. */
    fun pheromone(): PheromoneView

    /** Changes whenever the terrain changes (for example the mound grows), so the server knows when to resend it. */
    val terrainVersion: Int

    fun terrain(): TerrainView

    /** The signal- and symbol-level state sent about twice a second. */
    fun state(): ColonyState
}

/** Reusable buffers for one frame of ants, laid out the way the ant frame sends them. */
class AntBuffer(capacity: Int = 1024) {
    var count = 0
    var x = FloatArray(capacity); private set
    var y = FloatArray(capacity); private set
    var heading = FloatArray(capacity); private set
    var flags = ByteArray(capacity); private set

    fun ensureCapacity(n: Int) {
        if (n <= x.size) return
        val size = maxOf(n, x.size * 2)
        x = x.copyOf(size); y = y.copyOf(size); heading = heading.copyOf(size); flags = flags.copyOf(size)
    }

    companion object {
        const val RETURNING = 1
        const val CARRYING_FOOD = 2
        const val SCOUT = 4
    }
}

/** Square grids of [size] × [size] cells, row-major, rows south to north, each row west to east. */
class PheromoneView(val size: Int, val food: FloatArray, val home: FloatArray)

/** Heights in world units, and blocked cells, same layout as [PheromoneView]. */
class TerrainView(val size: Int, val heights: FloatArray, val blocked: BooleanArray)

// ---- Colony state (the JSON "state" message, minus its "type" field). See Interfaces in ProjectPlan.md. ----

@Serializable
data class ColonyState(
    val tick: Long,
    val nest: Nest,
    val food: List<FoodPile>,
    val voices: List<Voice>,
    val symbols: List<Symbol>,
    /** 16 bins, the first centered on east, counterclockwise; each is a share of recent newborns. */
    val headings: List<Double>,
)

@Serializable
data class Nest(
    val x: Double,
    val y: Double,
    val hubRadius: Double,
    val store: Double,
    val population: Int,
    val moundHeight: Double,
)

@Serializable
data class FoodPile(val id: Int, val x: Double, val y: Double, val amount: Double)

@Serializable
data class Voice(
    val id: Int,
    /** 0–3, fixed for the voice's lifetime; picks its color and instrument. */
    val slot: Int,
    val foodId: Int,
    val bornTick: Long,
    /** Median of the points' strengths. */
    val strength: Double,
    /** Each point is [x, y, height, strength], about every 2 world units from the hub's edge outward. */
    val points: List<DoubleArray>,
)

@Serializable
data class Symbol(
    val id: Int,
    /** Radians, heading convention. */
    val direction: Double,
    val width: Double,
    val strength: Double,
    val bornTick: Long,
    val voiceIds: List<Int>,
)
