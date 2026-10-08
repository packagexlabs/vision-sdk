package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Read
import io.packagex.arcount.TrackStats
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import io.packagex.arcount.ray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import kotlin.math.sqrt

class SessionRecorderTest {
    private val ms = 1_000_000L
    // The camera 1 m back, turned 90 degrees about +Y: its forward (-Z) looks along world -X
    private val camera = Pose(Vec3(0.0, 0.0, 1.0), Quat(0.0, sqrt(0.5), 0.0, sqrt(0.5)))
    private fun frame(ts: Long) = PoseRecord(ts, camera, null, Tracking.TRACKING, null, Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160), 33 * ms)
    private val centred = Read(10 * ms, "A\"1", listOf(1820.0, 1060.0, 2020.0, 1060.0, 2020.0, 1100.0, 1820.0, 1100.0), engineId = 7, symbology = "ean13")
    private val metas = CaptureMetaRing().apply { add(CaptureMeta(10 * ms, 33 * ms, 1550, 32_500_000L, 0, "[30, 30]", 2, focusDiopters = 3.5f)) }

    private fun lines(out: StringWriter): List<JsonObject> = out.toString().lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.num(k: String) = getValue(k).jsonPrimitive.double

    @Test fun aReadIsWrittenWithItsFramesRayAndCaptureMetadata() {
        val out = StringWriter()
        SessionRecorder(out, metas, log = {}).apply { frame(frame(10 * ms)); reads(10 * ms, listOf(centred)) }
        val (f, r) = lines(out)
        assertEquals("frame", f.str("t")); assertEquals(10 * ms, f.getValue("ts").jsonPrimitive.long)
        assertEquals(2896.0, f.getValue("intr").jsonObject.num("fx"), 1e-9); assertEquals("TRACKING", f.str("tracking"))
        assertEquals("read", r.str("t")); assertEquals("A\"1", r.str("rawText")); assertEquals("exact", r.str("paired"))
        assertEquals(7L, r.getValue("id").jsonPrimitive.long); assertEquals(33 * ms, r.getValue("exposureNs").jsonPrimitive.long)
        assertEquals(3.5, r.num("focusD"), 1e-9)
        assertEquals(listOf(0.0, 0.0, 1.0), r.getValue("o").jsonArray.map { it.jsonPrimitive.double })
        // The image centre's ray is the camera's forward: world -X
        val d = r.getValue("d").jsonArray.map { it.jsonPrimitive.double }
        assertEquals(-1.0, d[0], 1e-9); assertEquals(0.0, d[1], 1e-9); assertEquals(0.0, d[2], 1e-9)
    }

    @Test fun aReadBeforeItsFrameWaitsAtMostATenthOfASecond() {
        val out = StringWriter()
        val rec = SessionRecorder(out, metas, log = {})
        rec.reads(10 * ms, listOf(centred))
        rec.reads(20 * ms, listOf(centred.copy(timestampNs = 20 * ms)))
        rec.frame(frame(10 * ms))
        rec.frame(frame(121 * ms))   // 20 ms's frame never came
        val written = lines(out)
        assertEquals(listOf("frame", "read", "frame"), written.map { it.str("t") })
        assertEquals("late", written[1].str("paired")); assertEquals(1, rec.unpaired)
    }

    @Test fun linesFromOtherThreadsAreWrittenAsTheyCameBetweenTheFrames() {
        val out = StringWriter()
        SessionRecorder(out, metas, log = {}).apply { frame(frame(10 * ms)); diag(arrLine(43 * ms, 31 * ms, null, false)); frame(frame(43 * ms)) }
        val written = lines(out)
        assertEquals(listOf("frame", "arr", "frame"), written.map { it.str("t") })
        assertEquals(31 * ms, written[1].getValue("arrNs").jsonPrimitive.long)
        assertEquals("null", written[1].getValue("blurPx").toString())
    }

    @Test fun thePlansMeasurementLinesAreJsonAndHaveNoNaN() {
        val pin = Pin(4, "A\"1", Pose(Vec3(0.0, 0.0, -0.4), Quat.IDENTITY))
        val capture = frame(10 * ms)
        val s = sightingOf(centred, capture, Vec3(0.0, 0.0, -0.4), Quat.IDENTITY, centred.ray(capture, null), 104.0)
        val claim = ClaimSample(Double.NaN, Double.NaN, 0.03, Double.NaN, 1, true, 300 * ms, 1_200 * ms, true)
        val drawnRead = centred.copy(timestampNs = 0)
        val window = GlWindow(3_000 * ms, 3.0, 2, 3.0, 4.0, 4.0, 0.5, 0.5, 0.5, 40.0, 41.0, -1, 0, 0, Double.NaN, "none", doubleArrayOf(3.0, 4.0006), doubleArrayOf(0.5, 0.5), diagMs = 1.25, diagMaxMs = 1.0)
        val all = listOf(
            camLine("0", "REALTIME", floatArrayOf(0.01f, -0.02f, 0f, 0f, 0f), null, intArrayOf(0, 0, 4000, 3000), null),
            arrLine(10 * ms, 31 * ms, 12.5, true),
            hitLine(centred, "A\"1", 43 * ms, 33 * ms, "ok", "Plane", 0.4, 0.4, 1.5, null, listOf(HitSeen("Point", 0.2, null, "width"), HitSeen("Plane", 0.4, true, "ok")), Double.POSITIVE_INFINITY),
            pinBirthLine(43 * ms, 10 * ms, pin, pin.position, Vec3.ZERO, BirthGate.State.SURFACE.trace),
            pinGoneLine(43 * ms, pin, "merge", pin.position),
            pinClaimLine(43 * ms, 10 * ms, pin, s, null, claim),
            outLine(centred, OutlineSample(drawnRead, 10 * ms, OverlayRules.IOS, OutlineShown.WHERE_READ, 12.0)),
            glLine(window),
            outLine(centred, OutlineSample(drawnRead, 10 * ms, OverlayRules.ANDROID, OutlineShown.CARRIED, 3.0, Double.POSITIVE_INFINITY, 1925.0, 1080.0)),
            outLine(centred, OutlineSample(drawnRead, 10 * ms, OverlayRules.ANDROID, OutlineShown.CARRIED, 3.0, 0.42, 1925.0, 1080.0, farSafe = true)),
            outLine(centred, OutlineSample(drawnRead, 10 * ms, OverlayRules.ANDROID, OutlineShown.MAP_MOVED, Double.NaN, atU = Double.NaN, atV = Double.NaN)),
            flagsLine(OverlayRules.ANDROID, farSafe = false, blurSkip = true, pinRules = PinRules.IOS, pinRefine = false, readBoost = false),
            pinVoidLine(43 * ms, 10 * ms, pin, s),
        ).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("cam", "arr", "hit", "pin", "pin", "pin", "out", "gl", "out", "out", "out", "flags", "pin"), all.map { it.str("t") })
        assertEquals("void", all[12].str("ev")); assertEquals("0", all[12].str("badInRow")); assertEquals("false", all[3].str("guessed"))
        assertEquals("surface", all[3].str("gate"))
        assertEquals("false", all[6].str("carried")); assertEquals("null", all[6].getValue("depthM").toString())
        assertEquals("IOS", all[6].str("rules")); assertEquals("whereRead", all[6].str("shown")); assertEquals("false", all[6].str("farSafe"))
        assertEquals(listOf(1920.0, 1080.0), all[6].getValue("atUV").jsonArray.map { it.jsonPrimitive.double })
        assertEquals("true", all[8].str("carried")); assertEquals("null", all[8].getValue("depthM").toString())   // rotation only
        assertEquals("ANDROID", all[8].str("rules")); assertEquals("carried", all[8].str("shown"))
        assertEquals(0.42, all[9].num("depthM"), 1e-12); assertEquals("true", all[9].str("farSafe"))
        assertEquals(listOf(1925.0, 1080.0), all[9].getValue("atUV").jsonArray.map { it.jsonPrimitive.double })
        // Not drawn: no error and no place, but its arm and why
        assertEquals("mapMoved", all[10].str("shown")); assertEquals("false", all[10].str("carried"))
        assertEquals("null", all[10].getValue("errPx").toString()); assertEquals("[null,null]", all[10].getValue("atUV").toString())
        assertEquals("ANDROID", all[11].str("overlayRules")); assertEquals("false", all[11].str("outlineFarSafe")); assertEquals("true", all[11].str("blurSkip"))
        assertEquals("IOS", all[11].str("pinRules")); assertEquals("false", all[11].str("pinRefine")); assertEquals("false", all[11].str("readBoost"))
        assertEquals("REALTIME", all[0].str("tsSource")); assertEquals(5, all[0].getValue("distortion").jsonArray.size)
        val hit = all[2]
        assertEquals("ok", hit.str("outcome")); assertEquals("null", hit.getValue("nearPx").toString())
        assertEquals(listOf("Point", "Plane"), hit.getValue("hits").jsonArray.map { it.jsonArray[0].jsonPrimitive.content })
        assertEquals(listOf("width", "ok"), hit.getValue("hits").jsonArray.map { it.jsonArray[3].jsonPrimitive.content })   // Phase 2's verdicts
        assertEquals("false", all[3].str("onPlane"))
        assertEquals(listOf("birth", "merge", "claim"), all.subList(3, 6).map { it.str("ev") })
        assertFalse(all[4].containsKey("misses")) // a merge says nothing of misses; a retirement says why it went
        pin.misses = 6
        pin.firstMissNs = 8 * ms
        val retired = Json.parseToJsonElement(pinGoneLine(43 * ms, pin, "retire", pin.position, 2_018 * ms)).jsonObject
        assertEquals("6", retired.str("misses")); assertEquals("2010", retired.str("missMs")); assertEquals("${2_018 * ms}", retired.str("ts"))
        assertEquals("null", all[5].getValue("errPx").toString()); assertEquals("true", all[5].str("repeated"))
        assertEquals(1_200 * ms, all[5].getValue("awayNs").jsonPrimitive.long); assertEquals("true", all[5].str("reacquired"))
        assertEquals(12.0, all[6].num("errPx"), 1e-9); assertEquals(0L, all[6].getValue("drawnTs").jsonPrimitive.long)
        assertEquals(listOf(3.0, 4.0, 4.0), all[7].getValue("cpuMs").jsonArray.map { it.jsonPrimitive.double })
        assertEquals("null", all[7].getValue("probeMs").toString())
        assertEquals(listOf(1.25, 1.0), all[7].getValue("diagMs").jsonArray.map { it.jsonPrimitive.double })
        assertEquals(listOf(3000L, 4001L), all[7].getValue("cpuUs").jsonArray.map { it.jsonPrimitive.long })
    }

    @Test fun engineStatsAreWrittenAndSummedUpEveryTwoSeconds() {
        val out = StringWriter()
        val logged = mutableListOf<String>()
        val rec = SessionRecorder(out, metas, log = { logged += it })
        val s = EngineStats(7f, 5f, 20f, 18f, 3, 2, 45f, droppedImages = 10)
        rec.engine(0, s, reads = 1, dropped = 0)
        rec.engine(1_000 * ms, s.copy(scanMs = 120f, droppedImages = 12), reads = 2, dropped = 1)
        assertTrue(logged.isEmpty())
        rec.engine(2_000 * ms, s.copy(droppedImages = 13), reads = 0, dropped = 1)
        val e = lines(out)
        assertEquals(listOf("engine", "engine", "engine"), e.map { it.str("t") })
        assertEquals(120.0, e[1].num("scanMs"), 1e-9); assertEquals(2.0, e[1].num("reads"), 1e-9); assertEquals(3.0, e[1].num("barcodes"), 1e-9)
        assertEquals(1, logged.size)
        assertTrue(logged[0], logged[0].startsWith("engine: 2 images in 2.0 s (1.0/s, engine fps 7.0), scan mean 83 max 120 ms"))
        assertTrue(logged[0], logged[0].endsWith("; 3 images replaced by a newer one unread, 1 reads batches dropped"))
    }

    @Test fun theTwoSecondLineHasTheRefreshTheLumaCopyAndTheBlurSkips() {
        val logged = mutableListOf<String>()
        val rec = SessionRecorder(StringWriter(), metas, log = { logged += it })
        val s = EngineStats(7f, 5f, 20f, 18f, 3, 2, 45f, 0, refreshAfterMs = 300, pipe = PipeCounters(10, 10 * ms, 40 * ms, 0, 1))
        rec.engine(0, s, reads = 1, dropped = 0)
        rec.engine(2_000 * ms, s.copy(pipe = PipeCounters(70, 130 * ms, 260 * ms, 4, 9)), reads = 1, dropped = 0)
        // 60 copies in 120 ms; 56 of them downscaled in 220 ms
        assertTrue(
            logged[0],
            logged[0].contains("decode 18 ms, refresh 300 ms, luma 60 copies mean 2.00 ms on the camera thread + 3.93 ms downscale, 4 replaced, 8 images skipped for blur, per image"),
        )
    }

    @Test fun theTwoSecondLineSaysWhatThePatchTrackerDidInTheWindow() {
        val logged = mutableListOf<String>()
        val rec = SessionRecorder(StringWriter(), metas, log = { logged += it })
        val s = EngineStats(7f, 5f, 20f, 18f, 3, 2, 45f, 0)
        rec.engine(0, s, reads = 1, dropped = 0, track = TrackStats(10, 20, 5, 1, 2, 3, 4, 5, 1, 1))
        rec.engine(2_000 * ms, s, reads = 1, dropped = 0, track = TrackStats(50, 140, 45, 7, 3, 5, 4, 9, 4, 2))
        assertTrue(
            logged[0],
            logged[0].endsWith(
                "; tracker: 40 frames, 3.0 units tracked per frame, 40 tracked rays, 6 one-dimensional, dropped 1 ncc 2 prediction 0 neighbour 4 age; " +
                    "units 4 with depth, 2 plane prior only",
            ),
        )
    }
}
