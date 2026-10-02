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
 *
 * `ARCOUNT_ITEMS=code1,code2,...` replays in item mode (spec 5.10): the list is set first and no trigger is inferred.
 * A trace recorded in label mode carries the old app's anchors, which appear and go with its sections; so item mode
 * answers the core's own anchor requests instead: each anchor at the world pose asked for, tracking while the frame
 * tracks, and moved with every correction ARCore made to the trace's own anchor between two consecutive frames (the
 * only record of ARCore's anchor updates); the first record after a new one carries the one it replaced
 * (previousAnchor). `ARCOUNT_TRACE_ANCHORS=1` keeps the trace's anchors.
 */
class TraceReplayTest {
    @Test
    fun replay() {
        val path = System.getenv("ARCOUNT_TRACE")
        assumeTrue("set ARCOUNT_TRACE to a trace file", path != null)
        val lines = File(path!!).readLines().filter { it.isNotBlank() }
        val frames = lines.filter { it.startsWith("{\"t\":\"frame\"") }.map(::frameOf)
        // ponytail: the long presses are inferred from the anchors; record commands in the trace if this guesses wrong
        val items = System.getenv("ARCOUNT_ITEMS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val triggerAt = mutableSetOf<Long>()
        if (items == null && System.getenv("ARCOUNT_TRACE_NO_TRIGGER") == null) {
            for (i in frames.indices) if (frames[i].anchor != null && (i == 0 || frames[i - 1].anchor == null)) triggerAt += frames[maxOf(0, i - 2)].timestampNs
        }
        val core = CountingCore()
        items?.let { core.setItems(it.toCollection(LinkedHashSet())) }
        val t0 = frames.first().timestampNs
        fun s(ts: Long) = "%7.2f s".format((ts - t0) / 1e9)
        var lastAnchor: Pose? = null
        val synthetic = items != null && System.getenv("ARCOUNT_TRACE_ANCHORS") == null
        var mine: Pose? = null
        var replaced: Pose? = null
        fun answer(ts: Long) {
            if (!synthetic) return
            val req = core.anchorRequest() ?: return
            println("${s(ts)}  -> anchor created where the core asked, ${req.world.t}")
            replaced = mine
            mine = req.world
            core.onAnchorCreated(true)
        }
        var lastLine = ""
        var lastTs = t0
        core.onResume(t0)
        var units = ""
        var closedSeen = 0
        fun report(ts: Long) {
            val v = core.view()
            // item mode: each section's units per code as it closes, from the last view before it closed
            if (items != null && v.closed.size > closedSeen) println("${s(ts)}  ${v.closed.last().sectionId} closed ${v.closed.last().status}: $units")
            closedSeen = v.closed.size
            if (items != null) units = core.units.groupBy { it.gtin }.mapValues { (_, us) -> us.groupingBy { it.state }.eachCount() }.toString()
            val b = v.bracket
            val line = "${v.state} prompt=${v.prompt} count=${b?.let { "${it.countLow}..${it.countHigh} ${it.gtin}" } ?: "-"} markers=${v.markers.groupingBy { it.state }.eachCount()} closed=${v.closed.size}" +
                if (items == null) "" else " items=${v.items.joinToString { "${it.code}:${it.countLow}..${it.countHigh}${if (it.inView) "*" else ""}" }}"
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
                    if (synthetic) {
                        val a = f.anchor
                        val m = mine
                        if (m != null && a != null && lastAnchor != null && a != lastAnchor) mine = a * lastAnchor!!.inverse() * m
                        lastAnchor = a
                        val tracking = if (f.frameTracking == Tracking.TRACKING) Tracking.TRACKING else Tracking.PAUSED
                        core.onFrame(f.copy(anchor = mine, anchorTracking = mine?.let { tracking }, previousAnchor = replaced))
                        replaced = null
                        answer(f.timestampNs)
                    } else {
                        val a = f.anchor
                        if (a != null && a != lastAnchor) {
                            println("${s(f.timestampNs)}  -> anchor in the trace; the core asked for ${core.anchorRequest()?.world?.t}")
                            core.onAnchorCreated(true)
                        }
                        lastAnchor = a
                        core.onFrame(f)
                    }
                    report(f.timestampNs)
                }
                l.startsWith("{\"t\":\"read\"") -> {
                    val r = readOf(l)
                    core.onReads(r.timestampNs, listOf(r))
                    answer(r.timestampNs)
                    report(r.timestampNs)
                }
            }
        }
        core.onCommand(Command.Finish, lastTs)
        report(lastTs)
        println("closed sections:")
        core.view().closed.forEach { println("  $it") }
        if (items != null) {
            println("items:")
            core.view().items.forEach { println("  $it") }
            println("events:")
            core.events.fold("") { prev, e -> if (e != prev) println("  $e"); e }
        }
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
