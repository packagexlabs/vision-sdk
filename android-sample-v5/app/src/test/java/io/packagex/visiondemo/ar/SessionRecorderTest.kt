package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Read
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
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
    private val metas = CaptureMetaRing().apply { add(CaptureMeta(10 * ms, 33 * ms, 1550, 32_500_000L, 0, "[30, 30]", 2)) }

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
}
