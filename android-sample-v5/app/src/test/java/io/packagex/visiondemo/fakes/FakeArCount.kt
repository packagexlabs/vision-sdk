package io.packagex.visiondemo.fakes

import android.opengl.GLSurfaceView
import io.packagex.arcount.Command
import io.packagex.arcount.CountView
import io.packagex.visiondemo.ar.AppStream
import io.packagex.visiondemo.ar.ArCount
import io.packagex.visiondemo.ar.ArScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Records what the ViewModel asked of the AR session; [count], [codesInView] and [seen] stand in for what the
 * controller publishes. [reset] empties the count and the seen list, as a new counter does; the codes in view stay.
 */
class FakeArCount(var installed: Boolean = true, private val onDetach: () -> Unit = {}) : ArCount {
    override val count = MutableStateFlow(CountView.EMPTY)
    override val stream = MutableStateFlow<AppStream?>(null)
    override val screen = MutableStateFlow(ArScreen.NONE)
    override val codesInView = MutableStateFlow(emptyList<String>())
    override val seen = MutableStateFlow(emptyList<String>())
    private val errorChannel = Channel<String>(Channel.UNLIMITED)
    override val errors: Flow<String> = errorChannel.receiveAsFlow()
    private val exitChannel = Channel<String>(Channel.UNLIMITED)
    override val exits: Flow<String> = exitChannel.receiveAsFlow()
    override var tracing = false
    var paused = false
    var resets = 0
    var detaches = 0

    /** Every list the ViewModel sent, and "reset" where it reset the counter, in order */
    val calls = mutableListOf<String>()

    fun fail(message: String) { errorChannel.trySend(message) }
    /** The session can't run here (no session, no app stream configures). */
    fun exit(message: String) { exitChannel.trySend(message) }

    override fun installed() = installed
    override fun attach(view: GLSurfaceView) {}
    override fun detach(view: GLSurfaceView?) { detaches++; onDetach() }
    override fun pause() { paused = true }
    override fun resume() { paused = false }
    override fun command(command: Command) {}
    override fun reset() { resets++; calls += "reset"; count.value = CountView.EMPTY; seen.value = emptyList() }
    override fun setItems(codes: Set<String>) { calls += codes.sorted().joinToString(",", "items ") }
    override fun focus(x: Float, y: Float) { calls += "focus $x $y" }
}
