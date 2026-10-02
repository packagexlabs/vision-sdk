package io.packagex.arcount

import io.packagex.arcount.SectionState.CLOSED
import io.packagex.arcount.SectionState.COUNTING
import io.packagex.arcount.SectionState.FROZEN
import io.packagex.arcount.SectionState.IDLE
import io.packagex.arcount.SectionState.OPEN
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * A resume attempt (spec 5.1): the label re-read where it was opens a window for re-reading two COUNTED units; in item
 * mode (spec 5.10) the first read of a listed code opens it, and [label] is null
 */
internal class ResumeAttempt(val startNs: Long, val label: Read?, val record: PoseRecord) {
    val reread = HashSet<Int>()

    /** Item mode: the pose corrections tried (ruling R2) */
    var hypotheses: List<Hypothesis> = emptyList()
    var tieNoted = false
}

/** Ruling R1: the anchor an item section asked for at its reach, with the view it was asked from, until its first record */
internal class Handoff(val request: AnchorRequest, val view: Vec3) {
    var created = false
}

/**
 * One section (spec 5.1): one SKU location with its label, GTIN set, anchor, units and breaks. An item section (spec
 * 5.10, [items]) has no label: its code set is the item list, and it holds every listed code's units.
 */
class Section internal constructor(id: String, val labelPayload: String?, gtins: Set<String>, openedNs: Long, maxRays: Int, val items: Boolean = false) {
    /** An item section takes the next id at each anchor handoff (spec 5.10, ruling R1) */
    var id = id
        internal set
    var openedNs = openedNs
        internal set
    val labelled get() = labelPayload != null
    var gtins = gtins
        internal set

    /** The units, from the moment the anchor is live */
    var table: UnitTable? = null
        internal set

    /** The anchor's latest pose in the world */
    var anchor: Pose? = null
        internal set
    val breaks: List<Pair<Long, BreakReason>> get() = breakList

    /** Numbers the unbroken tracking segments: a resume starts the next */
    var segment = 1
        internal set
    var manualAdded = 0
        internal set
    var manualRemoved = 0
        internal set
    var rangeAccepted = false
        internal set

    internal val breakList = ArrayList<Pair<Long, BreakReason>>()
    internal var request: AnchorRequest? = null
    internal var anchorCreated = false
    internal var seeded = false
    internal var seedRay: Ray? = null
    internal var seedPoint = Vec3.ZERO
    internal var view = Vec3(0.0, 0.0, -1.0)
    internal var lastInSectionNs = openedNs
    internal var quietSinceNs = openedNs
    internal var lastSectionReadNs = openedNs
    internal var attempt: ResumeAttempt? = null

    /** Capture times before which a late batch of reads changes nothing: the anchor's first pose, the last break, this segment's start */
    internal var liveSinceNs = Long.MAX_VALUE
    internal var frozenAtNs = Long.MIN_VALUE
    internal var segmentStartNs = Long.MIN_VALUE
    internal val labelTrack = DepthTrack(maxRays)

    /** Ruling R2: the correction applied to every pose this section sees, from a jump-compensated resume */
    internal var fix: Pose? = null

    /** Ruling R2: the correction measured from the world jumps since the last resume, relative to the poses the section sees */
    internal var pending: Pose? = null
    internal var handoff: Handoff? = null
}

/**
 * The section state machine of spec 5.1: IDLE → OPEN → COUNTING ⇄ FROZEN → CLOSED, the start-up guard, the anchor,
 * the break conditions, the resume rule, unlabelled sections and the trigger. One section at a time; nothing is
 * carried from one section, or one break, to the next.
 */
class SectionMachine(private val config: CountConfig = CountConfig()) {
    var state = IDLE
        private set

    /** The open section (OPEN, COUNTING, FROZEN); null in IDLE and CLOSED */
    var section: Section? = null
        private set
    val closed: List<SectionResult> get() = results
    var lastRecord: PoseRecord? = null
        private set
    val events: List<String> get() = log.toList()

    /** Batches of reads dropped because they were captured before the section's state last changed (a break, the anchor, a resume) */
    var staleBatches = 0
        private set

    private val results = ArrayList<SectionResult>()
    private val log = ArrayDeque<String>()
    private val recent = LinkedHashMap<Long, List<Read>>()
    private var resumedAtNs: Long? = null
    private var trackingSinceNs: Long? = null
    private var nextSection = 1
    internal var itemList = ItemList(emptyList())
        private set
    internal val itemTotals = ItemTotals()

    /** AR Item Count (spec 5.10): on while the item list is not empty */
    val itemMode get() = !itemList.isEmpty()

    /** The start-up guard: TRACKING for 2 s and 5 s since resume() (the first frame when there was none) */
    fun guardHolds(nowNs: Long): Boolean {
        val tracking = trackingSinceNs ?: return true
        val resumed = resumedAtNs ?: return true
        return nowNs - tracking < config.guardTrackingNs || nowNs - resumed < config.guardSinceResumeNs
    }

    /** ARCore resumed: the guard starts again; a counting section freezes, as nothing tells how far it moved meanwhile */
    fun onResume(timestampNs: Long) {
        resumedAtNs = timestampNs
        val s = section ?: return
        if (state == COUNTING) freeze(s, timestampNs, BreakReason.FRAME_NOT_TRACKING)
    }

    fun onFrame(raw: PoseRecord) {
        if (resumedAtNs == null) resumedAtNs = raw.timestampNs
        trackingSinceNs = if (raw.frameTracking == Tracking.TRACKING) trackingSinceNs ?: raw.timestampNs else null
        var prevRaw = lastRecord
        lastRecord = raw
        val h = section?.handoff
        if (h != null && isSwitch(h, raw, prevRaw)) {
            switchAnchor(section!!, h, raw, prevRaw)
            prevRaw = null
        }
        val s = section ?: return
        val r = corrected(s, raw)
        val prev = prevRaw?.let { corrected(s, it) }
        when (state) {
            OPEN -> openFrame(s, r)
            COUNTING -> countingFrame(s, r, prev)
            FROZEN -> frozenFrame(s, r, prev)
            else -> Unit
        }
    }

    /** [r] as the open section sees it: with the correction of a jump-compensated resume (ruling R2) */
    fun corrected(r: PoseRecord): PoseRecord = section?.let { corrected(it, r) } ?: r

    private fun corrected(s: Section, r: PoseRecord) = JumpResume.corrected(r, s.fix)

    /** The raw reads of a capture as they arrive, for the jump test's picture motion */
    fun noteReads(timestampNs: Long, reads: List<Read>) {
        recent[timestampNs] = reads
        while (recent.size > 8) recent.remove(recent.keys.first())
    }

    fun onReads(paired: Paired) {
        val s = section
        val r = if (s == null) paired.record else corrected(s, paired.record)
        when {
            s == null -> idleReads(r, paired.reads)
            state == OPEN -> openReads(s, r, paired.reads)
            state == COUNTING -> countingReads(s, r, paired.reads)
            state == FROZEN -> frozenReads(s, r, paired.reads)
        }
    }

    fun onCommand(command: Command, timestampNs: Long) {
        val s = section
        when (command) {
            // item mode opens no section by trigger (spec 5.10): a section opens on a listed read
            Command.TriggerShort -> when {
                s == null -> if (!itemMode && !guardHolds(timestampNs)) openUnlabelled(timestampNs)
                state == COUNTING -> note("${s.id}: still-burst")
                else -> Unit
            }
            Command.TriggerLong -> when {
                itemMode -> Unit
                s == null -> if (!guardHolds(timestampNs)) openUnlabelled(timestampNs)
                state == COUNTING -> {
                    close(s, timestampNs, statusOf(s))
                    openUnlabelled(timestampNs)
                }
                else -> Unit
            }
            Command.Finish -> when {
                s == null -> Unit
                state == COUNTING -> close(s, timestampNs, statusOf(s))
                state == FROZEN -> close(s, timestampNs, SectionStatus.CLOSED_FROZEN)
                else -> {
                    note("${s.id}: dropped by Finish before a unit was read")
                    section = null
                    state = IDLE
                }
            }
            Command.Restart -> if (s != null && (state == FROZEN || (state == COUNTING && unresolved(s)))) restart(s, timestampNs)
            Command.AcceptRange -> if (s != null && state == COUNTING && unresolved(s)) {
                s.rangeAccepted = true
                note("${s.id}: range accepted")
            }
            Command.RemoveManualUnit -> if (s != null && state == COUNTING && s.table?.removeLastManual() == true) s.manualRemoved++
            Command.AddUnit, is Command.FillGap -> Unit
        }
    }

    /** A unit added by hand at [point] (anchor frame) while counting; the core decides where (gap, extent's end) */
    fun addManual(point: Vec3, timestampNs: Long): Boolean {
        val s = section ?: return false
        val t = s.table ?: return false
        if (state != COUNTING) return false
        t.addManual(point, lastRecord?.cameraInAnchor()?.t ?: Vec3.ZERO, timestampNs)
        s.manualAdded++
        return true
    }

    /**
     * The item list (spec 5.10). Within item mode the open section takes the new list at once: a removed code's units
     * stay in it but are read no more, an added code counts from its next read. Turning item mode on or off ends the
     * open section as Finish would.
     */
    fun setItems(codes: Collection<String>) {
        val next = ItemList(codes)
        itemList = next
        note("items: ${next.keys}")
        val s = section ?: return
        if (s.items != itemMode) {
            val ts = lastRecord?.timestampNs ?: s.openedNs
            when (state) {
                COUNTING -> close(s, ts, statusOf(s))
                FROZEN -> close(s, ts, SectionStatus.CLOSED_FROZEN)
                else -> {
                    note("${s.id}: dropped, item mode changed before a unit was read")
                    section = null
                    state = IDLE
                }
            }
            return
        }
        if (!s.items) return
        s.gtins = s.gtins + next.keys
        s.table?.frame?.gtins = next.keys
    }

    fun anchorRequest(): AnchorRequest? = section?.let { s -> s.handoff?.takeIf { !it.created }?.request ?: s.request }

    fun onAnchorCreated(ok: Boolean) {
        val s = section ?: return
        val h = s.handoff
        if (h != null && !h.created) {
            if (ok) {
                h.created = true
            } else {
                s.handoff = null
                note("${s.id}: the handoff anchor was not created")
                if (state == COUNTING) close(s, lastRecord?.timestampNs ?: s.openedNs, statusOf(s))
            }
            return
        }
        if (s.request == null) return
        s.request = null
        if (ok) {
            s.anchorCreated = true
        } else {
            note("${s.id}: anchor not created, back to IDLE")
            section = null
            state = IDLE
        }
    }

    private fun openFrame(s: Section, r: PoseRecord) {
        if (s.anchorCreated && s.table == null && r.anchor != null) goLive(s, r)
        if (s.table != null && r.anchorTracking == Tracking.STOPPED) {
            note("${s.id}: anchor stopped while open")
            openAgain(s, r.timestampNs)
            return
        }
        if (s.items && s.table != null && r.anchor != null && beyondReach(r)) {
            note("${s.id}: camera beyond the section's reach before a unit was read, back to IDLE")
            section = null
            state = IDLE
            return
        }
        if (r.timestampNs - s.openedNs > config.openTimeoutNs) {
            note("${s.id}: no unit within ${config.openTimeoutNs / 1_000_000_000} s, back to IDLE")
            section = null
            state = IDLE
        }
    }

    /** The anchor's first pose: the plane, label and rays move into its frame */
    private fun goLive(s: Section, r: PoseRecord) {
        val a = r.anchor ?: return
        val inv = a.inverse()
        val point = inv.apply(s.seedPoint)
        val plane = SectionPlane.vertical(inv.rotate(s.view), inv.rotate(Vec3(0.0, 1.0, 0.0)), point)
        val seed = s.seedRay
        val frame = if (s.labelled && seed != null) {
            val ray = Ray(inv.apply(seed.origin), inv.rotate(seed.dir))
            s.labelTrack.add(ray)
            SectionFrame(plane, s.gtins, ray, point)
        } else if (s.items) {
            SectionFrame(plane, itemList.keys, keyOf = ItemCode::key)
        } else {
            SectionFrame(plane, s.gtins)
        }
        s.table = UnitTable(config, frame)
        s.anchor = a
        s.liveSinceNs = r.timestampNs
        s.segmentStartNs = r.timestampNs
        note("${s.id}: anchor live")
    }

    private fun countingFrame(s: Section, r: PoseRecord, prev: PoseRecord?) {
        val ts = r.timestampNs
        when {
            r.frameTracking != Tracking.TRACKING -> freeze(s, ts, BreakReason.FRAME_NOT_TRACKING)
            r.anchorTracking == Tracking.STOPPED -> {
                note("${s.id}: anchor stopped")
                restart(s, ts)
            }
            r.anchor == null || r.anchorTracking != Tracking.TRACKING -> freeze(s, ts, BreakReason.ANCHOR_NOT_TRACKING)
            prev?.anchor != null && jumped(prev, r) -> {
                if (s.items) s.pending = JumpResume.compose(s.pending, JumpResume.step(prev, r))
                freeze(s, ts, BreakReason.WORLD_JUMP)
            }
            else -> {
                s.anchor = r.anchor
                val t = s.table ?: return
                // spec 5.10: leaving the view and silence are not breaks in item mode; beyond the reach the section
                // asks for a new anchor near the camera and hands its units over to it (ruling R1)
                if (s.items) {
                    if (beyondReach(r) && s.handoff == null) requestHandoff(s, r)
                    return
                }
                if (elementInImage(s, t, r)) s.lastInSectionNs = ts
                if (t.units.none { it.state != UnitState.MANUAL && t.predict(it, r)?.inImage(r.intrinsics) == true }) s.quietSinceNs = ts
                when {
                    ts - s.lastInSectionNs > config.leftSectionNs -> freeze(s, ts, BreakReason.LEFT_SECTION)
                    ts - max(s.quietSinceNs, s.lastSectionReadNs) > config.silenceNs -> freeze(s, ts, BreakReason.SILENCE)
                }
            }
        }
    }

    /**
     * A world jump (spec 5.1), on T_ac between consecutive frames: a camera-centre step over [CountConfig.jumpStep];
     * or over max(jumpSpeed · Δt, jumpMinStep) unless the picture is known to have moved with it.
     */
    private fun jumped(prev: PoseRecord, r: PoseRecord): Boolean {
        val step = (r.cameraInAnchor().t - prev.cameraInAnchor().t).norm()
        if (step > config.jumpStep) return true
        val dt = (r.timestampNs - prev.timestampNs) / 1e9
        if (step <= max(config.jumpSpeed * dt, config.jumpMinStep)) return false
        return !pictureMoved(prev.timestampNs, r.timestampNs)
    }

    /** Whether the codes read in both frames (same engine id and text) all moved [CountConfig.jumpStillPx] or more; false without one */
    private fun pictureMoved(a: Long, b: Long): Boolean {
        val before = recent[a] ?: return false
        val after = recent[b] ?: return false
        val moves = after.mapNotNull { r ->
            before.firstOrNull { it.engineId == r.engineId && it.text == r.text }?.let { hypot(r.centreU - it.centreU, r.centreV - it.centreV) }
        }
        return moves.isNotEmpty() && moves.all { it >= config.jumpStillPx }
    }

    /** Whether the label, or a COUNTED, TENTATIVE or AMBIGUOUS unit (ruling R4), is predicted inside the image */
    private fun elementInImage(s: Section, t: UnitTable, r: PoseRecord): Boolean {
        val k = r.intrinsics
        val label = t.frame.labelPoint
        if (s.labelled && label != null) {
            val p = Prediction.pixel(label, r.cameraInAnchor(), k)
            if (p != null && p.first >= 0 && p.second >= 0 && p.first < k.width && p.second < k.height) return true
        }
        return t.units.any { it.state != UnitState.MANUAL && t.predict(it, r)?.inImage(k) == true }
    }

    private fun frozenFrame(s: Section, r: PoseRecord, prev: PoseRecord?) {
        if (r.anchor != null) s.anchor = r.anchor
        if (r.anchorTracking == Tracking.STOPPED) {
            note("${s.id}: anchor stopped")
            restart(s, r.timestampNs)
            return
        }
        // review I1: the break conditions keep running while FROZEN. Each one moves the stale cut-off, so nothing
        // captured before it can open or complete a resume; inside the resume window it also cancels the attempt
        val reason = when {
            r.frameTracking != Tracking.TRACKING -> BreakReason.FRAME_NOT_TRACKING
            r.anchor == null || r.anchorTracking != Tracking.TRACKING -> BreakReason.ANCHOR_NOT_TRACKING
            prev?.anchor != null && prev.frameTracking == Tracking.TRACKING && jumped(prev, r) -> BreakReason.WORLD_JUMP
            else -> null
        }
        val at = s.attempt
        if (reason == BreakReason.WORLD_JUMP && s.items) s.pending = JumpResume.compose(s.pending, JumpResume.step(prev!!, r))
        if (reason != null) {
            if (at != null) {
                note("${s.id}: $reason inside the resume window, attempt cancelled")
                freeze(s, r.timestampNs, reason)
            } else {
                if (reason == BreakReason.WORLD_JUMP) note("${s.id}: WORLD_JUMP while frozen")
                s.frozenAtNs = r.timestampNs
            }
            return
        }
        if (s.items && beyondReach(r)) {
            failResume(s, r.timestampNs, null, null, "the camera went beyond the section's reach")
            return
        }
        if (at != null && r.timestampNs - at.startNs > config.resumeWindowNs) {
            val tried = if (at.hypotheses.isEmpty()) "" else "; candidates " + at.hypotheses.joinToString { "${it.pitches}: ${it.reread.size}" + if (it.ruledOut) " ruled out" else "" }
            failResume(s, r.timestampNs, at.label, at.record, "two counted units not re-read within the window$tried")
        }
    }

    /** Item mode (spec 5.10): the camera centre farther than [CountConfig.sectionReach] from the section anchor */
    private fun beyondReach(r: PoseRecord) = r.cameraInAnchor().t.norm() > config.sectionReach

    private fun idleReads(r: PoseRecord, reads: List<Read>) {
        if (guardHolds(r.timestampNs) || r.frameTracking != Tracking.TRACKING) return
        if (itemMode) {
            reads.firstOrNull { !it.touchesBorder && ItemCode.key(it) in itemList.keys }?.let { openItem(it, r) }
            return
        }
        aimed(reads.filter { isLabel(it, r, null) }, r)?.let { openLabelled(it, r, r.timestampNs) }
    }

    private fun openReads(s: Section, r: PoseRecord, reads: List<Read>) {
        val ts = r.timestampNs
        if (stale(s, ts, if (s.table == null) s.openedNs else s.liveSinceNs)) return
        if (r.frameTracking != Tracking.TRACKING) return
        val labels = if (s.items) emptyList() else reads.filter { isLabel(it, r, s) }
        val units = reads.filterNot { it in labels }
        if (s.labelled) {
            val own = labels.firstOrNull { it.text == s.labelPayload && !it.touchesBorder }
            if (own != null && !s.seeded && !guardHolds(ts)) seed(s, own, r)
            if (own != null && s.table != null) refineLabel(s, own, r)
        } else if (!s.seeded) {
            if (guardHolds(ts)) return
            val first = units.firstOrNull { !it.touchesBorder } ?: return
            s.gtins = setOf(Gtin.normalize(first.text, first.symbology))
            seed(s, first, r)
            return
        }
        val t = s.table ?: return
        if (r.anchor == null || r.anchorTracking != Tracking.TRACKING) return
        if (s.gtins.isEmpty()) {
            val first = units.firstOrNull { nearLabel(it, r, t) } ?: return
            s.gtins = setOf(Gtin.normalize(first.text, first.symbology))
            t.frame.gtins = s.gtins
            note("${s.id}: GTIN set from the first unit by the label, ${s.gtins}")
        }
        t.associate(r, units, s.segment)
        if (t.units.isEmpty()) return
        state = COUNTING
        s.lastInSectionNs = ts
        s.quietSinceNs = ts
        s.lastSectionReadNs = ts
        note("${s.id}: COUNTING")
    }

    private fun countingReads(s: Section, r: PoseRecord, reads: List<Read>) {
        if (stale(s, r.timestampNs, s.segmentStartNs)) return
        if (r.frameTracking != Tracking.TRACKING || r.anchor == null || r.anchorTracking != Tracking.TRACKING) return
        val labels = if (s.items) emptyList() else reads.filter { isLabel(it, r, s) }
        nextLabel(s, labels, r)?.let {
            close(s, r.timestampNs, statusOf(s))
            openLabelled(it, r, r.timestampNs)
            return
        }
        if (s.labelled) labels.firstOrNull { it.text == s.labelPayload && !it.touchesBorder }?.let { refineLabel(s, it, r) }
        val out = s.table?.associate(r, reads.filterNot { it in labels }, s.segment) ?: return
        if (out.accepted > 0) s.lastSectionReadNs = max(s.lastSectionReadNs, r.timestampNs)
    }

    /**
     * A batch captured before [since] (the anchor's first pose, the last break, or the start of this segment) was decoded
     * from a pose the section no longer stands on: it is dropped and counted, so it can neither resume nor change a unit.
     */
    private fun stale(s: Section, captureNs: Long, since: Long): Boolean {
        if (captureNs >= since) return false
        staleBatches++
        note("${s.id}: reads captured before the section's last change of state dropped")
        return true
    }

    private fun frozenReads(s: Section, r: PoseRecord, reads: List<Read>) {
        val ts = r.timestampNs
        if (stale(s, ts, s.frozenAtNs)) return
        if (guardHolds(ts) || r.frameTracking != Tracking.TRACKING || r.anchor == null || r.anchorTracking != Tracking.TRACKING) return
        if (s.items) {
            itemResume(s, r, reads)
            return
        }
        val labels = reads.filter { isLabel(it, r, s) }
        nextLabel(s, labels, r)?.let {
            close(s, ts, SectionStatus.CLOSED_FROZEN)
            openLabelled(it, r, ts)
            return
        }
        if (!s.labelled) return
        val t = s.table ?: return
        val own = labels.firstOrNull { it.text == s.labelPayload && !it.touchesBorder }
        val open = s.attempt
        // only reads captured at or after the window's label read count for it (review I1)
        if (open != null && ts < open.startNs) return
        if (own != null && !labelWhereItWas(t, own, r)) {
            failResume(s, ts, own, r, if (open == null) "the label was read away from where it was" else "the label moved inside the resume window")
            return
        }
        if (open == null) {
            if (own == null) return
            s.attempt = ResumeAttempt(ts, own, r)
            note("${s.id}: label where it was, resume window open")
        }
        val at = s.attempt ?: return
        val check = t.check(r, reads.filterNot { it in labels })
        if (check.inBand) {
            failResume(s, ts, at.label, at.record, "a read fell in an ambiguity band")
            return
        }
        at.reread += check.countedReread
        if (at.reread.size >= config.resumeMinUnits) resume(s, ts)
    }

    /**
     * Item mode (spec 5.10): the first read of a listed code opens the resume window, captured at or after the
     * break's cut-off; two COUNTED units re-read inside their gates within it resume, with no read in an ambiguity band.
     */
    private fun itemResume(s: Section, r: PoseRecord, reads: List<Read>) {
        val ts = r.timestampNs
        val t = s.table ?: return
        val open = s.attempt
        if (open != null && ts < open.startNs) return
        if (open == null) {
            if (reads.none { !it.touchesBorder && t.frame.keyOf(it) in t.frame.gtins }) return
            val base = s.pending
            s.attempt = ResumeAttempt(ts, null, r).also {
                it.hypotheses = JumpResume.hypotheses(base ?: Pose.IDENTITY, t.pitch, t.frame.plane.shelfAxis, aliases = base != null)
            }
            note("${s.id}: listed code read, resume window open" + if (base != null) ", trying the jump's correction and its aliases" else "")
        }
        val at = s.attempt ?: return
        when (val v = JumpResume.evaluate(at.hypotheses, t, r, reads, config.resumeMinUnits, anyBand = at.hypotheses.size == 1, tied = at.tieNoted)) {
            JumpResume.Verdict.AllRuledOut -> failResume(s, ts, null, null, "a read fell in an ambiguity band or on a unit of another code")
            JumpResume.Verdict.Tie -> if (!at.tieNoted) {
                at.tieNoted = true
                note("${s.id}: a tie between ${at.hypotheses.filter { !it.ruledOut && it.reread.size >= config.resumeMinUnits }.map { it.pitches }} pitches, no resume")
            }
            JumpResume.Verdict.Wait -> Unit
            is JumpResume.Verdict.Resume -> {
                if (s.pending != null) {
                    s.fix = v.with.correction * (s.fix ?: Pose.IDENTITY)
                    note("${s.id}: resumed with the jump's correction, alias ${v.with.pitches}")
                }
                s.pending = null
                resume(s, ts)
            }
        }
    }

    /** Ruling R1: an anchor 0.40 m along the camera's optical axis, gravity-aligned, facing the camera */
    private fun requestHandoff(s: Section, r: PoseRecord) {
        val view = r.camera.rotate(Vec3(0.0, 0.0, -1.0))
        s.handoff = Handoff(AnchorRequest(anchorPose(r.camera.t + view * config.anchorDepth, view)), view)
        note("${s.id}: camera beyond the section's reach, new anchor asked for")
    }

    /** The first record carrying the handoff anchor: it names the anchor it replaced, or lies nearer the asked-for pose than the old one */
    private fun isSwitch(h: Handoff, raw: PoseRecord, prevRaw: PoseRecord?): Boolean {
        val a = raw.anchor ?: return false
        if (raw.previousAnchor != null) return true
        if (!h.created) return false
        val old = prevRaw?.anchor ?: return true
        return (a.t - h.request.world.t).norm() < (a.t - old.t).norm()
    }

    /**
     * Ruling R1: the open item section moves to the new anchor. Its units within the reach of that anchor go into its
     * frame by T_new_old = A_new⁻¹ · A_old · fix⁻¹, both anchors taken from one record (the old one from the record
     * before when the app did not report it), and keep their ids; the units left behind close as the old section,
     * COMPLETE or UNRESOLVED. A section that froze meanwhile is abandoned, and a new one starts on the new anchor.
     */
    private fun switchAnchor(s: Section, h: Handoff, raw: PoseRecord, prevRaw: PoseRecord?) {
        s.handoff = null
        val ts = raw.timestampNs
        val aNew = raw.anchor!!
        val aOld = raw.previousAnchor ?: prevRaw?.anchor
        if (raw.previousAnchor == null) note("${s.id}: the record has no previous anchor, the old anchor's last pose is used")
        val t = s.table
        if (state != COUNTING || t == null || aOld == null) {
            if (t != null && state != OPEN) close(s, ts, SectionStatus.ABANDONED)
            val n = Section("S${nextSection++}", null, itemList.keys, ts, config.maxRays, items = true)
            n.seeded = true
            n.anchorCreated = true
            n.seedPoint = h.request.world.t
            n.view = h.view
            section = n
            state = OPEN
            note("${n.id}: OPEN on the handoff anchor after ${s.id}")
            return
        }
        val tno = aNew.inverse() * aOld * (s.fix ?: Pose.IDENTITY).inverse()
        val left = t.reanchor(tno, config.sectionReach)
        val counts = left.counts()
        val status = if (counts.tentative + counts.ambiguous > 0) SectionStatus.UNRESOLVED else SectionStatus.COMPLETE
        results += sectionResult(s.id, null, s.gtins, status, counts, 0, 0, s.breaks.toList(), (ts - s.openedNs) / 1_000_000)
        itemTotals.add(left.countsByCode())
        val next = "S${nextSection++}"
        note("${s.id}: $status, ${counts.low}..${counts.high}, handed over to $next")
        s.id = next
        s.openedNs = ts
        s.anchor = aNew
        s.fix = null
        s.pending = null
        s.attempt = null
        s.breakList.clear()
        s.liveSinceNs = ts
        s.segmentStartNs = ts
    }

    private fun resume(s: Section, ts: Long) {
        s.attempt = null
        s.segment++
        s.segmentStartNs = ts
        s.lastInSectionNs = ts
        s.quietSinceNs = ts
        s.lastSectionReadNs = ts
        state = COUNTING
        note("${s.id}: resumed, count kept")
    }

    /** Resume condition (i): the label's read within a quarter pitch_px of X_label projected through the current T_ac */
    private fun labelWhereItWas(t: UnitTable, l: Read, r: PoseRecord): Boolean {
        val x = t.frame.labelPoint ?: return false
        val tac = r.cameraInAnchor()
        val (u, v) = Prediction.pixel(x, tac, r.intrinsics) ?: return false
        return hypot(l.centreU - u, l.centreV - v) <= config.resumeLabelGate * t.pitchPx(r.intrinsics.fx, Prediction.cameraDepth(x, tac))
    }

    /** Closes [s] as ABANDONED; a labelled one opens again at [label], an item section waits for the next listed read */
    private fun failResume(s: Section, ts: Long, label: Read?, labelRecord: PoseRecord?, why: String) {
        note("${s.id}: resume failed, $why")
        close(s, ts, SectionStatus.ABANDONED)
        if (label != null && labelRecord != null) openLabelled(label, labelRecord, ts)
    }

    /**
     * Label recognition (spec 5.1): the host's predicate; else, in a labelled section with a live anchor, a read in
     * the rail band whose symbol is under 25 mm wide at its depth on the plane; else under 25 mm wide at 0.40 m.
     */
    private fun isLabel(read: Read, r: PoseRecord, s: Section?): Boolean {
        config.isLabel?.let { return it(read) }
        val f = r.intrinsics.fx
        val t = s?.table
        val h = t?.frame?.labelHeight
        if (t != null && h != null && r.anchor != null) {
            val p = t.frame.plane.intersect(read.ray(r)) ?: return false
            val z = Prediction.cameraDepth(p, r.cameraInAnchor())
            return abs(t.frame.plane.height(p) - h) <= config.railHalfHeight && read.widthPx * z / f < config.labelMaxWidth
        }
        return read.widthPx * config.labelAssumedDepth / f < config.labelMaxWidth
    }

    /** The label read aimed at: the most central one, inside the middle [CountConfig.labelAimFraction] of the width */
    private fun aimed(labels: List<Read>, r: PoseRecord): Read? {
        val k = r.intrinsics
        return labels.filter { !it.touchesBorder && abs(it.centreU - k.cx) <= k.width * config.labelAimFraction / 2 }.minByOrNull { abs(it.centreU - k.cx) }
    }

    /** Another section's label, aimed at and nearer the image centre than this section's own label */
    private fun nextLabel(s: Section, labels: List<Read>, r: PoseRecord): Read? {
        val next = aimed(labels.filter { !s.labelled || it.text != s.labelPayload }, r) ?: return null
        val own = s.table?.frame?.labelPoint?.takeIf { s.labelled }?.let { Prediction.pixel(it, r.cameraInAnchor(), r.intrinsics) }
        if (own != null && abs(own.first - r.intrinsics.cx) < abs(next.centreU - r.intrinsics.cx)) return null
        return next
    }

    /** Before the GTIN set is known: a read outside the rail band within one pitch of the label along the shelf */
    private fun nearLabel(read: Read, r: PoseRecord, t: UnitTable): Boolean {
        if (read.touchesBorder) return false
        val p = t.frame.plane.intersect(read.ray(r)) ?: return false
        val label = t.frame.labelPoint ?: return true
        val h = t.frame.labelHeight
        if (h != null && abs(t.frame.plane.height(p) - h) <= config.railHalfHeight) return false
        return abs(t.frame.plane.along(p) - t.frame.plane.along(label)) <= config.defaultPitch
    }

    private fun refineLabel(s: Section, l: Read, r: PoseRecord) {
        val t = s.table ?: return
        if (r.anchor == null) return
        val ray = l.ray(r)
        s.labelTrack.add(ray)
        t.frame.labelRay = ray
        val fit = s.labelTrack.fit(config.sigmaRayPx / r.intrinsics.fx, config)
        if (fit?.gate != null) t.frame.labelPoint = fit.point
        t.replane()
        s.lastSectionReadNs = r.timestampNs
    }

    private fun openLabelled(l: Read, r: PoseRecord, openedNs: Long) {
        val payload = l.text
        val gtins = config.gtinsOfLabel?.invoke(payload)?.map { Gtin.normalize(it) }?.toSet()
            ?: if (Gtin.isGtin(payload)) setOf(Gtin.normalize(payload, l.symbology)) else emptySet()
        val s = Section("S${nextSection++}", payload, gtins, openedNs, config.maxRays)
        section = s
        state = OPEN
        seed(s, l, r)
        note("${s.id}: OPEN at label $payload, GTINs $gtins")
    }

    private fun openUnlabelled(ts: Long) {
        val s = Section("S${nextSection++}", null, emptySet(), ts, config.maxRays)
        section = s
        state = OPEN
        note("${s.id}: OPEN, unlabelled")
    }

    /** Item mode (spec 5.10): a section opens on the first read of a listed code, its anchor on that read's ray */
    private fun openItem(read: Read, r: PoseRecord) {
        val s = Section("S${nextSection++}", null, itemList.keys, r.timestampNs, config.maxRays, items = true)
        section = s
        state = OPEN
        seed(s, read, r)
        note("${s.id}: OPEN at listed code ${read.text}")
    }

    /** OPEN again after an abandonment: same label and GTINs (none for an unlabelled section), waiting for its seed read */
    private fun openAgain(old: Section, ts: Long) {
        if (old.items) {
            section = null
            state = if (state == OPEN) IDLE else CLOSED
            return
        }
        val s = Section("S${nextSection++}", old.labelPayload, if (old.labelled) old.gtins else emptySet(), ts, config.maxRays)
        section = s
        state = OPEN
        note("${s.id}: OPEN again after ${old.id}")
    }

    /** The anchor request: 0.40 m along the seed read's world ray, gravity-aligned, its +Z facing the camera */
    private fun seed(s: Section, read: Read, r: PoseRecord) {
        val ray = read.ray(r, anchor = null)
        val view = r.camera.rotate(Vec3(0.0, 0.0, -1.0))
        val point = ray.at(config.anchorDepth)
        s.seedRay = ray
        s.seedPoint = point
        s.view = view
        s.seeded = true
        s.request = AnchorRequest(anchorPose(point, view))
    }

    /** Gravity-aligned at [point], its +Z facing a camera looking along [view] */
    private fun anchorPose(point: Vec3, view: Vec3): Pose {
        val yaw = if (view.x * view.x + view.z * view.z < 1e-12) 0.0 else atan2(-view.x, -view.z)
        return Pose(point, Quat.axisAngle(Vec3(0.0, 1.0, 0.0), yaw))
    }

    private fun restart(s: Section, ts: Long) {
        close(s, ts, SectionStatus.ABANDONED)
        openAgain(s, ts)
    }

    private fun freeze(s: Section, ts: Long, reason: BreakReason) {
        if (s.handoff?.created == false) {
            s.handoff = null
            note("${s.id}: handoff cancelled by the break")
        }
        s.breakList += ts to reason
        s.frozenAtNs = ts
        s.attempt = null
        state = FROZEN
        note("${s.id}: FROZEN, $reason")
    }

    private fun close(s: Section, ts: Long, status: SectionStatus) {
        val counts = s.table?.counts() ?: Counts(0, 0, 0, 0)
        if (s.items) s.table?.let { itemTotals.add(it.countsByCode(), abandoned = status == SectionStatus.ABANDONED) }
        results += sectionResult(s.id, s.labelPayload, s.gtins, status, counts, s.manualAdded, s.manualRemoved, s.breaks.toList(), (ts - s.openedNs) / 1_000_000)
        section = null
        state = CLOSED
        note("${s.id}: $status, ${counts.low}..${counts.high}")
    }

    private fun unresolved(s: Section) = s.table?.counts()?.let { it.tentative + it.ambiguous > 0 } ?: false

    private fun statusOf(s: Section) = if (unresolved(s)) SectionStatus.UNRESOLVED else SectionStatus.COMPLETE

    private fun note(s: String) {
        log.addLast(s)
        if (log.size > 2000) log.removeFirst()
    }
}
