package io.packagex.arcount

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.random.Random

/** What the simulator drives: the whole core, or the bare section state machine */
interface Counter {
    fun resume(ts: Long)

    fun frame(r: PoseRecord)

    fun reads(ts: Long, reads: List<Read>)

    fun command(c: Command, ts: Long)

    fun anchorRequest(): AnchorRequest?

    fun anchorCreated(ok: Boolean)
}

/** The state machine alone: reads pair with the record of their own timestamp, which the simulator always sends */
class MachineCounter(val m: SectionMachine) : Counter {
    private val records = HashMap<Long, PoseRecord>()
    private val early = HashMap<Long, List<Read>>()

    override fun resume(ts: Long) = m.onResume(ts)

    override fun frame(r: PoseRecord) {
        records[r.timestampNs] = r
        m.onFrame(r)
        early.remove(r.timestampNs)?.let { m.onReads(Paired(r, it)) }
    }

    override fun reads(ts: Long, reads: List<Read>) {
        m.noteReads(ts, reads)
        val r = records[ts]
        if (r == null) early[ts] = reads else m.onReads(Paired(r, reads))
    }

    override fun command(c: Command, ts: Long) = m.onCommand(c, ts)

    override fun anchorRequest() = m.anchorRequest()

    override fun anchorCreated(ok: Boolean) = m.onAnchorCreated(ok)
}

/**
 * A synthetic AR session: a camera path at 30 fps over a shelf of [symbols], ARCore's pose records (with the anchor
 * once the counter asks for one, created where it asks), and the engine's reads every [engineEvery] frames, made
 * from the true camera pose. A world jump shifts the reported camera from then on; the anchor's reported pose moves
 * only when the test says ARCore moved it.
 */
class Sim(
    var symbols: List<Symbol>,
    val counter: Counter,
    val noisePx: Double = 0.0,
    seed: Int = 1,
    val engineEvery: Int = 3,
    val readsFirst: Boolean = true,
    val t0: Long = 1_000_000_000L,
) {
    private val rnd = Random(seed)
    var frame = 0
        private set
    var anchor: Pose? = null
        private set
    private var cameraShift = Vec3.ZERO
    private var anchorShift = Vec3.ZERO
    var tracking = Tracking.TRACKING
    var anchorTracking = Tracking.TRACKING
    var hidden: (Symbol) -> Boolean = { false }
    var readsEnabled = true

    /** The engine's decode budget: at most this many codes per engine frame, taken in turn */
    var readsPerFrame = Int.MAX_VALUE
    private var turn = 0
    val anchorRequests = ArrayList<AnchorRequest>()
    var lastRecord: PoseRecord? = null
        private set

    fun ts(i: Int = frame) = t0 + i * FRAME_NS

    init {
        counter.resume(ts(0))
    }

    /** All later reported camera poses move by [delta]; the true camera does not */
    fun jumpCamera(delta: Vec3) {
        cameraShift += delta
    }

    /** ARCore moves the anchor's reported pose by [delta] */
    fun moveAnchor(delta: Vec3) {
        anchorShift += delta
    }

    fun command(c: Command) = counter.command(c, ts(frame - 1))

    /** One frame with the true camera at [camera]; [after] runs once the frame has been fed */
    fun step(camera: Pose) {
        val ts = ts()
        val reported = Pose(camera.t + cameraShift, camera.q)
        val a = anchor?.let { Pose(it.t + anchorShift, it.q) }
        val record = PoseRecord(ts, reported, a, tracking, a?.let { anchorTracking }, K4K, EXPOSURE_NS)
        val reads = if (readsEnabled && frame % engineEvery == 0) budget(shoot(camera, symbols.filterNot(hidden), ts, noisePx, rnd)) else null
        if (readsFirst && reads != null) counter.reads(ts, reads)
        counter.frame(record)
        if (!readsFirst && reads != null) counter.reads(ts, reads)
        lastRecord = record
        counter.anchorRequest()?.let {
            anchorRequests += it
            anchor = it.world
            anchorShift = Vec3.ZERO
            counter.anchorCreated(true)
        }
        frame++
    }

    private fun budget(reads: List<Read>): List<Read> {
        if (reads.size <= readsPerFrame) return reads
        turn++
        return List(readsPerFrame) { reads[(turn * readsPerFrame + it) % reads.size] }
    }

    fun run(path: List<Pose>, each: () -> Unit = {}) {
        for (p in path) {
            step(p)
            each()
        }
    }
}

/** Camera paths at 30 fps */
object Paths {
    fun frames(seconds: Double) = ceil(seconds * 30 - 1e-9).toInt()

    fun hold(at: Pose, seconds: Double) = List(frames(seconds)) { at }

    /** From [from] to [to] at [speed] m/s, looking along -Z */
    fun move(from: Vec3, to: Vec3, speed: Double, q: Quat = Quat.IDENTITY): List<Pose> {
        val n = maxOf(1, frames((to - from).norm() / speed))
        return (1..n).map { Pose(from + (to - from) * (it.toDouble() / n), q) }
    }

    /** Sideways sway about [centre]: x = centre.x + amplitude · sin(2πt / period) */
    fun sway(centre: Vec3, amplitude: Double, period: Double, seconds: Double) =
        List(frames(seconds)) { Pose(centre + Vec3(amplitude * sin(2 * PI * it / (30 * period)), 0.0, 0.0), Quat.IDENTITY) }

    /** Turning about the vertical through the camera centre: yaw = amplitude · sin(2πt / period) */
    fun yaw(centre: Vec3, amplitude: Double, period: Double, seconds: Double) =
        List(frames(seconds)) { Pose(centre, Quat.axisAngle(Vec3(0.0, 1.0, 0.0), amplitude * sin(2 * PI * it / (30 * period)))) }
}

/** A row of [n] identical units at [pitch] on a shelf [depth] in front of the camera's start */
fun row(n: Int, pitch: Double, depth: Double = 0.30, y: Double = 0.04, x0: Double = 0.0, text: String = GTIN) =
    (0 until n).map { Symbol(text, Vec3(x0 + it * pitch, y, -depth), engineId = it + 1) }

/** The shelf-edge label, 12 cm below the units, 20 mm wide */
fun label(x: Double = 0.0, depth: Double = 0.30, y: Double = -0.08, text: String = LABEL) =
    Symbol(text, Vec3(x, y, -depth), engineId = 100, width = 0.020, height = 0.008)

/** The host knows its labels and what they hold */
val hostConfig = CountConfig(isLabel = { it.text.startsWith("LABEL") }, gtinsOfLabel = { if (it.startsWith("LABEL")) setOf(GTIN) else null })
