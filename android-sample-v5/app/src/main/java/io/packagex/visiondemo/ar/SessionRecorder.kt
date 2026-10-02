package io.packagex.visiondemo.ar

import android.content.Context
import android.util.Log
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Read
import io.packagex.arcount.TrackStats
import java.io.Closeable
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics: an NDJSON trace of an AR session, for replay off the device (the spike's v2 schema). Written only
 * while Settings › Advanced › "AR traces" is on (spec 5.7: off by default). A `frame` line per ARCore frame (its
 * [PoseRecord], with the stream's intrinsics), a `read` line per decoded read (what was read where, the capture's
 * metadata, and its ray: `o` camera centre, `d` unit direction, world frame, from its frame's pose) and an `engine`
 * line per decoded image ([EngineStats]), which also go to logcat as a line every 2 s. A read whose frame has not come
 * waits up to [waitNs] of camera time ("paired": "late"), then is dropped and counted ([unpaired]). Used on the
 * mapper thread only.
 */
class SessionRecorder(
    private val out: Writer,
    private val metas: CaptureMetaRing,
    private val waitNs: Long = 100_000_000L,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : Closeable {
    private val frames = ArrayDeque<PoseRecord>()
    private val waiting = ArrayDeque<Pair<Long, List<Read>>>()
    private val window = EngineWindow()

    var unpaired = 0
        private set

    fun frame(rec: PoseRecord) {
        emit(frameLine(rec))
        frames.addLast(rec)
        while (rec.timestampNs - frames.first().timestampNs > KEEP_NS) frames.removeFirst()
        val it = waiting.iterator()
        while (it.hasNext()) {
            val (ts, reads) = it.next()
            when {
                ts == rec.timestampNs -> { reads.forEach { r -> emit(readLine(r, rec, metas.at(ts), "late")) }; it.remove() }
                rec.timestampNs - ts > waitNs -> { unpaired += reads.size; it.remove() }
            }
        }
    }

    fun reads(timestampNs: Long, reads: List<Read>) {
        if (reads.isEmpty()) return
        val rec = frames.lastOrNull { it.timestampNs == timestampNs }
        if (rec == null) waiting.addLast(timestampNs to reads) else reads.forEach { emit(readLine(it, rec, metas.at(timestampNs), "exact")) }
    }

    /**
     * What the engine took for the image at [timestampNs], which gave [reads] reads; [dropped]: reads batches the mapper
     * dropped so far; [track]: the counter's patch-tracker counters now, for the 2 s line.
     */
    fun engine(timestampNs: Long, stats: EngineStats, reads: Int, dropped: Long, track: TrackStats? = null) {
        emit(engineLine(timestampNs, stats, reads))
        window.add(timestampNs, stats, reads, dropped, track)?.let(log)
    }

    override fun close() {
        runCatching { out.flush(); out.close() }
    }

    private fun emit(line: String) {
        runCatching { out.write(line); out.write("\n") }
    }

    companion object {
        private const val TAG = "ArTrace"
        private const val KEEP_NS = 2_000_000_000L

        /** A new trace in the app's private files (`files/ar-traces/trace-<time>.ndjson`); null when it cannot be made. */
        fun open(context: Context, metas: CaptureMetaRing): SessionRecorder? = runCatching {
            val dir = File(context.filesDir, "ar-traces").apply { mkdirs() }
            val file = File(dir, "trace-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.ndjson")
            Log.i(TAG, "recording to ${file.absolutePath}")
            SessionRecorder(file.bufferedWriter(), metas)
        }.onFailure { Log.w(TAG, "no trace", it) }.getOrNull()
    }
}

internal fun frameLine(rec: PoseRecord): String {
    val i = rec.intrinsics
    return """{"t":"frame","ts":${rec.timestampNs},"cam":${poseJson(rec.camera)},"anchor":${rec.anchor?.let(::poseJson) ?: "null"},""" +
        """"tracking":"${rec.frameTracking}","anchorTracking":${rec.anchorTracking?.let { "\"$it\"" } ?: "null"},""" +
        """"intr":{"fx":${i.fx},"fy":${i.fy},"cx":${i.cx},"cy":${i.cy},"w":${i.width},"h":${i.height}},"exposureNs":${rec.exposureNs}}"""
}

internal fun readLine(r: Read, rec: PoseRecord, meta: CaptureMeta?, paired: String): String {
    val o = rec.camera.t
    val d = rec.camera.rotate(rec.intrinsics.rayInCamera(r.centreU, r.centreV))
    return """{"t":"read","ts":${r.timestampNs},"id":${r.engineId},"rawText":${quote(r.text)},"symbology":${r.symbology?.let(::quote) ?: "null"},""" +
        """"raw":${r.corners.joinToString(",", "[", "]")},"border":${r.touchesBorder},""" +
        """"exposureNs":${meta?.exposureNs ?: -1},"skewNs":${meta?.rollingShutterSkewNs ?: -1},"iso":${meta?.sensitivity ?: -1},""" +
        """"aeComp":${meta?.aeCompensation ?: 0},"fps":${quote(meta?.fpsRange ?: "")},"af":${meta?.afState ?: -1},""" +
        """"o":[${o.x},${o.y},${o.z}],"d":[${d.x},${d.y},${d.z}],"paired":"$paired"}"""
}

internal fun engineLine(timestampNs: Long, s: EngineStats, reads: Int): String =
    """{"t":"engine","ts":$timestampNs,"scanMs":${s.scanMs},"prepareMs":${s.prepareMs},"detectMs":${s.detectMs},"decodeMs":${s.decodeMs},""" +
        """"fps":${s.fps},"barcodes":${s.barcodes},"decoded":${s.decoded},"reads":$reads}"""

/** The engine's [EngineStats] over windows of 2 s of camera time, summed up as one logcat line per window. */
internal class EngineWindow(private val lengthNs: Long = 2_000_000_000L) {
    private var startNs = Long.MIN_VALUE
    private var frames = 0
    private var scanSum = 0.0
    private var scanMax = 0f
    private var prepareSum = 0.0
    private var detectSum = 0.0
    private var decodeSum = 0.0
    private var barcodes = 0
    private var decoded = 0
    private var reads = 0
    private var droppedBefore = 0L
    private var imagesDroppedBefore = 0L
    private var lastFps = 0f
    private var lastRefreshMs = -1L
    private var pipeBefore = PipeCounters()
    private var trackBefore: TrackStats? = null

    /** Adds one image; the line of the window it closes, if it closes one. */
    fun add(timestampNs: Long, s: EngineStats, reads: Int, dropped: Long, track: TrackStats? = null): String? {
        if (startNs == Long.MIN_VALUE) start(timestampNs, dropped, s.droppedImages, s.pipe, track)
        val line = if (timestampNs - startNs >= lengthNs && frames > 0) {
            (line(timestampNs, dropped, s.droppedImages, s.pipe) + trackerPart(track)).also { start(timestampNs, dropped, s.droppedImages, s.pipe, track) }
        } else {
            null
        }
        frames++
        scanSum += s.scanMs
        scanMax = maxOf(scanMax, s.scanMs)
        prepareSum += s.prepareMs
        detectSum += s.detectMs
        decodeSum += s.decodeMs
        barcodes += s.barcodes
        decoded += s.decoded
        this.reads += reads
        lastFps = s.fps
        lastRefreshMs = s.refreshAfterMs
        return line
    }

    private fun start(timestampNs: Long, dropped: Long, imagesDropped: Long, pipe: PipeCounters, track: TrackStats?) {
        trackBefore = track
        startNs = timestampNs
        frames = 0
        scanSum = 0.0
        scanMax = 0f
        prepareSum = 0.0
        detectSum = 0.0
        decodeSum = 0.0
        barcodes = 0
        decoded = 0
        reads = 0
        droppedBefore = dropped
        imagesDroppedBefore = imagesDropped
        pipeBefore = pipe
    }

    private fun line(nowNs: Long, dropped: Long, imagesDropped: Long, pipe: PipeCounters): String {
        val seconds = (nowNs - startNs) / 1e9
        val n = frames.toDouble()
        val p = pipe
        val b = pipeBefore
        val copies = p.lumaFrames - b.lumaFrames
        val scaled = copies - (p.lumaDropped - b.lumaDropped)
        return String.format(
            Locale.US,
            "engine: %d images in %.1f s (%.1f/s, engine fps %.1f), scan mean %.0f max %.0f ms, prepare %.0f, detect %.0f, decode %.0f ms, " +
                "refresh %d ms, luma %d copies mean %.2f ms on the camera thread + %.2f ms downscale, %d replaced, %d images skipped for blur, " +
                "per image %.1f boxes, %.1f shown, %.1f reads; %d images replaced by a newer one unread, %d reads batches dropped",
            frames, seconds, frames / seconds, lastFps, scanSum / n, scanMax, prepareSum / n, detectSum / n, decodeSum / n,
            lastRefreshMs, copies, meanMs(p.lumaCopyNs - b.lumaCopyNs, copies), meanMs(p.lumaScaleNs - b.lumaScaleNs, scaled),
            p.lumaDropped - b.lumaDropped, p.blurSkipped - b.blurSkipped,
            barcodes / n, decoded / n, reads / n, imagesDropped - imagesDroppedBefore, dropped - droppedBefore,
        )
    }

    /** The patch tracker over the window (spec 5.9): whether it runs on the device at all, and how its tracks end */
    private fun trackerPart(t: TrackStats?): String {
        if (t == null) return ""
        val b = trackBefore ?: TrackStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val frames = t.trackingFrames - b.trackingFrames
        val units = t.unitsTracked - b.unitsTracked
        return String.format(
            Locale.US,
            "; tracker: %d frames, %.1f units tracked per frame, %d tracked rays, %d one-dimensional, dropped %d ncc %d prediction %d neighbour %d age; " +
                "units %d with depth, %d plane prior only",
            frames, if (frames > 0) units.toDouble() / frames else 0.0, t.trackedRays - b.trackedRays, t.oneDimensional - b.oneDimensional,
            t.droppedNcc - b.droppedNcc, t.droppedPrediction - b.droppedPrediction, t.droppedNeighbour - b.droppedNeighbour, t.droppedAge - b.droppedAge,
            t.unitsWithDepth, t.unitsPriorOnly,
        )
    }

    private fun meanMs(ns: Long, count: Long) = if (count > 0) ns / 1e6 / count else 0.0
}

private fun poseJson(p: io.packagex.arcount.Pose) = "[${p.t.x},${p.t.y},${p.t.z},${p.q.x},${p.q.y},${p.q.z},${p.q.w}]"

/** Payloads are arbitrary text: escaped for JSON */
private fun quote(s: String): String {
    val sb = StringBuilder(s.length + 2).append('"')
    for (c in s) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format(Locale.US, "\\u%04x", c.code))
            else -> sb.append(c)
        }
    }
    return sb.append('"').toString()
}
