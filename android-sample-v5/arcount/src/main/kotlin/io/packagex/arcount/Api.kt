package io.packagex.arcount

import kotlin.math.hypot

/** ARCore's tracking state of a frame or an anchor */
enum class Tracking { TRACKING, PAUSED, STOPPED }

/**
 * One ARCore frame, recorded on the GL thread after Session.update().
 *
 * @property timestampNs Frame.getAndroidCameraTimestamp(): the same number as Image.timestamp of the same capture on
 *   the app's 4K stream, so reads pair with their frame exactly
 * @property camera Camera.getPose(): the physical camera in the world, +X right, +Y up, -Z forward of the image readout
 * @property anchor the section anchor's pose in the world; null while the core has none
 * @property intrinsics of the image the reads come from: the CPU image's intrinsics scaled to the 4K stream
 * @property exposureNs SENSOR_EXPOSURE_TIME of the capture; -1 when unknown
 */
data class PoseRecord(
    val timestampNs: Long,
    val camera: Pose,
    val anchor: Pose?,
    val frameTracking: Tracking,
    val anchorTracking: Tracking?,
    val intrinsics: Intrinsics,
    val exposureNs: Long = -1,
)

/**
 * A barcode the decoder read in one frame of the 4K stream: an engine FrameBarcode whose raw is not null.
 *
 * @property text rawText: what was read in this frame, which may differ from the engine track's shown text
 * @property corners tl, tr, br, bl as x0, y0, ..., x3, y3 in pixels of the unrotated 4K image; tl -> tr runs along the
 *   reading direction, so an upside-down symbol has tl at its bottom right
 * @property engineId the engine's track id: a tie-break in association only, never an identity
 * @property touchesBorder whether a corner lies within 2 px of the image border: a cut symbol's corners are guessed
 */
data class Read(
    val timestampNs: Long,
    val text: String,
    val corners: List<Double>,
    val engineId: Int,
    val symbology: String? = null,
    val touchesBorder: Boolean = false,
) {
    init {
        require(corners.size == 8) { "corners has ${corners.size} numbers, not 8" }
    }

    val centreU get() = (corners[0] + corners[2] + corners[4] + corners[6]) / 4

    val centreV get() = (corners[1] + corners[3] + corners[5] + corners[7]) / 4

    /** Length of the tl -> tr edge in pixels: the symbol's width along its reading direction */
    val widthPx get() = hypot(corners[2] - corners[0], corners[3] - corners[1])
}

/** What the worker asks for, from the hardware trigger and the screen */
sealed interface Command {
    /** Trigger pressed briefly: opens an unlabelled section in IDLE; a still-burst while COUNTING; nothing in FROZEN */
    data object TriggerShort : Command

    /** Trigger held 0.8 s or more: closes the section and opens the next unlabelled one */
    data object TriggerLong : Command

    /** Tap on the bracket: one unit added by hand */
    data object AddUnit : Command

    /** Long press on the bracket: the last unit added by hand is taken back; counted units are never removed */
    data object RemoveManualUnit : Command

    /** Tap on a gap marker: a unit added by hand where the gap is */
    data class FillGap(val gapId: Int) : Command

    /** Finish: closes the open section */
    data object Finish : Command

    /** Restart the section from FROZEN, or from an unresolved count */
    data object Restart : Command

    /** Keep the range that an unresolved section shows as its count */
    data object AcceptRange : Command
}

enum class SectionState { IDLE, OPEN, COUNTING, FROZEN, CLOSED }

enum class UnitState { TENTATIVE, COUNTED, AMBIGUOUS, MANUAL }

enum class SectionStatus { COMPLETE, UNRESOLVED, CLOSED_FROZEN, ABANDONED }

/** Why a section froze (spec 5.1, break conditions) */
enum class BreakReason { FRAME_NOT_TRACKING, ANCHOR_NOT_TRACKING, WORLD_JUMP, LEFT_SECTION, SILENCE, ANCHOR_STOPPED }

/** One line of guidance; the view carries the highest-priority one only (spec 5.5) */
enum class Prompt {
    HOLD_STILL_A_MOMENT,
    SCAN_SHELF_LABEL,
    SCAN_LABEL_TO_CONTINUE,
    RANGE_RESCAN,
    TOO_DARK,
    SLOW_DOWN,
    SLIDE_A_LITTLE,
    MOVE_CLOSER,
    HOLD_WITHIN_30CM,
    DEVICE_HOT,
}

/** A unit's marker in normalized coordinates (0..1) of the unrotated image of the latest frame */
data class Marker(val unitId: Int, val state: UnitState, val u: Double, val v: Double, val sizeU: Double)

/** A place in the row where a unit should be and none was read: tapping it adds a unit by hand */
data class Gap(val gapId: Int, val u: Double, val v: Double)

/** The section's count, drawn at the section anchor; normalized image coordinates of the latest frame */
data class Bracket(
    val u: Double,
    val v: Double,
    val inImage: Boolean,
    val gtin: String?,
    val countLow: Int,
    val countHigh: Int,
    val frozen: Boolean,
)

/** One closed section, as the host app gets it (spec 5.7) */
data class SectionResult(
    val sectionId: String,
    val labelPayload: String?,
    val gtins: Set<String>,
    val status: SectionStatus,
    val counted: Int,
    val manualAdded: Int,
    val manualRemoved: Int,
    val tentative: Int,
    val ambiguous: Int,
    val countLow: Int,
    val countHigh: Int,
    val breaks: List<Pair<Long, BreakReason>>,
    val durationMs: Long,
)

/** Everything the UI draws; immutable, a new one after every update */
/** A listed code's count over every section of the session (spec 5.10); [inView] when one of its units is in view now */
data class ItemCount(val code: String, val countLow: Int, val countHigh: Int, val inView: Boolean)

data class CountView(
    val state: SectionState,
    val prompt: Prompt?,
    val markers: List<Marker>,
    val gaps: List<Gap>,
    val bracket: Bracket?,
    val closed: List<SectionResult>,
    /** AR Item Count (spec 5.10): every listed code with its count over all sections, in list order */
    val items: List<ItemCount> = emptyList(),
) {
    companion object {
        val EMPTY = CountView(SectionState.IDLE, null, emptyList(), emptyList(), null, emptyList())
    }
}

/** Where the core wants the section anchor: the app creates it with Session.createAnchor([world]) */
data class AnchorRequest(val world: Pose)

/**
 * The counting core: everything that decides what is counted, as plain data in and out. One instance per AR
 * session, called from one thread at a time (the app's mapper thread).
 */
interface ArCounter {
    /** ARCore resumed, or tracking came back: starts the start-up guard again */
    fun onResume(timestampNs: Long)

    /** Every ARCore frame, in order */
    fun onFrame(frame: PoseRecord)

    /** The reads of one 4K frame, when the engine finished it; may come before that frame's PoseRecord */
    fun onReads(timestampNs: Long, reads: List<Read>)

    fun onCommand(command: Command, timestampNs: Long)

    /** An anchor the app is to create now; null when none is wanted */
    fun anchorRequest(): AnchorRequest?

    /** Whether the requested anchor was created; later PoseRecords carry its pose */
    fun onAnchorCreated(ok: Boolean)

    /** The latest view */
    fun view(): CountView

    /** AR Item Count (spec 5.10): the item list, the codes counted; called whenever it changes. Empty: nothing counted. */
    fun setItems(codes: Set<String>) {}
}
