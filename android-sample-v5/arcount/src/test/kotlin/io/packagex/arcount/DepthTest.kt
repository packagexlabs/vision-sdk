package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

class DepthTest {
    private val f = 2896.0
    private val sigma15 = 15.0 / f
    private val config = CountConfig()

    /** A ray from [origin] to [target], its direction turned by Gaussian noise of [noisePx] pixels at f */
    private fun ray(origin: Vec3, target: Vec3, noisePx: Double = 0.0, rnd: Random = Random(1)): Ray {
        val d = (target - origin).unit()
        if (noisePx == 0.0) return Ray(origin, d)
        val a = (d cross Vec3(0.0, 1.0, 0.0)).unit()
        val b = d cross a
        val s = noisePx / f
        return Ray(origin, (d + a * (gauss(rnd) * s) + b * (gauss(rnd) * s)).unit())
    }

    private fun gauss(rnd: Random): Double {
        val u = rnd.nextDouble(1e-12, 1.0)
        return sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * PI * rnd.nextDouble())
    }

    /** [n] camera centres on the x axis spread over [baseline], all looking at [target] */
    private fun line(n: Int, baseline: Double, target: Vec3, noisePx: Double = 0.0, seed: Int = 7): List<Ray> {
        val rnd = Random(seed)
        return (0 until n).map { i -> ray(Vec3(-baseline / 2 + baseline * i / (n - 1), 0.0, 0.0), target, noisePx, rnd) }
    }

    /** Two clusters of camera centres at the ends of a span of [spanDeg] seen from [target] at depth [z] */
    private fun twoClusters(left: Int, right: Int, spanDeg: Double, z: Double): List<Ray> {
        val half = z * tan(spanDeg / 2 * PI / 180)
        val target = Vec3(0.0, 0.0, -z)
        return List(left) { ray(Vec3(-half, 0.0, 0.0), target) } + List(right) { ray(Vec3(half, 0.0, 0.0), target) }
    }

    @Test
    fun leastSquaresRecoversDepthWithin2mmOn1pxNoiseAt30cmWithA3cmBaseline() {
        val target = Vec3(0.02, -0.01, -0.30)
        val fit = Triangulation.fit(line(30, 0.03, target, noisePx = 1.0), sigma15, config)!!
        assertEquals(target.z, fit.point.z, 0.002)
        assertTrue((fit.point - target).norm() < 0.002)
    }

    @Test
    fun theWideGateFiresOnFiveInliersOverThreeDegreesWithTheBaselineAndSigmaZWithin2cm() {
        // 3.5 cm at 30 cm: a 6.7° span, more than 2 * z * tan(1.5°) = 1.57 cm of baseline, sigma z about 1.7 cm
        val fit = Triangulation.fit(line(5, 0.035, Vec3(0.0, 0.0, -0.30)), sigma15, config)!!
        assertEquals(DepthGate.WIDE, fit.gate)
        assertTrue(fit.baseline >= 2 * fit.range * tan(1.5 * PI / 180))
        assertTrue(fit.sigmaZ <= 0.02)
    }

    @Test
    fun theWideGateNeedsSigmaZWithin2cm() {
        // 4.9 cm at 80 cm: span and baseline pass, sigma z is about 8.6 cm
        val fit = Triangulation.fit(line(5, 0.049, Vec3(0.0, 0.0, -0.80)), sigma15, config)!!
        assertTrue(fit.spanRad >= 3 * PI / 180 && fit.baseline >= 2 * fit.range * tan(1.5 * PI / 180))
        assertTrue(fit.sigmaZ > 0.02)
        assertNull(fit.gate)
    }

    @Test
    fun noGateTakesADepthFromRaysThatDisagreeByMoreThanOneAndAHalfSigma() {
        // twelve rays over 5 cm at 30 cm, each turned 2 sigma up or down in turn: inside the 3 sigma rejection
        val target = Vec3(0.0, 0.0, -0.30)
        val rays = (0 until 12).map { i ->
            val o = Vec3(-0.025 + 0.05 * i / 11, 0.0, 0.0)
            val d = (target - o).unit()
            Ray(o, (d + Vec3(0.0, if (i % 2 == 0) 2 * sigma15 else -2 * sigma15, 0.0)).unit())
        }
        val fit = Triangulation.fit(rays, sigma15, config)!!
        assertEquals(12, fit.inliers)
        assertTrue(fit.sigmaZ <= 0.02)
        assertTrue(fit.rmsResidual > 1.5 * sigma15)
        assertNull(fit.gate)
    }

    @Test
    fun fourRaysAreNotEnoughForTheWideGate() {
        assertNull(Triangulation.fit(line(4, 0.035, Vec3(0.0, 0.0, -0.30)), sigma15, config)!!.gate)
    }

    @Test
    fun theWideGateNeedsThreeDegrees() {
        // 1.3 cm at 30 cm is 2.5°: enough inliers, not enough span (and sigma z is far above 2 cm for the dense gate)
        assertNull(Triangulation.fit(line(7, 0.013, Vec3(0.0, 0.0, -0.30)), sigma15, config)!!.gate)
    }

    @Test
    fun theDenseGateFiresOnEightInliersSpanningTwoDegreesWithSigmaZWithin2cm() {
        val fit = Triangulation.fit(twoClusters(4, 4, 2.8, 0.25), sigma15, config)!!
        assertEquals(DepthGate.DENSE, fit.gate)
        assertTrue(fit.sigmaZ <= 0.02)
        assertEquals(8, fit.inliers)
    }

    @Test
    fun sevenRaysAreNotEnoughForTheDenseGate() {
        val fit = Triangulation.fit(twoClusters(4, 3, 2.8, 0.25), sigma15, config)!!
        assertNull(fit.gate)
    }

    @Test
    fun theDenseGateNeedsSigmaZWithin2cm() {
        val fit = Triangulation.fit(twoClusters(4, 4, 2.8, 0.40), sigma15, config)!!
        assertTrue(fit.sigmaZ > 0.02)
        assertNull(fit.gate)
    }

    @Test
    fun theDenseGateNeedsTwoDegrees() {
        val fit = Triangulation.fit(twoClusters(15, 15, 1.8, 0.10), sigma15, config)!!
        assertTrue(fit.sigmaZ <= 0.02)
        assertNull(fit.gate)
    }

    @Test
    fun raysFromOneCameraCentreGiveNoDepth() {
        val rnd = Random(3)
        val rays = List(30) { ray(Vec3.ZERO, Vec3(0.0, 0.0, -0.30), 1.0, rnd) }
        assertNull(Triangulation.fit(rays, sigma15, config)?.gate)
    }

    @Test
    fun aRayFarOffIsRejectedAtThreeSigma() {
        val target = Vec3(0.0, 0.0, -0.30)
        val good = line(10, 0.03, target)
        val bad = ray(Vec3(0.0, 0.0, 0.0), Vec3(0.05, 0.0, -0.30))
        val fit = Triangulation.fit(good + bad, sigma15, config)!!
        assertEquals(10, fit.inliers)
        assertTrue((fit.point - target).norm() < 1e-6)
    }

    @Test
    fun aDepthBeyondOneAndAHalfMetresIsDiscarded() {
        val fit = Triangulation.fit(line(10, 0.3, Vec3(0.0, 0.0, -2.0)), sigma15, config)!!
        assertNull(fit.gate)
    }

    @Test
    fun theWindowKeepsTheLastThirtyRays() {
        val track = DepthTrack(30)
        repeat(40) { track.add(Ray(Vec3(it.toDouble(), 0.0, 0.0), Vec3(0.0, 0.0, -1.0))) }
        assertEquals(30, track.size)
        assertEquals(39.0, track.rays.last().origin.x, 0.0)
        assertEquals(10.0, track.rays.first().origin.x, 0.0)
    }

    @Test
    fun theSectionPlaneIsVerticalAndItsShelfAxisHorizontal() {
        val down = Vec3(0.3, -0.5, -1.0)
        val plane = SectionPlane.vertical(down, Vec3(0.0, 1.0, 0.0), Vec3(0.1, 0.2, -0.4))
        assertEquals(0.0, plane.normal.y, 1e-12)
        assertEquals(0.0, plane.shelfAxis.y, 1e-12)
        assertEquals(0.0, plane.shelfAxis dot plane.normal, 1e-12)
        assertTrue(plane.normal dot down > 0)
        assertEquals(plane.offset, plane.normal dot Vec3(0.1, 0.2, -0.4), 1e-12)
    }

    @Test
    fun thePlanePriorPutsThePointWhereTheRayMeetsThePlaneWith15cmAlongTheRay() {
        val plane = SectionPlane.vertical(Vec3(0.0, 0.0, -1.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, -0.4))
        val r = Ray(Vec3.ZERO, Vec3(0.1, 0.05, -1.0).unit())
        val prior = plane.prior(r, 0.15, sigma15)!!
        assertEquals(-0.4, prior.point.z, 1e-12)
        assertEquals(0.0, r.distanceTo(prior.point), 1e-12)
        assertEquals(0.15, prior.sigmaZ, 0.0)
        assertEquals(0.15 * 0.15, prior.covariance.quadratic(r.dir), 1e-12)
        val across = (r.dir cross Vec3(0.0, 1.0, 0.0)).unit()
        val range = prior.point.norm()
        assertEquals(sigma15 * range, sqrt(prior.covariance.quadratic(across)), 1e-12)
    }

    @Test
    fun aRayAwayFromThePlaneMeetsItNowhere() {
        val plane = SectionPlane.vertical(Vec3(0.0, 0.0, -1.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, -0.4))
        assertNull(plane.intersect(Ray(Vec3.ZERO, Vec3(1.0, 0.0, 0.0))))
        assertNull(plane.intersect(Ray(Vec3.ZERO, Vec3(0.0, 0.0, 1.0))))
        assertNotNull(plane.intersect(Ray(Vec3.ZERO, Vec3(0.0, 0.0, -1.0))))
        assertTrue(abs(plane.height(Vec3(0.0, 0.3, 0.0)) - 0.3) < 1e-12)
    }

    @Test
    fun aThreeByThreeInverseUndoesItsMatrix() {
        val m = Mat3(doubleArrayOf(4.0, 1.0, 0.5, 1.0, 3.0, 0.2, 0.5, 0.2, 2.0))
        val v = Vec3(0.3, -1.0, 2.0)
        val back = m.inverse()!! * (m * v)
        assertEquals(v.x, back.x, 1e-12)
        assertEquals(v.y, back.y, 1e-12)
        assertEquals(v.z, back.z, 1e-12)
        assertNull(Mat3(DoubleArray(9)).inverse())
    }
}
