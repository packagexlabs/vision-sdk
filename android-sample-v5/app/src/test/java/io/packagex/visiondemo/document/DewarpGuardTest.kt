package io.packagex.visiondemo.document

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DewarpGuardTest {
    private fun grid(vararg v: Float) = BackwardMap(2, 2, floatArrayOf(-1f, 1f, -1f, 1f), floatArrayOf(*v))

    @Test fun finiteGridIsUsable() {
        assertTrue(grid(-1f, -1f, 1f, 1f).isFinite)
    }

    @Test fun nanOrInfinityAnywhereIsRejected() {
        assertFalse(grid(-1f, Float.NaN, 1f, 1f).isFinite)
        assertFalse(grid(-1f, -1f, Float.POSITIVE_INFINITY, 1f).isFinite)
        assertFalse(BackwardMap(2, 2, floatArrayOf(-1f, Float.NEGATIVE_INFINITY, -1f, 1f), floatArrayOf(-1f, -1f, 1f, 1f)).isFinite)
    }

    @Test fun gpuSelfCheckKeepsOnlyARunThatPredictsAFiniteGrid() {
        assertTrue(gpuPassesSelfCheck { grid(-1f, -1f, 1f, 1f) })
        assertFalse(gpuPassesSelfCheck { grid(-1f, Float.NaN, 1f, 1f) })   // non-finite output
        assertFalse(gpuPassesSelfCheck { null })                           // unexpected output shape
        assertFalse(gpuPassesSelfCheck { throw IllegalStateException("delegate failed") })
    }
}
