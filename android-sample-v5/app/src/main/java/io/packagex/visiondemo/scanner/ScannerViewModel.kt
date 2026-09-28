package io.packagex.visiondemo.scanner

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.packagex.visiondemo.camera.Camera
import io.packagex.visiondemo.camera.CameraOwner
import io.packagex.visiondemo.camera.ScanEvent
import io.packagex.visiondemo.data.EntitlementRepository
import io.packagex.visiondemo.data.ExtractionRepository
import io.packagex.visiondemo.data.ItemCatalogRepository
import io.packagex.visiondemo.data.ModelRepository
import io.packagex.visiondemo.data.Prefs
import io.packagex.visiondemo.data.PreferencesRepository
import io.packagex.visiondemo.data.PriceTag
import io.packagex.visiondemo.data.ReportRepository
import io.packagex.visiondemo.data.ScanError
import io.packagex.visiondemo.data.Secrets
import io.packagex.visiondemo.designsystem.PXButtonKind
import io.packagex.visiondemo.model.Box
import io.packagex.visiondemo.model.Feedback
import io.packagex.visiondemo.model.ModelState
import io.packagex.visiondemo.model.Phase
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.ScanResult
import io.packagex.visiondemo.model.SheetKind
import io.packagex.visiondemo.model.isCode
import io.packagex.visiondemo.model.isDocument
import io.packagex.visiondemo.model.gated
import io.packagex.visiondemo.model.scannerConfig
import io.packagex.visionsdk.core.ScanningMode
import io.packagex.visionsdk.dto.ScannedCodeResult
import io.packagex.visionsdk.exceptions.VisionSDKException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/**
 * State and SDK wiring for the camera screen. Ported from iOS `Model/DemoModel.swift`.
 * Runs entirely on viewModelScope (Main), so the SDK view is only touched on the main thread.
 */
@HiltViewModel
class ScannerViewModel @Inject constructor(
    internal val camera: Camera,
    private val prefs: PreferencesRepository,
    private val models: ModelRepository,
    private val extraction: ExtractionRepository,
    private val report: ReportRepository,
    private val entitlement: EntitlementRepository,
    private val catalog: ItemCatalogRepository,
    secrets: Secrets,
) : ViewModel() {
    private val _state = MutableStateFlow(ScannerUiState(missingKey = if (secrets.isMissing) secrets.missingMessage else null))
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    private val _effects = Channel<ScannerEffect>(Channel.BUFFERED)
    val effects: Flow<ScannerEffect> = _effects.receiveAsFlow()

    /** Bumped on every mode switch (and cancel); async work started earlier drops its result. */
    private var modeGeneration = 0

    /** The result waiting out the success flash; cancelled by a mode switch or close so it can't land later. */
    private var pendingShow: Job? = null

    /** What the current alert's "Try again" re-runs. */
    private var retry: (() -> Unit)? = null

    /** A sheet's hide animation is running (between DismissSheet and SheetDismissed). */
    private var dismissing = false

    /** Viewfinder rect in camera-view px, from [ScannerAction.FrameChanged]. */
    private var frame: RectF? = null

    /** Item retrieval: per code, the timer that drops it from [ScannerUiState.codesInView]. */
    private val inViewExpiry = mutableMapOf<String, Job>()

    /** [ScannerAction.UpdatePrefs] writes not yet persisted. */
    private var prefWrites = 0

    private val s get() = _state.value
    private val usesScanner get() = ownerFor(s.mode) == CameraOwner.Scanner

    init {
        // While a local write is in flight the repo can still emit the value from before it; skip those.
        viewModelScope.launch { prefs.prefs.collect { if (prefWrites == 0) setPrefs(it) } }
        viewModelScope.launch { models.states.collect { m -> _state.update { it.copy(models = m) } } }
        viewModelScope.launch { catalog.items.collect { i -> _state.update { it.copy(items = i) } } }
        viewModelScope.launch { camera.paused.collect(::onPaused) }
        viewModelScope.launch { camera.events.collect(::onEvent) }
        // Repo states are in-memory; read what the SDK already has on disk / in memory.
        viewModelScope.launch {
            try { models.refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* rows stay NotDownloaded */ }
        }
        camera.userActive()
    }

    fun onAction(a: ScannerAction) {
        camera.userActive()
        // An alert button: dismiss the alert, then run its action (iOS: every AlertAction sets alert = nil first).
        if (a != ScannerAction.DismissAlert && s.alert?.actions?.any { it.action == a } == true) _state.update { it.copy(alert = null) }
        when (a) {
            is ScannerAction.SetMode -> setMode(a.m)
            ScannerAction.Shutter -> shutter()
            ScannerAction.CloseResult -> closeResult()
            ScannerAction.ReopenLast -> reopenLast()
            is ScannerAction.OpenSheet -> openSheet(a.k)
            ScannerAction.DismissSheet -> { dismissing = true; _state.update { it.copy(sheet = null) } }
            ScannerAction.SheetDismissed -> sheetDismissed()
            ScannerAction.Resume -> if (!camera.resume()) toast("Still too hot. Let the phone cool down first.")
            ScannerAction.UserActive -> {}
            is ScannerAction.UpdatePrefs -> {
                setPrefs(a.t(s.prefs))   // at once, so the next action sees it; the repo echoes the same value
                prefWrites++
                viewModelScope.launch { try { prefs.update(a.t) } finally { prefWrites-- } }
            }
            ScannerAction.ToggleTorch -> setTorch(!s.torch)
            ScannerAction.ToggleAuto -> toggleAuto()
            is ScannerAction.Report -> sendReport(a.fields, a.message)
            ScannerAction.CancelProcessing -> cancelProcessing()
            ScannerAction.DismissAlert -> {
                retry = null
                _state.update { it.copy(alert = null) }
                if (usesScanner) camera.rescan()
            }
            is ScannerAction.PermissionResult -> {
                _state.update { it.copy(permissionDenied = !a.granted) }
                if (a.granted) configureCamera()
            }
            is ScannerAction.FrameChanged -> if (a.rect != frame) { frame = a.rect; applyConfig() }
            ScannerAction.Retry -> retry.also { retry = null }?.invoke()
            ScannerAction.TorchRetry -> { setTorch(true); camera.rescan() }
            ScannerAction.Authenticate -> checkEntitlement(s.mode, announce = true)
            is ScannerAction.DownloadModel -> downloadModel(a)
            is ScannerAction.LoadModel -> viewModelScope.launch {
                toast(runCatchingModel { models.load(a.t, a.s) } ?: "Model loaded")
            }
            is ScannerAction.UnloadModel -> models.unload(a.t, a.s)
            is ScannerAction.DeleteModel -> viewModelScope.launch { runCatchingModel { models.delete(a.t, a.s) }?.let(::toast) }
            is ScannerAction.CancelDownload -> { models.cancel(a.t, a.s); toast("Download cancelled") }
            ScannerAction.CheckUpdates -> viewModelScope.launch { toast(runCatching { models.checkUpdates() }.getOrElse { it.message ?: "Update check failed" }) }
        }
    }

    // MARK: Mode and camera

    private fun setMode(m: ScanMode) {
        if (m == s.mode) return
        pendingShow?.cancel(); pendingShow = null
        modeGeneration++
        retry = null
        clearInView()
        dismissing = false
        if (s.torch) camera.torch(false)
        _state.update {
            it.copy(
                mode = m, result = null, sheet = null, pendingSheet = null, phase = Phase.Idle, boxes = emptyList(),
                codeInFrame = false, torch = false, gated = m.gated, entitlementChecking = false, feedback = null, tags = emptyList(),
            )
        }
        if (!s.permissionDenied) configureCamera()
    }

    /** Hands the sensor to the mode's owner; for the scanner, applies its config and (gated modes) the entitlement check. */
    private fun configureCamera() {
        camera.claim(ownerFor(s.mode))
        if (!usesScanner) return
        applyConfig()
        if (s.result == null && (s.sheet == null || s.sheet == SheetKind.Items)) camera.resumeDetection()
        if (s.mode.gated) checkEntitlement(s.mode)
    }

    private fun applyConfig() {
        if (!usesScanner) return
        val p = s.prefs
        val auto = p.autoCapture && (s.mode.isCode || s.mode.isDocument)
        camera.apply(scannerConfig(s.mode, p.multi, p.showBoxes), frame, if (auto) ScanningMode.Auto else ScanningMode.Manual)
    }

    private fun setPrefs(p: Prefs) {
        val old = s.prefs
        if (p == old) return
        _state.update { it.copy(prefs = p) }
        if (old.multi != p.multi || old.showBoxes != p.showBoxes || old.autoCapture != p.autoCapture) {
            applyConfig()
            if (old.multi != p.multi && usesScanner) camera.rescan()   // drops any half-finished single capture
        }
    }

    private fun onPaused(paused: Boolean) {
        _state.update { it.copy(paused = paused) }
        if (paused) setTorch(false)   // iOS pauseCamera: torch = false
    }

    private fun setTorch(on: Boolean) {
        if (on == s.torch) return
        _state.update { it.copy(torch = on) }
        camera.torch(on)
    }

    /**
     * The SDK's check is `enable*Mode` on the live view, which also switches the view into that mode, so it runs
     * on every entry. [rescan] after a pass when detection was refused mid-scan (iOS :927); [announce] for the gate card.
     */
    private fun checkEntitlement(mode: ScanMode, announce: Boolean = false, rescan: Boolean = announce) {
        if (!mode.gated) return
        _state.update { it.copy(gated = true, entitlementChecking = true) }
        viewModelScope.launch {
            val r = entitlement.check(camera.view, mode)
            if (s.mode != mode) return@launch
            _state.update { it.copy(gated = r.isFailure, entitlementChecking = false) }
            if (r.isSuccess) {
                if (announce) toast("Authenticated")
                if (rescan) camera.rescan()
            } else if (announce) {
                val e = r.exceptionOrNull()
                toast(if (e is IOException) "You're offline. Try again once connected." else e?.message ?: "Not entitled for ${mode.label}")
            }
        }
    }

    // MARK: Shutter

    private fun shutter() {
        if (s.phase != Phase.Idle || s.result != null || pendingShow != null || s.gated || s.permissionDenied) return
        when (s.mode) {
            // ponytail: rows come from the AR controller (Task 11); until then there are never any markers.
            ScanMode.Ar -> toast("No markers yet. Point at barcodes first.")
            ScanMode.Price -> show(ScanResult.Price(s.tags))   // iOS shows the drawer even with no tags yet
            ScanMode.DocAcq -> {}     // the document pipeline's capture (Task 12)
            ScanMode.Retrieval -> {
                if (s.items.isEmpty()) {
                    _state.update {
                        it.copy(alert = Alert(
                            "No items to find",
                            "Add item codes to the list first. The scanner then reports which of them are in view.",
                            listOf(
                                AlertAction("Open item list", action = ScannerAction.OpenSheet(SheetKind.Items)),
                                AlertAction("Cancel", PXButtonKind.Tertiary, ScannerAction.DismissAlert),
                            ),
                        ))
                    }
                    return
                }
                show(ScanResult.Retrieval(s.codesInView.map { it to (it in s.items) }))
            }
            ScanMode.Barcode, ScanMode.QR, ScanMode.Ocr -> {
                // Wild card prepares its own models (iOS :427).
                if (s.mode == ScanMode.Ocr && !s.prefs.wildCard && !cloudSelected(s.prefs) && !ensureModelReady()) return
                _state.update { it.copy(phase = Phase.Scanning) }
                camera.capture()
            }
        }
    }

    private fun toggleAuto() {
        if (!s.mode.isCode && !s.mode.isDocument) return
        val on = !s.prefs.autoCapture
        onAction(ScannerAction.UpdatePrefs { it.copy(autoCapture = on) })
        toast(if (on) "Auto capture on" else "Auto capture off")
    }

    /** False (and prompts) when the on-device model for the current document type isn't loaded. */
    private fun ensureModelReady(): Boolean {
        val (t, size) = activeModel(s.prefs) ?: return true
        val st = s.models[t to size] ?: ModelState.NotDownloaded
        if (st == ModelState.Loaded) return true
        _state.update { it.copy(alert = modelPrompt(t, size, st)) }
        return false
    }

    // MARK: Events

    private fun onEvent(e: ScanEvent) {
        when (e) {
            is ScanEvent.Codes -> {
                if (s.result != null || pendingShow != null || e.codes.isEmpty()) return
                if (s.mode != ScanMode.Barcode && s.mode != ScanMode.QR) return
                // A multiple-scan onScanResult already carries every code, so both cases show the list (iOS .multi / .code).
                show(ScanResult.Codes(e.codes.map { it.toDetected() }))
            }
            is ScanEvent.Boxes -> onBoxes(e.codes)
            is ScanEvent.Captured -> if (s.mode == ScanMode.Ocr) runOcr(e.bitmap, e.codes) else _state.update { it.copy(phase = Phase.Idle) }
            is ScanEvent.PriceTag -> {   // iOS codeScannerViewDidCapturePrice: collect unique tags; the shutter shows them
                if (s.mode != ScanMode.Price) return
                val tag = PriceTag.from(e.data.productSKU, e.data.productPrice)
                if (s.tags.none { it.sku == tag.sku }) _state.update { it.copy(tags = it.tags + tag) }
            }
            is ScanEvent.Retrieved -> if (s.mode == ScanMode.Retrieval) sawInView(e.code.scannedCode)
            is ScanEvent.Failure -> onFailure(e.e)
            is ScanEvent.Indications, ScanEvent.Started -> {}
        }
    }

    /**
     * The Android SDK reports item-retrieval codes one callback at a time (no per-frame batch like iOS
     * `codeScannerViewDidCaptureItemCodesWith`), so a code counts as in view until [IN_VIEW_MS] without a report.
     */
    private fun sawInView(code: String) {
        inViewExpiry.remove(code)?.cancel()
        if (code !in s.codesInView) _state.update { it.copy(codesInView = it.codesInView + code) }
        inViewExpiry[code] = viewModelScope.launch {
            delay(IN_VIEW_MS)
            inViewExpiry.remove(code)
            _state.update { it.copy(codesInView = it.codesInView - code) }
        }
    }

    private fun clearInView() {
        inViewExpiry.values.forEach { it.cancel() }
        inViewExpiry.clear()
        _state.update { it.copy(codesInView = emptyList()) }
    }

    private fun onBoxes(codes: List<ScannedCodeResult>) {
        val cfg = scannerConfig(s.mode, s.prefs.multi, s.prefs.showBoxes)
        val detected = codes.map { it.toDetected() }
        val f = frame?.takeIf { cfg.restrictToFrame && !it.isEmpty }
        val inFrame = if (f != null) {
            val fb = Box(f.left.toInt(), f.top.toInt(), f.right.toInt(), f.bottom.toInt())
            detected.any { it.box.inside(fb) }
        } else {
            detected.isNotEmpty()
        }
        _state.update { it.copy(codeInFrame = inFrame, boxes = if (cfg.showBoxes) detected else emptyList()) }
    }

    private fun onFailure(e: VisionSDKException) {
        when {
            e is VisionSDKException.CameraUsageNotAuthorized -> _state.update { it.copy(permissionDenied = true) }
            e.errorCode in 1..5 ->   // No*Detected: after a shutter capture tell the user; per-frame / auto, try the next frame
                if (s.phase == Phase.Scanning) noCodeFound() else camera.rescan()
            // As iOS: re-check once (the SDK repeats this per frame); the gate only stays up if the license lacks it.
            e is VisionSDKException.PriceTagNotEligible || e is VisionSDKException.ItemRetrievalNotEligible ->
                if (!s.gated && !s.entitlementChecking) checkEntitlement(s.mode, rescan = true)
            s.alert == null -> ScanError.from(e).let { fail(it.title, it.message) }
        }
    }

    /** iOS `noCodeFound`. */
    private fun noCodeFound() {
        val text = s.mode == ScanMode.Ocr
        val extra = if (s.torch) emptyList() else listOf(AlertAction("Turn on torch and retry", PXButtonKind.Secondary, ScannerAction.TorchRetry))
        fail(
            if (text) "No Text Found" else if (s.mode == ScanMode.QR) "No QR Code Found" else "No Barcode Found",
            if (text) "Fill the frame with the label and hold still, then capture again." else "Move closer so the code fills the frame, then try again.",
            retry = { camera.rescan(); shutter() },
            extra = extra,
        )
    }

    // MARK: Vision Scanner (OCR)

    private fun runOcr(bitmap: Bitmap, codes: List<ScannedCodeResult>) {
        val gen = modeGeneration
        val p = s.prefs
        val retryThis = { runOcr(bitmap, codes) }
        _state.update { it.copy(phase = Phase.Processing) }
        viewModelScope.launch {
            val outcome = runOcrExtraction(extraction, bitmap, codes, p)
            if (gen != modeGeneration) return@launch   // user switched mode or cancelled while this was in flight
            when (outcome) {
                is OcrOutcome.Done -> show(outcome.result)
                is OcrOutcome.Failed -> fail(outcome.title, outcome.message, retryThis, outcome.extra)
            }
        }
    }

    private fun sendReport(fields: Set<String>, message: String) {
        val r = s.result as? ScanResult.Ocr ?: return
        viewModelScope.launch {
            report.report(r.result, fields, message, r.image).fold(
                onSuccess = { toast("Report sent · ${fields.size} field${if (fields.size == 1) "" else "s"}") },
                onFailure = { toast("Report failed (${it.message})") },
            )
        }
    }

    // MARK: Results

    /** 380 ms success flash, then the drawer (iOS `show`). */
    private fun show(r: ScanResult) {
        _state.update { it.copy(phase = Phase.Idle, feedback = Feedback.Success) }
        haptic()
        pendingShow?.cancel()
        val forMode = s.mode
        pendingShow = viewModelScope.launch {
            delay(380)
            if (s.mode != forMode) return@launch
            pendingShow = null
            present(r)
        }
    }

    private fun present(r: ScanResult) {
        _state.update { it.copy(result = r, lastResult = it.mode to r, feedback = null) }
        if (usesScanner) camera.pauseDetection()   // nothing to detect under the drawer
    }

    private fun reopenLast() {
        val (m, r) = s.lastResult ?: return
        if (m != s.mode || s.result != null || pendingShow != null) return
        present(r)
    }

    private fun closeResult() {
        if (pendingShow != null) {
            pendingShow?.cancel(); pendingShow = null
            _state.update { it.copy(feedback = null) }
        }
        _state.update { it.copy(result = null, tags = emptyList()) }
        if (usesScanner) {
            if (s.sheet == null || s.sheet == SheetKind.Items) camera.resumeDetection()
            camera.rescan()
        }
    }

    /** Abandons a slow request (VLM can take up to 90 s): its late result is dropped via the generation. */
    private fun cancelProcessing() {
        if (s.phase == Phase.Idle) return
        modeGeneration++
        _state.update { it.copy(phase = Phase.Idle) }
        if (usesScanner) camera.rescan()
        toast("Cancelled")
    }

    /** iOS `fail`: "Try again" (if [retry]), [extra], then Ok / Cancel (which rescans). Error flash for 1.2 s. */
    private fun fail(title: String, message: String, retry: (() -> Unit)? = null, extra: List<AlertAction> = emptyList()) {
        _state.update { it.copy(phase = Phase.Idle, feedback = Feedback.Error) }
        haptic()
        viewModelScope.launch {
            delay(1200)
            if (s.feedback == Feedback.Error) _state.update { it.copy(feedback = null) }
        }
        this.retry = retry
        val acts = buildList {
            if (retry != null) add(AlertAction("Try again", action = ScannerAction.Retry))
            addAll(extra)
        }
        val close = if (acts.isEmpty()) AlertAction("Ok", action = ScannerAction.DismissAlert)
        else AlertAction("Cancel", PXButtonKind.Tertiary, ScannerAction.DismissAlert)
        _state.update { it.copy(alert = Alert(title, message, acts + close)) }
    }

    // MARK: Sheets

    /** Swapping one sheet for another in one step can show neither: close the current one and present
     *  the next once its hide animation has finished (iOS `presentAfterDismiss`, built in). */
    private fun openSheet(k: SheetKind) {
        when {
            s.sheet != null -> { dismissing = true; _state.update { it.copy(sheet = null, pendingSheet = k) } }
            dismissing -> _state.update { it.copy(pendingSheet = k) }
            else -> presentSheet(k)
        }
    }

    private fun sheetDismissed() {
        dismissing = false
        val next = s.pendingSheet
        if (next != null) {
            _state.update { it.copy(pendingSheet = null) }
            presentSheet(next)
        } else if (usesScanner && s.result == null) {
            camera.resumeDetection()
        }
    }

    /** Detection pauses under sheets (the camera is covered) except the item list, whose Add Item reads the codes in view. */
    private fun presentSheet(k: SheetKind) {
        _state.update { it.copy(sheet = k) }
        if (!usesScanner || s.result != null) return
        if (k == SheetKind.Items) camera.resumeDetection() else camera.pauseDetection()
    }

    // MARK: Models

    private fun downloadModel(a: ScannerAction.DownloadModel) {
        viewModelScope.launch {
            val error = runCatchingModel { models.download(a.t, a.s, a.thenLoad) }
            when {
                error != null -> toast(error)
                a.thenLoad -> toast("Model loaded")
            }
        }
    }

    /** Runs [block]; returns the user-facing error (null on success, and for a cancelled download, which toasts itself). */
    private suspend fun runCatchingModel(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: VisionSDKException.ModelDownloadCancelledException) {
        null
    } catch (e: IOException) {
        "Download failed. Check the connection."
    } catch (e: Exception) {
        (e as? VisionSDKException)?.errorMessage ?: e.message ?: "Something went wrong"
    }

    // MARK: Effects

    private fun toast(text: String) { _effects.trySend(ScannerEffect.Toast(text)) }

    private fun haptic() { if (s.prefs.sound) _effects.trySend(ScannerEffect.Haptic) }

    private companion object {
        const val IN_VIEW_MS = 1_000L
    }
}
