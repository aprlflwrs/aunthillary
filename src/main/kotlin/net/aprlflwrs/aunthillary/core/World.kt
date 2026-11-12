package net.aprlflwrs.aunthillary.core

/**
 * World-wide constants. See "World and coordinates" in ProjectPlan.md.
 *
 * x increases to the east, y to the north, origin at the south-west corner.
 * Headings are radians, 0 = east, counterclockwise positive.
 */
object World {
    /** The world is a square this many world units on a side (about one ant length per unit). */
    const val SIZE = 512.0

    /** Ticks per second of simulated time. */
    const val TICKS_PER_SECOND = 30

    /**
     * Simulated seconds per tick. Every tick advances time by exactly this much, whatever
     * the speed control says; speed only changes how often ticks run. That keeps replays exact.
     */
    const val DT = 1.0 / TICKS_PER_SECOND
}
