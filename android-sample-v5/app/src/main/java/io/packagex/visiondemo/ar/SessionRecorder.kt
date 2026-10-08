package io.packagex.visiondemo.ar

import android.content.Context
import android.util.Log
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Read
import io.packagex.arcount.TrackStats
import io.packagex.arcount.Vec3
import java.io.Closeable
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Diagnostics: an NDJSON trace of an AR session, for replay off the device (the spike's v2 schema). Written only
 * while Settings › Advanced › "AR traces" is on (spec 5.7: off by default). A `frame` line per ARCore frame (its
 * [PoseRecord], with the stream's intrinsics), a `read` line per decoded read (what was read where, the capture's
 * metadata, and its ray: `o` camera centre, `d` unit direction, world frame, from its frame's pose) and an `engine`
 * line per decoded image ([EngineStats]), which also go to logcat as a line every 2 s. A read whose frame has not come
 * waits up to [waitNs] of camera time ("paired": "late"), then is dropped and counted ([unpaired]). The drift plan's
 * measurement lines come from the GL and camera threads through [diag] ([ArMapper.diag]): `cam` and `flags` first,
 * then `arr`, `hit`, `pin`, `out` and `gl` ([camLine] .. [glLine]). Used on the mapper thread only.
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

    /** A line built on another thread ([ArEvent.Diag]), as it is */
    fun diag(line: String) = emit(line)

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

        /**
         * A new trace in the app's external files (`Android/data/<package>/files/ar-traces/trace-<time>.ndjson`, which
         * `adb pull` reaches on a release build too; the private files when there is no external storage), starting
         * with the [header] lines that are not null; null when it cannot be made.
         */
        fun open(context: Context, metas: CaptureMetaRing, vararg header: String?): SessionRecorder? = runCatching {
            val dir = (context.getExternalFilesDir("ar-traces") ?: File(context.filesDir, "ar-traces")).apply { mkdirs() }
            val file = File(dir, "trace-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.ndjson")
            Log.i(TAG, "recording to ${file.absolutePath}")
            SessionRecorder(file.bufferedWriter(), metas).also { r -> header.forEach { it?.let(r::diag) } }
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
        """"aeComp":${meta?.aeCompensation ?: 0},"fps":${quote(meta?.fpsRange ?: "")},"af":${meta?.afState ?: -1},"focusD":${meta?.focusDiopters ?: -1f},""" +
        """"o":[${o.x},${o.y},${o.z}],"d":[${d.x},${d.y},${d.z}],"paired":"$paired"}"""
}

internal fun engineLine(timestampNs: Long, s: EngineStats, reads: Int): String =
    """{"t":"engine","ts":$timestampNs,"scanMs":${s.scanMs},"prepareMs":${s.prepareMs},"detectMs":${s.detectMs},"decodeMs":${s.decodeMs},""" +
        """"fps":${s.fps},"barcodes":${s.barcodes},"decoded":${s.decoded},"reads":$reads}"""

/**
 * The camera at the head of a trace (plan Phase 0): its [id], the clock of its timestamps ([timestampSource], which
 * must be REALTIME for the capture-to-arrival times), LENS_DISTORTION, LENS_INTRINSIC_CALIBRATION (fx, fy, cx, cy, s
 * in pixels of the active array) and the active and pre-correction arrays (left, top, right, bottom); null: not given.
 */
internal fun camLine(id: String, timestampSource: String, distortion: FloatArray?, intrinsic: FloatArray?, active: IntArray?, preCorrection: IntArray?): String =
    """{"t":"cam","id":${quote(id)},"tsSource":${quote(timestampSource)},"distortion":${floats(distortion)},"intrinsic":${floats(intrinsic)},""" +
        """"active":${active?.joinToString(",", "[", "]") ?: "null"},"preCorrection":${preCorrection?.joinToString(",", "[", "]") ?: "null"}}"""

/**
 * One app-stream image as the camera thread took it: capture to arrival ([arrivalNs], elapsedRealtimeNanos minus its
 * timestamp), its predicted blur in stream pixels (null: none known) and whether the pre-skip kept it from the engine.
 */
internal fun arrLine(timestampNs: Long, arrivalNs: Long, blurPx: Double?, skipped: Boolean): String =
    """{"t":"arr","ts":$timestampNs,"arrNs":$arrivalNs,"blurPx":${num(blurPx)},"skipped":$skipped}"""

/**
 * One listed read at the pins (ArPins), on the frame [nowNs], [ageNs] after its capture by the clock: [outcome] is
 * "ok" (a sighting on a valid hit), "vouched" (on a plane met off its polygon, where verified pins lie on it), "width"
 * or "nearest" (a sighting with no valid hit, [HitSource]), "offView",
 * "noHit", "far" or "lowConfidence"; or, for a read the pins did not use, "notTracking", "stale" or
 * "captureNotTracking" ([Unused]), or "unmeasured" (its frame was never drawn here); the hit chosen ([kind], [distM]
 * along the ray, its [depthM] and [reprojPx] in the read's frame, the depth [confidence] where it lies; null: none, and
 * no [kind] for the nominal width's point), every tracked hit on the ray nearest first ([hits]: kind, distance, for a
 * plane whether it lies in its polygon, and its verdict under Phase 2's rules, "ok" or a [HitReject]), and [nearPx],
 * the nearest same-code pin as drawn on the read's frame (null: none, or unmeasured).
 */
internal fun hitLine(
    read: Read,
    code: String,
    nowNs: Long,
    ageNs: Long,
    outcome: String,
    kind: String?,
    distM: Double?,
    depthM: Double?,
    reprojPx: Double?,
    confidence: Int?,
    hits: List<HitSeen>,
    nearPx: Double,
): String =
    """{"t":"hit","ts":${read.timestampNs},"now":$nowNs,"ageNs":$ageNs,"text":${quote(read.text)},"code":${quote(code)},"id":${read.engineId},""" +
        """"u":${read.centreU},"v":${read.centreV},"outcome":"$outcome","kind":${kind?.let(::quote) ?: "null"},"distM":${num(distM)},""" +
        """"depthM":${num(depthM)},"reprojPx":${num(reprojPx)},"conf":${confidence ?: "null"},""" +
        """"hits":${hits.joinToString(",", "[", "]") { """[${quote(it.kind)},${num(it.distM)},${it.inPolygon ?: "null"},${it.verdict?.let(::quote) ?: "null"}]""" }},"nearPx":${num(nearPx)}}"""

/**
 * A hit on a read's ray: its trackable's [kind], [distM] from the ray's origin, for a plane whether it lies in its
 * polygon, and its [verdict] under Phase 2's rules ("ok", or a [HitReject.trace]; null: not judged)
 */
data class HitSeen(val kind: String, val distM: Double, val inPolygon: Boolean?, val verdict: String? = null)

/**
 * A pin born on the frame [nowNs] from the read captured at [captureNs], its anchor at [at] (world), with the camera at
 * [camera]; `onPlane` when most of its sightings hit a plane ([Pin.bornOnPlane]), `guessed` when most hit nothing
 * ([Pin.bornGuessed]), its label's `widthM` there, under the Android rules its `prior` ([PriorSource]) and the point its
 * birth rays gave (`est`, world; null: not refined), and the surface gate's state then (`gate`: "holding", "surface" or
 * "fallback", [BirthGate.State])
 */
internal fun pinBirthLine(nowNs: Long, captureNs: Long, pin: Pin, at: Vec3, camera: Vec3, gate: String): String =
    """{"t":"pin","ev":"birth","ts":$captureNs,"now":$nowNs,"pin":${pin.id},"code":${quote(pin.code)},"at":${vec(at)},"cam":${vec(camera)},""" +
        """"onPlane":${pin.bornOnPlane},"guessed":${pin.bornGuessed},"widthM":${num(pin.widthM)},"prior":"${pin.est.priorSource}",""" +
        """"est":${if (pin.est.started) vec(pin.position) else "null"},"verified":${pin.verified},"gate":"$gate"}"""

/**
 * A pin moved on the frame [nowNs] other than by a claim ([why]: "reinit", rule 4's restart on its bad claims;
 * "reanchor", rule 8's new anchor), to [at] (world), with its σ along the newest ray (`sigmaZ`)
 */
internal fun pinMovedLine(nowNs: Long, pin: Pin, why: String, at: Vec3): String =
    """{"t":"pin","ev":"$why","now":$nowNs,"pin":${pin.id},"code":${quote(pin.code)},"at":${vec(at)},"verified":${pin.verified},"sigmaZ":${num(pin.est.sigmaZ)}}"""

/**
 * A claim voided on the frame [nowNs] (rule 3): the read [s] captured at [captureNs] lay too far off [pin], whose last
 * good claim was at `lastGoodNs`, and went to the candidates as a likely neighbour's read; `badInRow` is the pin's run
 * of counted bad claims before it
 */
internal fun pinVoidLine(nowNs: Long, captureNs: Long, pin: Pin, s: Sighting): String =
    """{"t":"pin","ev":"void","ts":$captureNs,"now":$nowNs,"pin":${pin.id},"code":${quote(pin.code)},"u":${s.centreU},"v":${s.centreV},""" +
        """"lastGoodNs":${pin.lastGoodNs},"badInRow":${pin.badInRow}}"""

/**
 * A pin removed on the frame [nowNs] ([why]: "merge", "retire", "clear"), last at [at] (world); a retirement with its
 * batch's capture [captureNs] adds the pin's `misses` and `missMs`, the time from their run's first to that capture
 */
internal fun pinGoneLine(nowNs: Long, pin: Pin, why: String, at: Vec3, captureNs: Long = -1L): String =
    """{"t":"pin","ev":"$why","now":$nowNs,"pin":${pin.id},"code":${quote(pin.code)},"at":${vec(at)}""" +
        if (captureNs < 0) "}" else ""","ts":$captureNs,"misses":${pin.misses},"missMs":${(captureNs - pin.firstMissNs) / 1_000_000}}"""

/**
 * A claim (M1, M2): the read [s] captured at [captureNs] claimed [pin], drawn on that frame at [drawn] (null: not
 * drawn); the pin after it (`verified`, `sigmaZ` along the newest ray, `badInRow`) for M1's pin-state bins
 */
internal fun pinClaimLine(nowNs: Long, captureNs: Long, pin: Pin, s: Sighting, drawn: Vec3?, m: ClaimSample): String =
    """{"t":"pin","ev":"claim","ts":$captureNs,"now":$nowNs,"pin":${pin.id},"code":${quote(pin.code)},"u":${s.centreU},"v":${s.centreV},""" +
        """"drawn":${drawn?.let(::vec) ?: "null"},"errPx":${num(m.errPx)},"depthM":${num(m.depthM)},"travelM":${num(m.travelM)},""" +
        """"omegaDps":${num(m.omegaDps)},"row":${m.row},"repeated":${m.repeated},"gapNs":${m.gapNs},"awayNs":${m.awayNs},"reacquired":${m.reacquired},""" +
        """"verified":${pin.verified},"sigmaZ":${num(pin.est.sigmaZ)},"badInRow":${pin.badInRow}}"""

/**
 * M3: the unlisted [read] against the outline for its track on its own frame, under `rules` (and `farSafe`): from the
 * read at `drawnUV` in its own image; `shown` as [OutlineShown] ("whereRead", "carried", "mapMoved", "behind");
 * drawn at `atUV` (null: not drawn); `carried` at `depthM` (null: rotation only, or no depth)
 */
internal fun outLine(read: Read, s: OutlineSample): String =
    """{"t":"out","ts":${read.timestampNs},"text":${quote(read.text)},"id":${read.engineId},"symbology":${read.symbology?.let(::quote) ?: "null"},""" +
        """"drawnTs":${s.drawn.timestampNs},"ageNs":${s.ageNs},"errPx":${num(s.errPx)},"drawnUV":[${s.drawn.centreU},${s.drawn.centreV}],"uv":[${read.centreU},${read.centreV}],""" +
        """"rules":"${s.rules}","farSafe":${s.farSafe},"shown":"${s.shown.trace}","carried":${s.carried},"depthM":${num(s.depthM)},"atUV":[${num(s.atU)},${num(s.atV)}]}"""

/**
 * The switches of Settings › Advanced that change what a run measures, after the `cam` line at the head of a trace and
 * again whenever one changes: the unlisted outlines' [rules] and [farSafe] depth, the blur pre-skip ([blurSkip]), the
 * pins' rules ([pinRules]), whether claims refine them ([pinRefine], Phase 4) and the read-rate boost ([readBoost], P2c);
 * in a replay, the recording replayed ([replayOf], its file name)
 */
internal fun flagsLine(
    rules: OverlayRules,
    farSafe: Boolean,
    blurSkip: Boolean,
    pinRules: PinRules,
    pinRefine: Boolean,
    readBoost: Boolean,
    replayOf: String? = null,
): String =
    """{"t":"flags","overlayRules":"$rules","outlineFarSafe":$farSafe,"blurSkip":$blurSkip,"pinRules":"$pinRules","pinRefine":$pinRefine,"readBoost":$readBoost""" +
        (if (replayOf == null) "}" else ""","replayOf":"${replayOf.replace("\\", "\\\\").replace("\"", "\\\"")}"}""")

/**
 * The GL thread's window ([GlWindow]): its quantiles (p50, p99, max), the measuring's own CPU outside the frames (in
 * all, max on a frame), and every frame's thread CPU and pin work in µs, for a run's own quantiles
 */
internal fun glLine(w: GlWindow): String =
    """{"t":"gl","ts":${w.timestampNs},"seconds":${num(w.seconds)},"frames":${w.frames},"cpuMs":[${num(w.glP50Ms)},${num(w.glP99Ms)},${num(w.glMaxMs)}],""" +
        """"pinMs":[${num(w.pinP50Ms)},${num(w.pinP99Ms)},${num(w.pinMaxMs)}],"updateMs":[${num(w.updateP50Ms)},${num(w.updateP90Ms)}],"gcs":${w.gcs},""" +
        """"probes":${w.probes},"probesOk":${w.probesOk},"probeMs":${num(w.probeMeanMs)},"probe":${quote(w.probeNote)},""" +
        """"diagMs":[${num(w.diagMs)},${num(w.diagMaxMs)}],"cpuUs":${micros(w.glMs)},"pinUs":${micros(w.pinMs)}}"""

private fun micros(ms: DoubleArray) = ms.joinToString(",", "[", "]") { (it * 1000).roundToLong().toString() }

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

private fun vec(v: Vec3) = "[${v.x},${v.y},${v.z}]"

/** JSON has no NaN or infinity: null */
private fun num(d: Double?) = if (d == null || !d.isFinite()) "null" else d.toString()

private fun floats(a: FloatArray?) = a?.joinToString(",", "[", "]") ?: "null"

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
