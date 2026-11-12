package net.aprlflwrs.aunthillary.core

import java.io.File

/**
 * Everything that changes a colony from outside, in the order it happened, each with the tick
 * it was applied on (the colony's [Colony.tick] at that moment, before the next step).
 *
 * A colony built with the same seed and grid sizes, fed the same log by [replay], ends up in
 * exactly the same state, because the colony itself is deterministic: one seeded generator,
 * a fixed time step, a single thread, and StrictMath for every transcendental function.
 *
 * As text, one action per line: `<tick> <name> [arguments]`, for example
 * `120 placeFood 400.0 310.0` or `300 setParam pheromone.evaporation 0.08`.
 * Numbers are written with Kotlin's shortest round-tripping form, so reading back is exact.
 */
sealed class Action {
    data class PlaceFood(val x: Double, val y: Double) : Action()
    data object Flood : Action()
    data object Reset : Action()
    data class SetParam(val key: String, val value: Double) : Action()
    /** Takes a pile away at once (used by the lesion test). */
    data class RemoveFood(val id: Int) : Action()
    /** Forgets the heading distribution (the lesion). */
    data object FlattenHeadings : Action()
}

data class LoggedAction(val tick: Long, val action: Action) {
    fun toLine(): String = when (action) {
        is Action.PlaceFood -> "$tick placeFood ${action.x} ${action.y}"
        Action.Flood -> "$tick flood"
        Action.Reset -> "$tick reset"
        is Action.SetParam -> "$tick setParam ${action.key} ${action.value}"
        is Action.RemoveFood -> "$tick removeFood ${action.id}"
        Action.FlattenHeadings -> "$tick flattenHeadings"
    }

    companion object {
        fun parse(line: String): LoggedAction {
            val f = line.trim().split(Regex("\\s+"))
            val tick = f[0].toLong()
            val action = when (f[1]) {
                "placeFood" -> Action.PlaceFood(f[2].toDouble(), f[3].toDouble())
                "flood" -> Action.Flood
                "reset" -> Action.Reset
                "setParam" -> Action.SetParam(f[2], f[3].toDouble())
                "removeFood" -> Action.RemoveFood(f[2].toInt())
                "flattenHeadings" -> Action.FlattenHeadings
                else -> throw IllegalArgumentException("Unknown action '${f[1]}' in: $line")
            }
            return LoggedAction(tick, action)
        }
    }
}

class ActionLog {
    private val entries = mutableListOf<LoggedAction>()

    val actions: List<LoggedAction> get() = entries

    fun add(tick: Long, action: Action) {
        entries += LoggedAction(tick, action)
    }

    fun toText(): String = entries.joinToString("") { it.toLine() + "\n" }

    fun write(file: File) = file.writeText(toText())

    companion object {
        fun parse(text: String): List<LoggedAction> =
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { LoggedAction.parse(it) }.toList()

        fun read(file: File) = parse(file.readText())
    }
}

/**
 * Plays [log] into [colony], which should be freshly built with the original's seed and sizes.
 *
 * Before every step it applies, in order, each action logged at the colony's current tick, then
 * steps; it does this [steps] times. A logged reset sets the tick back to 0, and the actions
 * logged after it carry the restarted ticks, so taking the log strictly in order is enough.
 * [onStep] runs after every step (the headless runner uses it to write metrics).
 */
class Replayer(private val colony: AntColony, private val log: List<LoggedAction>) {
    private var next = 0

    /** Applies every action due at the colony's current tick. */
    fun applyDue() {
        while (next < log.size && log[next].tick == colony.tick) {
            colony.apply(log[next].action)
            next++
        }
        check(next >= log.size || log[next].tick > colony.tick) {
            "Action log out of order: ${log[next].toLine()} at tick ${colony.tick}"
        }
    }

    fun run(steps: Long, onStep: () -> Unit = {}) {
        repeat(steps.toInt()) {
            applyDue()
            colony.step()
            onStep()
        }
    }
}
