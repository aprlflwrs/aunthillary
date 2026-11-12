package net.aprlflwrs.aunthillary.trails

import net.aprlflwrs.aunthillary.core.Params

/**
 * The detector's tunable numbers, declared in the params registry under `trails.` so they get
 * sliders in the tuning panel. Lengths are in world units, so they mean the same thing at any grid
 * resolution. Strengths are shares of `pheromone.displayMax` (0–1), the same scale as a voice
 * point's strength.
 *
 * Declaring twice on the same [Params] throws, so make one detector per registry.
 */
class TrailParams(params: Params) {
    private val threshold = params.declare(
        "trails.threshold", "Trail threshold (share of display max)", GROUP,
        min = 0.0, max = 1.0, step = 0.01, default = 0.15,
    )
    private val keepRatio = params.declare(
        "trails.keepRatio", "Keep a voice down to (share of threshold)", GROUP,
        min = 0.1, max = 1.0, step = 0.05, default = 0.6,
    )
    private val maxGap = params.declare(
        "trails.maxGap", "Longest gap in a trail (world units)", GROUP,
        min = 0.0, max = 50.0, step = 0.5, default = 6.0,
    )
    private val costFloor = params.declare(
        "trails.costFloor", "Cost floor (bare ground costs 1 + 1/floor)", GROUP,
        min = 0.005, max = 1.0, step = 0.005, default = 0.05,
    )
    private val routeDiscount = params.declare(
        "trails.routeDiscount", "Discount on last time's routes", GROUP,
        min = 0.0, max = 0.9, step = 0.05, default = 0.2,
    )
    private val pileRadius = params.declare(
        "trails.pileRadius", "Pile reach (world units)", GROUP,
        min = 0.0, max = 20.0, step = 0.5, default = 4.0,
    )
    private val smoothing = params.declare(
        "trails.smoothing", "Curve smoothing width (world units)", GROUP,
        min = 0.0, max = 10.0, step = 0.25, default = 2.0,
    )
    private val pointSpacing = params.declare(
        "trails.pointSpacing", "Point spacing (world units)", GROUP,
        min = 0.5, max = 10.0, step = 0.5, default = 2.0,
    )

    /**
     * Copies every value at once. The detector takes one snapshot per call, so a slider moved
     * while a detection runs (on its own coroutine) can never leave it half old, half new.
     */
    fun snapshot() = TrailConfig(
        threshold = threshold.value,
        keepRatio = keepRatio.value,
        maxGap = maxGap.value,
        costFloor = costFloor.value,
        routeDiscount = routeDiscount.value,
        pileRadius = pileRadius.value,
        smoothing = smoothing.value,
        pointSpacing = pointSpacing.value,
    )

    companion object {
        const val GROUP = "Trails"
    }
}

/** One call's worth of parameter values. See [TrailParams] for what each means. */
data class TrailConfig(
    /** A new voice's route must hold at least this strength, apart from short gaps. */
    val threshold: Double,
    /** A living voice only needs `threshold × keepRatio`, so it doesn't flicker at the threshold. */
    val keepRatio: Double,
    /** Longest stretch of a route, in world units, allowed below the bar. */
    val maxGap: Double,
    /** A cell costs `(1 + floor) / (strength + floor)` per world unit: 1 at full strength. */
    val costFloor: Double,
    /** Cells on last call's voices cost this share less. */
    val routeDiscount: Double,
    /** How far from a pile's center a route may end. */
    val pileRadius: Double,
    /** Standard deviation, in world units, of the Gaussian that smooths a voice's curve. */
    val smoothing: Double,
    /** Distance between a voice's points along its curve. */
    val pointSpacing: Double,
)
