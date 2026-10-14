package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurTest {
    private val config = CountConfig()
    private val y = Vec3(0.0, 1.0, 0.0)

    @Test
    fun predictedBlurIsRotationPlusTranslationOverTheModule() {
        val expected = (0.1 * 0.033 * 2896 + 0.05 * 0.033 * 2896 / 0.4) / 2.4
        assertEquals(expected, Blur.modules(0.1, 0.05, 0.033, 2896.0, 0.4, 2.4), 1e-9)
    }

    @Test
    fun ratesComeFromConsecutivePoseRecords() {
        val a = PoseRecord(0, Pose(Vec3.ZERO, Quat.IDENTITY), null, Tracking.TRACKING, null, K4K, 33_000_000)
        val b = a.copy(timestampNs = 33_333_333, camera = Pose(Vec3(0.001, 0.0, 0.0), Quat.axisAngle(y, 0.01)))
        assertEquals(0.3, Blur.rotationRate(a.camera, b.camera, 1 / 30.0), 1e-9)
        assertEquals(0.03, Blur.speed(a.camera, b.camera, 1 / 30.0), 1e-9)
        val modules = Blur.of(a, b, z = 0.4, modulePx = 2.4)!!
        assertEquals(Blur.modules(0.3, 0.03, 0.033, 2896.0, 0.4, 2.4), modules, 1e-6)
        assertNull(Blur.of(a, b.copy(exposureNs = -1), z = 0.4, modulePx = 2.4))
    }

    @Test
    fun slowDownOnlyAfterMoreThanASecondAboveOneModule() {
        val watch = BlurWatch(config)
        assertFalse(watch.update(0, 1.5))
        assertFalse(watch.update(1_000_000_000L, 1.5))
        assertTrue(watch.update(1_033_000_000L, 1.5))
        assertFalse(watch.update(1_066_000_000L, 0.8))
        assertFalse(watch.update(2_000_000_000L, 1.5))
        assertFalse(watch.update(2_500_000_000L, null))
        assertFalse(watch.update(3_100_000_000L, 1.5))
    }

    @Test
    fun slideALittleWhenAUnitInViewHasHadNoDepthForASecond() {
        val t = UnitTable(config, SectionFrame(SectionPlane.vertical(Vec3(0.0, 0.0, -1.0), y, Vec3(0.0, 0.0, -0.3)), setOf(GTIN14)))
        val r = PoseRecord(0, cameraAt(0.0), Pose.IDENTITY, Tracking.TRACKING, Tracking.TRACKING, K4K, EXPOSURE_NS)
        t.associate(r, shoot(r.camera, listOf(Symbol(GTIN, Vec3(0.0, 0.04, -0.3), 1)), 0), 1)
        assertFalse(Blur.slideALittle(t.units, 900_000_000L, config))
        assertTrue(Blur.slideALittle(t.units, 1_000_000_000L, config))
        assertFalse(Blur.slideALittle(emptyList(), 5_000_000_000L, config))
    }
}
