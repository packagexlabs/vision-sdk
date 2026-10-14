package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt

class PredictionTest {
    private val k = Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160)
    private val config = CountConfig()
    private val anchor = Pose(Vec3(0.2, -0.1, -0.6), Quat.axisAngle(Vec3(0.0, 1.0, 0.0), 0.4))
    private val y = Vec3(0.0, 1.0, 0.0)

    @Test
    fun predictionIsWithinOnePixelOnPerfectDataAfterOneMetreOfTravel() {
        val unit = Vec3(0.05, 0.03, -0.30)
        // ten noise-free reads over 3 cm of sideways travel give the unit's point in the anchor frame
        val rays = (0 until 10).map { i ->
            val camera = Pose(Vec3(-0.015 + 0.003 * i, 0.0, 0.0), Quat.axisAngle(y, 0.01 * i))
            val record = PoseRecord(0, camera, anchor, Tracking.TRACKING, Tracking.TRACKING, k)
            val (u, v) = k.project(camera.inverse().apply(unit))!!
            record.ray(u, v)
        }
        val fit = Triangulation.fit(rays, config.sigmaRayPx / k.fx, config)!!
        // the camera walks 0.5 m along the shelf and back (1 m of travel), and ends turned and lower
        val end = Pose(Vec3(0.08, -0.04, 0.05), Quat.axisAngle(Vec3(0.3, 1.0, 0.1), -0.12))
        val predicted = Prediction.pixel(fit.point, cameraInAnchor(end, anchor), k)!!
        val truth = k.project(end.inverse().apply(unit))!!
        assertTrue(hypot(predicted.first - truth.first, predicted.second - truth.second) <= 1.0)
    }

    @Test
    fun aPointBehindTheCameraHasNoPrediction() {
        assertNull(Prediction.pixel(Vec3(0.0, 0.0, 0.5), Pose.IDENTITY, k))
    }

    @Test
    fun cameraDepthIsTheDistanceAlongTheOpticalAxis() {
        val tac = Pose(Vec3(0.0, 0.0, 0.1), Quat.IDENTITY)
        assertEquals(0.4, Prediction.cameraDepth(Vec3(0.2, 0.1, -0.3), tac), 1e-12)
    }

    @Test
    fun travelAlongTheViewingRayIsNotPerpendicular() {
        val x = Vec3(0.0, 0.0, -0.4)
        assertEquals(0.0, Prediction.perpendicularTravel(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, -0.1), x), 1e-12)
        assertEquals(0.03, Prediction.perpendicularTravel(Vec3(0.0, 0.0, 0.0), Vec3(0.03, 0.0, 0.0), x), 1e-3)
    }

    @Test
    fun withoutTravelSigmaIsTheRelativePoseAndRayNoise() {
        assertEquals(sqrt(24.0 * 24.0 + 15.0 * 15.0), Prediction.sigmaPx(2896.0, 0.0, 0.15, 0.4, 0.0, config), 1e-9)
    }

    @Test
    fun sigmaGrowsWithPerpendicularTravelAndWithDepthSigma() {
        val base = Prediction.sigmaPx(2896.0, 0.01, 0.01, 0.4, 0.01, config)
        assertTrue(Prediction.sigmaPx(2896.0, 0.05, 0.01, 0.4, 0.05, config) > base)
        assertTrue(Prediction.sigmaPx(2896.0, 0.01, 0.15, 0.4, 0.01, config) > base)
        // f * t * sigmaZ / z^2: 5 cm across with the 15 cm prior at 40 cm is 136 px
        val prior = Prediction.sigmaPx(2896.0, 0.05, 0.15, 0.4, 0.05, config)
        assertEquals(sqrt(135.75 * 135.75 + 24.0 * 24.0 + 15.0 * 15.0), prior, 1e-6)
    }

    @Test
    fun theRelativePoseTermGrowsWithTravelByItsSlope() {
        val sloped = config.copy(sigmaTSlopePxPerM = 10.0)
        assertEquals(sqrt(29.0 * 29.0 + 15.0 * 15.0), Prediction.sigmaPx(2896.0, 0.0, 0.0, 0.4, 0.5, sloped), 1e-9)
    }

    @Test
    fun aUnitsPredictionCarriesItsPixelDepthAndSigma() {
        val tac = Pose(Vec3(0.02, 0.0, 0.0), Quat.IDENTITY)
        val p = Prediction.of(Vec3(0.0, 0.0, -0.4), 0.15, Vec3.ZERO, tac, k, config)!!
        assertEquals(1920.0 - 2896.0 * 0.02 / 0.4, p.u, 1e-9)
        assertEquals(1080.0, p.v, 1e-9)
        assertEquals(0.4, p.z, 1e-12)
        assertTrue(p.sigmaPx > 28.3)
        assertTrue(p.inImage(k))
    }
}
