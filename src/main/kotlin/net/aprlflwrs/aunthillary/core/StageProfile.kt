package net.aprlflwrs.aunthillary.core

/**
 * Wall-clock time spent in each stage of the tick, accumulated since the colony was built.
 * The headless runner reads it at intervals and reports the difference. Timing never feeds
 * back into the simulation, so it can't affect a replay.
 */
class StageProfile {
    var ticks = 0L; internal set
    /** Moving, sensing, depositing, picking up, delivering and dying. */
    var antsNanos = 0L; internal set
    /** Evaporation and diffusion of both layers. */
    var pheromoneNanos = 0L; internal set
    /** Everything else: hatching, the heading distribution, symbols, the mound. */
    var otherNanos = 0L; internal set

    data class Snapshot(val ticks: Long, val antsNanos: Long, val pheromoneNanos: Long, val otherNanos: Long) {
        /** Average milliseconds per tick of each stage between [earlier] and this snapshot. */
        fun msPerTickSince(earlier: Snapshot): DoubleArray {
            val n = (ticks - earlier.ticks).coerceAtLeast(1).toDouble()
            return doubleArrayOf(
                (antsNanos - earlier.antsNanos) / n / 1e6,
                (pheromoneNanos - earlier.pheromoneNanos) / n / 1e6,
                (otherNanos - earlier.otherNanos) / n / 1e6,
            )
        }
    }

    fun snapshot() = Snapshot(ticks, antsNanos, pheromoneNanos, otherNanos)
}
