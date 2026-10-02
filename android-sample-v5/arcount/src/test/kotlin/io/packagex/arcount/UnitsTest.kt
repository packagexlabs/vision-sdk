package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitsTest {
    private val y = 0.04
    private val depth = 0.30

    /** A table whose plane is at [planeDepth]; the label sits 12 cm below the units */
    private fun table(planeDepth: Double = depth): UnitTable {
        val plane = SectionPlane.vertical(Vec3(0.0, 0.0, -1.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, -planeDepth))
        val labelRay = Ray(Vec3.ZERO, Vec3(0.0, y - 0.12, -depth).unit())
        return UnitTable(CountConfig(), SectionFrame(plane, setOf(GTIN14), labelRay, plane.intersect(labelRay)))
    }

    private fun record(ts: Long, x: Double = 0.0) =
        PoseRecord(ts, cameraAt(x), Pose.IDENTITY, Tracking.TRACKING, Tracking.TRACKING, K4K, EXPOSURE_NS)

    private fun unit(x: Double, id: Int = 1, upsideDown: Boolean = false, text: String = GTIN, height: Double = y) =
        Symbol(text, Vec3(x, height, -depth), id, upsideDown = upsideDown)

    /** Frame [i] (at 30 fps) seen from camera position [x], reading [symbols] */
    private fun UnitTable.see(i: Int, x: Double, vararg symbols: Symbol, segment: Int = 1): FrameOutcome {
        val r = record(i * FRAME_NS, x)
        return associate(r, shoot(r.camera, symbols.toList(), r.timestampNs), segment)
    }

    private fun UnitTable.states() = units.map { it.state }

    @Test
    fun aUnitIsCountedOnItsSecondGatedReadInALaterFrame() {
        val t = table()
        t.see(0, 0.0, unit(0.0))
        assertEquals(listOf(UnitState.TENTATIVE), t.states())
        t.see(1, 0.0, unit(0.0))
        assertEquals(listOf(UnitState.COUNTED), t.states())
        assertEquals(2, t.units[0].observations)
    }

    @Test
    fun theSecondReadMustComeInTheSameSegment() {
        val t = table()
        t.see(0, 0.0, unit(0.0), segment = 1)
        t.see(1, 0.0, unit(0.0), segment = 2)
        assertEquals(listOf(UnitState.TENTATIVE), t.states())
        t.see(2, 0.0, unit(0.0), segment = 2)
        assertEquals(listOf(UnitState.COUNTED), t.states())
    }

    @Test
    fun aGatedReadUpdatesTheUnitsQuadPoseObservationsAndRays() {
        val t = table()
        t.see(0, 0.0, unit(0.0))
        val second = t.see(1, 0.002, unit(0.0, id = 9))
        val u = t.units.single()
        assertEquals(second.matched.keys.single(), u.lastRead)
        assertEquals(FRAME_NS, u.lastReadNs)
        assertEquals(9, u.lastEngineId)
        assertEquals(0.002, u.lastCentre.x, 1e-12)
        assertEquals(2, u.rays)
    }

    @Test
    fun theSameCodeDecodedTwiceInOneFrameAThirdOfAPitchApartIsOneUnit() {
        val t = table()
        val out = t.see(0, 0.0, unit(0.0, id = 1), unit(0.02, id = 2))
        assertEquals(1, t.units.size)
        assertEquals(Drop.DUPLICATE, out.dropped.single().second)
    }

    @Test
    fun twoReadsOfOneGtinAPitchApartInOneFrameAreTwoUnits() {
        val t = table()
        t.see(0, 0.0, unit(0.0, id = 1), unit(0.06, id = 2))
        assertEquals(2, t.units.size)
    }

    @Test
    fun anUpsideDownSymbolNextToAnUprightOneIsTwoUnitsAndIsLogged() {
        val t = table()
        t.see(0, 0.0, unit(0.0, id = 1), unit(0.06, id = 2, upsideDown = true))
        t.see(1, 0.0, unit(0.0, id = 1), unit(0.06, id = 2, upsideDown = true))
        assertEquals(listOf(UnitState.COUNTED, UnitState.COUNTED), t.states())
        assertTrue(t.events.any { it.contains("opposite reading directions") })
    }

    @Test
    fun aReadTouchingTheBorderInTheRailBandOfAnotherGtinOrBeyondTheExtentIsNotAUnit() {
        val t = table()
        val out = t.see(0, 0.0, unit(0.19), unit(0.0, height = y - 0.12), unit(-0.1, text = OTHER_GTIN))
        assertEquals(0, t.units.size)
        assertEquals(setOf(Drop.BORDER, Drop.RAIL_BAND, Drop.OTHER_GTIN), out.dropped.map { it.second }.toSet())
        assertEquals(Drop.OUTSIDE_EXTENT, t.see(1, 1.2, unit(1.2)).dropped.single().second)
        // 5 cm above the label is outside its ±4 cm band
        t.see(2, 0.0, unit(0.0, height = y - 0.07))
        assertEquals(1, t.units.size)
    }

    @Test
    fun aReadWithinAQuarterPitchOfThePredictionIsTheSameUnit() {
        val t = table()
        t.see(0, 0.0, unit(0.0, id = 1))
        t.see(1, 0.0, unit(0.2 * 0.06, id = 1))
        assertEquals(listOf(UnitState.COUNTED), t.states())
    }

    @Test
    fun anUnmatchedReadInsideTheBandOfAnUnmatchedUnitIsAmbiguous() {
        val t = table()
        t.see(0, 0.0, unit(0.0, id = 1))
        t.see(1, 0.0, unit(0.3 * 0.06, id = 2))
        assertEquals(listOf(UnitState.TENTATIVE, UnitState.AMBIGUOUS), t.states())
        assertEquals(t.units[0].id, t.units[1].linkedTo)
    }

    @Test
    fun anUnmatchedReadBeyondTheBandIsANewTentativeUnit() {
        val t = table()
        t.see(0, 0.0, unit(0.0, id = 1))
        t.see(1, 0.0, unit(0.06, id = 2))
        assertEquals(listOf(UnitState.TENTATIVE, UnitState.TENTATIVE), t.states())
    }

    @Test
    fun anAmbiguousUnitIsCountedWhenItAndItsLinkedUnitAreReadInOneFrame() {
        val t = table()
        // 0.6 pitch: outside the gate, inside the band, and far enough apart not to be one code decoded twice
        t.see(0, 0.0, unit(0.0, id = 1))
        t.see(1, 0.0, unit(0.036, id = 2))
        assertEquals(listOf(UnitState.TENTATIVE, UnitState.AMBIGUOUS), t.states())
        t.see(2, 0.0, unit(0.0, id = 1), unit(0.036, id = 2))
        assertEquals(listOf(UnitState.COUNTED, UnitState.COUNTED), t.states())
    }

    /** V at 0 and W at 8 cm read; then U, 0.3 pitch from V, read alone: AMBIGUOUS linked to V */
    private fun ambiguousNextToV(): UnitTable {
        val t = table()
        t.see(0, 0.0, unit(0.0, id = 1), unit(0.08, id = 3))
        t.see(1, 0.0, unit(0.018, id = 2))
        assertEquals(listOf(UnitState.TENTATIVE, UnitState.TENTATIVE, UnitState.AMBIGUOUS), t.states())
        assertEquals(t.units[0].id, t.units[2].linkedTo)
        return t
    }

    @Test
    fun anAmbiguousUnitMergesAfterThreeFramesThatReadEveryOtherUnitInViewAndNothingInItsGate() {
        val t = ambiguousNextToV()
        t.see(2, 0.0, unit(0.0, id = 1), unit(0.08, id = 3))
        t.see(3, 0.0, unit(0.0, id = 1), unit(0.08, id = 3))
        assertEquals(3, t.units.size)
        val out = t.see(4, 0.0, unit(0.0, id = 1), unit(0.08, id = 3))
        assertEquals(listOf(UnitState.COUNTED, UnitState.COUNTED), t.states())
        assertEquals(1, out.merged.size)
    }

    @Test
    fun noMergeWhileAnotherUnitInViewGoesUnreadForTheEngineHadNoBudgetToSpare() {
        val t = ambiguousNextToV()
        for (i in 2..7) t.see(i, 0.0, unit(0.0, id = 1))
        assertEquals(UnitState.AMBIGUOUS, t.units[2].state)
    }

    @Test
    fun noMergeWhileTheLinkedUnitGoesUnread() {
        val t = ambiguousNextToV()
        for (i in 2..7) t.see(i, 0.0, unit(0.08, id = 3))
        assertEquals(UnitState.AMBIGUOUS, t.units[2].state)
    }

    @Test
    fun aReadInsideTheAmbiguousUnitsGateIsNoEvidenceOfItsAbsence() {
        val t = table()
        t.see(0, 0.0, unit(0.0, id = 1))
        t.see(1, 0.0, unit(0.018, id = 2))
        // one code between them, inside both gates: matched to the linked unit by its engine id
        for (i in 2..7) t.see(i, 0.0, unit(0.009, id = 1))
        assertEquals(listOf(UnitState.COUNTED, UnitState.AMBIGUOUS), t.states())
    }

    @Test
    fun theEngineIdBreaksATieBetweenTwoUnitsInsideTheGate() {
        for (engineId in listOf(1, 2)) {
            val t = table()
            t.see(0, 0.0, unit(0.0, id = 1))
            t.see(1, 0.0, unit(0.018, id = 2))
            val out = t.see(2, 0.0, unit(0.009, id = engineId))
            val expected = t.units.first { it.lastEngineId == engineId && it.lastReadNs == 2 * FRAME_NS }
            assertEquals(expected.id, out.matched.values.single())
        }
    }

    @Test
    fun aReadWhereAUnitWasAddedByHandIsAmbiguousNotANewUnit() {
        val t = table()
        t.addManual(Vec3(0.0, y, -depth), Vec3.ZERO, 0L)
        t.see(1, 0.0, unit(0.0))
        assertEquals(listOf(UnitState.MANUAL, UnitState.AMBIGUOUS), t.states())
        assertEquals(Counts(counted = 0, manual = 1, tentative = 0, ambiguous = 1), t.counts())
        assertTrue(t.removeLastManual())
        assertEquals(listOf(UnitState.TENTATIVE), t.states())
    }

    @Test
    fun onceAUnitTriangulatesThePlaneMovesToItsDepthAndPriorsFollow() {
        val t = table(planeDepth = 0.40)
        for (i in 0 until 10) t.see(i, -0.015 + 0.003 * i, unit(0.0, id = 1))
        t.see(10, 0.015, unit(0.0, id = 1), unit(0.06, id = 2))
        val (a, b) = t.units
        assertTrue(a.depthGate != null)
        assertEquals(-depth, a.point.z, 1e-6)
        assertEquals(depth, t.frame.plane.offset, 1e-6)
        assertEquals(null, b.depthGate)
        assertEquals(-depth, b.point.z, 1e-6)
        assertEquals(0.06, b.point.x, 1e-6)
    }

    @Test
    fun thePitchFollowsTheCountedUnits() {
        val t = table()
        val row = (0 until 4).map { unit(-0.12 + 0.08 * it, id = it + 1) }.toTypedArray()
        t.see(0, 0.0, *row)
        assertEquals(0.06, t.pitch, 0.0)
        t.see(1, 0.0, *row)
        assertEquals(0.08, t.pitch, 1e-9)
    }
}
