package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** The tracker's policy per unit (spec 5.9) on a unit table, with luma frames rendered from the true camera */
class PatchTracksTest {
    /** A shelf 30 cm away, the anchor the world, units decoded and patches followed frame by frame */
    private class Rig(val config: CountConfig, var symbols: List<Symbol>, textureAmplitude: Double = 60.0) {
        val plane = SectionPlane.vertical(Vec3(0.0, 0.0, -1.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, -0.30))
        val table = UnitTable(config, SectionFrame(plane, setOf(GTIN14)))
        val tracks = PatchTracks(config)
        val lumas = LumaRing()
        val scene = LumaScene({ symbols }, textureAmplitude = textureAmplitude)
        var frame = 0
        var last: PoseRecord? = null

        /** One frame from x: its luma copy, the reads of [read] associated, patches re-captured, then tracked when [moving] */
        fun step(x: Double, read: List<Symbol> = emptyList(), moving: Boolean = true, scene: LumaScene = this.scene): PoseRecord {
            val ts = frame * FRAME_NS
            val r = PoseRecord(ts, cameraAt(x), Pose.IDENTITY, Tracking.TRACKING, Tracking.TRACKING, K4K, EXPOSURE_NS)
            val luma = LumaFrame(ts, scene.render(ts, r.camera), scene.scale)
            lumas.add(luma)
            if (read.isNotEmpty()) table.associate(r, shoot(r.camera, read, ts), 1)
            tracks.capture(table, lumas)
            tracks.track(table, r, luma, moving)
            frame++
            last = r
            return r
        }

        /** Where [s] is in the latest frame, in stream pixels */
        fun truth(s: Symbol) = K4K.project(last!!.camera.inverse().apply(s.centre))!!
    }

    private val a = Symbol(GTIN, Vec3(0.0, 0.04, -0.30), 1)

    @Test
    fun aTrackedPositionFollowsTheBarcodeWhileTheCameraMoves() {
        val rig = Rig(CountConfig(), listOf(a))
        rig.step(0.0, read = listOf(a))
        val u = rig.table.units.single()
        var x = 0.0
        repeat(6) {
            x += 0.003
            val r = rig.step(x)
            val (tu, tv) = rig.tracks.trackedAt(u.id, r.timestampNs)!!
            val (pu, pv) = rig.truth(a)
            assertTrue("frame $it off by ${hypot(tu - pu, tv - pv)} px", hypot(tu - pu, tv - pv) < 4.0)
        }
        assertEquals(6, rig.tracks.trackCalls)
        assertEquals(6, rig.tracks.nccs(u.id).size)
        assertTrue(rig.tracks.nccs(u.id).all { it > 0.8 })
    }

    @Test
    fun nothingIsTrackedWhileTheCameraIsStill() {
        val rig = Rig(CountConfig(), listOf(a))
        rig.step(0.0, read = listOf(a))
        val r = rig.step(0.003, moving = false)
        assertEquals(0, rig.tracks.trackCalls)
        assertNull(rig.tracks.trackedAt(1, r.timestampNs))
        assertTrue(rig.tracks.hasPatch(1))
        // the first frame of motion picks the unit up again from its decoded position
        assertNotNull(rig.tracks.trackedAt(1, rig.step(0.006).timestampNs))
    }

    @Test
    fun aReadRecapturesThePatchAndTheDecodeWins() {
        val rig = Rig(CountConfig(), listOf(a))
        rig.step(0.0, read = listOf(a))
        rig.step(0.003)
        rig.step(0.006)
        assertTrue(rig.tracks.isTracked(1))
        val u = rig.table.units.single()
        val r = rig.step(0.009, read = listOf(a))
        // the read's own frame: its position is the decode, nothing tracked over it
        assertEquals(r.timestampNs, u.lastReadNs)
        assertFalse(rig.tracks.isTracked(1))
        assertTrue(rig.tracks.hasPatch(1))
        assertNull(rig.tracks.anchorPoint(u))
        assertEquals(2, rig.tracks.nccs(1).size)
        val next = rig.step(0.012)
        assertNotNull(rig.tracks.trackedAt(1, next.timestampNs))
    }

    @Test
    fun aTrackIsDroppedWhenItsNccFallsBelowTheFractionOfItsOwnMean() {
        fun run(fraction: Double): Rig {
            val rig = Rig(CountConfig(minNcc = 0.05, nccDropFraction = fraction), listOf(a))
            rig.step(0.0, read = listOf(a))
            repeat(4) { rig.step(0.003 * (it + 1)) }
            // the frame shows other bars at the barcode's place: a fit somewhere, at a much lower correlation
            val other = LumaScene({ rig.symbols.map { it.copy(text = OTHER_GTIN) } })
            rig.step(0.015, scene = other)
            return rig
        }
        val kept = run(0.0)
        val ncc = kept.tracks.nccs(1)
        val mean = ncc.dropLast(1).average()
        // the track still lands, at a correlation under 0.6 of the unit's mean
        assertTrue("nccs $ncc", ncc.size == 5 && ncc.last() < 0.6 * mean && ncc.last() > 0.05)
        assertTrue(kept.tracks.hasPatch(1))
        val dropped = run(0.6)
        assertFalse(dropped.tracks.hasPatch(1))
        assertEquals(4, dropped.tracks.nccs(1).size)
    }

    @Test
    fun aUnitIsTrackedForAtMostTrackMaxAgeSinceItsDecode() {
        val rig = Rig(CountConfig(), listOf(a))
        rig.step(0.0, read = listOf(a))
        var x = 0.0
        // 90 frames is 3 s less 30 ns
        repeat(90) {
            x += 0.0007
            rig.step(x)
        }
        assertTrue(rig.tracks.isTracked(1))
        rig.step(x + 0.0007)
        assertFalse(rig.tracks.hasPatch(1))
        assertEquals(90, rig.tracks.trackCalls)
    }

    /** Units A and B of one GTIN, B [dy] above A; both decoded, then both tracked in one frame */
    private fun twoUnits(dy: Double): Rig {
        val b = Symbol(GTIN, Vec3(0.0, 0.04 + dy, -0.30), 2)
        val rig = Rig(CountConfig(), listOf(a, b))
        rig.step(0.0, read = listOf(a))
        rig.step(0.003, read = listOf(b))
        assertEquals(2, rig.table.units.size)
        rig.step(0.006)
        return rig
    }

    @Test
    fun twoTrackedPositionsOfOneGtinWithinHalfAPitchDropBoth() {
        // 2.5 cm apart at 30 cm is 241 px, under half the 6 cm pitch_px of 579 px
        val near = twoUnits(0.025)
        assertFalse(near.tracks.hasPatch(1))
        assertFalse(near.tracks.hasPatch(2))
        assertEquals(0L, near.tracks.trackedRays)
        // 4.5 cm apart (434 px): both kept
        val apart = twoUnits(0.045)
        assertTrue(apart.tracks.isTracked(1))
        assertTrue(apart.tracks.isTracked(2))
    }

    /** A symbol 12 cm tall on a plain shelf: the patch has bars only, no top or bottom edge */
    private fun bars(tall: Boolean): Pair<Rig, CountUnit> {
        val s = Symbol(GTIN, Vec3(0.0, 0.04, -0.30), 1, height = if (tall) 0.12 else 0.02)
        val rig = Rig(CountConfig(trackMinParallaxSigmas = 0.0), listOf(s), textureAmplitude = 0.0)
        rig.step(0.0, read = listOf(s))
        repeat(5) { rig.step(0.003 * (it + 1)) }
        return rig to rig.table.units.single()
    }

    @Test
    fun aOneDimensionalTrackGivesNoRay() {
        val (tall, unit) = bars(tall = true)
        assertTrue(tall.tracks.isTracked(unit.id))
        assertEquals(0L, tall.tracks.trackedRays)
        assertEquals(1, unit.rays)
        // the same bars with their top and bottom edges in the patch give a ray every frame
        val (short, other) = bars(tall = false)
        assertEquals(5L, short.tracks.trackedRays)
        assertEquals(6, other.rays)
    }

    @Test
    fun trackedRaysWaitForParallaxOfTenSigmaSinceTheCapture() {
        // 10 σray = 150 px at f 2896 and 30 cm is 1.55 cm of travel across the ray
        val rig = Rig(CountConfig(), listOf(a))
        rig.step(0.0, read = listOf(a))
        repeat(5) { rig.step(0.003 * (it + 1)) }
        assertEquals(0L, rig.tracks.trackedRays)
        assertEquals(1, rig.table.units.single().rays)
        rig.step(0.018)
        assertEquals(6L, rig.tracks.trackedRays)
        assertEquals(7, rig.table.units.single().rays)
    }
}
