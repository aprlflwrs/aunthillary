package net.aprlflwrs.aunthillary.trails

import net.aprlflwrs.aunthillary.core.FoodPile
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Traces routes back from food piles through one search's tree (step 4), one call's worth.
 *
 * Each route is the chain of parents from a cell near the pile to the hub's edge, with one
 * addition, a merge: while walking back, if the route passes right beside a route already traced
 * this call, and stepping across onto it costs no more than [TrailConfig.mergeTolerance] (a share
 * of the route's cost so far) above staying put, the route steps across and follows that route the
 * rest of the way.
 *
 * Why: a trail several cells wide holds parallel lanes that cost almost exactly the same. The
 * search tree picks one lane per cell, and nothing makes two piles' routes pick the same lane, so
 * two voices on one trunk could run side by side a cell apart, never touching, and never share an
 * opening phrase. The ridge blur makes one lane cheapest in most places; merging handles the rest
 * (for instance a trail whose center lies exactly between two rows of cells). Two routes that
 * reach the same cell share everything from there to the hub anyway, because the tree gives every
 * cell one parent; merging only extends that to near-ties. Since a merge is only taken when it is
 * nearly as cheap, it never drags a route onto a different trail.
 */
internal class RouteTracer(
    private val size: Int,
    private val cell: Double,
    private val dist: DoubleArray,
    private val parent: IntArray,
    private val strength: FloatArray,
    private val cost: DoubleArray,
    private val cfg: TrailConfig,
) {
    private val traced = ArrayList<IntArray>()

    /** Cell → (index into [traced]) shl 32 or (position in that route), for every traced cell. */
    private val owner = HashMap<Int, Long>()

    /**
     * The route to [pile], hub end first, or null if nothing near the pile was reached. It ends at
     * the strongest reached cell within the pile's reach (ties: cheaper, then lower index), since
     * the pile's own cell may be blocked or bare while the trail runs right beside it.
     */
    fun trace(pile: FoodPile): IntArray? {
        val end = endCell(pile)
        if (end < 0) return null
        val backwards = ArrayList<Int>()
        var c = end
        while (c >= 0) {
            val own = owner[c]
            if (own != null) {
                // Joined a traced route: follow it the rest of the way to the hub.
                val route = traced[(own ushr 32).toInt()]
                for (k in (own and 0xffffffffL).toInt() downTo 0) backwards += route[k]
                break
            }
            backwards += c
            c = mergeTarget(c) ?: parent[c]
        }
        backwards.reverse()
        val route = backwards.toIntArray()
        val index = traced.size.toLong()
        traced += route
        for (k in route.indices) owner.putIfAbsent(route[k], (index shl 32) or k.toLong())
        return route
    }

    private fun endCell(pile: FoodPile): Int {
        // At least the pile's own cell and its neighbors, whatever the radius.
        val reach = maxOf(cfg.pileRadius, cell * 1.5)
        val i0 = ((pile.x - reach) / cell).toInt().coerceIn(0, size - 1)
        val i1 = ((pile.x + reach) / cell).toInt().coerceIn(0, size - 1)
        val j0 = ((pile.y - reach) / cell).toInt().coerceIn(0, size - 1)
        val j1 = ((pile.y + reach) / cell).toInt().coerceIn(0, size - 1)
        var best = -1
        for (j in j0..j1) for (i in i0..i1) {
            val c = j * size + i
            if (dist[c].isInfinite()) continue
            if (hypot((i + 0.5) * cell - pile.x, (j + 0.5) * cell - pile.y) > reach) continue
            if (best < 0 || strength[c] > strength[best] ||
                (strength[c] == strength[best] && dist[c] < dist[best])
            ) best = c
        }
        return best
    }

    /** A traced neighbor of [c] worth stepping onto, or null. The cheapest wins, then the lowest index. */
    private fun mergeTarget(c: Int): Int? {
        if (owner.isEmpty()) return null
        val limit = dist[c] * (1 + cfg.mergeTolerance)
        val x = c % size
        val y = c / size
        var best = -1
        var bestCost = Double.POSITIVE_INFINITY
        for (dy in -1..1) {
            val ny = y + dy
            if (ny < 0 || ny >= size) continue
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                if (nx < 0 || nx >= size) continue
                val a = ny * size + nx
                if (a !in owner) continue
                val step = (if (dx != 0 && dy != 0) cell * SQRT2 else cell) * (cost[c] + cost[a]) / 2
                val via = dist[a] + step
                if (via <= limit && (via < bestCost || (via == bestCost && a < best))) {
                    best = a
                    bestCost = via
                }
            }
        }
        return if (best >= 0) best else null
    }

    private companion object {
        val SQRT2 = sqrt(2.0)
    }
}
