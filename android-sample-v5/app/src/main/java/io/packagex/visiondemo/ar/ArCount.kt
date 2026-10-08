package io.packagex.visiondemo.ar

import android.opengl.GLSurfaceView
import io.packagex.arcount.Command
import io.packagex.arcount.CountView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * What the scanner ViewModel and [ArSurface] need from the AR session of AR Item Count; [ArSessionController] is the
 * real one, tests use a fake. All calls are made on the main thread. [pause]/[resume] are idempotent and remembered across [attach], so the
 * ViewModel can pause before the view exists.
 */
interface ArCount {
    /** What Compose shows of the counter's newest view ([forUi]); [CountView.EMPTY] while no session runs. */
    val count: StateFlow<CountView>

    /** The app stream of the running session (its working distance shows in the hint); null while none runs. */
    val stream: StateFlow<AppStream?>

    /** Where the GL thread drew the bracket and the gaps on its last frame, in view pixels: always [ArScreen.NONE], as
     *  AR Item Count draws neither (spec 5.10). */
    val screen: StateFlow<ArScreen>

    /** AR Item Count: the distinct codes read within the last second, at most every 250 ms; empty while no session runs. */
    val codesInView: StateFlow<List<String>>

    /** AR Item Count: every distinct code read in this session, the most recently first read first, at most 30;
     *  [reset] clears it. */
    val seen: StateFlow<List<String>>

    /** AR Item Count: listed reads are waiting for ARCore to find a real surface before they make pins (the pins' surface
     *  gate held one within the last second); false while no session runs. */
    val findingSurface: StateFlow<Boolean>

    /** Messages to toast (the session or its camera could not start). */
    val errors: Flow<String>

    /** Why AR Item Count can't run here (no session can be made, no app stream configures): say so and leave the mode (spec 6). */
    val exits: Flow<String>

    /** Settings › Advanced › "AR traces": the session writes a [SessionRecorder] trace while true. */
    var tracing: Boolean

    /** Settings › Advanced › "AR blur pre-skip" (spec 5.6, on by default): off keeps every image for the engine, for
     *  measurement runs (drift plan §5). */
    var blurSkip: Boolean

    /** Settings › Advanced › "AR outlines fixed to the world" (drift plan Phase 1, on by default): how the unlisted
     *  codes' outlines are drawn. */
    var overlayRules: OverlayRules

    /** Settings › Advanced › "AR far-safe outline depth" (off by default): codes other than EAN/UPC carried at 0.8 m,
     *  not rotation only. */
    var outlineFarSafe: Boolean

    /** Settings › Advanced › "AR pins: Android rules" (drift plan Phases 2-4, on by default): which hit seeds a pin,
     *  which identity rules place and retire pins, and a pin whose depth is unsure drawn as a ring. */
    var pinRules: PinRules

    /** Settings › Advanced › "AR pins refined by every read" (drift plan Phase 4, on by default): under the Android pin
     *  rules, each claim refines its pin, which is a ring until its depth is verified; off, pins stay as born (Phase 3). */
    var pinRefine: Boolean

    /** Settings › Advanced › "AR read-rate boost" (drift plan P2c, on by default): with refined Android pins, every shown
     *  code is read in every frame while a listed pin waits for its depth (at most 2 s per pin) or a listed read has no pin. */
    var readBoost: Boolean

    /** Settings › Advanced › "AR record session" (off by default): from the next start, each run of the session is recorded
     *  for replay (ARCore's MP4 with the engine's reads, [RecordingInfo]). */
    var record: Boolean

    /** ARCore is installed and supported. False can also mean "not known yet": then ask for the install. */
    fun installed(): Boolean

    /** Starts a new session on [view] (a new counter, no sections). Call from the view's factory. */
    fun attach(view: GLSurfaceView)

    /** Stops and closes the session, releasing the camera. Safe to call twice. With [view], only when that
     *  view is still the attached one (a stale dispose must not close a newer session). */
    fun detach(view: GLSurfaceView? = null)

    /** Pauses the session and releases the camera (heat, idle, background, result drawer); the count is kept. */
    fun pause()

    /** Resumes after [pause]: the camera opens again and the counter's start-up guard runs again. */
    fun resume()

    /** A command of the worker (the buttons, a tap or long press on the count, a tap on a gap) for the counter. */
    fun command(command: Command)

    /** "New Scan": a new counter on the running session; the closed sections go with the old one. */
    fun reset()

    /** AR Item Count's list (spec 5.10): the codes counted, by this session's counter and by every later one. */
    fun setItems(codes: Set<String>)

    /** Tap to focus at ([x], [y]), 0..1 of the view: one AF trigger on the running session, never a repeating request. */
    fun focus(x: Float, y: Float)
}

/** Default for ViewModels built without AR (tests, previews). */
object NoArCount : ArCount {
    override val count: StateFlow<CountView> = MutableStateFlow(CountView.EMPTY)
    override val stream: StateFlow<AppStream?> = MutableStateFlow(null)
    override val screen: StateFlow<ArScreen> = MutableStateFlow(ArScreen.NONE)
    override val codesInView: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val seen: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val findingSurface: StateFlow<Boolean> = MutableStateFlow(false)
    override val errors: Flow<String> = emptyFlow()
    override val exits: Flow<String> = emptyFlow()
    override var tracing = false
    override var blurSkip = true
    override var overlayRules = OverlayRules.ANDROID
    override var outlineFarSafe = false
    override var pinRules = PinRules.ANDROID
    override var pinRefine = true
    override var readBoost = true
    override var record = false
    override fun installed() = true // nothing to install: entering AR just shows no camera
    override fun attach(view: GLSurfaceView) {}
    override fun detach(view: GLSurfaceView?) {}
    override fun pause() {}
    override fun resume() {}
    override fun command(command: Command) {}
    override fun reset() {}
    override fun setItems(codes: Set<String>) {}
    override fun focus(x: Float, y: Float) {}
}

/** Where the GL thread drew the section's bracket (null: none, or not in the image) and the gap markers on its last
 *  frame, in view pixels: the count follows the bracket, and a tap near a gap fills it. */
data class ArScreen(val bracket: ScreenPoint?, val gaps: List<ScreenGap>) {
    companion object {
        val NONE = ArScreen(null, emptyList())
    }
}

data class ScreenPoint(val x: Float, val y: Float)

data class ScreenGap(val gapId: Int, val x: Float, val y: Float)

/**
 * What Compose shows of this view: all but the per-frame geometry (markers, gaps and the bracket's place in the
 * image), which the GL thread draws from the full view; so the UI state changes when the count, the prompt or the
 * state does, not on every frame.
 */
fun CountView.forUi(): CountView = copy(markers = emptyList(), gaps = emptyList(), bracket = bracket?.copy(u = 0.0, v = 0.0), unitPoints = emptyList())
