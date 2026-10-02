package io.packagex.visiondemo.ar

import android.opengl.GLSurfaceView
import io.packagex.arcount.Command
import io.packagex.arcount.CountView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * What the scanner ViewModel and [ArSurface] need from AR Count; [ArSessionController] is the real one, tests use a
 * fake. All calls are made on the main thread. [pause]/[resume] are idempotent and remembered across [attach], so the
 * ViewModel can pause before the view exists.
 */
interface ArCount {
    /** What Compose shows of the counter's newest view ([forUi]); [CountView.EMPTY] while no session runs. */
    val count: StateFlow<CountView>

    /** The app stream of the running session (its working distance shows in the hint); null while none runs. */
    val stream: StateFlow<AppStream?>

    /** Where the GL thread drew the bracket and the gaps on its last frame, in view pixels. */
    val screen: StateFlow<ArScreen>

    /** Messages to toast (the session or its camera could not start). */
    val errors: Flow<String>

    /** Why AR Count can't run here (no session can be made, no app stream configures): say so and leave the mode (spec 6). */
    val exits: Flow<String>

    /** Settings › Advanced › "AR Count traces": the session writes a [SessionRecorder] trace while true. */
    var tracing: Boolean

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
}

/** Default for ViewModels built without AR (tests, previews). */
object NoArCount : ArCount {
    override val count: StateFlow<CountView> = MutableStateFlow(CountView.EMPTY)
    override val stream: StateFlow<AppStream?> = MutableStateFlow(null)
    override val screen: StateFlow<ArScreen> = MutableStateFlow(ArScreen.NONE)
    override val errors: Flow<String> = emptyFlow()
    override val exits: Flow<String> = emptyFlow()
    override var tracing = false
    override fun installed() = true // nothing to install: entering AR just shows no camera
    override fun attach(view: GLSurfaceView) {}
    override fun detach(view: GLSurfaceView?) {}
    override fun pause() {}
    override fun resume() {}
    override fun command(command: Command) {}
    override fun reset() {}
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
fun CountView.forUi(): CountView = copy(markers = emptyList(), gaps = emptyList(), bracket = bracket?.copy(u = 0.0, v = 0.0))
