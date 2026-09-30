package io.packagex.visiondemo.scanner

import android.graphics.Bitmap
import io.packagex.visiondemo.document.CaptureStart
import io.packagex.visiondemo.document.DocumentCamera
import io.packagex.visiondemo.document.DocumentPages
import io.packagex.visiondemo.model.Phase
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.ScanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Document Acquisition's part of [ScannerViewModel] (iOS DemoModel docacq): the pages, capture, per-page
 * processing and PDF export. Runs on the ViewModel's scope; [host] is the ViewModel.
 */
internal class DocumentFlow(private val document: DocumentCamera, private val scope: CoroutineScope, private val host: Host) {
    interface Host {
        val s: ScannerUiState
        /** Bumped on every mode switch; a page processed for an older one is dropped. */
        val generation: Int
        fun update(t: (ScannerUiState) -> ScannerUiState)
        fun show(r: ScanResult)
        fun flash()
        fun toast(text: String)
        fun effect(e: ScannerEffect)
    }

    /** iOS `docPages` / `docExported`. */
    private val pages = DocumentPages()
    private var export: Job? = null
    /** The SDK camera's lens when Document Acquisition took over; restored in the state on leaving. */
    private var sdkFront = false

    fun start() {
        scope.launch { document.stills.collect(::onStill) }
        scope.launch {
            document.quad.collect { q ->
                if (host.s.mode == ScanMode.DocAcq) {
                    // codeInFrame drives the live-outline swap and corner/fill color (Chrome.kt cornerColor/
                    // fillColor); seesDocument drives the hint text, same source, kept separate so the SDK's
                    // own Indications-driven seesDocument (Vision Scanner) isn't confused with this camera's
                    // boundary detector.
                    host.update { it.copy(codeInFrame = q != null, seesDocument = q != null) }
                }
            }
        }
    }

    /** Detection and auto capture follow the live camera: off under the drawer, sheets, alerts and processing. */
    fun sync(st: ScannerUiState) {
        document.auto = st.prefs.autoCapture
        document.detecting = st.mode == ScanMode.DocAcq && st.result == null && st.phase == Phase.Idle && st.sheet == null &&
            st.alert == null && st.feedback == null && !st.paused && !st.permissionDenied
    }

    /** Entering Document Acquisition: its camera starts on the current lens. */
    fun enter() {
        sdkFront = host.s.frontCamera
        document.lens(sdkFront)
    }

    /** Leaving (iOS setMode): drop the pages, release the CameraX pipeline before the next owner claims the sensor,
     *  and give the SDK camera's lens back to the state (a flip here stays with this camera). */
    fun leave() {
        pages.reset()
        document.release()
        host.update { it.copy(frontCamera = sdkFront) }
    }

    fun shutter() = when (document.capture()) {
        CaptureStart.Started -> { host.flash(); host.update { it.copy(phase = Phase.Scanning) } }
        CaptureStart.Busy -> {}   // an auto capture is already taking the page
        CaptureStart.NoPage -> host.toast("Fit the page inside the frame")
    }

    /** iOS `rescanDocument`: the caller then closes the result. */
    fun rescan(dropLast: Boolean) = pages.retake(dropLast)

    /** iOS `addDocPage`: the captured page runs through dewarp, enhance and quality, then the drawer shows every page. */
    private fun onStill(original: Bitmap?) {
        if (host.s.mode != ScanMode.DocAcq) return
        if (original == null) {
            host.update { it.copy(phase = Phase.Idle) }
            return host.toast("Capture failed")
        }
        val gen = host.generation
        host.update { it.copy(phase = Phase.Processing) }
        scope.launch {
            val page = document.process(original, pages.nextIndex())
            if (gen != host.generation) return@launch
            pages.add(page)
            host.show(ScanResult.Document(pages.pages))
        }
    }

    /** iOS ResultDrawer `exportPDF`: the pages the drawer shows; the next capture after an export starts a new document. */
    fun exportPdf(enhanced: Boolean) {
        if (export?.isActive == true) return
        val shown = (host.s.result as? ScanResult.Document)?.pages?.takeIf { it.isNotEmpty() } ?: return
        if (shown.any { it.lines == null }) host.toast("Preparing searchable PDF…")
        export = scope.launch {
            val file = document.exportPdf(shown, enhanced) ?: return@launch host.toast("PDF export failed")
            pages.exported = true
            host.effect(ScannerEffect.SharePdf(file))
        }
    }
}
