package io.packagex.arcount

import kotlin.math.hypot

/**
 * The AR counting core (spec 5.1, 5.4–5.7): pairs every read with the pose of its capture, runs the section state
 * machine and the association, and publishes what the UI draws. One instance per AR session, called from one
 * thread at a time.
 */
class CountingCore(private val config: CountConfig = CountConfig()) : ArCounter {
    private val pairing = Pairing(config)
    internal val machine = SectionMachine(config)
    private val blurWatch = BlurWatch(config)
    private var latest: PoseRecord? = null
    private var slowDown = false
    private var modulePx: Double? = null
    private val gapIds = HashMap<Pair<Int, Int>, Int>()
    private var nextGapId = 1
    private var shownGaps: List<GapSpot> = emptyList()
    private var cached: CountView? = null

    /** The open section's units */
    internal val units: List<CountUnit> get() = machine.section?.table?.units ?: emptyList()

    /** What the state machine and the open section's association logged */
    internal val events: List<String> get() = machine.events + (machine.section?.table?.events ?: emptyList())

    internal val droppedReads get() = pairing.droppedReads

    override fun onResume(timestampNs: Long) {
        machine.onResume(timestampNs)
        cached = null
    }

    override fun onFrame(frame: PoseRecord) {
        val prev = latest
        latest = frame
        machine.onFrame(frame)
        for (p in pairing.addRecord(frame)) machine.onReads(p)
        watchMotion(prev, frame)
        cached = null
    }

    override fun onReads(timestampNs: Long, reads: List<Read>) {
        machine.noteReads(timestampNs, reads)
        pairing.addReads(timestampNs, reads)?.let { machine.onReads(it) }
        cached = null
    }

    override fun onCommand(command: Command, timestampNs: Long) {
        when (command) {
            Command.AddUnit -> spotForAddUnit()?.let { machine.addManual(it, timestampNs) }
            is Command.FillGap -> shownGaps.firstOrNull { it.id == command.gapId }?.let { machine.addManual(it.point, timestampNs) }
            else -> machine.onCommand(command, timestampNs)
        }
        cached = null
    }

    override fun anchorRequest(): AnchorRequest? = machine.anchorRequest()

    override fun onAnchorCreated(ok: Boolean) {
        machine.onAnchorCreated(ok)
        cached = null
    }

    override fun view(): CountView = cached ?: build().also { cached = it }

    private fun build(): CountView {
        val r = latest ?: return CountView.EMPTY.copy(closed = machine.closed.toList())
        val s = machine.section
        val t = s?.table
        val live = s != null && t != null && r.anchor != null
        val counting = live && machine.state == SectionState.COUNTING
        shownGaps = if (counting) gapSpots(t!!) else emptyList()
        val tac = r.cameraInAnchor()
        val k = r.intrinsics
        return CountView(
            state = machine.state,
            prompt = prompt(r),
            markers = if (counting) markers(t!!, r) else emptyList(),
            gaps = shownGaps.mapNotNull { g -> Prediction.pixel(g.point, tac, k)?.let { (u, v) -> Gap(g.id, u / k.width, v / k.height) } },
            bracket = if (live) bracket(s!!, t!!, r) else null,
            closed = machine.closed.toList(),
        )
    }

    /** One prompt, by spec 5.5's priority; "Too dark" needs a light estimate the API does not carry, "Device hot" is the app's */
    private fun prompt(r: PoseRecord): Prompt? {
        val state = machine.state
        val s = machine.section
        if (state != SectionState.COUNTING && machine.guardHolds(r.timestampNs)) return Prompt.HOLD_STILL_A_MOMENT
        when (state) {
            SectionState.IDLE, SectionState.CLOSED -> return Prompt.SCAN_SHELF_LABEL
            SectionState.FROZEN -> return Prompt.SCAN_LABEL_TO_CONTINUE
            SectionState.OPEN -> return if (s != null && s.labelled && !s.seeded) Prompt.SCAN_SHELF_LABEL else fallback(r)
            SectionState.COUNTING -> Unit
        }
        val t = s?.table ?: return null
        val c = t.counts()
        if (c.tentative + c.ambiguous > 0 && !s.rangeAccepted) return Prompt.RANGE_RESCAN
        if (slowDown) return Prompt.SLOW_DOWN
        if (r.anchor != null && Blur.slideALittle(inView(t, r), r.timestampNs, config)) return Prompt.SLIDE_A_LITTLE
        val m = modulePx
        if (m != null && m < config.minModulePx) return Prompt.MOVE_CLOSER
        return fallback(r)
    }

    /** "Hold within 30 cm" while the stream is a fallback below 4K (spec 5.2) */
    private fun fallback(r: PoseRecord) = if (r.intrinsics.width < config.fullResolutionWidth) Prompt.HOLD_WITHIN_30CM else null

    /** Predicted blur from the newest motion, at the median depth and the widest module of the units in view (spec 5.6) */
    private fun watchMotion(prev: PoseRecord?, r: PoseRecord) {
        val t = machine.section?.table
        var modules: Double? = null
        modulePx = null
        if (machine.state == SectionState.COUNTING && t != null && r.anchor != null) {
            val units = inView(t, r)
            val tac = r.cameraInAnchor()
            val module = units.mapNotNull { widthNow(it, tac) }.maxOrNull()?.div(config.modulesPerSymbol)
            val depths = units.map { Prediction.cameraDepth(it.point, tac) }.sorted()
            modulePx = module
            if (prev != null && module != null && depths.isNotEmpty()) modules = Blur.of(prev, r, depths[depths.size / 2], module)
        }
        slowDown = blurWatch.update(r.timestampNs, modules)
    }

    private fun inView(t: UnitTable, r: PoseRecord) = t.units.filter { it.state != UnitState.MANUAL && t.predict(it, r)?.inImage(r.intrinsics) == true }

    /** A unit's last quad width in pixels, scaled from its camera depth then to its depth now */
    private fun widthNow(u: CountUnit, tac: Pose): Double? {
        val read = u.lastRead ?: return null
        val last = u.lastRecord ?: return null
        val then = Prediction.cameraDepth(u.point, last.cameraInAnchor())
        val now = Prediction.cameraDepth(u.point, tac)
        return if (then > 0 && now > 0) read.widthPx * then / now else null
    }

    /**
     * Markers (spec 5.5): a unit whose last read is at most a second old and whose warp is certain to a tenth of a
     * pitch, at its warped quad; a unit added by hand at its point on the plane.
     */
    private fun markers(t: UnitTable, r: PoseRecord): List<Marker> {
        val k = r.intrinsics
        val tac = r.cameraInAnchor()
        return t.units.mapNotNull { u ->
            if (u.state == UnitState.MANUAL) {
                val (pu, pv) = Prediction.pixel(u.point, tac, k) ?: return@mapNotNull null
                return@mapNotNull Marker(u.id, u.state, pu / k.width, pv / k.height, t.pitchPx(k.fx, Prediction.cameraDepth(u.point, tac)) / k.width)
            }
            if (r.timestampNs - u.lastReadNs > config.markerMaxAgeNs) return@mapNotNull null
            val p = t.predict(u, r) ?: return@mapNotNull null
            if (p.sigmaPx > config.markerSigmaPitchFraction * t.pitchPx(k.fx, p.z)) return@mapNotNull null
            val quad = warp(u, tac, k) ?: return@mapNotNull null
            val width = hypot(quad[1].first - quad[0].first, quad[1].second - quad[0].second)
            Marker(u.id, u.state, quad.sumOf { it.first } / 4 / k.width, quad.sumOf { it.second } / 4 / k.height, width / k.width)
        }
    }

    /** The last decoded quad warped by T_ac(now) · T_ac(last)⁻¹ at the unit's depth (spec 5.5) */
    private fun warp(u: CountUnit, tac: Pose, k: Intrinsics): List<Pair<Double, Double>>? {
        val read = u.lastRead ?: return null
        val last = u.lastRecord ?: return null
        val lastTac = last.cameraInAnchor()
        val z = Prediction.cameraDepth(u.point, lastTac)
        if (z <= 0) return null
        return (0 until 4).map { i ->
            val d = last.intrinsics.rayInCamera(read.corners[2 * i], read.corners[2 * i + 1])
            Prediction.pixel(lastTac.apply(d * (z / -d.z)), tac, k) ?: return null
        }
    }

    private class GapSpot(val id: Int, val point: Vec3)

    /** Gaps (spec 5.5): once enough units are COUNTED, between units adjacent along the shelf 1.75 pitches or more apart */
    private fun gapSpots(t: UnitTable): List<GapSpot> {
        if (t.counts().counted < config.gapMinCounted) return emptyList()
        val plane = t.frame.plane
        return t.units.sortedBy { plane.along(it.point) }.zipWithNext()
            .filter { (a, b) -> plane.along(b.point) - plane.along(a.point) >= config.gapPitches * t.pitch }
            .map { (a, b) -> GapSpot(gapIds.getOrPut(a.id to b.id) { nextGapId++ }, (a.point + b.point) * 0.5) }
    }

    /** Where a tap on the bracket puts a unit: the first gap along the shelf, else one pitch past the last unit */
    private fun spotForAddUnit(): Vec3? {
        val t = machine.section?.table ?: return null
        if (machine.state != SectionState.COUNTING) return null
        gapSpots(t).firstOrNull()?.let { return it.point }
        val plane = t.frame.plane
        val last = t.units.maxByOrNull { plane.along(it.point) } ?: return null
        return last.point + plane.shelfAxis * t.pitch
    }

    /** The bracket at the section anchor: the count as [N, N + a], locked while FROZEN */
    private fun bracket(s: Section, t: UnitTable, r: PoseRecord): Bracket {
        val k = r.intrinsics
        val c = t.counts()
        val p = Prediction.pixel(Vec3.ZERO, r.cameraInAnchor(), k)
        val inImage = p != null && p.first >= 0 && p.second >= 0 && p.first < k.width && p.second < k.height
        return Bracket(p?.let { it.first / k.width } ?: -1.0, p?.let { it.second / k.height } ?: -1.0, inImage, s.gtins.minOrNull(), c.low, c.high, machine.state == SectionState.FROZEN)
    }
}
