package io.packagex.visiondemo.fakes

import android.opengl.GLSurfaceView
import io.packagex.visiondemo.ar.ArCamera
import io.packagex.visiondemo.ar.PayloadCount
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** Records what the ViewModel asked of AR; [counts] stands in for the renderer's marker counts. */
class FakeAr(var installed: Boolean = true, private val onDetach: () -> Unit = {}) : ArCamera {
    override val counts = MutableStateFlow<List<PayloadCount>>(emptyList())
    private val errorChannel = Channel<String>(Channel.UNLIMITED)
    override val errors: Flow<String> = errorChannel.receiveAsFlow()
    override var catalog: Map<String, String> = emptyMap()
    var paused = false
    var clears = 0
    var detaches = 0

    fun fail(message: String) { errorChannel.trySend(message) }

    override fun installed() = installed
    override fun attach(view: GLSurfaceView) {}
    override fun detach() { detaches++; onDetach() }
    override fun pause() { paused = true }
    override fun resume() { paused = false }
    override fun clear() { clears++; counts.value = emptyList() }
}
