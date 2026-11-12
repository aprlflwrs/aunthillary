package net.aprlflwrs.aunthillary.core

/**
 * Measurements of the pheromone field used by the headless runner and the tests, so that
 * "a trail links the nest to the food" is a number, not a judgment by eye.
 */
object TrailMetrics {
    /**
     * Whether food pheromone at or above [threshold] forms an unbroken path (cells touching
     * side or corner) from the hub to within [radius] of each target, by flood fill from every
     * cell within [hubRadius] of the nest. Returns one answer per target.
     */
    fun linked(
        layer: FloatArray, size: Int, nestX: Double, nestY: Double, hubRadius: Double,
        targets: List<Pair<Double, Double>>, radius: Double, threshold: Double,
    ): List<Boolean> {
        val cell = World.SIZE / size
        val reached = BooleanArray(size * size)
        val queue = IntArray(size * size)
        var head = 0; var tail = 0
        for (r in 0 until size) for (c in 0 until size) {
            val dx = (c + 0.5) * cell - nestX
            val dy = (r + 0.5) * cell - nestY
            val i = r * size + c
            if (dx * dx + dy * dy <= hubRadius * hubRadius && layer[i] >= threshold) {
                reached[i] = true; queue[tail++] = i
            }
        }
        while (head < tail) {
            val i = queue[head++]
            val r = i / size; val c = i % size
            for (dr in -1..1) for (dc in -1..1) {
                val rr = r + dr; val cc = c + dc
                if (rr !in 0 until size || cc !in 0 until size) continue
                val j = rr * size + cc
                if (!reached[j] && layer[j] >= threshold) { reached[j] = true; queue[tail++] = j }
            }
        }
        return targets.map { (tx, ty) ->
            var hit = false
            val c0 = ((tx - radius) / cell).toInt().coerceIn(0, size - 1)
            val c1 = ((tx + radius) / cell).toInt().coerceIn(0, size - 1)
            val r0 = ((ty - radius) / cell).toInt().coerceIn(0, size - 1)
            val r1 = ((ty + radius) / cell).toInt().coerceIn(0, size - 1)
            for (r in r0..r1) for (c in c0..c1) {
                val dx = (c + 0.5) * cell - tx
                val dy = (r + 0.5) * cell - ty
                if (dx * dx + dy * dy <= radius * radius && reached[r * size + c]) hit = true
            }
            hit
        }
    }

    /** Total amount of pheromone in [layer] (density times cell area, summed). */
    fun total(layer: FloatArray, size: Int): Double {
        val cell = World.SIZE / size
        var sum = 0.0
        for (v in layer) sum += v
        return sum * cell * cell
    }
}
