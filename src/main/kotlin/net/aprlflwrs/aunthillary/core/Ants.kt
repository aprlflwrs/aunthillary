package net.aprlflwrs.aunthillary.core

/**
 * Every ant's state, stored as one array per field ("structure of arrays") rather than one
 * object per ant: at 100,000 ants this keeps the tick loop running through contiguous
 * memory, with no garbage to collect. Ant i is index i of every array.
 *
 * Ants have no lasting identity: when one dies, the last ant moves into its index. The
 * protocol says ants come in no guaranteed order, so nothing depends on it.
 */
class Ants(capacity: Int = 1024) {
    var count = 0
        private set

    var x = FloatArray(capacity); private set
    var y = FloatArray(capacity); private set
    /** Radians, kept in [0, 2π). */
    var heading = FloatArray(capacity); private set
    /** [RETURNING], [CARRYING] and [SCOUT] bits, the same as the ant frame's flags. */
    var flags = ByteArray(capacity); private set

    /** Preferred heading, set at birth, stored as its cosine and sine (that's all the steering needs). */
    var prefCos = FloatArray(capacity); private set
    var prefSin = FloatArray(capacity); private set
    /** Chance per tick of steering by food pheromone when some is sensed. Set at birth. */
    var fidelity = FloatArray(capacity); private set

    /** Seconds since this ant last left the nest (searching) or picked up food (returning). */
    var tripTime = FloatArray(capacity); private set
    /** exp(−tripTime / fade): how much pheromone the ant lays now. Kept multiplicatively. */
    var strength = FloatArray(capacity); private set
    /** 1 = full, 0 = starved. Falls as the ant walks and lives; refilled at the nest. */
    var energy = FloatArray(capacity); private set
    var age = FloatArray(capacity); private set
    var lifespan = FloatArray(capacity); private set
    /**
     * Direction, seen from the nest, where the ant found food or gave up. Counted into the
     * heading distribution when it gets home. NaN means "don't count" (after a flood).
     */
    var eventAngle = FloatArray(capacity); private set
    /** Id of the pile the ant's load came from, or 0. */
    var pile = IntArray(capacity); private set

    fun clear() {
        count = 0
    }

    /** Appends an ant and returns its index; the caller fills in the fields. */
    fun add(): Int {
        if (count == x.size) grow(count * 2)
        return count++
    }

    /** Removes ant [i] by moving the last ant into its place. */
    fun remove(i: Int) {
        val last = count - 1
        if (i != last) {
            x[i] = x[last]; y[i] = y[last]; heading[i] = heading[last]; flags[i] = flags[last]
            prefCos[i] = prefCos[last]; prefSin[i] = prefSin[last]; fidelity[i] = fidelity[last]
            tripTime[i] = tripTime[last]; strength[i] = strength[last]; energy[i] = energy[last]
            age[i] = age[last]; lifespan[i] = lifespan[last]
            eventAngle[i] = eventAngle[last]; pile[i] = pile[last]
        }
        count = last
    }

    private fun grow(n: Int) {
        x = x.copyOf(n); y = y.copyOf(n); heading = heading.copyOf(n); flags = flags.copyOf(n)
        prefCos = prefCos.copyOf(n); prefSin = prefSin.copyOf(n); fidelity = fidelity.copyOf(n)
        tripTime = tripTime.copyOf(n); strength = strength.copyOf(n); energy = energy.copyOf(n)
        age = age.copyOf(n); lifespan = lifespan.copyOf(n)
        eventAngle = eventAngle.copyOf(n); pile = pile.copyOf(n)
    }

    companion object {
        const val RETURNING = AntBuffer.RETURNING
        const val CARRYING = AntBuffer.CARRYING_FOOD
        const val SCOUT = AntBuffer.SCOUT
    }
}
