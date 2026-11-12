package net.aprlflwrs.aunthillary.core

import kotlinx.serialization.Serializable

/**
 * The params registry: every tunable number in the colony is declared here, once, with a
 * name, range and default. The browser's tuning panel is built from [specs], so declaring a
 * parameter is all it takes to get a slider for it.
 *
 * Only the tick loop changes values (setParam actions go through its action queue), so no
 * locking is needed. Code reads a parameter through the handle it got when declaring it:
 *
 *     val evaporation = params.declare("pheromone.evaporation", "Evaporation (per second)",
 *                                      "Pheromone", min = 0.0, max = 1.0, step = 0.001, default = 0.05)
 *     ...
 *     grid.evaporate(evaporation.value * World.DT)
 */
class Params {
    private val byKey = LinkedHashMap<String, Param>()

    /** Incremented on every change, so the server knows when to resend the params message. */
    var version = 0
        private set

    fun declare(
        key: String,
        label: String,
        group: String,
        min: Double,
        max: Double,
        step: Double,
        default: Double,
    ): Param {
        require(key !in byKey) { "Parameter '$key' declared twice" }
        require(default in min..max) { "Default for '$key' is outside its range" }
        val param = Param(ParamSpec(key, label, group, min, max, step, default))
        byKey[key] = param
        version++
        return param
    }

    operator fun get(key: String): Param? = byKey[key]

    /** Sets a value, clamped to its range. Returns false if the key is unknown. */
    fun set(key: String, value: Double): Boolean {
        val param = byKey[key] ?: return false
        val clamped = value.coerceIn(param.spec.min, param.spec.max)
        if (clamped != param.value) {
            param.value = clamped
            version++
        }
        return true
    }

    fun resetToDefaults() {
        byKey.values.forEach { it.value = it.spec.default }
        version++
    }

    /** Current specs and values, in declaration order; this is what the params message carries. */
    fun specs(): List<ParamInfo> = byKey.values.map { p ->
        with(p.spec) { ParamInfo(key, label, group, min, max, step, default, p.value) }
    }
}

data class ParamSpec(
    val key: String,
    val label: String,
    val group: String,
    val min: Double,
    val max: Double,
    val step: Double,
    val default: Double,
)

/** A handle to one parameter. Read [value]; change it only through [Params.set]. */
class Param internal constructor(val spec: ParamSpec) {
    var value: Double = spec.default
        internal set
}

/** One entry of the params message (see Interfaces in ProjectPlan.md). */
@Serializable
data class ParamInfo(
    val key: String,
    val label: String,
    val group: String,
    val min: Double,
    val max: Double,
    val step: Double,
    val default: Double,
    val value: Double,
)
