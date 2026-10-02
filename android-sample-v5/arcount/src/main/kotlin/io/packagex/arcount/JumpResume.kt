package io.packagex.arcount

/**
 * One pose correction a FROZEN item section tries (ruling R2): T_ac' = [correction] · T_ac, for the records the
 * section sees. [pitches] is its alias shift along the shelf axis; [reread] the COUNTED units re-read inside their
 * gates under it so far; [ruledOut] once a read contradicted it.
 */
internal class Hypothesis(val correction: Pose, val pitches: Int) {
    val reread = HashSet<Int>()
    var ruledOut = false
}

/**
 * Jump-compensated resume (ruling R2, spec 5.10). A WORLD_JUMP records the camera's apparent step in the anchor frame
 * across the frame pair that showed it; while FROZEN the section tries the correction that undoes it and its aliases
 * one and two pitches along the shelf; it resumes under the one candidate that re-reads two COUNTED units inside their
 * gates with every other read consistent, and never on a tie.
 */
internal object JumpResume {
    /** Alias shifts in pitches, the measured correction first */
    val ALIASES = listOf(0, -1, 1, -2, 2)

    /** The correction D with D · T_ac(r) = T_ac(prev): it undoes the apparent step between two consecutive records */
    fun step(prev: PoseRecord, r: PoseRecord): Pose = prev.cameraInAnchor() * r.cameraInAnchor().inverse()

    /** A further step [d], measured on the same records as [pending], composed onto it */
    fun compose(pending: Pose?, d: Pose): Pose = (pending ?: Pose.IDENTITY) * d

    /** [r] as seen under correction [c]: its anchor moved so that its T_ac is c · T_ac */
    fun corrected(r: PoseRecord, c: Pose?): PoseRecord {
        val a = r.anchor
        return if (c == null || a == null) r else r.copy(anchor = a * c.inverse())
    }

    /** The candidates: [base] and, when [aliases], its shifts by ±1 and ±2 [pitch] along [shelfAxis] (anchor frame) */
    fun hypotheses(base: Pose, pitch: Double, shelfAxis: Vec3, aliases: Boolean = true): List<Hypothesis> =
        (if (aliases) ALIASES else listOf(0)).map { k -> Hypothesis(Pose(shelfAxis * (k * pitch), Quat.IDENTITY) * base, k) }

    /** Ruling R5: the units that are the only COUNTED, TENTATIVE or AMBIGUOUS unit of their code in the section */
    fun uniqueUnits(t: UnitTable): Set<Int> =
        t.units.filter { it.state != UnitState.MANUAL }.groupBy { it.gtin }.values.filter { it.size == 1 }.map { it.single().id }.toSet()

    sealed interface Verdict {
        data class Resume(val with: Hypothesis) : Verdict

        data object AllRuledOut : Verdict

        data object Tie : Verdict

        data object Wait : Verdict
    }

    /**
     * One frame of the window: each candidate still standing checks [reads] under its correction. A read in the
     * ambiguity band of a COUNTED unit it did not match (with [anyBand], of any unit: the rule without a measured
     * jump), or a code read where a COUNTED unit of another code is predicted, rules it out; else its COUNTED re-reads
     * accumulate; a re-read of a unit whose code has no other unit in the section (COUNTED, TENTATIVE or AMBIGUOUS)
     * counts twice, as no alias can put another unit of that code under it (ruling R5). One candidate with [minUnits]
     * and no other with as many resumes. Ties never resume: once
     * two have had as many at once ([tied]), only the measured correction (no alias shift) may still resume, and only
     * once every other candidate is ruled out.
     */
    fun evaluate(hs: List<Hypothesis>, t: UnitTable, r: PoseRecord, reads: List<Read>, minUnits: Int, anyBand: Boolean = false, tied: Boolean = false): Verdict {
        for (h in hs.filterNot { it.ruledOut }) {
            val check = t.check(corrected(r, h.correction), reads)
            val band = if (anyBand) check.inBand else check.inCountedBand
            if (band || check.codeConflict) h.ruledOut = true else h.reread += check.countedReread
        }
        val standing = hs.filterNot { it.ruledOut }
        if (standing.isEmpty()) return Verdict.AllRuledOut
        val unique = uniqueUnits(t)
        val passing = standing.filter { h -> h.reread.sumOf { if (it in unique) 2 else 1 } >= minUnits }
        return when {
            passing.size > 1 -> Verdict.Tie
            passing.isEmpty() -> if (tied) Verdict.Tie else Verdict.Wait
            // after a tie only the measured correction may resume, once every rival is ruled out
            tied && passing.single().pitches != 0 -> Verdict.Tie
            else -> Verdict.Resume(passing.single())
        }
    }
}

/**
 * One world jump over consecutive frames (ruling R6): the correction is the composition of the per-frame corrections
 * of the frames the jump detector flags, consecutive from the first, within [CountConfig.jumpRunNs] of it; for pure
 * translations that is minus the sum of the camera's apparent displacements in the anchor frame.
 */
internal class JumpRun(private val startNs: Long, first: Pose) {
    enum class Step { ADDED, PAST_THE_RUN, NOT_CONSECUTIVE }

    var correction = first
        private set
    private var lastNs = startNs

    /** The flagged frame at [ts], whose previous frame is at [prevNs], with its correction [d] */
    fun extend(prevNs: Long, ts: Long, d: Pose, maxNs: Long): Step {
        if (prevNs != lastNs) return Step.NOT_CONSECUTIVE
        lastNs = ts
        if (ts - startNs > maxNs) return Step.PAST_THE_RUN
        correction = correction * d
        return Step.ADDED
    }
}
