package io.packagex.visiondemo.ar

import io.packagex.arcount.Blur
import io.packagex.arcount.Intrinsics
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Read
import io.packagex.arcount.Vec3
import java.util.Arrays
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.hypot

/*
 * What AR Item Count measures on the device (drift plan, Phase 0 and §5): M1 pin registration, M2 re-acquisition,
 * M3 outline prediction error, the capture-to-arrival and capture-to-update times (M5) and the GL thread's cost (M6).
 * Pure, for the JVM tests; the GL and camera threads feed it and log its lines. Nothing here changes what is drawn.
 */

/** Doubles kept unboxed in an array that grows; [quantiles] sorts a copy. */
internal class Samples(capacity: Int = 128) {
    private var values = DoubleArray(capacity)
    private var ascending = DoubleArray(0)
    var size = 0
        private set

    fun add(v: Double) {
        if (size == values.size) values = values.copyOf(maxOf(8, size * 2))
        values[size++] = v
    }

    fun clear() {
        size = 0
    }

    /** A copy of the samples, in the order they came */
    fun values(): DoubleArray = values.copyOf(size)

    /**
     * The first [size] of the array are the samples in ascending order, the order List<Double>.sorted() gives them
     * (Double.compareTo's); the array is kept, valid until the next call
     */
    fun sorted(): DoubleArray {
        if (ascending.size < size) ascending = DoubleArray(values.size)
        System.arraycopy(values, 0, ascending, 0, size)
        Arrays.sort(ascending, 0, size)
        return ascending
    }

    /** The nearest-rank quantile at each of [ps] (0..1); NaN while there is no sample */
    fun quantiles(vararg ps: Double): DoubleArray {
        val s = sorted()
        return DoubleArray(ps.size) { quantileOfSorted(s, size, ps[it]) }
    }

    /** The share (0..1) of the samples above [limit]; NaN while there is none */
    fun shareAbove(limit: Double): Double {
        if (size == 0) return Double.NaN
        var n = 0
        for (i in 0 until size) if (values[i] > limit) n++
        return n.toDouble() / size
    }
}

/**
 * Counts in [bins] bins [width] wide from 0, and one more for everything at or above [top] (+∞ too), for numbers kept
 * over a whole session: constant memory, an O(1) [add] and an O(bins) [quantiles], no sort. Made on the first sample.
 */
internal class Histogram(private val width: Double = 1.0, private val bins: Int = 2000) {
    private var counts: IntArray? = null
    var size = 0
        private set

    /** The first value of the last bin: a quantile of [top] means at least that */
    val top: Double get() = width * bins

    /** Adds [v]; NaN is not a sample */
    fun add(v: Double) {
        if (v.isNaN()) return
        val c = counts ?: IntArray(bins + 1).also { counts = it }
        c[if (v >= top) bins else (v / width).toInt().coerceAtLeast(0)]++
        size++
    }

    /**
     * The nearest-rank quantile at each of [ps] (0..1), as the lower edge of its bin; NaN while there is no sample.
     * Ascending [ps] share one pass over the bins (a rank's bin is never before a smaller rank's).
     */
    fun quantiles(vararg ps: Double): DoubleArray {
        val c = counts ?: return DoubleArray(ps.size) { Double.NaN }
        val out = DoubleArray(ps.size)
        var seen = 0
        var bin = 0
        for (i in ps.indices) {
            if (i > 0 && !(ps[i] >= ps[i - 1])) {
                seen = 0
                bin = 0
            }
            val rank = ceil(ps[i] * size).toInt().coerceIn(1, size)
            while (seen + c[bin] < rank) seen += c[bin++]
            out[i] = bin * width
        }
        return out
    }

    /**
     * A quantile as the summaries print it: whole units, "2000+" in the last bin. A whole number of them (every bin edge
     * of a whole [width]) is its digits, which is what "%.0f" prints for it, without a Formatter.
     */
    fun format(q: Double): String = when {
        q >= top -> "${top.toLong()}+"
        q >= 1.0 && q < 1e15 && q == Math.floor(q) -> q.toLong().toString()
        else -> String.format(Locale.US, "%.0f", q)
    }
}

/** The nearest-rank quantile [p] (0..1) of the first [n] of [sorted], which is ascending; NaN when [n] is 0 */
internal fun quantileOfSorted(sorted: DoubleArray, n: Int, p: Double): Double =
    if (n <= 0) Double.NaN else sorted[(ceil(p * n).toInt() - 1).coerceIn(0, n - 1)]

/** The index of the bin [x] falls in: 0 below [edges]`[0]`, then one per edge passed */
internal fun binOf(x: Double, edges: DoubleArray): Int {
    var i = 0
    while (i < edges.size && x >= edges[i]) i++
    return i
}

/** M1's travel bins: how far the camera is from where it was at the pin's birth, metres (§5) */
internal val TRAVEL_EDGES_M = doubleArrayOf(0.02, 0.05, 0.10, 0.30)
internal val TRAVEL_LABELS = listOf("0-2 cm", "2-5 cm", "5-10 cm", "10-30 cm", ">30 cm")

/** M1's rotation speed bins at the read's capture, degrees a second */
internal val OMEGA_EDGES_DPS = doubleArrayOf(14.0, 30.0)
internal val OMEGA_LABELS = listOf("<14 °/s", "14-30 °/s", ">30 °/s")

/** M1's thirds of the stream image's rows: the sensor's readout order, the rolling shutter's axis */
internal val ROW_LABELS = listOf("rows top", "rows middle", "rows bottom")

/** M3's age bins: how much older the outlined read was than the frame it was drawn on, ms */
internal val AGE_EDGES_MS = doubleArrayOf(50.0, 150.0, 300.0)
internal val AGE_LABELS = listOf("<50 ms", "50-150 ms", "150-300 ms", "300-500 ms")

/**
 * M2: a pin out of view (or not drawn) and unclaimed this long is re-acquired by its next claim. By view, not by the
 * claim gap alone: a still camera's engine re-reads a code every refreshMaxMs (1000 ms), so every steady claim of a
 * pin in plain view comes about a second after the one before.
 */
internal const val REACQUIRE_NS = 1_000_000_000L

/** M2: a re-acquired pin has settled once a claim lies within this many pixels of its read */
internal const val SETTLED_PX = 20.0

/** The ">150 px" of the plan's replay table: a read whose pin is this far off (or has none) counts as a miss */
internal const val MISS_PX = 150.0

/** Which third of the rows of an image [height] rows high row [v] lies in (0 top .. 2 bottom) */
internal fun rowBand(v: Double, height: Int): Int = (v * 3 / height).toInt().coerceIn(0, 2)

/** The camera's rotation speed from [prev] to [cur], degrees a second; NaN without a time step between them */
internal fun omegaDps(prev: PoseRecord, cur: PoseRecord): Double {
    val dtS = (cur.timestampNs - prev.timestampNs) / 1e9
    return if (dtS > 0) Math.toDegrees(Blur.rotationRate(prev.camera, cur.camera, dtS)) else Double.NaN
}

/** How far [point] (world) lands from pixel ([u], [v]) of the image [camera] and [intrinsics] are of; +∞ behind it */
internal fun registrationPx(point: Vec3, u: Double, v: Double, camera: Pose, intrinsics: Intrinsics): Double =
    registrationPx(point.x, point.y, point.z, u, v, camera.inverse(), intrinsics)

/** [registrationPx] of the world point (x, y, z), [toCamera] the camera's inverse */
internal fun registrationPx(x: Double, y: Double, z: Double, u: Double, v: Double, toCamera: Pose, intrinsics: Intrinsics): Double =
    applied(toCamera, x, y, z) { cx, cy, cz ->
        projected(intrinsics, cx, cy, cz, { Double.POSITIVE_INFINITY }) { pu, pv -> hypot(pu - u, pv - v) }
    }

/** How far in front of [camera] [point] (world) lies, metres; negative behind it */
internal fun depthM(point: Vec3, camera: Pose): Double = depthM(point.x, point.y, point.z, camera.inverse())

/** [depthM] of the world point (x, y, z), [toCamera] the camera's inverse */
internal fun depthM(x: Double, y: Double, z: Double, toCamera: Pose): Double = -applied(toCamera, x, y, z) { _, _, cz -> cz }

/**
 * Where each pin was drawn on each of the newest [frames] drawn frames, by the frame's timestamp: M1 compares a read
 * with its pin as drawn on the read's own frame (out of sample: the read came after). No allocation once the arrays
 * have grown to the pin count.
 */
internal class DrawnPins(private val frames: Int = 32) {
    private val stamps = LongArray(frames) { Long.MIN_VALUE }
    private val counts = IntArray(frames)
    private val ids = Array(frames) { IntArray(16) }
    private val points = Array(frames) { DoubleArray(48) }
    private var cur = -1

    /** The frame at [timestampNs] is drawn now; drawing the same frame again starts it over */
    fun begin(timestampNs: Long) {
        if (cur < 0 || stamps[cur] != timestampNs) {
            cur = (cur + 1) % frames
            stamps[cur] = timestampNs
        }
        counts[cur] = 0
    }

    fun add(pinId: Int, x: Double, y: Double, z: Double) {
        if (cur < 0) return
        val n = counts[cur]
        if (n == ids[cur].size) {
            ids[cur] = ids[cur].copyOf(n * 2)
            points[cur] = points[cur].copyOf(n * 6)
        }
        ids[cur][n] = pinId
        points[cur][3 * n] = x
        points[cur][3 * n + 1] = y
        points[cur][3 * n + 2] = z
        counts[cur] = n + 1
    }

    /** Whether the frame at [timestampNs] is one of the newest drawn (with or without pins) */
    fun has(timestampNs: Long): Boolean {
        for (f in 0 until frames) if (stamps[f] == timestampNs) return true
        return false
    }

    /** Pin [pinId]'s world point as drawn on the frame at [timestampNs]; null when that frame or that pin was not drawn */
    fun at(timestampNs: Long, pinId: Int): Vec3? {
        val p = DoubleArray(3)
        return if (pointInto(timestampNs, pinId, p)) Vec3(p[0], p[1], p[2]) else null
    }

    /** [at], into [out] (3); false when that frame or that pin was not drawn */
    fun pointInto(timestampNs: Long, pinId: Int, out: DoubleArray): Boolean {
        for (f in 0 until frames) {
            if (stamps[f] != timestampNs) continue
            for (i in 0 until counts[f]) {
                if (ids[f][i] == pinId) {
                    out[0] = points[f][3 * i]
                    out[1] = points[f][3 * i + 1]
                    out[2] = points[f][3 * i + 2]
                    return true
                }
            }
            return false
        }
        return false
    }
}

/** How an unlisted outline came out on a frame (M3) */
enum class OutlineShown(val trace: String) {
    /** Drawn where its read was decoded: the outline before Phase 1, and [OverlayRules.ANDROID]'s fallback */
    WHERE_READ("whereRead"),

    /** Drawn carried to the frame ([transfer]) */
    CARRIED("carried"),

    /** Not drawn: the map moved since its read ([chooseOutline]) */
    MAP_MOVED("mapMoved"),

    /** Not drawn: a corner fell behind the camera ([transfer]) */
    BEHIND("behind"),
    ;

    val drawn: Boolean get() = this == WHERE_READ || this == CARRIED
}

/**
 * M3 for one read: the outline for its track on its frame came from [drawn], [ageNs] older, under [rules] (and
 * [farSafe]), and came out as [shown]. Drawn, its centre was at ([atU], [atV]), stream pixels of that frame, [errPx]
 * off; not drawn, the three are NaN. [depthM]: the camera depth its corners were carried at (+∞: rotation only); NaN
 * when drawn where [drawn] was read, or dropped before a depth was taken.
 */
data class OutlineSample(
    val drawn: Read,
    val ageNs: Long,
    val rules: OverlayRules,
    val shown: OutlineShown,
    val errPx: Double,
    val depthM: Double = Double.NaN,
    val atU: Double = drawn.centreU,
    val atV: Double = drawn.centreV,
    val farSafe: Boolean = false,
) {
    val carried: Boolean get() = shown == OutlineShown.CARRIED
}

/**
 * Which unlisted outlines each of the newest [frames] drawn frames had, by the frame's timestamp, and the rules it was
 * drawn under: per outline the read it came from, how it came out, the centre drawn, in stream pixels of that frame,
 * and the depth it was carried at (NaN: none). An outline not drawn is kept too, so M3 sees what each arm left out.
 * No allocation once the arrays have grown.
 */
internal class DrawnOutlines(private val frames: Int = 32) {
    private val stamps = LongArray(frames) { Long.MIN_VALUE }
    private val rules = arrayOfNulls<OverlayRules>(frames)
    private val farSafe = BooleanArray(frames)
    private val counts = IntArray(frames)
    private val reads = Array(frames) { arrayOfNulls<Read>(8) }
    private val shown = Array(frames) { arrayOfNulls<OutlineShown>(8) }
    private val centres = Array(frames) { DoubleArray(16) }
    private val depths = Array(frames) { DoubleArray(8) }
    private var cur = -1

    /** The frame at [timestampNs] is drawn now, under [rules] (and [farSafe]); drawing the same frame again starts it over */
    fun begin(timestampNs: Long, rules: OverlayRules, farSafe: Boolean = false) {
        if (cur < 0 || stamps[cur] != timestampNs) {
            cur = (cur + 1) % frames
            stamps[cur] = timestampNs
        }
        this.rules[cur] = rules
        this.farSafe[cur] = farSafe
        counts[cur] = 0
    }

    /** [read]'s outline came out as [shown], its centre drawn at ([u], [v]) (NaN: not drawn); [depthM] as in [OutlineSample] */
    fun add(read: Read, shown: OutlineShown, u: Double = Double.NaN, v: Double = Double.NaN, depthM: Double = Double.NaN) {
        if (cur < 0) return
        val n = counts[cur]
        if (n == reads[cur].size) {
            reads[cur] = reads[cur].copyOf(n * 2)
            this.shown[cur] = this.shown[cur].copyOf(n * 2)
            centres[cur] = centres[cur].copyOf(n * 4)
            depths[cur] = depths[cur].copyOf(n * 2)
        }
        reads[cur][n] = read
        this.shown[cur][n] = shown
        centres[cur][2 * n] = u
        centres[cur][2 * n + 1] = v
        depths[cur][n] = depthM
        counts[cur] = n + 1
    }

    /**
     * M3 for [read]: the outline for its track (its text and engine id) on its own frame against where it was read;
     * null when that frame is gone or had none for the track.
     */
    fun sample(read: Read): OutlineSample? {
        for (f in 0 until frames) {
            if (stamps[f] != read.timestampNs) continue
            for (i in 0 until counts[f]) {
                val d = reads[f][i] ?: continue
                if (d.engineId != read.engineId || d.text != read.text || d.timestampNs >= read.timestampNs) continue
                val u = centres[f][2 * i]
                val v = centres[f][2 * i + 1]
                return OutlineSample(
                    d, read.timestampNs - d.timestampNs, rules[f] ?: OverlayRules.IOS, shown[f][i] ?: OutlineShown.WHERE_READ,
                    hypot(u - read.centreU, v - read.centreV), depths[f][i], u, v, farSafe[f],
                )
            }
            return null
        }
        return null
    }
}

/**
 * M3 over the session, by symbology, arm and age (§5): [OverlayRules.IOS]'s outlines under the Phase-0 labels
 * ("ean13"), [OverlayRules.ANDROID]'s carried ("ean13 carried", "carried far-safe" with the flag) apart from its
 * fallback where read ("ean13 fallback"). Median/p90 of the drawn outlines' centre error, stream pixels to the pixel
 * below ([Histogram]: the 3 s summary costs the same at minute 10 as at minute 1), and how many were not drawn (the map
 * moved, or behind the camera), which have no error but are the arm's hard cases.
 */
internal class OutlineErrors {
    private class Bins {
        val errors = Array(AGE_LABELS.size) { Histogram() }
        val notDrawn = IntArray(AGE_LABELS.size)
    }

    private val byKey = LinkedHashMap<String, Bins>()

    fun add(symbology: String?, s: OutlineSample) {
        val name = symbology ?: "?"
        val key = when {
            s.rules == OverlayRules.IOS -> name
            s.shown == OutlineShown.WHERE_READ -> "$name fallback"
            s.farSafe -> "$name carried far-safe"
            else -> "$name carried"
        }
        val bins = byKey.getOrPut(key) { Bins() }
        val age = binOf(s.ageNs / 1e6, AGE_EDGES_MS)
        if (s.shown.drawn) bins.errors[age].add(s.errPx) else bins.notDrawn[age]++
    }

    /** One line, or null before the first sample */
    fun summary(): String? {
        if (byKey.isEmpty()) return null
        val parts = ArrayList<String>()
        for ((key, bins) in byKey) {
            for (i in AGE_LABELS.indices) {
                val b = bins.errors[i]
                val notDrawn = bins.notDrawn[i]
                if (b.size == 0 && notDrawn == 0) continue
                val errs = if (b.size == 0) "-" else b.quantiles(0.5, 0.9).let { q -> "${b.format(q[0])}/${b.format(q[1])}" }
                parts += "$key ${AGE_LABELS[i]} ${b.size}: $errs" + if (notDrawn > 0) " ($notDrawn not drawn)" else ""
            }
        }
        return "M3 outline centre error since start, 4K px median/p90: " + parts.joinToString(", ")
    }
}

/** Why the pins left a listed read unused; it is measured against the pins as drawn on its frame all the same */
internal enum class Unused(val trace: String, val label: String) {
    /** The frame it would be used on does not track */
    NOT_TRACKING("notTracking", "not tracking"),

    /** Older than the pins take */
    STALE("stale", "stale"),

    /** Its own frame did not track */
    CAPTURE_NOT_TRACKING("captureNotTracking", "their frame not tracking"),
}

/**
 * One claim's M1 sample: [errPx] from the read's centre to its pin as drawn on the read's frame, stream pixels (NaN:
 * the pin was not drawn there), at [depthM] in that frame; [travelM] the camera's distance from where it stood at the
 * pin's birth, [omegaDps] its rotation speed then (NaN: unknown), [row] the image third of the read ([rowBand]),
 * [repeated] whether its code was ever read twice in one image; [gapNs] the capture time since the pin's previous
 * claim (or birth), [awayNs] the longest the pin was out of view and unclaimed in that time, and [reacquired] whether
 * that was [REACQUIRE_NS] or more (M2).
 */
data class ClaimSample(
    val errPx: Double,
    val depthM: Double,
    val travelM: Double,
    val omegaDps: Double,
    val row: Int,
    val repeated: Boolean,
    val gapNs: Long,
    val awayNs: Long,
    val reacquired: Boolean,
)

/**
 * M1 and M2 over the session (§5), on the GL thread. M1: every claim's error, overall and by travel, rotation speed,
 * row third and unique vs repeated code; besides, every listed read against its nearest same-code pin as drawn on its
 * frame (the replay table's measure: none, or more than [MISS_PX], is a miss), the reads the pins did not use
 * included (a pin hidden then is a miss, §5 M4). M2: the first claim after its pin was out of view and unclaimed for
 * [REACQUIRE_NS], its error, and how long until a claim lies within [SETTLED_PX]. Errors are kept in [Histogram]s, so
 * memory and the summary's cost stay flat over a long session.
 */
internal class PinMetrics {
    private class Track(val bornAt: Vec3, var lastClaimNs: Long) {
        /** The newest frame the pin was drawn in view on (born in view: its read was) */
        var inViewNs = lastClaimNs

        /** The longest stretch out of view and unclaimed since the last claim, as the frames drawn since show it */
        var awayNs = 0L

        /** Capture time of the re-acquisition not yet settled, -1 when none */
        var settlingSinceNs = -1L
    }

    /** By pin id, unboxed: [inView] runs every frame for every pin, and ids grow past the Integer cache */
    private val tracks = IntMap<Track>()
    private val repeatedCodes = HashSet<String>()
    private val all = Histogram()
    private var claimsMissed = 0
    private val byTravel = Array(TRAVEL_LABELS.size) { Histogram() }
    private val byOmega = Array(OMEGA_LABELS.size) { Histogram() }
    private val byRow = Array(ROW_LABELS.size) { Histogram() }
    private val unique = Histogram()
    private val repeated = Histogram()
    private var notDrawn = 0
    private var listedReads = 0
    private var listedMissed = 0
    private val unused = IntArray(Unused.entries.size)
    private var unmeasured = 0
    private val reacquiredPx = Histogram()
    private var reacquiredNotDrawn = 0
    private val settledMs = Histogram(width = 10.0, bins = 1000)
    private var neverSettled = 0

    // The last claim's sample ([lastClaim]), kept unboxed: only the trace makes it
    private var lastErrPx = Double.NaN
    private var lastDepthM = Double.NaN
    private var lastTravelM = Double.NaN
    private var lastOmegaDps = Double.NaN
    private var lastRow = 0
    private var lastRepeated = false
    private var lastGapNs = 0L
    private var lastAwayNs = 0L
    private var lastReacquired = false

    /** The codes (keys) of one image's listed reads: one read twice marks its code repeated for good */
    fun batch(codes: List<String>) {
        if (codes.size < 2) return
        for (i in 1 until codes.size) {
            val c = codes[i]
            for (j in 0 until i) {
                if (codes[j] == c) {
                    repeatedCodes += c
                    break
                }
            }
        }
    }

    fun isRepeated(code: String) = code in repeatedCodes

    /** Pin [pinId] was born from the image captured at [captureNs] by a camera at [camera] (world) */
    fun born(pinId: Int, captureNs: Long, camera: Vec3) {
        tracks[pinId] = Track(camera, captureNs)
    }

    /**
     * Pin [pinId] was drawn in view on the frame at [timestampNs] (every drawn frame): the time since it was last in
     * view or claimed, whichever came later, it was away.
     */
    fun inView(pinId: Int, timestampNs: Long) {
        val t = tracks[pinId] ?: return
        val away = timestampNs - maxOf(t.inViewNs, t.lastClaimNs)
        if (away > t.awayNs) t.awayNs = away
        if (timestampNs > t.inViewNs) t.inViewNs = timestampNs
    }

    /** Pin [pinId] is gone (merged, cleared): a re-acquisition it had not settled never will */
    fun removed(pinId: Int) {
        if ((tracks.remove(pinId)?.settlingSinceNs ?: -1L) >= 0) neverSettled++
    }

    /** A listed read the pins used, and its nearest same-code pin as drawn on its frame, [errPx] off (+∞: none) */
    fun nearest(errPx: Double) {
        listedReads++
        if (!(errPx <= MISS_PX)) listedMissed++
    }

    /** A listed read the pins did not use ([why]), measured as [nearest] all the same */
    fun unused(errPx: Double, why: Unused) {
        nearest(errPx)
        unused[why.ordinal]++
    }

    /** A listed read whose frame was never drawn here: no pin to measure it against, so not in the share */
    fun unmeasured() {
        unmeasured++
    }

    /**
     * A read of [code] captured at [captureNs] by a camera at [camera] (world) claimed pin [pinId], whose drawn point
     * on that frame was [errPx] off at [depthM] ([registrationPx], [depthM]); [omegaDps] and [row] as in [ClaimSample].
     */
    fun claim(pinId: Int, code: String, captureNs: Long, camera: Vec3, errPx: Double, depthM: Double, omegaDps: Double, row: Int): ClaimSample {
        claimed(pinId, code, captureNs, camera, errPx, depthM, omegaDps, row)
        return lastClaim()
    }

    /** The sample of the last [claimed] (the trace's; the metrics need none) */
    fun lastClaim() = ClaimSample(lastErrPx, lastDepthM, lastTravelM, lastOmegaDps, lastRow, lastRepeated, lastGapNs, lastAwayNs, lastReacquired)

    /** [claim], its sample kept for [lastClaim] rather than made */
    fun claimed(pinId: Int, code: String, captureNs: Long, camera: Vec3, errPx: Double, depthM: Double, omegaDps: Double, row: Int) {
        val t = tracks[pinId] ?: Track(camera, captureNs).also { tracks[pinId] = it }
        val gapNs = captureNs - t.lastClaimNs
        // Not in view since before its capture: that stretch counts as far as the capture
        val awayNs = if (t.inViewNs < captureNs) maxOf(t.awayNs, captureNs - maxOf(t.inViewNs, t.lastClaimNs)) else t.awayNs
        t.awayNs = 0L
        val reacquired = awayNs >= REACQUIRE_NS
        val drawn = !errPx.isNaN()
        if (reacquired) {
            if (t.settlingSinceNs >= 0) neverSettled++
            t.settlingSinceNs = -1L
            if (drawn) {
                reacquiredPx.add(errPx)
                if (errPx < SETTLED_PX) settledMs.add(0.0) else t.settlingSinceNs = captureNs
            } else {
                reacquiredNotDrawn++
            }
        } else if (t.settlingSinceNs >= 0 && drawn && errPx < SETTLED_PX) {
            settledMs.add((captureNs - t.settlingSinceNs) / 1e6)
            t.settlingSinceNs = -1L
        }
        if (captureNs > t.lastClaimNs) t.lastClaimNs = captureNs
        val travelM = norm(camera.x - t.bornAt.x, camera.y - t.bornAt.y, camera.z - t.bornAt.z)
        val repeat = code in repeatedCodes
        if (drawn) {
            all.add(errPx)
            if (errPx > MISS_PX) claimsMissed++
            byTravel[binOf(travelM, TRAVEL_EDGES_M)].add(errPx)
            if (!omegaDps.isNaN()) byOmega[binOf(omegaDps, OMEGA_EDGES_DPS)].add(errPx)
            byRow[row].add(errPx)
            (if (repeat) repeated else unique).add(errPx)
        } else {
            notDrawn++
        }
        lastErrPx = errPx
        lastDepthM = depthM
        lastTravelM = travelM
        lastOmegaDps = omegaDps
        lastRow = row
        lastRepeated = repeat
        lastGapNs = gapNs
        lastAwayNs = awayNs
        lastReacquired = reacquired
    }

    /** New Scan: every pin goes, the session's numbers stay */
    fun clearPins() {
        while (tracks.size > 0) removed(tracks.keyAt(tracks.size - 1))
    }

    /** M1 since the start: the median and p90 of the claims' registration error in 4K px, and how many claims */
    fun m1(): DoubleArray = all.quantiles(0.5, 0.9).let { doubleArrayOf(it[0], it[1], all.size.toDouble()) }

    /** M1 and M2 so far, one line; null before any listed read */
    fun summary(): String? {
        if (listedReads == 0 && unmeasured == 0 && all.size == 0 && notDrawn == 0) return null
        val sb = StringBuilder("M1 since start, 4K px median/p90: claims ")
        sb.append(part(all)).append(", >").append(MISS_PX.toInt()).append(" px ")
            .append(pct(if (all.size == 0) Double.NaN else claimsMissed.toDouble() / all.size))
        sb.append(", ").append(notDrawn).append(" not drawn on their frame")
        fun group(labels: List<String>, bins: Array<Histogram>) {
            for (i in bins.indices) if (bins[i].size > 0) sb.append("; ").append(labels[i]).append(' ').append(part(bins[i]))
        }
        group(TRAVEL_LABELS, byTravel)
        group(OMEGA_LABELS, byOmega)
        group(ROW_LABELS, byRow)
        if (unique.size > 0) sb.append("; unique ").append(part(unique))
        if (repeated.size > 0) sb.append("; repeated ").append(part(repeated))
        sb.append(". Listed reads ").append(listedReads).append(", no pin within ").append(MISS_PX.toInt()).append(" px ")
            .append(pct(if (listedReads == 0) Double.NaN else listedMissed.toDouble() / listedReads))
        sb.append(", of them not used by the pins")
        for (why in Unused.entries) sb.append(if (why.ordinal == 0) " " else ", ").append(unused[why.ordinal]).append(' ').append(why.label)
        sb.append("; ").append(unmeasured).append(" unmeasured (their frame never drawn)")
        var settling = 0
        for (i in 0 until tracks.size) if (tracks.valueAt(i).settlingSinceNs >= 0) settling++
        sb.append(". M2 re-acquisitions ").append(part(reacquiredPx)).append(", ").append(reacquiredNotDrawn).append(" not drawn, under ")
            .append(SETTLED_PX.toInt()).append(" px after (ms) ").append(part(settledMs)).append(", ").append(neverSettled)
            .append(" never, ").append(settling).append(" settling")
        return sb.toString()
    }

    private fun part(h: Histogram): String {
        if (h.size == 0) return "0"
        val q = h.quantiles(0.5, 0.9)
        return "${h.size} ${h.format(q[0])}/${h.format(q[1])}"
    }

    private fun pct(share: Double) = if (share.isNaN()) "n/a" else String.format(Locale.US, "%.1f%%", share * 100)
}

/**
 * The GL thread's numbers over one window of frames (M5, M6): thread CPU per new frame after `update()` ([glP50Ms] ..,
 * and every frame's in [glMs]), the pins' share of it ([pinP50Ms] .., [pinMs]), capture to `update()` ([updateP50Ms],
 * [updateP90Ms]), the runtime's GCs in the window (-1: unknown), the CPU-image probes ([probesOk] of [probes],
 * [probeMeanMs], [probeNote] the last one's size or failure), and the measuring's own GL thread CPU outside the
 * frames' (the probes and the 3 s lines: [diagMs] in all, [diagMaxMs] on one frame). [glMs] and [pinMs] are kept only
 * for an open trace (empty otherwise). Besides ([sessionLine]): every frame since the start pooled at 0.05 ms, so a p99
 * is one over many windows (a window's ~90 frames make its nearest-rank p99 its max), and the window's slowest pin frame
 * ([slowPinMs], its thread CPU [slowGlMs]) with what the pins did on it ([slow]).
 */
class GlWindow(
    val timestampNs: Long,
    val seconds: Double,
    val frames: Int,
    val glP50Ms: Double,
    val glP99Ms: Double,
    val glMaxMs: Double,
    val pinP50Ms: Double,
    val pinP99Ms: Double,
    val pinMaxMs: Double,
    val updateP50Ms: Double,
    val updateP90Ms: Double,
    val gcs: Long,
    val probes: Int,
    val probesOk: Int,
    val probeMeanMs: Double,
    val probeNote: String,
    val glMs: DoubleArray = DoubleArray(0),
    val pinMs: DoubleArray = DoubleArray(0),
    val diagMs: Double = 0.0,
    val diagMaxMs: Double = 0.0,
    val sessionFrames: Int = 0,
    val sessionGlP50Ms: Double = Double.NaN,
    val sessionGlP99Ms: Double = Double.NaN,
    val sessionPinP50Ms: Double = Double.NaN,
    val sessionPinP99Ms: Double = Double.NaN,
    val slowPinMs: Double = Double.NaN,
    val slowGlMs: Double = Double.NaN,
    val slow: PinWork? = null,
) {
    /**
     * M6 pooled since the start, and the window's slowest pin frame: its batches placed and dropped, listed reads
     * sighted, ARCore hit tests and their hits, pin events, and the thread CPU of each step
     */
    fun sessionLine(): String {
        val sb = StringBuilder(320)
        sb.append(
            String.format(
                Locale.US,
                "GL since start: %d frames, thread CPU after update() p50 %.2f p99 %.2f ms, pins p50 %.2f p99 %.2f ms (0.05 ms bins); " +
                    "this window's slowest pin frame %.2f ms (thread CPU %.2f ms)",
                sessionFrames, sessionGlP50Ms, sessionGlP99Ms, sessionPinP50Ms, sessionPinP99Ms, slowPinMs, slowGlMs,
            ),
        )
        slow?.describe(sb)
        return sb.toString()
    }

    fun logLine(): String = String.format(
        Locale.US,
        "GL: %d frames in %.1f s, thread CPU after update() p50 %.2f p99 %.2f max %.2f ms, pins p50 %.2f p99 %.2f max %.2f ms, " +
            "capture to update() p50 %.0f p90 %.0f ms, %s GCs; CPU image probe %d/%d ok, mean %.2f ms (%s); " +
            "measuring outside the frames %.2f ms, max %.2f ms on a frame",
        frames, seconds, glP50Ms, glP99Ms, glMaxMs, pinP50Ms, pinP99Ms, pinMaxMs, updateP50Ms, updateP90Ms,
        if (gcs < 0) "?" else gcs.toString(), probesOk, probes, probeMeanMs, probeNote, diagMs, diagMaxMs,
    )
}

/**
 * The GL thread's cost over windows of [lengthNs] of frame time (M6), and capture to `update()` (M5); and once every
 * [probeEveryNs] whether ARCore's CPU image can be had under SHARED_CAMERA, and how fast (plan Phase 0); and what the
 * measuring itself costs the GL thread after each frame ([diag]). A pause longer than a window starts a new one. The
 * per-frame calls do not allocate.
 */
internal class FrameCosts(private val lengthNs: Long = 3_000_000_000L, private val probeEveryNs: Long = 1_000_000_000L) {
    private val gl = Samples()
    private val pins = Samples()
    private val update = Samples()
    private var startNs = Long.MIN_VALUE
    private var lastNs = Long.MIN_VALUE
    private var gcBefore = -1L
    private var lastProbeNs = Long.MIN_VALUE
    private var probes = 0
    private var probesOk = 0
    private var probeSumNs = 0L

    // The last probe's note: given as text, or (null) made from its image's size or what it threw when a window closes
    private var probeNote: String? = "none"
    private var probeWidth = 0
    private var probeHeight = 0
    private var probeFormat = 0
    private var probeError: Class<*>? = null
    private var diagSumNs = 0L
    private var diagMaxNs = 0L

    // Every frame since the start, at 0.05 ms; the window's slowest pin frame and what the pins did on it
    private val glSession = Histogram(width = 0.05, bins = 1000)
    private val pinSession = Histogram(width = 0.05, bins = 1000)
    private var slowPinNs = -1L
    private var slowGlNs = 0L
    private var slowKnown = false
    private val slow = PinWork()

    /**
     * A new frame at [timestampNs]: [glNs] of GL thread CPU after `update()`, [pinNs] of it the pins', [updateNs] from
     * capture to `update()`; [work], what the pins did on it (kept for the window's slowest pin frame)
     */
    fun frame(timestampNs: Long, glNs: Long, pinNs: Long, updateNs: Long, work: PinWork? = null) {
        if (startNs == Long.MIN_VALUE || timestampNs - lastNs > lengthNs) restart(timestampNs, gcCount = -1L)
        lastNs = timestampNs
        gl.add(glNs / 1e6)
        pins.add(pinNs / 1e6)
        update.add(updateNs / 1e6)
        glSession.add(glNs / 1e6)
        pinSession.add(pinNs / 1e6)
        if (pinNs > slowPinNs) {
            slowPinNs = pinNs
            slowGlNs = glNs
            slowKnown = work != null
            if (work != null) slow.copyFrom(work)
        }
    }

    fun probeDue(timestampNs: Long): Boolean = lastProbeNs == Long.MIN_VALUE || timestampNs - lastProbeNs >= probeEveryNs

    /** The CPU-image probe at [timestampNs]: [ok] or not, after [ns]; [note] its size, or what it threw */
    fun probe(timestampNs: Long, ok: Boolean, ns: Long, note: String) {
        probed(timestampNs, ok, ns)
        probeNote = note
    }

    /** [probe] that got a [width]x[height] image of [format]: its note ("1280x720 format 35") made only when a window closes */
    fun probeImage(timestampNs: Long, ns: Long, width: Int, height: Int, format: Int) {
        probed(timestampNs, true, ns)
        probeNote = null
        probeWidth = width
        probeHeight = height
        probeFormat = format
        probeError = null
    }

    /** [probe] that threw [error]: its note (the exception's simple name) made only when a window closes */
    fun probeFailed(timestampNs: Long, ns: Long, error: Throwable) {
        probed(timestampNs, false, ns)
        probeNote = null
        probeError = error.javaClass
    }

    private fun probed(timestampNs: Long, ok: Boolean, ns: Long) {
        lastProbeNs = timestampNs
        probes++
        if (ok) probesOk++
        probeSumNs += ns
    }

    private fun note(): String = probeNote ?: probeError?.simpleName ?: "${probeWidth}x$probeHeight format $probeFormat"

    /** [ns] of GL thread CPU spent measuring after the frame just added (probe, log lines), outside its [frame] cost */
    fun diag(ns: Long) {
        diagSumNs += ns
        if (ns > diagMaxNs) diagMaxNs = ns
    }

    /** The window has run [lengthNs] */
    fun due(timestampNs: Long): Boolean = startNs != Long.MIN_VALUE && timestampNs - startNs >= lengthNs

    /**
     * Closes the window at [timestampNs]; [gcCount] is the runtime's GC count now (-1: unknown), the first window's GCs
     * unknown. Every frame's numbers ([GlWindow.glMs], [GlWindow.pinMs]) are copied only with [frames] (an open trace).
     */
    fun close(timestampNs: Long, gcCount: Long, frames: Boolean = true): GlWindow {
        val g = gl.quantiles(0.5, 0.99, 1.0)
        val p = pins.quantiles(0.5, 0.99, 1.0)
        val u = update.quantiles(0.5, 0.9)
        val sg = glSession.quantiles(0.5, 0.99)
        val sp = pinSession.quantiles(0.5, 0.99)
        val w = GlWindow(
            timestampNs, (timestampNs - startNs) / 1e9, gl.size, g[0], g[1], g[2], p[0], p[1], p[2], u[0], u[1],
            if (gcBefore >= 0 && gcCount >= 0) gcCount - gcBefore else -1L,
            probes, probesOk, if (probes > 0) probeSumNs / 1e6 / probes else Double.NaN, note(),
            if (frames) gl.values() else NO_FRAMES, if (frames) pins.values() else NO_FRAMES,
            diagSumNs / 1e6, diagMaxNs / 1e6,
            glSession.size, sg[0], sg[1], sp[0], sp[1],
            if (slowPinNs >= 0) slowPinNs / 1e6 else Double.NaN, if (slowPinNs >= 0) slowGlNs / 1e6 else Double.NaN,
            if (slowKnown) PinWork().also { it.copyFrom(slow) } else null,
        )
        restart(timestampNs, gcCount)
        return w
    }

    private fun restart(timestampNs: Long, gcCount: Long) {
        startNs = timestampNs
        gcBefore = gcCount
        gl.clear()
        pins.clear()
        update.clear()
        probes = 0
        probesOk = 0
        probeSumNs = 0L
        diagSumNs = 0L
        diagMaxNs = 0L
        slowPinNs = -1L
        slowGlNs = 0L
        slowKnown = false
    }

    private companion object {
        val NO_FRAMES = DoubleArray(0)
    }
}

/**
 * What the pins did on one frame (M6), so the window's slowest pin frame can be pinned on a cause: the read batches
 * placed and dropped, the listed reads sighted, ARCore's hit tests and the hits they gave, the pin events, and the thread
 * CPU of each step (ns). Counters only: filled every frame for nothing, and read once a window.
 */
class PinWork {
    var batches = 0
    var dropped = 0
    var reads = 0
    var hitTests = 0
    var hits = 0
    var births = 0
    var reanchors = 0
    var removals = 0
    var reinits = 0
    var voids = 0
    var refreshNs = 0L
    var hitTestNs = 0L
    var depthNs = 0L
    var placeNs = 0L
    var anchorNs = 0L
    var anchorMaxNs = 0L
    var boostNs = 0L
    var marksNs = 0L

    /** One ARCore anchor call (createAnchor, detach) took [ns] */
    fun anchorCall(ns: Long) {
        anchorNs += ns
        if (ns > anchorMaxNs) anchorMaxNs = ns
    }

    fun clear() {
        batches = 0
        dropped = 0
        reads = 0
        hitTests = 0
        hits = 0
        births = 0
        reanchors = 0
        removals = 0
        reinits = 0
        voids = 0
        refreshNs = 0L
        hitTestNs = 0L
        depthNs = 0L
        placeNs = 0L
        anchorNs = 0L
        anchorMaxNs = 0L
        boostNs = 0L
        marksNs = 0L
    }

    fun copyFrom(o: PinWork) {
        batches = o.batches
        dropped = o.dropped
        reads = o.reads
        hitTests = o.hitTests
        hits = o.hits
        births = o.births
        reanchors = o.reanchors
        removals = o.removals
        reinits = o.reinits
        voids = o.voids
        refreshNs = o.refreshNs
        hitTestNs = o.hitTestNs
        depthNs = o.depthNs
        placeNs = o.placeNs
        anchorNs = o.anchorNs
        anchorMaxNs = o.anchorMaxNs
        boostNs = o.boostNs
        marksNs = o.marksNs
    }

    /** As the [GlWindow.sessionLine] tells it */
    fun describe(sb: StringBuilder) {
        sb.append(": ").append(batches).append(" batches placed, ").append(dropped).append(" dropped, ")
            .append(reads).append(" listed reads, ").append(hitTests).append(" hit tests with ").append(hits).append(" hits, ")
            .append(births).append(" births, ").append(reanchors).append(" re-anchors, ").append(removals).append(" removed, ")
            .append(reinits).append(" re-inits, ").append(voids).append(" voided; ms: anchors ").append(ms(refreshNs))
            .append(", hit tests ").append(ms(hitTestNs)).append(", depth confidence ").append(ms(depthNs))
            .append(", placing ").append(ms(placeNs)).append(", ARCore anchor calls ").append(ms(anchorNs))
            .append(" (max ").append(ms(anchorMaxNs)).append("), read boost ").append(ms(boostNs))
            .append(", drawing ").append(ms(marksNs))
    }

    /** [ns] as milliseconds to two decimals, rounded half up, without a Formatter */
    private fun ms(ns: Long): String {
        val hundredths = (ns + 5_000L) / 10_000L
        val frac = hundredths % 100
        return "${hundredths / 100}.${if (frac < 10) "0" else ""}$frac"
    }
}

/**
 * The app stream's images as the camera thread takes them (M5), over windows of [lengthNs] of capture time: capture
 * to arrival, and how many the blur pre-skip kept from the engine. A pause longer than a window starts a new one.
 * Camera thread only.
 */
internal class ArrivalWindow(private val lengthNs: Long = 3_000_000_000L) {
    private val arrival = Samples()
    private var startNs = Long.MIN_VALUE
    private var lastNs = Long.MIN_VALUE
    private var skipped = 0

    /** The image taken at [timestampNs] came [arrivalNs] after capture, [skippedForBlur] or not; the window's line when this closes it */
    fun add(timestampNs: Long, arrivalNs: Long, skippedForBlur: Boolean): String? {
        if (startNs == Long.MIN_VALUE || timestampNs - lastNs > lengthNs) {
            startNs = timestampNs
            arrival.clear()
            skipped = 0
        }
        lastNs = timestampNs
        val line = if (timestampNs - startNs >= lengthNs && arrival.size > 0) {
            val q = arrival.quantiles(0.5, 0.9, 1.0)
            String.format(
                Locale.US,
                "4K images: %d in %.1f s, capture to arrival p50 %.1f p90 %.1f max %.1f ms, %d skipped for blur",
                arrival.size, (timestampNs - startNs) / 1e9, q[0], q[1], q[2], skipped,
            ).also {
                startNs = timestampNs
                arrival.clear()
                skipped = 0
            }
        } else {
            null
        }
        arrival.add(arrivalNs / 1e6)
        if (skippedForBlur) skipped++
        return line
    }
}
