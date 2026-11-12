package net.aprlflwrs.aunthillary.trails

import net.aprlflwrs.aunthillary.core.FoodPile
import net.aprlflwrs.aunthillary.core.Nest
import net.aprlflwrs.aunthillary.core.PheromoneView
import net.aprlflwrs.aunthillary.core.TerrainView
import net.aprlflwrs.aunthillary.core.World

/**
 * One snapshot of everything the detector reads. It is a plain copy, not the live colony, so the
 * detector can run on its own coroutine while the colony keeps ticking, and can be fed synthetic
 * or saved grids in tests.
 *
 * Grids are laid out as in core/Colony.kt: square, row-major, rows south to north, each row west
 * to east, so cell (i, j) is at index `j * size + i` and its center is at world coordinates
 * `((i + 0.5) * cell, (j + 0.5) * cell)` with `cell = World.SIZE / size`.
 *
 * The terrain grid may have a different resolution from the pheromone grid (the fake colony's is
 * 128² while pheromone is finer); the detector samples it by world position.
 *
 * The detector never writes to these arrays, but it also does not copy them, so whoever builds an
 * input must not change the arrays while a detection is running. [of] makes the copies.
 */
class DetectorInput(
    val tick: Long,
    /** Pheromone grid side, in cells. */
    val size: Int,
    /** The food-pheromone layer, `size * size` raw values (not normalized). */
    val food: FloatArray,
    /** Terrain grid side, in cells. */
    val terrainSize: Int,
    /** Ground height in world units, `terrainSize * terrainSize`. */
    val heights: FloatArray,
    /** Cells ants cannot enter, `terrainSize * terrainSize`. */
    val blocked: BooleanArray,
    val nestX: Double,
    val nestY: Double,
    val hubRadius: Double,
    /** The food piles that exist now. A pile missing here that had a voice becomes a ghost. */
    val piles: List<FoodPile>,
    /**
     * The value of the `pheromone.displayMax` parameter. A cell's strength is
     * `min(food / displayMax, 1)`, exactly as the pheromone frame and the Interfaces section use it.
     * It is passed in rather than read from the registry so the detector never declares a key the
     * colony owns (Params.declare throws on duplicates).
     */
    val displayMax: Double,
) {
    init {
        require(size > 0 && food.size == size * size) { "food must be size × size" }
        require(terrainSize > 0) { "terrainSize must be positive" }
        require(heights.size == terrainSize * terrainSize) { "heights must be terrainSize × terrainSize" }
        require(blocked.size == terrainSize * terrainSize) { "blocked must be terrainSize × terrainSize" }
        require(displayMax > 0) { "displayMax must be positive" }
    }

    /** World units per pheromone cell. */
    val cell: Double get() = World.SIZE / size

    companion object {
        /**
         * Builds an input from the views a [net.aprlflwrs.aunthillary.core.Colony] hands out,
         * copying the arrays so the colony can keep ticking while the detector runs. Call it on the
         * tick loop's thread, at the tick the detection is for.
         */
        fun of(
            tick: Long,
            pheromone: PheromoneView,
            terrain: TerrainView,
            nest: Nest,
            piles: List<FoodPile>,
            displayMax: Double,
        ) = DetectorInput(
            tick = tick,
            size = pheromone.size,
            food = pheromone.food.copyOf(),
            terrainSize = terrain.size,
            heights = terrain.heights.copyOf(),
            blocked = terrain.blocked.copyOf(),
            nestX = nest.x,
            nestY = nest.y,
            hubRadius = nest.hubRadius,
            piles = piles.toList(),
            displayMax = displayMax,
        )
    }
}
