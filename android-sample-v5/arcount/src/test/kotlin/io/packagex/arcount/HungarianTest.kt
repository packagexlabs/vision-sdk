package io.packagex.arcount

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class HungarianTest {
    private val no = Double.POSITIVE_INFINITY

    private fun m(vararg rows: DoubleArray) = arrayOf(*rows)

    private fun r(vararg v: Double) = v

    @Test
    fun aSquareMatrixGetsItsMinimumCostAssignment() {
        val a = m(r(4.0, 1.0, 3.0), r(2.0, 0.0, 5.0), r(3.0, 2.0, 2.0))
        assertArrayEquals(intArrayOf(1, 0, 2), Hungarian.assign(a))
    }

    @Test
    fun moreColumnsThanRows() {
        assertArrayEquals(intArrayOf(1, 2), Hungarian.assign(m(r(5.0, 1.0, 9.0), r(2.0, 8.0, 1.0))))
    }

    @Test
    fun moreRowsThanColumns() {
        assertArrayEquals(intArrayOf(1, 0, -1), Hungarian.assign(m(r(5.0, 2.0), r(1.0, 8.0), r(3.0, 3.0))))
    }

    @Test
    fun forbiddenPairsAreNeverAssigned() {
        assertArrayEquals(intArrayOf(1, -1), Hungarian.assign(m(r(no, 1.0), r(no, no))))
        assertArrayEquals(intArrayOf(-1, -1), Hungarian.assign(m(r(no, no), r(no, no))))
    }

    @Test
    fun asManyGatedPairsAsPossibleAreMatchedThenTheCheapest() {
        // alone, row 0 would take column 1; both rows match when it takes column 0
        assertArrayEquals(intArrayOf(0, 1), Hungarian.assign(m(r(0.1, 0.05), r(no, 0.2))))
    }

    @Test
    fun forbiddenPairsInATallMatrix() {
        // two pairs at most; the cheapest two are row 1 to column 0 and row 2 to column 1
        val a = m(r(no, 0.3), r(0.2, no), r(0.1, 0.1))
        assertArrayEquals(intArrayOf(-1, 0, 1), Hungarian.assign(a))
    }

    @Test
    fun emptyInputs() {
        assertArrayEquals(intArrayOf(), Hungarian.assign(arrayOf()))
        assertArrayEquals(intArrayOf(-1, -1), Hungarian.assign(arrayOf(DoubleArray(0), DoubleArray(0))))
    }
}
