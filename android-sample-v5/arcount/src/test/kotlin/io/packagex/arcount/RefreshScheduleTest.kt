package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine refresh schedule (spec 5.3) and the camera motion it rests on */
class RefreshScheduleTest {
    private val config = CountConfig()
    private val schedule = RefreshSchedule(config)
    private var frame = 0
    private var x = 0.0

    /** A table with a plane at 30 cm, read from the camera at the origin */
    private val table = UnitTable(config, SectionFrame(SectionPlane.vertical(Vec3(0.0, 0.0, -1.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, -0.30)), setOf(GTIN14)))

    private fun ts(i: Int = frame) = i * FRAME_NS

    /** The next pose record, the camera [step] metres further along x */
    private fun next(step: Double = 0.0): PoseRecord {
        x += step
        val r = PoseRecord(ts(), cameraAt(x), Pose.IDENTITY, Tracking.TRACKING, Tracking.TRACKING, K4K, EXPOSURE_NS)
        schedule.onFrame(r)
        frame++
        return r
    }

    /** One engine frame reading [symbols] (none: an engine frame that read nothing) */
    private fun engine(vararg symbols: Symbol) {
        schedule.onEngineFrame()
        if (symbols.isNotEmpty()) {
            val r = PoseRecord(ts(frame - 1), cameraAt(x), Pose.IDENTITY, Tracking.TRACKING, Tracking.TRACKING, K4K, EXPOSURE_NS)
            table.associate(r, shoot(r.camera, symbols.toList(), r.timestampNs), 1)
        }
        schedule.onUnits(table, ts(frame - 1))
    }

    private fun unit(x: Double, id: Int) = Symbol(GTIN, Vec3(x, 0.04, -0.30), id)

    private fun now() = schedule.desiredMs(true, ts(frame - 1))

    /** A table with one unit, then [seconds] still with an empty engine frame every frame */
    private fun withOneUnitThenStill(seconds: Double) {
        next()
        engine(unit(0.0, 1))
        repeat(Paths.frames(seconds)) {
            next()
            engine()
        }
    }

    @Test
    fun theDefaultsAreTheBriefsInitialValues() {
        assertEquals(0.02, config.stopSpeed, 0.0)
        assertEquals(3.0, config.stopRotationDegPerS, 0.0)
        assertEquals(8, config.burstFrames)
        assertEquals(300, config.refreshMovingMs)
        assertEquals(3_000_000_000L, config.backoffAfterNs)
        assertEquals(1000, config.refreshMaxMs)
        assertEquals(0.5, config.minNcc, 0.0)
        assertEquals(0.6, config.nccDropFraction, 0.0)
        assertEquals(3_000_000_000L, config.trackMaxAgeNs)
        assertEquals(config.sigmaRayPx, config.sigmaTrackPx, 0.0)
    }

    @Test
    fun zeroUntilTheTrackerIsFedAndTheSectionHasAUnit() {
        next()
        assertEquals(0, schedule.desiredMs(true, ts()))
        withOneUnitThenStill(1.0)
        assertEquals(300, now())
        assertEquals(0, schedule.desiredMs(false, ts(frame - 1)))
    }

    @Test
    fun aReadThatCreatesAUnitGivesZeroForTheNextEightEngineFrames() {
        withOneUnitThenStill(1.0)
        assertEquals(300, now())
        next()
        engine(unit(0.0, 1), unit(0.12, 2))
        assertEquals(2, table.units.size)
        repeat(8) {
            assertEquals("engine frame $it of the burst", 0, now())
            next()
            engine()
        }
        assertEquals(300, now())
    }

    @Test
    fun theCameraSettlingGivesZeroForEightEngineFrames() {
        withOneUnitThenStill(1.0)
        repeat(10) { next(0.005) }
        assertTrue(schedule.motion.moving)
        assertEquals(300, now())
        next()
        assertFalse(schedule.motion.moving)
        assertFalse(schedule.motion.settled)
        assertEquals(300, now())
        next()
        assertTrue(schedule.motion.settled)
        repeat(8) {
            assertEquals(0, now())
            engine()
        }
        assertEquals(300, now())
    }

    @Test
    fun movingGivesTheMovingRefreshAndTurningCountsAsMoving() {
        withOneUnitThenStill(4.0)
        assertTrue(now() > 300)
        next(0.001)
        // 1 mm at 30 fps is 3 cm/s
        assertTrue(schedule.motion.moving)
        assertEquals(300, now())
        val turned = PoseRecord(ts(), Pose(Vec3(x, 0.0, 0.0), Quat.axisAngle(Vec3(0.0, 1.0, 0.0), Math.toRadians(0.2))), Pose.IDENTITY, Tracking.TRACKING, Tracking.TRACKING, K4K)
        frame++
        schedule.onFrame(turned)
        // 0.2° in a frame is 6°/s with the centre still
        assertTrue(schedule.motion.moving)
    }

    @Test
    fun withNoNewUnitTheRefreshBacksOffByHalfAgainEverySecondToOneSecond() {
        next()
        engine(unit(0.0, 1))
        repeat(8) { engine() }
        val start = ts(frame - 1)
        assertEquals(300, schedule.desiredMs(true, start + 2_900_000_000L))
        assertEquals(450, schedule.desiredMs(true, start + 3_000_000_000L))
        assertEquals(450, schedule.desiredMs(true, start + 3_900_000_000L))
        assertEquals(675, schedule.desiredMs(true, start + 4_000_000_000L))
        assertEquals(1000, schedule.desiredMs(true, start + 5_000_000_000L))
        assertEquals(1000, schedule.desiredMs(true, start + 60_000_000_000L))
    }

    @Test
    fun aNewUnitOrASettleResetsTheBackOff() {
        withOneUnitThenStill(6.0)
        assertEquals(1000, now())
        next()
        engine(unit(0.0, 1), unit(0.12, 2))
        repeat(8) { engine() }
        assertEquals(300, now())
        repeat(Paths.frames(6.0)) { next() }
        assertEquals(1000, now())
        next(0.005)
        next()
        next()
        assertTrue(schedule.motion.settled)
        repeat(8) { engine() }
        assertEquals(300, now())
    }

    @Test
    fun aNewSectionStartsAgainFromNoUnit() {
        withOneUnitThenStill(1.0)
        val other = UnitTable(config, SectionFrame(table.frame.plane, setOf(GTIN14)))
        schedule.onUnits(other, ts(frame - 1))
        assertEquals(0, now())
    }

    @Test
    fun theLumaRingKeepsTheLastEightFramesByTimestamp() {
        val ring = LumaRing(8)
        val img = LumaImage(4, 4, ByteArray(16))
        assertTrue(ring.mayStillCome(5))
        assertFalse(ring.fed)
        for (i in listOf(3, 1, 2) + (4..10)) ring.add(LumaFrame(i.toLong(), img, 4.0))
        assertTrue(ring.fed)
        assertNull(ring.at(2))
        assertNotNull(ring.at(3))
        assertNotNull(ring.at(10))
        assertFalse(ring.mayStillCome(9))
        assertTrue(ring.mayStillCome(11))
        val f = ring.at(10)!!
        assertEquals(1.5, f.toStream(0.0), 0.0)
        assertEquals(0.0, f.toLuma(1.5), 0.0)
    }
}
