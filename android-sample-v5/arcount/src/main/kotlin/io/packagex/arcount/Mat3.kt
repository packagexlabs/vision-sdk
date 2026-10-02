package io.packagex.arcount

import kotlin.math.abs

/** A 3×3 matrix, row-major: the normal equations and covariances of a unit's point */
class Mat3(private val m: DoubleArray) {
    init {
        require(m.size == 9) { "a 3×3 matrix has 9 numbers, not ${m.size}" }
    }

    operator fun get(r: Int, c: Int) = m[3 * r + c]

    operator fun plus(o: Mat3) = Mat3(DoubleArray(9) { m[it] + o.m[it] })

    operator fun times(s: Double) = Mat3(DoubleArray(9) { m[it] * s })

    operator fun times(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    /** vᵀ · this · v: the variance along unit direction v when this is a covariance */
    fun quadratic(v: Vec3) = v dot (this * v)

    fun det() = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])

    /** The inverse; null when the matrix is singular to working precision */
    fun inverse(): Mat3? {
        val d = det()
        val scale = m.maxOf { abs(it) }
        if (scale == 0.0 || !d.isFinite() || abs(d) <= 1e-14 * scale * scale * scale) return null
        val c = doubleArrayOf(
            m[4] * m[8] - m[5] * m[7], m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
            m[5] * m[6] - m[3] * m[8], m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
            m[3] * m[7] - m[4] * m[6], m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3],
        )
        return Mat3(DoubleArray(9) { c[it] / d })
    }

    companion object {
        val ZERO = Mat3(DoubleArray(9))
        val IDENTITY = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        /** a · bᵀ */
        fun outer(a: Vec3, b: Vec3) = Mat3(doubleArrayOf(a.x * b.x, a.x * b.y, a.x * b.z, a.y * b.x, a.y * b.y, a.y * b.z, a.z * b.x, a.z * b.y, a.z * b.z))

        /** I − d·dᵀ for a unit direction d: what a ray along d constrains */
        fun across(d: Vec3) = IDENTITY + outer(d, d) * -1.0
    }
}
