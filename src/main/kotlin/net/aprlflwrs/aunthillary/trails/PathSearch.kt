package net.aprlflwrs.aunthillary.trails

import kotlin.math.sqrt

/**
 * A multi-source shortest-path search (Dijkstra) over a square grid, 8-connected.
 *
 * Every source starts at distance 0. Stepping from cell a to a neighbor b costs the step's length
 * in world units times the average of the two cells' costs, so a diagonal step costs √2 times a
 * straight one and the result does not depend on the grid resolution. A cell whose cost is
 * infinite is never entered. After [run], [dist] holds each cell's cheapest cost from any source
 * and [parent] the cell it was reached from (-1 for a source or an unreached cell), so following
 * [parent] from any cell walks its cheapest route back to the sources. All those routes together
 * form a tree, which is why voices can share a trunk but can never form spurs or loops.
 *
 * Speed: the heap is hand-rolled over primitive arrays (no boxing), and buffers are reused between
 * runs. Determinism: the heap orders by distance and then by cell index, so ties always resolve
 * the same way, and a cell's parent only changes on a strictly cheaper route.
 */
internal class PathSearch(val size: Int) {
    private val n = size * size
    val dist = DoubleArray(n)
    val parent = IntArray(n)
    private val done = BooleanArray(n)

    // Binary min-heap of (key, cell) with lazy deletion: a cell may be pushed several times, and
    // stale entries are skipped when popped.
    private var heapKey = DoubleArray(1024)
    private var heapCell = IntArray(1024)
    private var heapCount = 0

    /**
     * @param cost cost per world unit of each cell; [Double.POSITIVE_INFINITY] means impassable.
     * @param sources the cells the search starts from, all at distance 0.
     * @param cell world units per cell.
     */
    fun run(cost: DoubleArray, sources: IntArray, cell: Double) {
        dist.fill(Double.POSITIVE_INFINITY)
        parent.fill(-1)
        done.fill(false)
        heapCount = 0
        for (s in sources) {
            if (cost[s].isInfinite() || dist[s] == 0.0) continue
            dist[s] = 0.0
            push(0.0, s)
        }
        val straight = cell * 0.5
        val diagonal = cell * sqrt(2.0) * 0.5
        while (heapCount > 0) {
            val d = heapKey[0]
            val a = heapCell[0]
            pop()
            if (done[a] || d > dist[a]) continue
            done[a] = true
            val ax = a % size
            val ay = a / size
            val ca = cost[a]
            // Neighbors in a fixed order; the order only matters for ties, which the heap's
            // index tie-break already settles, but a fixed order keeps it obvious.
            for (dy in -1..1) {
                val by = ay + dy
                if (by < 0 || by >= size) continue
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val bx = ax + dx
                    if (bx < 0 || bx >= size) continue
                    val b = by * size + bx
                    if (done[b]) continue
                    val cb = cost[b]
                    if (cb.isInfinite()) continue
                    val half = if (dx != 0 && dy != 0) diagonal else straight
                    val nd = d + half * (ca + cb)
                    if (nd < dist[b]) {
                        dist[b] = nd
                        parent[b] = a
                        push(nd, b)
                    }
                }
            }
        }
    }

    private fun less(k1: Double, c1: Int, k2: Double, c2: Int) = k1 < k2 || (k1 == k2 && c1 < c2)

    private fun push(key: Double, c: Int) {
        if (heapCount == heapKey.size) {
            heapKey = heapKey.copyOf(heapCount * 2)
            heapCell = heapCell.copyOf(heapCount * 2)
        }
        var i = heapCount++
        while (i > 0) {
            val p = (i - 1) ushr 1
            if (!less(key, c, heapKey[p], heapCell[p])) break
            heapKey[i] = heapKey[p]; heapCell[i] = heapCell[p]
            i = p
        }
        heapKey[i] = key; heapCell[i] = c
    }

    private fun pop() {
        val last = --heapCount
        if (last == 0) return
        val key = heapKey[last]
        val c = heapCell[last]
        var i = 0
        while (true) {
            var child = 2 * i + 1
            if (child >= last) break
            val right = child + 1
            if (right < last && less(heapKey[right], heapCell[right], heapKey[child], heapCell[child])) child = right
            if (!less(heapKey[child], heapCell[child], key, c)) break
            heapKey[i] = heapKey[child]; heapCell[i] = heapCell[child]
            i = child
        }
        heapKey[i] = key; heapCell[i] = c
    }
}
