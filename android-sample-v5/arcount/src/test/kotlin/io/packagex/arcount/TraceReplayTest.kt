package io.packagex.arcount

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays an AR Count trace (the app's Settings › Advanced › "AR Count traces", files/ar-traces/trace-*.ndjson)
 * through [CountingCore], in the order the app's mapper fed it, and prints every change of state, prompt and count,
 * then each closed section. Runs only with `ARCOUNT_TRACE=<trace path>` in the environment:
 *
 *     ARCOUNT_TRACE=/path/trace.ndjson ./gradlew :arcount:test --tests '*TraceReplayTest*' -i
 *
 * A trace holds frames and reads, not commands: a long press of the trigger is put two frames before each anchor
 * that appears after a stretch without one; an anchor that replaces another is the core's own request, answered as
 * created. Finish is sent after the last frame. Pass `ARCOUNT_TRACE_NO_TRIGGER=1` to send no trigger at all.
 */
class TraceReplayTest {
    @Test
    fun replay() {
        val path = System.getenv("ARCOUNT_TRACE")
        assumeTrue("set ARCOUNT_TRACE to a trace file", path != null)
        val lines = File(path!!).readLines().filter { it.isNotBlank() }
        val frames = lines.filter { it.startsWith("{\"t\":\"frame\"") }.map(::frameOf)
        // ponytail: the long presses are inferred from the anchors; record commands in the trace if this guesses wrong
        val triggerAt = mutableSetOf<Long>()
        if (System.getenv("ARCOUNT_TRACE_NO_TRIGGER") == null) {
            for (i in frames.indices) if (frames[i].anchor != null && (i == 0 || frames[i - 1].anchor == null)) triggerAt += frames[maxOf(0, i - 2)].timestampNs
        }
        val core = CountingCore()
        val t0 = frames.first().timestampNs
        fun s(ts: Long) = "%7.2f s".format((ts - t0) / 1e9)
        var lastAnchor: Pose? = null
        var lastLine = ""
        var lastTs = t0
        core.onResume(t0)
        fun report(ts: Long) {
            val v = core.view()
            val b = v.bracket
            val line = "${v.state} prompt=${v.prompt} count=${b?.let { "${it.countLow}..${it.countHigh} ${it.gtin}" } ?: "-"} markers=${v.markers.groupingBy { it.state }.eachCount()} closed=${v.closed.size}"
            if (line != lastLine) println("${s(ts)}  $line")
            lastLine = line
        }
        for (l in lines) {
            when {
                l.startsWith("{\"t\":\"frame\"") -> {
                    val f = frameOf(l)
                    lastTs = f.timestampNs
                    if (f.timestampNs in triggerAt) {
                        println("${s(f.timestampNs)}  -> TriggerLong (inferred)")
                        core.onCommand(Command.TriggerLong, f.timestampNs)
                    }
                    val a = f.anchor
                    if (a != null && a != lastAnchor) {
                        println("${s(f.timestampNs)}  -> anchor in the trace; the core asked for ${core.anchorRequest()?.world?.t}")
                        core.onAnchorCreated(true)
                    }
                    lastAnchor = a
                    core.onFrame(f)
                    report(f.timestampNs)
                }
                l.startsWith("{\"t\":\"read\"") -> {
                    val r = readOf(l)
                    core.onReads(r.timestampNs, listOf(r))
                    report(r.timestampNs)
                }
            }
        }
        core.onCommand(Command.Finish, lastTs)
        report(lastTs)
        println("closed sections:")
        core.view().closed.forEach { println("  $it") }
    }

    private fun num(l: String, key: String): Double = Regex("\"$key\":(-?[0-9.eE+-]+)").find(l)!!.groupValues[1].toDouble()

    private fun arr(l: String, key: String): List<Double>? =
        Regex("\"$key\":(null|\\[([^\\]]*)])").find(l)!!.groupValues.let { g -> if (g[1] == "null") null else g[2].split(',').map { it.trim().toDouble() } }

    private fun str(l: String, key: String): String? =
        Regex("\"$key\":(null|\"((?:[^\"\\\\]|\\\\.)*)\")").find(l)?.groupValues?.let { g -> if (g[1] == "null") null else g[2].replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/") }

    private fun poseOf(a: List<Double>) = Pose(Vec3(a[0], a[1], a[2]), Quat(a[3], a[4], a[5], a[6]))

    private fun frameOf(l: String): PoseRecord {
        val intr = l.substringAfter("\"intr\":")
        return PoseRecord(
            timestampNs = num(l, "ts").toLong(),
            camera = poseOf(arr(l, "cam")!!),
            anchor = arr(l, "anchor")?.let(::poseOf),
            frameTracking = Tracking.valueOf(str(l, "tracking")!!),
            anchorTracking = str(l, "anchorTracking")?.let(Tracking::valueOf),
            intrinsics = Intrinsics(num(intr, "fx"), num(intr, "fy"), num(intr, "cx"), num(intr, "cy"), num(intr, "w").toInt(), num(intr, "h").toInt()),
            exposureNs = num(l, "exposureNs").toLong(),
        )
    }

    private fun readOf(l: String) = Read(
        timestampNs = num(l, "ts").toLong(),
        text = str(l, "rawText")!!,
        corners = arr(l, "raw")!!,
        engineId = num(l, "id").toInt(),
        symbology = str(l, "symbology"),
        touchesBorder = Regex("\"border\":(true|false)").find(l)!!.groupValues[1] == "true",
    )
}
