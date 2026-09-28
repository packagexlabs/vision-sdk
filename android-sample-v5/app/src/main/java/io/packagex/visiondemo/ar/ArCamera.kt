package io.packagex.visiondemo.ar

import android.opengl.GLSurfaceView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * What the scanner ViewModel and [ArSurface] need from AR Barcode; [ArController] is the real one, tests
 * use a fake. All calls are made on the main thread. [pause]/[resume] are idempotent and remembered across
 * [attach], so the ViewModel can pause before the view exists.
 */
interface ArCamera {
    /** Marked instances per payload, most instances first. Emptied by [clear] and on [attach]. */
    val counts: StateFlow<List<PayloadCount>>
    /** Messages to toast (session could not start). */
    val errors: Flow<String>
    /** SKU -> item name; the renderer reads it on the GL thread. */
    var catalog: Map<String, String>
    /** ARCore is installed and supported. False can also mean "not known yet": then ask for the install. */
    fun installed(): Boolean
    /** Starts a new ARCore session on [view] (a fresh warm-up, no markers). Call from the view's factory. */
    fun attach(view: GLSurfaceView)
    /** Stops and closes the session, releasing the camera. Safe to call twice. */
    fun detach()
    /** Pauses the session and GL surface (releases the camera); markers and counts are kept. */
    fun pause()
    /** Resumes after [pause], keeping markers and counts; placement waits for a new warm-up. */
    fun resume()
    /** Removes every marker ("New Scan", entering AR). */
    fun clear()
}

/** Default for ViewModels built without AR (tests, previews). */
object NoArCamera : ArCamera {
    override val counts: StateFlow<List<PayloadCount>> = MutableStateFlow(emptyList())
    override val errors: Flow<String> = emptyFlow()
    override var catalog: Map<String, String> = emptyMap()
    override fun installed() = true   // nothing to install: entering AR just shows no camera
    override fun attach(view: GLSurfaceView) {}
    override fun detach() {}
    override fun pause() {}
    override fun resume() {}
    override fun clear() {}
}
