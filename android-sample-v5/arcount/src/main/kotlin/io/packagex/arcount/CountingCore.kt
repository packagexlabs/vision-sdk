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
    private val lumas = LumaRing(config.lumaFrames)
    private val tracks = PatchTracks(config)
    private val schedule = RefreshSchedule(config)

    /** Time spent following patches, and the frames it was spent on (spec 5.9) */
    internal var trackNanos = 0L
        private set
    internal var trackFrames = 0
        private set
    internal val patchTracks: PatchTracks get() = tracks

    /** The last pose records with whether the camera moved into each: a luma copy may come after its record */
    private val recent = ArrayDeque<Pair<PoseRecord, Boolean>>()
    private var trackedThroughNs = Long.MIN_VALUE

    /** Luma copies dropped for coming more than [CountConfig.lateLumaNs] after the newest pose record */
    internal var lateLumas = 0
        private set

    /** The open section's units */
    internal val units: List<CountUnit> get() = machine.section?.table?.units ?: emptyList()

    /** What the state machine and the open section's association logged */
    internal val events: List<String> get() = machine.events + (machine.section?.table?.events ?: emptyList())

    internal val droppedReads get() = pairing.droppedReads

    /** Reads dropped at the boundary because a corner was not a finite number */
    internal var nonFiniteReads = 0
        private set

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
        schedule.onFrame(frame)
        recent.addLast(frame to schedule.motion.moving)
        while (recent.size > config.lumaFrames) recent.removeFirst()
        afterReads(frame.timestampNs)
        lumas.at(frame.timestampNs)?.let { trackFrame(frame, schedule.motion.moving, it) }
        cached = null
    }

    override fun onReads(timestampNs: Long, reads: List<Read>) {
        // review M1: a corner that is not a finite number would throw in the ray, or place a NaN anchor: drop it here
        val finite = reads.filter { r -> r.corners.all { it.isFinite() } }
        nonFiniteReads += reads.size - finite.size
        schedule.onEngineFrame()
        machine.noteReads(timestampNs, finite)
        pairing.addReads(timestampNs, finite)?.let { machine.onReads(it) }
        afterReads(latest?.timestampNs ?: timestampNs)
        cached = null
    }

    /**
     * A luma copy may come before its frame's pose record (tracked in [onFrame]) or after it (tracked here, when the
     * record is among the last ones and no newer frame was tracked); one more than [CountConfig.lateLumaNs] behind the
     * newest record is dropped.
     */
    override fun onLuma(timestampNs: Long, img: LumaImage, streamPxPerLumaPx: Double) {
        val newest = latest?.timestampNs
        if (newest != null && newest - timestampNs > config.lateLumaNs) {
            lateLumas++
            return
        }
        val frame = LumaFrame(timestampNs, img, streamPxPerLumaPx)
        lumas.add(frame)
        afterReads(newest ?: timestampNs)
        recent.lastOrNull { it.first.timestampNs == timestampNs }?.let { (r, moving) -> trackFrame(r, moving, frame) }
        cached = null
    }

    /** After an association: patches re-captured at the new reads (only while COUNTING), and new units start a burst */
    private fun afterReads(nowNs: Long) {
        val t = machine.section?.table
        if (machine.state == SectionState.COUNTING && t != null) tracks.capture(t, lumas) else tracks.clear()
        schedule.onUnits(t, nowNs)
    }

    /** Follows the units' patches into [r], whose luma copy is [luma] (spec 5.9) */
    private fun trackFrame(r: PoseRecord, moving: Boolean, luma: LumaFrame) {
        val t = machine.section?.table ?: return
        if (machine.state != SectionState.COUNTING) return
        // frames are tracked in timestamp order: a luma copy older than a frame already tracked comes too late
        if (r.timestampNs <= trackedThroughNs) return
        trackedThroughNs = r.timestampNs
        val start = System.nanoTime()
        tracks.track(t, r, luma, moving)
        trackNanos += System.nanoTime() - start
        trackFrames++
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
            desiredRefreshMs = schedule.desiredMs(lumas.fed && counting && t!!.units.isNotEmpty(), r.timestampNs),
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
     * pitch or whose patch is tracked, at its warped quad or its tracked position; a unit added by hand at its point
     * on the plane. Each carries its anchor-frame point and metric size for the app to project at its own frame.
     */
    private fun markers(t: UnitTable, r: PoseRecord): List<Marker> {
        val k = r.intrinsics
        val tac = r.cameraInAnchor()
        return t.units.mapNotNull { u ->
            if (u.state == UnitState.MANUAL) {
                val (pu, pv) = Prediction.pixel(u.point, tac, k) ?: return@mapNotNull null
                val size = t.pitchPx(k.fx, Prediction.cameraDepth(u.point, tac)) / k.width
                return@mapNotNull Marker(u.id, u.state, pu / k.width, pv / k.height, size, u.point, t.pitch)
            }
            if (r.timestampNs - u.lastReadNs > config.markerMaxAgeNs) return@mapNotNull null
            val p = t.predict(u, r) ?: return@mapNotNull null
            val tracked = tracks.anchorPoint(u)
            if (tracked == null && p.sigmaPx > config.markerSigmaPitchFraction * t.pitchPx(k.fx, p.z)) return@mapNotNull null
            val quad = warp(u, tac, k) ?: return@mapNotNull null
            val width = hypot(quad[1].first - quad[0].first, quad[1].second - quad[0].second)
            val (cu, cv) = tracked?.let { Prediction.pixel(it, tac, k) } ?: (quad.sumOf { it.first } / 4 to quad.sumOf { it.second } / 4)
            Marker(u.id, u.state, cu / k.width, cv / k.height, width / k.width, tracked ?: u.point, sizeM(u))
        }
    }

    /** The symbol's width in metres: its last quad's tl → tr length at the unit's camera depth then */
    private fun sizeM(u: CountUnit): Double {
        val read = u.lastRead ?: return 0.0
        val last = u.lastRecord ?: return 0.0
        return read.widthPx * Prediction.cameraDepth(u.point, last.cameraInAnchor()) / last.intrinsics.fx
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
