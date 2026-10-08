package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.ItemCode
import io.packagex.arcount.ItemCount
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Ray
import io.packagex.arcount.Read
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import io.packagex.arcount.ray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The GL thread's allocation-free forms ([applied], [projected], [centreRay], the scalar [lateral], [Inverses],
 * [ReadKeys], [LineFormat], ...) against what they replace, kept here verbatim: every number bit for bit, every key and
 * line char for char.
 */
class HotPathTest {
    private val rnd = Random(20261006)
    private val k = Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160)

    private fun d(scale: Double = 1.0) = (rnd.nextDouble() * 2 - 1) * scale
    private fun vec(scale: Double = 1.0) = Vec3(d(scale), d(scale), d(scale))
    private fun quat() = Quat.axisAngle(Vec3(d(), d(), d() + 1e-3), d(PI))
    private fun pose() = Pose(vec(2.0), quat())

    private fun bits(v: Double) = java.lang.Double.doubleToRawLongBits(v)

    private fun assertSameBits(what: String, want: Double, got: Double) = assertEquals(what, bits(want), bits(got))

    private fun assertSameBits(what: String, want: Vec3?, got: Vec3?) {
        if (want == null || got == null) {
            assertEquals(what, want, got)
            return
        }
        assertSameBits("$what x", want.x, got.x)
        assertSameBits("$what y", want.y, got.y)
        assertSameBits("$what z", want.z, got.z)
    }

    private fun read(text: String = "4006381333931", symbology: String? = "ean13", ts: Long = 0, engineId: Int = 1) =
        Read(ts, text, List(8) { if (it % 2 == 0) 1920.0 + d(1900.0) else 1080.0 + d(1070.0) }, engineId, symbology)

    // --- geometry: as the core's Vec3/Quat/Pose/Intrinsics compute it ---

    @Test fun appliedAndRotatedAreTheCoresPoseApplyAndRotateBitForBit() {
        repeat(20_000) {
            val pose = if (it % 2 == 0) pose() else pose().inverse()
            val p = vec(3.0)
            assertSameBits("apply", pose.apply(p), applied(pose, p.x, p.y, p.z) { x, y, z -> Vec3(x, y, z) })
            assertSameBits("rotate", pose.q.rotate(p), rotated(pose.q, p.x, p.y, p.z) { x, y, z -> Vec3(x, y, z) })
        }
    }

    @Test fun projectedIsIntrinsicsProjectBitForBit() {
        repeat(20_000) {
            val p = if (it % 50 == 0) Vec3(d(), d(), -1e-6 * (it % 3)) else vec(2.0)
            val want = k.project(p)
            val got = projected(k, p.x, p.y, p.z, { null }) { u, v -> u to v }
            if (want == null) {
                assertNull(got)
            } else {
                assertSameBits("u", want.first, got!!.first)
                assertSameBits("v", want.second, got.second)
            }
        }
    }

    @Test fun theCentreRayIsReadRayBitForBit() {
        repeat(5_000) {
            val capture = PoseRecord(0, pose(), null, Tracking.TRACKING, null, k)
            val r = read()
            val want = r.ray(capture, null)
            val got = centreRay(r, capture)
            assertSame(want.origin, got.origin)
            assertSameBits("dir", want.dir, got.dir)
        }
    }

    @Test fun theScalarLateralIsTheVectorOneBitForBit() {
        repeat(20_000) {
            val ray = Ray(vec(), vec().let { v -> if (v.norm() > 0) v.unit() else Vec3(0.0, 0.0, -1.0) })
            val p = vec(2.0)
            assertSameBits("lateral", lateral(ray, p), lateral(ray, p.x, p.y, p.z))
        }
    }

    @Test fun registrationDepthCheckAndWellInsideAreAsBefore() {
        repeat(20_000) {
            val camera = pose()
            val p = camera.apply(Vec3(d(0.5), d(0.5), -0.05 - rnd.nextDouble() * 2) * (if (it % 10 == 0) -1.0 else 1.0))
            val u = 1920.0 + d(1900.0)
            val v = 1080.0 + d(1000.0)
            val old = k.project(camera.inverse().apply(p))?.let { (pu, pv) -> hypot(pu - u, pv - v) } ?: Double.POSITIVE_INFINITY
            assertSameBits("registration", old, registrationPx(p, u, v, camera, k))
            assertSameBits("depth", -camera.inverse().apply(p).z, depthM(p, camera))
            assertEquals(oldWellInside(p, camera, k), wellInside(p, camera, k))
            val r = read()
            val capture = PoseRecord(0, camera, null, Tracking.TRACKING, null, k)
            val c = checkHit(p, r, capture)
            val inCamera = camera.inverse().apply(p)
            val error = k.project(inCamera)?.let { (pu, pv) -> hypot(pu - r.centreU, pv - r.centreV) } ?: Double.POSITIVE_INFINITY
            assertSameBits("check error", error, c.errorPx)
            assertSameBits("check depth", -inCamera.z, c.depthM)
            val want = k.project(camera.inverse().apply(p))?.let { (pu, pv) -> pu / k.width to pv / k.height }
            val got = imageNormalized(p, camera, k)
            assertEquals(want?.let { bits(it.first) to bits(it.second) }, got?.let { bits(it.first) to bits(it.second) })
        }
    }

    /** wellInside before the scalar form */
    private fun oldWellInside(p: Vec3, camera: Pose, k: Intrinsics): Boolean {
        val c = camera.inverse().apply(p)
        if (-c.z > PIN_RETIRE_MAX_M) return false
        val (u, v) = k.project(c) ?: return false
        val mu = PIN_RETIRE_MARGIN * k.width
        val mv = PIN_RETIRE_MARGIN * k.height
        return u >= mu && u <= k.width - mu && v >= mv && v <= k.height - mv
    }

    @Test fun theMotionGateMeasuresAsWithVectors() {
        val motion = PinMotion()
        var prev: Pose? = null
        var prevNs = 0L
        for (i in 0 until 2_000) {
            val ts = i * 33_333_333L + (if (i % 7 == 0) 400_000_000L else 0L)
            val camera = Pose(vec(0.3), quat())
            motion.onFrame(ts, camera, true)
            val p = prev
            val dtS = (ts - prevNs) / 1e9
            if (p != null && dtS > 1e-4 && dtS < 0.5) {
                val forward = Vec3(0.0, 0.0, -1.0)
                assertSameBits("speed", (camera.t - p.t).norm() / dtS, motion.speedMps)
                val dot = (camera.rotate(forward) dot p.rotate(forward)).coerceIn(-1.0, 1.0)
                assertSameBits("rotation", Math.toDegrees(acos(dot)) / dtS, motion.rotationDps)
            }
            prev = camera
            prevNs = ts
        }
    }

    @Test fun aSightingsBoxIsAsFromTheBoxedCorners() {
        repeat(5_000) {
            val capture = PoseRecord(0, pose(), null, Tracking.TRACKING, null, k)
            val r = read(symbology = listOf("ean13", "UPC-A", "qr", null)[it % 4])
            val s = sightingOf(r, capture, vec(), Quat.IDENTITY, r.ray(capture, null), 50.0 + it % 200)
            val xs = (0 until 4).map { i -> r.corners[2 * i] }
            val ys = (0 until 4).map { i -> r.corners[2 * i + 1] }
            val w = xs.max() - xs.min()
            val h = ys.max() - ys.min()
            assertSameBits("minU", xs.min() - w * PIN_BOX_INFLATE, s.minU)
            assertSameBits("maxU", xs.max() + w * PIN_BOX_INFLATE, s.maxU)
            assertSameBits("minV", ys.min() - h * PIN_BOX_INFLATE, s.minV)
            assertSameBits("maxV", ys.max() + h * PIN_BOX_INFLATE, s.maxV)
            assertSameBits("radius", maxOf(s.maxU - s.minU, s.maxV - s.minV, 50.0 + it % 200), s.matchRadiusPx)
            assertEquals(ItemCode.key(r), s.code)
            assertSameBits("nominal", nominalWidthM(r.symbology) ?: 0.0, s.nominalM)
        }
    }

    // --- inverses, keys, ids ---

    @Test fun anInverseIsMadeOncePerPoseAndIsTheCoresInverse() {
        val inverses = Inverses()
        val a = pose()
        val b = pose()
        val c = pose()
        val ia = inverses[a]
        assertSame(ia, inverses[a])
        assertEquals(a.inverse(), ia)
        val ib = inverses[b]
        assertSame(ia, inverses[a]) // two kept
        assertEquals(c.inverse(), inverses[c]) // the oldest made goes
        assertSame(ib, inverses[b])
        assertEquals(a.inverse(), inverses[a]) // made again, the same values
    }

    @Test fun keysAndWidthsAreItemCodesAndNominalWidthsWhateverTheSymbology() {
        val keys = ReadKeys(cap = 4)
        val texts = listOf(
            "4006381333931", "04006381333931", "400638133393", "12345670", "01234565", "0123456", "ABC", "5901234123457",
            "036000291452", "99999999999999", "", "01234565", "4006381333931",
        )
        val symbologies = listOf(null, "UPC_E", "upce", "ean13", "EAN-13", "qr", "upc-e")
        repeat(3) {
            for (t in texts) for (sym in symbologies) {
                assertEquals("$t $sym", ItemCode.key(t, sym), keys.key(t, sym))
                assertEquals("$sym", nominalWidthM(sym), keys.nominalWidth(sym))
            }
        }
    }

    @Test fun anIntMapAndAnIntSetKeepUnboxedKeys() {
        val m = IntMap<String>()
        for (i in 0 until 40) m[1000 + i] = "v$i"
        assertEquals(40, m.size)
        assertEquals("v7", m[1007])
        m[1007] = "w"
        assertEquals("w", m[1007])
        assertEquals("w", m.remove(1007))
        assertNull(m[1007])
        assertNull(m.remove(1007))
        assertEquals(39, m.size)
        assertEquals((0 until 40).filter { it != 7 }.map { "v$it" }.toSet(), (0 until m.size).map { m.valueAt(it) }.toSet())
        m.clear()
        assertEquals(0, m.size)
        val s = IntSet()
        assertTrue(s.add(500))
        assertTrue(!s.add(500))
        for (i in 0 until 20) s.add(i * 300)
        assertTrue(600 in s && 500 in s && 601 !in s)
        s.clear()
        assertTrue(500 !in s && !s.isNotEmpty())
    }

    @Test fun aLineIsWhatStringFormatMakesInTheDefaultLocale() {
        val f = LineFormat()
        val before = Locale.getDefault(Locale.Category.FORMAT)
        try {
            for (l in listOf(Locale.US, Locale.GERMANY, Locale.FRANCE, Locale("ar", "EG"), Locale.US)) {
                Locale.setDefault(Locale.Category.FORMAT, l)
                for (v in listOf(0.0, -0.0, 0.0005, 1.25, 12.3456, -3.14159, 1e9, Double.NaN, Double.POSITIVE_INFINITY)) {
                    assertEquals("pin 7 created A at %.3f,%.3f,%.3f".format(v, -v, v * 3), f.format("pin 7 created A at %.3f,%.3f,%.3f", v, -v, v * 3))
                    assertEquals("median %.1f max %.1f px (%d)".format(v, v, 12), f.format("median %.1f max %.1f px (%d)", v, v, 12))
                    assertEquals("%.0f%%".format(v), f.format("%.0f%%", v))
                }
            }
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, before)
        }
    }

    // --- the outlines and the pins' colours ---

    @Test fun unlistedTracksAreAsBeforeWithKeysKeptAndNoBoxing() {
        repeat(200) { round ->
            val reads = (0 until rnd.nextInt(0, 40)).map {
                Read(
                    rnd.nextLong(0, 1_500_000_000L), listOf("4006381333931", "U1", "U2", "12345670", "04006381333931", "01234565")[rnd.nextInt(6)],
                    List(8) { rnd.nextDouble() }, rnd.nextInt(3), listOf("ean13", "upce", null)[rnd.nextInt(3)],
                )
            }.sortedBy { it.timestampNs }
            val listed = listOf(setOf("4006381333931"), emptySet(), setOf("U1", "12345670"), setOf("01234565"))[round % 4]
            val now = rnd.nextLong(0, 1_600_000_000L)
            val want = oldUnlistedTracks(reads, listed, now)
            assertEquals(want, unlistedTracks(reads, listed, now))
            assertEquals(want, unlistedTracksOf(reads, listedKeys(listed), now, readKeys = ReadKeys()))
        }
    }

    /** unlistedTracks before: every read keyed before the window's test, sorted with boxed timestamps */
    private fun oldUnlistedTracks(reads: List<Read>, listed: Set<String>, nowNs: Long, windowNs: Long = NEUTRAL_WINDOW_NS): List<List<Read>> =
        listedKeys(listed).let { keys ->
            reads.filter { ItemCode.key(it) !in keys && it.timestampNs >= nowNs - windowNs }
        }.groupBy { it.text to it.engineId }
            .map { (_, same) -> same.sortedByDescending { it.timestampNs } }

    @Test fun aPinsColourAgainstTheCountedKeysIsItsColourAgainstTheItems() {
        val codes = listOf("4006381333931", "04006381333931", "12345670", "ABC", "5901234123457", "0123456")
        repeat(500) {
            val items = codes.shuffled(rnd).take(rnd.nextInt(0, codes.size)).map { ItemCount(it, rnd.nextInt(0, 3), 3, rnd.nextBoolean()) }
            val keys = listedKeys(codes.take(rnd.nextInt(0, codes.size)).toSet())
            val counted = countedKeys(items)
            for (c in codes) assertEquals(c, pinColour(ItemCode.key(c), keys, items), pinColour(ItemCode.key(c), keys, counted))
        }
    }

    // --- the 3 s lines' numbers ---

    @Test fun histogramQuantilesInOnePassAreAsFromBinZeroAndItsFormatIsPercentZeroF() {
        repeat(300) {
            val h = Histogram(width = listOf(1.0, 10.0, 0.05)[it % 3], bins = 200)
            repeat(rnd.nextInt(1, 400)) { h.add(rnd.nextDouble() * 260 * (if (it % 3 == 2) 0.05 else 1.0)) }
            val ps = doubleArrayOf(0.5, 0.9, 0.99, 1.0, 0.1, 0.5)
            val got = h.quantiles(*ps)
            for (i in ps.indices) assertSameBits("q${ps[i]}", h.quantiles(ps[i])[0], got[i])
            for (q in got) assertEquals(if (q >= h.top) "${h.top.toLong()}+" else String.format(Locale.US, "%.0f", q), h.format(q))
        }
        val h = Histogram()
        for (q in listOf(0.0, -0.0, 0.4, 0.5, 1.0, 2.5, 3.5, 17.0, 1999.0, 1999.5)) assertEquals(String.format(Locale.US, "%.0f", q), h.format(q))
        val wide = Histogram(width = 1e12, bins = 1_000_000) // whole numbers from 1e15 on are formatted, not converted
        for (q in listOf(123456789012.0, 999999999999999.0, 1e15, 4.5e17)) assertEquals(String.format(Locale.US, "%.0f", q), wide.format(q))
    }

    @Test fun samplesSortAsTheBoxedListDid() {
        val s = Samples(4)
        val boxed = ArrayList<Double>()
        repeat(100) {
            val v = if (it % 17 == 0) -0.0 else d(50.0)
            s.add(v)
            boxed += v
        }
        val sorted = s.sorted()
        val want = boxed.sorted()
        for (i in want.indices) assertSameBits("$i", want[i], sorted[i])
    }
}
