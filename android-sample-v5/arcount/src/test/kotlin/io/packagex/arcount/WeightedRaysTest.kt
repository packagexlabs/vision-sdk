package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Depth from rays of different noise (spec 5.9): a ray of noise σ has weight (σray / σ)² in the fit */
class WeightedRaysTest {
    private val f = 2896.0
    private val sigma = 15.0 / f
    private val config = CountConfig()
    private val target = Vec3(0.0, 0.0, -0.30)

    /** [n] rays from camera centres spread over [baseline] along x, aimed at [at] */
    private fun line(n: Int, baseline: Double, at: Vec3, y: Double = 0.0) =
        (0 until n).map { i -> Vec3(-baseline / 2 + baseline * i / (n - 1), y, 0.0).let { o -> Ray(o, (at - o).unit()) } }

    @Test
    fun weightsOfOneGiveTheUnweightedFit() {
        val rays = line(10, 0.06, target) + line(10, 0.06, target + Vec3(0.003, 0.0, 0.0), y = 0.01)
        val plain = Triangulation.fit(rays, sigma, config)!!
        val ones = Triangulation.fit(rays, sigma, config, List(rays.size) { 1.0 })!!
        assertEquals(plain.point, ones.point)
        assertEquals(plain.sigmaZ, ones.sigmaZ, 0.0)
        assertEquals(plain.inliers, ones.inliers)
    }

    @Test
    fun raysOfLowWeightPullThePointLess() {
        // two groups that disagree by 3 mm across the view: equal weights land between them, a tenth on the light ones
        // lands a tenth of the way
        val good = line(10, 0.06, target)
        val off = line(10, 0.06, target + Vec3(0.003, 0.0, 0.0), y = 0.01)
        val equal = Triangulation.fit(good + off, sigma, config)!!
        val weighted = Triangulation.fit(good + off, sigma, config, List(10) { 1.0 } + List(10) { 0.1 })!!
        assertEquals(20, equal.inliers)
        assertEquals(20, weighted.inliers)
        assertEquals(0.0015, equal.point.x, 3e-4)
        assertEquals(0.003 * 0.1 / 1.1, weighted.point.x, 3e-4)
    }

    @Test
    fun rejectionIsInUnitsOfEachRaysOwnSigma() {
        // one ray 4 σray off: rejected at weight 1 (3 σ), kept at weight 1/4, where its own σ is 2 σray
        val rays = line(12, 0.06, target)
        val o = Vec3(0.01, 0.0, 0.0)
        val d = (target - o).unit()
        val turned = (d + Vec3(1.0, 0.0, 0.0) * (4 * sigma)).unit()
        val all = rays + Ray(o, turned)
        assertEquals(12, Triangulation.fit(all, sigma, config)!!.inliers)
        assertEquals(13, Triangulation.fit(all, sigma, config, List(12) { 1.0 } + 0.25)!!.inliers)
    }

    @Test
    fun aDepthTrackKeepsEachRaysWeightThroughItsWindow() {
        val track = DepthTrack(4)
        val rays = line(6, 0.06, target)
        rays.forEachIndexed { i, r -> track.add(r, if (i % 2 == 0) 1.0 else 0.5) }
        assertEquals(4, track.size)
        val fit = track.fit(sigma, config)!!
        val direct = Triangulation.fit(rays.drop(2), sigma, config, listOf(1.0, 0.5, 1.0, 0.5))!!
        assertEquals(direct.point, fit.point)
        assertTrue(abs(fit.point.z + 0.30) < 1e-9)
    }
}
