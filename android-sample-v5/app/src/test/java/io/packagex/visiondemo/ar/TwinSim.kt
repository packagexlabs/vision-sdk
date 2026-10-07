package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Ray
import io.packagex.arcount.Read
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import io.packagex.arcount.ray
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/** Which unit is read when (drift plan Phase 3's twin scenarios) */
enum class TwinScenario {
    /** Every unit read from the start */
    BASE,

    /** Unit 1 (B) is not read before 5 s; unit 0 (A) is undecodable for 0.3 s from 5 s, while pinless B is read */
    A,

    /** B is read only from 3 s, out of phase with A */
    B,

    /** A is undecodable from 8 s to 11 s while B is read */
    A2,
}

/**
 * What every [PinBook.place] of a [TwinSim] run cost on this thread: wall time (the run is single-threaded) and, where
 * the JVM counts them, the bytes allocated (HotSpot's per-thread counter, through reflection as the unit tests compile
 * against android.jar; the reading's own few bytes, measured on an empty span, are taken off)
 */
class PlaceTiming {
    private var perBatch = LongArray(1024)
    private var bytesBefore = 0L
    var batches = 0
        private set
    var sightings = 0
        private set
    var claims = 0
        private set
    var ns = 0L
        private set

    /** Bytes allocated by the placing in all; -1 where the JVM does not count them */
    var bytes = if (ALLOCATED == null) -1L else 0L
        private set

    fun start(): Long {
        if (ALLOCATED != null) bytesBefore = allocated()
        return System.nanoTime()
    }

    fun stop(t0: Long, sightings: Int, claims: Int) {
        val dt = System.nanoTime() - t0
        if (ALLOCATED != null) bytes += allocated() - bytesBefore - OVERHEAD
        if (batches == perBatch.size) perBatch = perBatch.copyOf(batches * 2)
        perBatch[batches++] = dt
        this.sightings += sightings
        this.claims += claims
        ns += dt
    }

    /** The nearest-rank quantile [p] of the batches' times, µs */
    fun batchUs(p: Double): Double {
        if (batches == 0) return Double.NaN
        val sorted = perBatch.copyOf(batches).also { it.sort() }
        return sorted[(Math.ceil(p * batches).toInt() - 1).coerceIn(0, batches - 1)] / 1e3
    }

    override fun toString() = "%d batches, %d sightings, %d claims: %.1f µs a claim, %.1f µs a batch (p50 %.1f, p99 %.1f, max %.1f), %s".format(
        batches, sightings, claims, ns / 1e3 / maxOf(claims, 1), ns / 1e3 / maxOf(batches, 1), batchUs(0.5), batchUs(0.99), batchUs(1.0),
        if (bytes < 0) "allocations not counted" else "%.2f KB allocated a batch".format(bytes / 1024.0 / maxOf(batches, 1)),
    )

    private companion object {
        /** HotSpot's ThreadMXBean and its getThreadAllocatedBytes(long); null elsewhere */
        val ALLOCATED: Pair<Any, java.lang.reflect.Method>? = runCatching {
            val bean: Any = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null) ?: return@runCatching null
            val type = Class.forName("com.sun.management.ThreadMXBean")
            if (!type.isInstance(bean) || type.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) != true) null
            else bean to type.getMethod("getThreadAllocatedBytes", java.lang.Long.TYPE)
        }.getOrNull()

        @Suppress("DEPRECATION") // Thread.threadId() is not in android.jar
        fun allocated(): Long = ALLOCATED!!.let { (bean, m) -> m.invoke(bean, Thread.currentThread().id) as Long }

        /** What one reading pair allocates itself, once the reflection has warmed up */
        val OVERHEAD: Long = if (ALLOCATED == null) {
            0L
        } else {
            repeat(5_000) { allocated() }
            var sum = 0L
            repeat(1_000) {
                val before = allocated()
                sum += allocated() - before
            }
            sum / 1_000
        }
    }
}

/** What one [TwinSim] run did; times in seconds of the run */
class TwinRun(
    val pins: Int,
    val maxPins: Int,
    /** Times a pin's nearest unit, within half the pitch, changed after the first 2 s */
    val hops: Int,
    /** Of the frames after the first 2 s, the share on which every unit had a pin on it ([TwinSim.COVER_M]) */
    val coverage: Double,
    val births: Int,
    val merges: Int,
    val retirements: Int,
    val voids: Int,
    /** Merges and retirements after the first 2 s */
    val removalsAfterWarmUp: Int,
    /** Pins that are ghosts ([TwinSim.GHOST_M]) at the end */
    val ghosts: Int,
    /** After a map shift, when every unit again had a pin on it ([TwinSim.COVER_M]; null: never) */
    val recoveredS: Double?,
    /** After the first 2 s, how long each pin stayed a ghost ([TwinSim.GHOST_M]) at a stretch: every unit stays in view, so ghost lifetimes in view */
    val ghostLivesS: List<Double>,
    /** When pins were retired */
    val retiredAtS: List<Double>,
    /** When pins were born */
    val bornAtS: List<Double>,
    /** Of the reads after the first 2 s, the share whose nearest pin, as it stood at their capture, lay over 150 px off them (or none) */
    val farShare: Double,
    /** After the first 2 s, the longest any pin went with no claim applied (a voided one is none) */
    val maxUnclaimedS: Double,
    /** Pins whose depth the rays verified by the end (Phase 4) */
    val verified: Int = 0,
) {
    val maxGhostS: Double get() = ghostLivesS.maxOrNull() ?: 0.0

    override fun toString(): String {
        fun s(v: Double?) = v?.let { "%.1f".format(it) }
        return "pins $pins (max $maxPins) hops $hops cover %.0f%% far %.1f%% ".format(coverage * 100, farShare * 100) +
            "births $births merges $merges retired $retirements voided $voids late removals $removalsAfterWarmUp ghosts $ghosts verified $verified " +
            "recovered ${s(recoveredS)} ghost ${s(maxGhostS)} unclaimed ${s(maxUnclaimedS)} s retiredAt ${retiredAtS.joinToString { s(it)!! }}"
    }
}

/**
 * Drift plan Phase 3's identical-unit scenarios (the replay's twin_rec.py and jump_rec.py) against the real [PinBook],
 * fed as [ArPins] feeds it (under [PinRules.ANDROID] with [refine] each claim refines its pin, Phase 4; without, pins are
 * frozen as in Phase 3). [units] EAN-13 labels of one payload, [pitch] apart along x at camera depth [depthM], face
 * the camera; the camera slides ±12 cm along the row (8 s period) with a 1 cm vertical wobble and never turns. Each unit
 * is decoded every [cadenceMs] with its own random phase (0: every frame), 10% of decodes missed. Each read's quad is
 * off by N(0, 5 px); ARCore's hit ([hits]) lies on its centre ray at the unit's range + [biasM] + N(0, 2 cm), and the
 * point is [pickHit]'s, else [defaultPick]'s. A batch is placed [latencyMs] after its capture. With [shiftM], every unit
 * moves that far along x at [shiftAtS], as the pins see it: a map correction their anchors did not follow, undetected
 * unless [shiftDetected], which sets [PinBook.mapMovedNs] to it as the app's MapBreaks does on an anchor's step.
 * A pin is judged where it is drawn: off the camera's ray to a unit, so a pin too deep that still lies on its unit in
 * the image covers it (the replay judged 3D distance, which counts every pin of a biased hit as a ghost).
 */
class TwinSim(
    private val rules: PinRules = PinRules.ANDROID,
    private val pitch: Double,
    private val cadenceMs: Int,
    private val scenario: TwinScenario = TwinScenario.BASE,
    private val biasM: Double = 0.0,
    private val seed: Long = 1,
    private val units: Int = 2,
    private val depthM: Double = 0.4,
    private val seconds: Double = 20.0,
    private val latencyMs: Int = 150,
    private val hits: Boolean = true,
    private val shiftM: Double = 0.0,
    private val shiftAtS: Double = 10.0,
    private val refine: Boolean = true,
    private val shiftDetected: Boolean = false,
    /** Times every [PinBook.place] of the run (the timing test); null: not timed */
    private val timing: PlaceTiming? = null,
) {
    fun run(): TwinRun {
        val rnd = java.util.Random(seed)
        val book = PinBook().also {
            it.rules = rules
            it.refine = refine
        }
        val base = (0 until units).map { Vec3(it * pitch, 0.0, -depthM) }
        val cadNs = cadenceMs * MS
        val next = LongArray(units) { if (cadNs > 0) (rnd.nextDouble() * cadNs).toLong() else 0L }
        if (scenario == TwinScenario.B) next[1] = 3 * S + (next[0] + 150 * MS) % (if (cadNs > 0) cadNs else FRAME_NS)
        val pending = ArrayDeque<Pair<Long, List<Sighting>>>()
        val nearestOf = HashMap<Int, Int>()
        var births = 0
        var merges = 0
        var retirements = 0
        var voids = 0
        var late = 0
        var hops = 0
        var covered = 0
        var frames = 0
        var maxPins = 0
        var reads = 0
        var far = 0
        var unclaimed = 0L
        var recovered: Double? = null
        val ghostSince = HashMap<Int, Long>()
        val ghostLives = ArrayList<Double>()
        val retiredAt = ArrayList<Double>()
        val bornAt = ArrayList<Double>()
        val shiftNs = (shiftAtS * S).toLong()
        var t = 0L
        var last = Pose.IDENTITY
        while (t < (seconds * S).toLong()) {
            val shifted = shiftM != 0.0 && t >= shiftNs
            if (shifted && shiftDetected && book.mapMovedNs < shiftNs) book.mapMovedNs = shiftNs
            val at = if (shifted) base.map { it + Vec3(shiftM, 0.0, 0.0) } else base
            val mid = 0.5 * pitch * (units - 1)
            val camera = Pose(Vec3(mid + 0.12 * sin(2 * PI * t / 8e9), 0.01 * sin(2 * PI * t / 1.3e9), 0.0), Quat.IDENTITY)
            val capture = PoseRecord(t, camera, null, Tracking.TRACKING, null, K)
            val batch = ArrayList<Sighting>()
            for (i in 0 until units) {
                if (undecodable(i, t)) {
                    if (cadNs > 0 && t >= next[i]) next[i] = t + cadNs
                    continue
                }
                if (scenario == TwinScenario.B && i == 1 && t < 3 * S) continue
                if (cadNs > 0 && t < next[i]) continue
                if (cadNs > 0) next[i] = t + cadNs
                if (rnd.nextDouble() < 0.1) continue
                val read = read(at[i], camera, t, 100 + i, rnd) ?: continue
                if (t > 2 * S) {
                    reads++
                    val off = book.pins.minOfOrNull { p ->
                        K.project(camera.inverse().apply(p.position))?.let { (u, v) -> hypot(u - read.centreU, v - read.centreV) } ?: Double.MAX_VALUE
                    }
                    if (off == null || off > FAR_PX) far++
                }
                val ray = read.ray(capture, null)
                val range = (at[i] - camera.t).norm() + biasM + rnd.nextGaussian() * 0.02
                val seen = if (hits) listOf(RayHit(HitKind.POINT, ray.at(range))) else emptyList()
                val pick = pickHit(seen, read, capture, ray) ?: defaultPick(read, capture)
                batch += sightingOf(read, capture, pick.point, Quat.IDENTITY, ray, FLOOR_PX, pick.onPlane, pick.source)
            }
            while (pending.isNotEmpty() && pending.first().first <= t - latencyMs * MS) {
                val (ts, ss) = pending.removeFirst()
                val t0 = timing?.start() ?: 0L
                val placed = book.place(ss, ts, t, true) { births++; bornAt += t / 1e9; true }
                timing?.stop(t0, ss.size, placed.claims.size)
                merges += placed.removed.size
                retirements += placed.retired.size
                voids += placed.voided.size
                placed.retired.forEach { _ -> retiredAt += t / 1e9 }
                if (t > 2 * S) late += placed.removed.size + placed.retired.size
            }
            if (batch.isNotEmpty()) pending.addLast(t to batch)
            maxPins = maxOf(maxPins, book.pins.size)
            if (t > 2 * S) {
                frames++
                if (at.all { u -> book.pins.any { off(it, u, camera) < COVER_M } }) {
                    covered++
                    if (shifted && recovered == null) recovered = (t - shiftNs) / 1e9
                }
                for (p in book.pins) {
                    if (at.all { off(p, it, camera) > GHOST_M }) ghostSince.getOrPut(p.id) { t } else ghostSince.remove(p.id)?.let { ghostLives += (t - it) / 1e9 }
                }
                val gone = ghostSince.keys.filter { id -> book.pins.none { it.id == id } }
                for (id in gone) ghostLives += (t - ghostSince.remove(id)!!) / 1e9
                for (p in book.pins) {
                    unclaimed = maxOf(unclaimed, t - p.lastSeenNs)
                    val j = at.indices.minBy { (p.position - at[it]).norm() }
                    if ((p.position - at[j]).norm() >= 0.5 * pitch) continue
                    val was = nearestOf.put(p.id, j)
                    if (was != null && was != j) hops++
                }
            }
            last = camera
            t += FRAME_NS
        }
        val end = if (shiftM != 0.0) base.map { it + Vec3(shiftM, 0.0, 0.0) } else base
        val ghosts = book.pins.count { p -> end.all { off(p, it, last) > GHOST_M } }
        for (since in ghostSince.values) ghostLives += (t - since) / 1e9
        return TwinRun(
            book.pins.size, maxPins, hops, covered / maxOf(frames, 1).toDouble(), births, merges, retirements, voids, late, ghosts,
            recovered, ghostLives, retiredAt, bornAt, far / maxOf(reads, 1).toDouble(), unclaimed / 1e9, book.pins.count { it.verified },
        )
    }

    /** How far [pin] lies off [unit] as [camera] sees it: laterally, off the ray to the unit, as the pins are judged */
    private fun off(pin: Pin, unit: Vec3, camera: Pose) = lateral(Ray(camera.t, (unit - camera.t).unit()), pin.position)

    private fun undecodable(i: Int, t: Long) = when (scenario) {
        TwinScenario.A -> (i == 1 && t < 5 * S) || (i == 0 && t >= 5 * S && t < 5300 * MS)
        TwinScenario.A2 -> i == 0 && t >= 8 * S && t < 11 * S
        else -> false
    }

    /** The EAN-13 at [unit] read by [camera] at [t], its quad off by N(0, 5 px); null when it is not wholly in the image */
    private fun read(unit: Vec3, camera: Pose, t: Long, id: Int, rnd: java.util.Random): Read? {
        val du = rnd.nextGaussian() * 5.0
        val dv = rnd.nextGaussian() * 5.0
        val toCamera = camera.inverse()
        val corners = ArrayList<Double>(8)
        for ((x, y) in CORNERS) {
            val (u, v) = K.project(toCamera.apply(unit + Vec3(x, y, 0.0))) ?: return null
            if (u + du < 2 || v + dv < 2 || u + du > K.width - 2 || v + dv > K.height - 2) return null
            corners += u + du
            corners += v + dv
        }
        return Read(t, TEXT, corners, id, "ean13")
    }

    companion object {
        const val MS = 1_000_000L
        const val S = 1_000_000_000L
        const val FRAME_NS = 33_333_333L

        /**
         * A unit is covered by a pin this close to the camera's ray to it (laterally, so a pin a little too deep that
         * is drawn on its unit still covers it); a pin farther than [GHOST_M] off every unit's ray is a ghost: 145 px at
         * 0.4 m, beyond the 113 px a claim may be off its pin and still be good (rule 3), so no read would keep it
         */
        const val COVER_M = 0.01
        const val GHOST_M = 0.02

        /** A read whose nearest pin lies farther off it than this, in pixels of its image, counts as far (the replay's M1 share) */
        const val FAR_PX = 150.0

        /** 4K stream; the 40 dp match floor at density 2.75 and 0.61 view px per stream px */
        val K = Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160)
        const val FLOOR_PX = 180.0
        const val TEXT = "4006381333931"

        /** An EAN-13's bars, 31.35 x 23 mm, tl, tr, br, bl */
        private val CORNERS = listOf(
            -EAN13_WIDTH_M / 2 to 0.0115, EAN13_WIDTH_M / 2 to 0.0115, EAN13_WIDTH_M / 2 to -0.0115, -EAN13_WIDTH_M / 2 to -0.0115,
        )
    }
}
