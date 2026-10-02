package io.packagex.arcount

/**
 * Minimum-cost one-to-one assignment on a rectangular matrix (Kuhn–Munkres with potentials, O(n²·m)). An infinite
 * cost is a pair outside the gate: it is never assigned. Among the assignments with the most gated pairs, the
 * cheapest wins.
 */
object Hungarian {
    /** Stands in for a forbidden pair: larger than any sum of gated costs */
    private const val FORBIDDEN = 1e6

    /** For each row of [cost], the column it is assigned, or -1 */
    fun assign(cost: Array<DoubleArray>): IntArray {
        val rows = cost.size
        val cols = if (rows == 0) 0 else cost[0].size
        if (rows == 0 || cols == 0) return IntArray(rows) { -1 }
        if (rows > cols) {
            val byColumn = solve(Array(cols) { c -> DoubleArray(rows) { r -> cost[r][c] } })
            val out = IntArray(rows) { -1 }
            byColumn.forEachIndexed { c, r -> if (r >= 0) out[r] = c }
            return out
        }
        return solve(cost)
    }

    /** rows ≤ columns */
    private fun solve(a: Array<DoubleArray>): IntArray {
        val n = a.size
        val m = a[0].size
        fun c(i: Int, j: Int) = a[i - 1][j - 1].let { if (it.isFinite()) it else FORBIDDEN }
        val u = DoubleArray(n + 1)
        val v = DoubleArray(m + 1)
        val p = IntArray(m + 1)
        val way = IntArray(m + 1)
        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
            val used = BooleanArray(m + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.POSITIVE_INFINITY
                var j1 = 0
                for (j in 1..m) {
                    if (used[j]) continue
                    val cur = c(i0, j) - u[i0] - v[j]
                    if (cur < minv[j]) {
                        minv[j] = cur
                        way[j] = j0
                    }
                    if (minv[j] < delta) {
                        delta = minv[j]
                        j1 = j
                    }
                }
                for (j in 0..m) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        val out = IntArray(n) { -1 }
        for (j in 1..m) {
            val i = p[j]
            if (i != 0 && a[i - 1][j - 1].isFinite()) out[i - 1] = j - 1
        }
        return out
    }
}
