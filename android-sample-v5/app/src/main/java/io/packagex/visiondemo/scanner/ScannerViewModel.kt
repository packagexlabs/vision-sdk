package io.packagex.visiondemo.scanner

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.packagex.visiondemo.ar.ArCount
import io.packagex.visiondemo.ar.NoArCount
import io.packagex.visiondemo.camera.Camera
import io.packagex.visiondemo.camera.CameraOwner
import io.packagex.visiondemo.camera.ScanEvent
import io.packagex.visiondemo.data.EntitlementRepository
import io.packagex.visiondemo.data.ExtractionRepository
import io.packagex.visiondemo.data.ItemCatalogRepository
import io.packagex.visiondemo.data.ItemLabelFeedback
import io.packagex.visiondemo.data.ModelRepository
import io.packagex.visiondemo.data.Prefs
import io.packagex.visiondemo.data.PreferencesRepository
import io.packagex.visiondemo.data.PriceTag
import io.packagex.visiondemo.data.ReportRepository
import io.packagex.visiondemo.data.ScanError
import io.packagex.visiondemo.data.Secrets
import io.packagex.visiondemo.data.TextTemplates
import io.packagex.visiondemo.data.NoTextTemplates
import io.packagex.visiondemo.data.TtPath
import io.packagex.visiondemo.data.VlmPrompts
import io.packagex.texttemplates.sdk.PXErrorCode
import io.packagex.texttemplates.sdk.PXException
import io.packagex.texttemplates.sdk.PXScanEvent
import io.packagex.visiondemo.designsystem.PXButtonKind
import io.packagex.visiondemo.document.DocumentCamera
import io.packagex.visiondemo.document.NoDocumentCamera
import io.packagex.visiondemo.model.Box
import io.packagex.visiondemo.model.Feedback
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.ModelState
import io.packagex.visiondemo.model.OcrResult
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
    internal val ar: ArCount = NoArCount,
    internal val document: DocumentCamera = NoDocumentCamera,
    internal val tt: TextTemplates = NoTextTemplates,
) : ViewModel() {
    private val _state = MutableStateFlow(ScannerUiState(missingKey = if (secrets.isMissing) secrets.missingMessage else null, tt = tt.state.value))
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    private val _effects = Channel<ScannerEffect>(Channel.BUFFERED)
    val effects: Flow<ScannerEffect> = _effects.receiveAsFlow()

    /** Bumped on every mode switch (and cancel); async work started earlier drops its result. */
    private var modeGeneration = 0

    /** The result waiting out the success flash; cancelled by a mode switch or close so it can't land later. */
    private var pendingShow: Job? = null

    /** The running entitlement check; a mode switch cancels it. */
    private var entitlementJob: Job? = null

    /** What the current alert's "Try again" re-runs. */
    private var retry: (() -> Unit)? = null

    /** A sheet's hide animation is running (between DismissSheet and SheetDismissed). */
    private var dismissing = false

    /** Viewfinder rect in camera-view px, from [ScannerAction.FrameChanged]. */
    private var frame: RectF? = null

    /** Price tag: per SKU, the timer that ends "still in view" ([TAG_GONE_MS] with no report). */
    private val tagInView = mutableMapOf<String, Job>()

    /** [ScannerAction.UpdatePrefs] writes not yet persisted. */
    private var prefWrites = 0

    /** Item-label feedback upload; replaced in tests (it is network I/O). */
    internal var submitFeedback: suspend (Bitmap, OcrResult, Map<String, ItemLabelFeedback.Entry>, String) -> String = ItemLabelFeedback::submit

    /** ARCore reported installed (requestInstall), so AR Item Count skips the availability check. */
    private var arInstalled = false
    private val doc = DocumentFlow(document, viewModelScope, object : DocumentFlow.Host {
        override val s get() = _state.value
        override val generation get() = modeGeneration
        override fun update(t: (ScannerUiState) -> ScannerUiState) = _state.update(t)
        override fun show(r: ScanResult) = this@ScannerViewModel.show(r)
        override fun flash() = flashOnce()
        override fun toast(text: String) = this@ScannerViewModel.toast(text)
        override fun effect(e: ScannerEffect) { _effects.trySend(e) }
    })

    /** The owner last handed the sensor ([configureCamera]), and the pending hand-off between the SDK camera
     *  and Text Templates' `PXScannerView`. */
    private var claimed = CameraOwner.None
    private var handoff: Job? = null

    private val s get() = _state.value
    private val usesScanner get() = ownerFor(s.mode, s.tt.stream) == CameraOwner.Scanner
    /** A camera the user drives (torch, flip, focus): the SDK scanner, or Document Acquisition's (iOS usesScanner). */
    private val ownCamera get() = usesScanner || s.mode == ScanMode.DocAcq
    /** AR Item Count: the mode runs on the AR session (spec 5.10). */
    private val usesAr get() = ownerFor(s.mode) == CameraOwner.Ar

    init {
        // The cameras are singletons that outlive a ViewModel (Back, then relaunch): match them to the fresh state.
        camera.lens(false)
        document.lens(false)
        ar.resume()   // no result, not paused; entering AR re-syncs with the camera pause (syncAr)
        ar.tracing = s.prefs.arTrace   // the stored setting follows through setPrefs
        ar.blurSkip = s.prefs.arBlurSkip
        ar.overlayRules = s.prefs.arOverlayRules
        ar.outlineFarSafe = s.prefs.arOutlineFarSafe
        ar.pinRules = s.prefs.arPinRules
        ar.pinRefine = s.prefs.arPinRefine
        ar.readBoost = s.prefs.arReadBoost
        // While a local write is in flight the repo can still emit the value from before it; skip those.
        viewModelScope.launch { prefs.prefs.collect { if (prefWrites == 0) setPrefs(it) } }
        viewModelScope.launch { models.states.collect { m -> _state.update { it.copy(models = m) } } }
        viewModelScope.launch { models.versions.collect { v -> _state.update { it.copy(modelVersions = v) } } }
        viewModelScope.launch { catalog.items.collect { i -> _state.update { it.copy(items = i) } } }
        viewModelScope.launch { catalog.names.collect { n -> _state.update { it.copy(itemNames = n) } } }
        viewModelScope.launch { ar.count.collect { v -> _state.update { it.copy(arCount = v) } } }
        viewModelScope.launch { ar.codesInView.collect { c -> _state.update { it.copy(codesInView = c) } } }
        viewModelScope.launch { ar.seen.collect { c -> _state.update { it.copy(seen = c) } } }
        // The counter counts the list's codes (spec 5.10); the session keeps the list for its next counters.
        viewModelScope.launch { state.map { it.items }.distinctUntilChanged().collect { ar.setItems(it.toSet()) } }
        viewModelScope.launch { ar.errors.collect(::toast) }
        viewModelScope.launch { ar.exits.collect(::leaveAr) }
        viewModelScope.launch { tt.state.collect { t -> _state.update { it.copy(tt = t) } } }
        viewModelScope.launch { tt.refresh() }
        viewModelScope.launch { camera.paused.collect(::onPaused) }
        viewModelScope.launch { camera.events.collect(::onEvent) }
        // No idle pause while a capture or extraction runs, nor while AR Item Count runs: a worker panning the shelf
        // touches nothing, and a pause breaks the open section.
        viewModelScope.launch {
            state.map { it.phase != Phase.Idle || (it.arOn && !it.home && !it.paused && it.result == null) }
                .distinctUntilChanged().collect(camera::setBusy)
        }
        viewModelScope.launch {
            state.map(::vlmAutoArmed).distinctUntilChanged().collectLatest { if (it) { delay(VLM_AUTO_MS); shutter() } }
        }
        doc.start()
        viewModelScope.launch { state.collect(doc::sync) }
        // Repo states are in-memory; read what the SDK already has on disk / in memory.
        viewModelScope.launch {
            try { models.refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* rows stay NotDownloaded */ }
        }
        camera.userActive()
    }

    fun onAction(a: ScannerAction) {
        camera.userActive()
        // An alert button: dismiss the alert, then run its action (iOS: every AlertAction sets alert = nil first).
        if (a != ScannerAction.DismissAlert && s.alert?.actions?.any { it.action == a } == true) {
            _state.update { it.copy(alert = null) }
            if (s.result is ScanResult.Pending) closeResult()   // the failed capture's screen goes with its alert
        }
        when (a) {
            is ScannerAction.SetMode -> setMode(a.m)
            ScannerAction.GoHome -> goHome()
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
            ScannerAction.ToggleTorch -> { setTorch(!s.torch); toast(if (s.torch) "Torch on" else "Torch off") }
            ScannerAction.ToggleAuto -> toggleAuto()
            is ScannerAction.Report -> sendReport(a.fields, a.message)
            ScannerAction.CancelProcessing -> cancelProcessing()
            ScannerAction.DismissAlert -> {
                retry = null
                _state.update { it.copy(alert = null) }
                if (s.result is ScanResult.Pending) closeResult() else if (usesScanner) camera.rescan()
            }
            is ScannerAction.PermissionResult -> {
                _state.update { it.copy(permissionDenied = !a.granted) }
                if (a.granted) configureCamera()
            }
            is ScannerAction.FrameChanged -> if (a.rect != frame) { frame = a.rect; applyConfig() }
            ScannerAction.Retry -> retry.also { retry = null }?.invoke()
            ScannerAction.TorchRetry -> { retry = null; setTorch(true); camera.rescan() }
            ScannerAction.Authenticate -> checkEntitlement(s.mode, announce = true)
            is ScannerAction.DownloadModel -> downloadModel(a)
            is ScannerAction.LoadModel -> viewModelScope.launch {
                toast(runCatchingModel { models.load(a.t, a.s) } ?: "Model loaded")
            }
            is ScannerAction.UnloadModel -> models.unload(a.t, a.s)
            is ScannerAction.DeleteModel -> viewModelScope.launch { runCatchingModel { models.delete(a.t, a.s) }?.let(::toast) }
            is ScannerAction.CancelDownload -> { models.cancel(a.t, a.s); toast("Download cancelled") }
            ScannerAction.CheckUpdates -> viewModelScope.launch { toast(runCatching { models.checkUpdates() }.getOrElse { it.message ?: "Update check failed" }) }
            is ScannerAction.AddItem -> a.sku.trim().takeIf { it.isNotEmpty() }?.let { sku ->
                if (s.items.lists(sku)) {
                    toast("Code already in list")
                } else {
                    setItems(s.items + sku)
                    a.name.trim().takeIf { it.isNotEmpty() }?.let { setNames(s.itemNames + (sku to it)) }
                }
            }
            ScannerAction.AddItemsInView -> addItemsInView()
            is ScannerAction.RemoveItem -> {
                setItems(s.items - a.sku)
                if (a.sku in s.itemNames) setNames(s.itemNames - a.sku)
            }
            ScannerAction.ClearItems -> { setItems(emptyList()); setNames(emptyMap()) }
            ScannerAction.ScanNext -> { closeResult(); if (usesAr) newCount() }
            is ScannerAction.Copy -> { _effects.trySend(ScannerEffect.Copy(a.text)); toast("Copied ${a.label}") }
            is ScannerAction.SendFeedback -> sendFeedback(a.entries, a.comment)
            ScannerAction.ClearTags -> _state.update { it.copy(tags = emptyList()) }
            is ScannerAction.Zoom -> setZoom(a.ratio)
            ScannerAction.OpenItemList -> openItemList()
            is ScannerAction.SetDetectionEnabled -> setDetection(a.on)
            ScannerAction.ResetSettings -> resetSettings()
            ScannerAction.FlipCamera -> flipCamera()
            is ScannerAction.RescanDocument -> { doc.rescan(a.dropLast); closeResult() }
            is ScannerAction.ExportPdf -> doc.exportPdf(a.enhanced)
            is ScannerAction.Focus -> focus(a.x, a.y)
            is ScannerAction.ArInstallResult -> when (a.result) {
                ArInstall.Installed -> { arInstalled = true; if (usesAr && !s.home && !s.gated) startAr() }
                ArInstall.Declined -> leaveAr("AR Item Count needs Google Play Services for AR")
                ArInstall.Unsupported -> leaveAr("AR isn't supported on this device")
            }
            is ScannerAction.TtSetEmail -> setTtEmail(a.email, a.fromSetup)
            ScannerAction.TtSignOut -> { tt.signOut(); openSheet(SheetKind.TtSetup) }   // straight to sign-in, as on first use
            ScannerAction.TtSync -> if (!s.tt.syncing) viewModelScope.launch { toast(tt.sync()) }
            ScannerAction.TtLoad -> viewModelScope.launch { toast(tt.load()) }
            ScannerAction.TtUnload -> viewModelScope.launch { toast(tt.unload()) }
            ScannerAction.TtClearScans -> viewModelScope.launch { toast(tt.clearScans()) }
            ScannerAction.TtClearTemplateCache -> viewModelScope.launch { toast(tt.clearTemplateCache()) }
            is ScannerAction.TtSetPath -> setTtPath(a.path)
            is ScannerAction.TtRepredict -> repredict(a.templateId)
        }
    }

    // MARK: Mode and camera

    /** Opens [m]'s camera from the module cards, or switches the open camera to [m]. */
    private fun setMode(m: ScanMode) {
        if (m == s.mode && !s.home) return
        if (!s.home) leaveMode()   // from home, the mode was already left
        if (m == ScanMode.DocAcq) doc.enter()
        _state.update {
            it.copy(
                home = false, mode = m, result = null, sheet = null, pendingSheet = null, phase = Phase.Idle, boxes = emptyList(),
                codeInFrame = false, seesDocument = false, seesText = false, torch = false, gated = m.gated,
                entitlementChecking = false, feedback = null, ttGuidance = null,
            )
        }
        if (!s.permissionDenied) configureCamera()
        syncAr()
        if (m == ScanMode.TextTemplates && !s.tt.hasEmail) openSheet(SheetKind.TtSetup)   // account first (iOS setup gate)
    }

    /** Back to the module cards: the mode is left as for a switch and no camera runs until a card is opened. */
    private fun goHome() {
        if (s.home) return
        leaveMode()
        _state.update {
            it.copy(
                home = true, result = null, sheet = null, pendingSheet = null, alert = null, phase = Phase.Idle, boxes = emptyList(),
                codeInFrame = false, seesDocument = false, seesText = false, entitlementChecking = false, feedback = null,
            )
        }
        configureCamera()   // no owner on the module cards
    }

    /** Ends the current mode's work and releases its camera (iOS setMode, the leaving half). */
    private fun leaveMode() {
        if (usesAr) {
            ar.detach()   // release ARCore's camera before the scanner claims it (iOS :250)
            _state.update { it.copy(arOn = false) }
        }
        pendingShow?.cancel(); pendingShow = null
        entitlementJob?.cancel(); entitlementJob = null
        modeGeneration++
        retry = null
        dismissing = false
        setTorch(false)
        if (s.mode == ScanMode.DocAcq) doc.leave()   // drops the pages, releases CameraX before the next owner claims
        setZoom(1f)
    }

    /** Hands the sensor to the mode's owner (none on the module cards); for the scanner, applies its config; for gated
     *  modes, the entitlement check (AR Item Count's AR session starts once it passes). */
    private fun configureCamera() {
        val owner = if (s.home) CameraOwner.None else ownerFor(s.mode, s.tt.stream)
        val from = claimed
        claimed = owner
        handoff?.cancel(); handoff = null
        if (s.ttCameraReady) _state.update { it.copy(ttCameraReady = false) }   // unmounts PXScannerView
        when {
            // PXScannerView unbinds all of CameraX when it leaves: let it go before the SDK camera starts (iOS handOffCamera).
            owner == CameraOwner.Scanner && from == CameraOwner.TextTemplates ->
                handoff = viewModelScope.launch { delay(HANDOFF_MS); handoff = null; startScanner() }
            owner == CameraOwner.Scanner -> startScanner()
            else -> {
                camera.claim(owner)
                // ... and PXScannerView binds only once the SDK camera has released the sensor.
                if (owner == CameraOwner.TextTemplates) {
                    handoff = viewModelScope.launch { delay(HANDOFF_MS); handoff = null; _state.update { it.copy(ttCameraReady = true) } }
                }
                // AR Item Count: the entitlement check first; its AR session starts once it passes (spec 5.10)
                if (!s.home && owner == CameraOwner.Ar && s.mode.gated) checkEntitlement(s.mode)
            }
        }
    }

    /** The SDK camera runs for the mode: its config, detection and (gated modes) the entitlement check. */
    private fun startScanner() {
        camera.claim(CameraOwner.Scanner)
        applyConfig()
        if (s.result == null && (s.sheet == null || s.sheet == SheetKind.Items)) resumeDetection()
        if (s.mode.gated) checkEntitlement(s.mode)
    }

    private fun applyConfig() {
        if (!usesScanner) return
        val p = s.prefs
        val auto = p.autoCapture && (s.mode.isCode || s.mode.isDocument)
        camera.apply(scannerConfig(s.mode, p.multi, p.showBoxes, p.vlm), frame, if (auto) ScanningMode.Auto else ScanningMode.Manual)
        if (!s.detectionEnabled) camera.pauseDetection()   // configuring may resume detection (iOS :259)
    }

    /** Detection runs only while enabled in Settings › Advanced (iOS `detectionEnabled`). */
    private fun resumeDetection() { if (s.detectionEnabled) camera.resumeDetection() }

    private fun setDetection(on: Boolean) {
        _state.update { it.copy(detectionEnabled = on) }
        if (!usesScanner) return
        // Resumes only where detection would run anyway; under Settings it resumes once the sheet closes.
        if (!on) camera.pauseDetection() else if (s.result == null && (s.sheet == null || s.sheet == SheetKind.Items)) camera.resumeDetection()
    }

    /** iOS SettingsSheet `reset()` (Sheets.swift:161-166). */
    private fun resetSettings() {
        onAction(ScannerAction.UpdatePrefs(resetPrefs))
        setDetection(true)
        toast("Settings reset to defaults")
    }

    /** iOS `flipCamera`: a facing switch turns the torch off and resets the zoom. */
    private fun flipCamera() {
        if (!ownCamera) return
        val front = !s.frontCamera
        setTorch(false)
        setZoom(1f)   // resets the camera's own zoom ratio too, not just the state (setZoom routes to camera/document)
        _state.update { it.copy(frontCamera = front) }
        if (s.mode == ScanMode.DocAcq) document.lens(front) else camera.lens(front)
        toast(if (front) "Front camera" else "Back camera")
    }

    /** Tap-to-focus and its ring, only on the live camera (iOS CameraScreen :17-21); in AR Item Count, the AR session's. */
    private fun focus(x: Float, y: Float) {
        if (s.result != null || s.sheet != null || s.alert != null) return
        when {
            s.mode == ScanMode.Retrieval -> if (s.arOn && !s.paused) ar.focus(x, y) else return
            !ownCamera -> return
            s.mode == ScanMode.DocAcq -> document.focus(x, y)
            else -> camera.focus(x, y)
        }
        _state.update { it.copy(focus = FocusTap(x, y, (it.focus?.id ?: 0) + 1)) }
    }

    private fun setPrefs(p: Prefs) {
        val old = s.prefs
        if (p == old) return
        _state.update { it.copy(prefs = p) }
        if (old.arTrace != p.arTrace) ar.tracing = p.arTrace
        if (old.arBlurSkip != p.arBlurSkip) ar.blurSkip = p.arBlurSkip
        if (old.arOverlayRules != p.arOverlayRules) ar.overlayRules = p.arOverlayRules
        if (old.arOutlineFarSafe != p.arOutlineFarSafe) ar.outlineFarSafe = p.arOutlineFarSafe
        if (old.arPinRules != p.arPinRules) ar.pinRules = p.arPinRules
        if (old.arPinRefine != p.arPinRefine) ar.pinRefine = p.arPinRefine
        if (old.arReadBoost != p.arReadBoost) ar.readBoost = p.arReadBoost
        if (old.multi != p.multi || old.showBoxes != p.showBoxes || old.autoCapture != p.autoCapture || old.vlm != p.vlm) {
            applyConfig()
            if (old.multi != p.multi && usesScanner) camera.rescan()   // drops any half-finished single capture
        }
    }

    private fun onPaused(paused: Boolean) {
        _state.update { it.copy(paused = paused) }
        if (paused) setTorch(false)   // iOS pauseCamera: torch = false
        syncAr()
    }

    /** ARCore runs unless the camera is paused (heat, idle, background) or a result covers it (iOS :482/:504).
     *  It keeps running under the sheets: a pause would break the section's tracking segment, and Add Item reads the
     *  codes in view. */
    private fun syncAr() {
        if (!s.arOn || s.home) return
        if (s.paused || s.result != null) ar.pause() else ar.resume()
    }

    /** AR Item Count past its gate (spec 5.10): ARCore's install first if it isn't there, else the AR session, which
     *  counts the list's codes. */
    private fun startAr() {
        if (!arInstalled && !ar.installed()) { _effects.trySend(ScannerEffect.InstallArCore); return }
        _state.update { it.copy(arOn = true) }
        ar.setItems(s.items.toSet())
        syncAr()
    }

    /** "New Scan" (spec 5.10): a new counter, the count and the seen codes gone; the list is kept. */
    private fun newCount() {
        ar.reset()
        ar.setItems(s.items.toSet())
    }

    /** AR Item Count can't run here (no ARCore, spec 6: no session, or no app stream configures): the message, then
     *  the module cards. */
    private fun leaveAr(message: String) {
        if (!usesAr || s.home) return
        toast(message)
        goHome()
    }

    private fun setTorch(on: Boolean) {
        if (on == s.torch) return
        _state.update { it.copy(torch = on) }
        if (s.mode == ScanMode.DocAcq) document.torch(on) else camera.torch(on)
    }

    /**
     * The SDK's check is `enable*Mode` on the live view, which also switches the view into that mode, so it runs
     * on every entry. [rescan] after a pass when detection was refused mid-scan (iOS :927); [announce] for the gate card.
     */
    private fun checkEntitlement(mode: ScanMode, announce: Boolean = false, rescan: Boolean = announce) {
        if (!mode.gated) return
        _state.update { it.copy(gated = true, entitlementChecking = true) }
        entitlementJob?.cancel()
        entitlementJob = viewModelScope.launch {
            // The check switches the view into [mode]; one that outlived its mode (cancelled, or finishing
            // past the cancel) re-applies the current mode's config.
            val r = try { entitlement.check(camera.view, mode) } finally { if (s.mode != mode) applyConfig() }
            if (s.mode != mode || !isActive) return@launch   // a newer check replaced this one
            _state.update {
                it.copy(
                    gated = r.isFailure, entitlementChecking = false,
                    notEntitled = if (r.isFailure) it.notEntitled + mode else it.notEntitled - mode,
                    arOn = it.arOn && r.isSuccess,
                )
            }
            if (r.isSuccess) {
                if (announce) toast("Authenticated")
                if (usesAr) startAr() else if (rescan) camera.rescan()
            } else if (announce) {
                val e = r.exceptionOrNull()
                toast(if (e is IOException) "You're offline. Try again once connected." else e?.message ?: "Not entitled for ${mode.label}")
            }
        }
    }

    // MARK: Shutter

    private fun shutter() {
        if (s.phase != Phase.Idle || s.result != null || pendingShow != null || s.gated || s.permissionDenied) return
        if (!s.detectionEnabled && usesScanner) return toast("Detection is paused. Resume it in Settings › Advanced.")
        when (s.mode) {
            ScanMode.Price -> show(ScanResult.Price)   // iOS shows the drawer even with no tags yet
            ScanMode.DocAcq -> doc.shutter()
            ScanMode.Retrieval -> {
                if (s.items.isEmpty()) return _state.update { it.copy(alert = noItemsAlert) }
                // The codes in view and the listed codes counted so far; the drawer's New Scan starts a fresh count.
                show(ScanResult.Retrieval(retrievalRows(s.codesInView, s.items, s.arCount.items, s.itemNames)))
            }
            ScanMode.TextTemplates -> when {
                !s.tt.hasEmail -> openSheet(SheetKind.TtSetup)
                s.tt.loadedIds.isEmpty() -> _state.update { it.copy(alert = ttNotLoadedAlert(s.tt.hasEmail)) }
                s.tt.stream -> toast("Stream captures on its own. Hold a label in view.")   // the SDK camera is off in Stream
                else -> {
                    flashOnce()
                    _state.update { it.copy(phase = Phase.Scanning) }
                    camera.capture()
                }
            }
            ScanMode.Barcode, ScanMode.QR, ScanMode.Ocr -> {
                // Wild card prepares its own models (iOS :427).
                if (s.mode == ScanMode.Ocr && !s.prefs.wildCard && !cloudSelected(s.prefs) && !ensureModelReady()) return
                flashOnce()
                _state.update { it.copy(phase = Phase.Scanning) }
                camera.capture()
            }
        }
    }

    private fun flashOnce() {
        _state.update { it.copy(flash = true) }
        viewModelScope.launch { delay(150); _state.update { it.copy(flash = false) } }
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
            is ScanEvent.Boxes -> onBoxes(if (s.mode == ScanMode.QR) e.qr else e.barcodes + e.qr)   // iOS :945
            is ScanEvent.Captured -> when (s.mode) {
                ScanMode.Ocr -> runOcr(e.bitmap, e.codes)
                ScanMode.TextTemplates -> predictTemplate(e.bitmap)
                else -> _state.update { it.copy(phase = Phase.Idle) }
            }
            is ScanEvent.PriceTag -> {   // iOS codeScannerViewDidCapturePrice: collect unique tags; the shutter shows them
                if (s.mode != ScanMode.Price) return
                val tag = PriceTag.from(e.data.productSKU, e.data.productPrice)
                // The SDK reports a tag again on every agreeing read while it stays in view: only a tag that
                // comes back after [TAG_GONE_MS] out of view is a rescan.
                val rescan = tag.sku !in tagInView
                tagInView.remove(tag.sku)?.cancel()
                tagInView[tag.sku] = viewModelScope.launch { delay(TAG_GONE_MS); tagInView.remove(tag.sku) }
                val listed = s.tags.any { it.sku == tag.sku }
                if (listed && !rescan) return
                if (!listed) _state.update { it.copy(tags = it.tags + tag) }
                // Auto capture: a new tag, or a listed one scanned again, opens the list, as Auto opens a code's result.
                // Manual keeps collecting for the shutter; a listed tag scanned again says so (its count doesn't change).
                if (s.prefs.autoCapture && s.result == null && pendingShow == null && s.sheet == null && s.alert == null &&
                    s.phase == Phase.Idle && !s.gated) show(ScanResult.Price)
                else if (listed && !s.prefs.autoCapture) toast("${tag.name} already in list")
            }
            is ScanEvent.Failure -> onFailure(e.e)
            // Vision Scanner's hint text (iOS seesText/seesDocument); DocAcq gets the same fields from its
            // own boundary detector (DocumentFlow), not from this SDK callback. Only Vision Scanner reads them: in other
            // modes the per-frame flags would change the state (and recompose the screen) for nothing.
            is ScanEvent.Indications -> if (s.mode == ScanMode.Ocr || s.mode == ScanMode.TextTemplates) _state.update { it.copy(seesText = e.text, seesDocument = e.document) }
            ScanEvent.Started -> {}
        }
    }

    private fun onBoxes(codes: List<ScannedCodeResult>) {
        val cfg = scannerConfig(s.mode, s.prefs.multi, s.prefs.showBoxes, s.prefs.vlm)
        val detected = codes.map { it.toDetected() }
        val f = frame?.takeIf { cfg.restrictToFrame && !it.isEmpty }
        val inFrame = if (f != null) {
            val fb = Box(f.left.toInt(), f.top.toInt(), f.right.toInt(), f.bottom.toInt())
            detected.any { it.box.inside(fb) }
        } else {
            detected.isNotEmpty()
        }
        // Where the SDK draws the boxes itself (cfg.sdkDrawsBoxes) the app's BoxesOverlay stays empty.
        _state.update { it.copy(codeInFrame = inFrame, boxes = if (cfg.showBoxes && !cfg.sdkDrawsBoxes) detected else emptyList()) }
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

    /** iOS `noCodeFound`: retry rescans and captures again. */
    private fun noCodeFound() {
        val (title, message, extra) = noCodeCopy(s.mode, s.torch)
        fail(title, message, retry = { camera.rescan(); shutter() }, extra = extra)
    }

    // MARK: Vision Scanner (OCR)

    private fun runOcr(bitmap: Bitmap, codes: List<ScannedCodeResult>) {
        val gen = modeGeneration
        val p = s.prefs
        val retryThis = { runOcr(bitmap, codes) }
        val title = if (p.wildCard) "Wild card" else VlmPrompts.spec(p.docType)?.title ?: p.docType.label
        openPending(bitmap, title, "Extracting · ${if (cloudSelected(p)) "Cloud" else "On-device"}")
        viewModelScope.launch {
            val outcome = runOcrExtraction(extraction, bitmap, codes, p)
            if (gen != modeGeneration) return@launch   // user switched mode or cancelled while this was in flight
            when (outcome) {
                is OcrOutcome.Done -> finish(outcome.result)
                is OcrOutcome.Failed -> fail(outcome.title, outcome.message, retryThis, outcome.extra)
            }
        }
    }

    private fun sendReport(fields: Set<String>, message: String) {
        (s.result as? ScanResult.TextTemplate)?.let { return reportTemplate(it, fields, message) }
        val r = s.result as? ScanResult.Ocr ?: return
        val modelSize = activeModel(s.prefs)?.second ?: ModelSize.Micro   // iOS `activeModel?.1 ?? .micro`
        viewModelScope.launch {
            report.report(r.result, fields, message, r.image, modelSize).fold(
                onSuccess = { toast("Report sent · ${fields.size} field${if (fields.size == 1) "" else "s"}") },
                onFailure = { toast("Report failed (${it.message})") },
            )
        }
    }

    private fun sendFeedback(entries: Map<String, ItemLabelFeedback.Entry>, comment: String) {
        val r = s.result as? ScanResult.Ocr ?: return
        val image = r.image ?: return toast("No image to send")
        viewModelScope.launch { toast(submitFeedback(image, r.result, entries, comment)) }
    }

    // MARK: Text Templates

    /** One-Shot: the SDK camera's still, predicted against the loaded pool (iOS `predictTemplate`). */
    private fun predictTemplate(bitmap: Bitmap) {
        val gen = modeGeneration
        openPending(bitmap, "Text Templates", "Predicting…")
        viewModelScope.launch {
            try {
                val r = tt.predict(bitmap)
                if (gen != modeGeneration) return@launch
                finish(ScanResult.TextTemplate(r, bitmap, TtPath.OneShot))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != modeGeneration) return@launch
                val code = (e as? PXException)?.errorCode
                if (code == PXErrorCode.NO_OCR_DATA) return@launch noCodeFound()
                val offline = code == PXErrorCode.NETWORK_ERROR || e is IOException
                fail(if (offline) "You're offline" else "Prediction failed", e.message ?: "Something went wrong", retry = { camera.rescan(); shutter() })
            }
        }
    }

    /** Stream: events from `PXScannerView`'s session, from any thread (iOS TTStreamLayer). */
    fun onTtEvent(e: PXScanEvent) {
        viewModelScope.launch {
            if (s.home || s.mode != ScanMode.TextTemplates || !s.tt.stream) return@launch   // queued before a switch
            when (e) {
                is PXScanEvent.Guidance -> _state.update { it.copy(ttGuidance = e.guidance.hint()) }
                is PXScanEvent.Failed -> _state.update { it.copy(ttGuidance = e.message) }
                is PXScanEvent.Prediction -> {
                    if (s.result != null || pendingShow != null || s.sheet != null || s.alert != null) return@launch
                    camera.userActive()
                    show(ScanResult.TextTemplate(e.result, e.result.capturedImage, TtPath.Stream))
                }
                is PXScanEvent.RegionResolved -> {}
            }
        }
    }

    private fun setTtEmail(email: String, fromSetup: Boolean) {
        if (!tt.setEmail(email)) return toast("Please enter a valid email address.")
        if (fromSetup) {
            dismissing = true
            _state.update { it.copy(sheet = null) }
            toast("Account saved. Load templates from the Templates chip.")
        } else {
            toast("Email saved")
        }
    }

    /** One-Shot uses the SDK camera, Stream `PXScannerView`'s own: hand the sensor over (iOS `setTTPath`). */
    private fun setTtPath(p: TtPath) {
        if (p == s.tt.path) return
        tt.setPath(p)
        _state.update { it.copy(tt = it.tt.copy(path = p), ttGuidance = null) }   // at once, so the hand-off sees it
        if (!s.home && s.mode == ScanMode.TextTemplates && !s.permissionDenied) configureCamera()
    }

    /** "Re-predict as…": the retained scan against another loaded template, no re-scan (iOS ResultDrawer). */
    private fun repredict(templateId: String) {
        val r = s.result as? ScanResult.TextTemplate ?: return
        val scanId = r.prediction.scanId ?: return
        viewModelScope.launch {
            try {
                val q = tt.repredict(scanId, templateId)
                if (s.result != r) return@launch   // closed or replaced meanwhile
                val next = r.copy(repredicted = q)
                _state.update { it.copy(result = next, lastResult = it.mode to next) }
                toast("As: ${q.templateName ?: templateId}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: "Re-predict failed")
            }
        }
    }

    /** iOS ReportCard `submit` for a Text Templates result: the scan's image goes up with the picked fields. */
    private fun reportTemplate(r: ScanResult.TextTemplate, fields: Set<String>, message: String) {
        val scanId = r.prediction.scanId ?: return
        val image = r.image ?: return toast("Stream results have no captured image to report")
        viewModelScope.launch {
            try {
                tt.report(scanId, image, message + " · " + fields.sorted().joinToString(", "))
                toast("Report sent · ${fields.size} field${if (fields.size == 1) "" else "s"}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Report failed (${e.message})")
            }
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

    /** The result screen opens on the photo just taken, loading until [finish] or [fail]. */
    private fun openPending(image: Bitmap, title: String, subtitle: String) {
        _state.update { it.copy(phase = Phase.Processing, result = ScanResult.Pending(image, title, subtitle)) }
        if (usesScanner) camera.pauseDetection()   // nothing to detect under the result screen
    }

    /** The extraction's result replaces the loading screen at once (no success flash: the camera is covered). */
    private fun finish(r: ScanResult) {
        _state.update { it.copy(phase = Phase.Idle) }
        haptic()
        present(r)
    }

    private fun present(r: ScanResult) {
        _state.update { it.copy(result = r, lastResult = it.mode to r, feedback = null) }
        if (usesScanner) camera.pauseDetection()   // nothing to detect under the drawer
        syncAr()
    }

    /** Retrieval drawer's "Open item list": iOS sets `result = nil` (no rescan), then opens the list. */
    private fun openItemList() {
        _state.update { it.copy(result = null) }
        openSheet(SheetKind.Items)
    }

    private fun reopenLast() {
        val (m, r) = s.lastResult ?: return
        if (m != s.mode || s.result != null || pendingShow != null) return
        present(r)
    }

    private fun closeResult() {
        if (s.result is ScanResult.Pending && s.phase == Phase.Processing) return cancelProcessing()
        if (pendingShow != null) {
            pendingShow?.cancel(); pendingShow = null
            _state.update { it.copy(feedback = null) }
        }
        _state.update { it.copy(result = null) }   // price tags stay until ClearTags / mode switch (iOS)
        if (usesScanner) {
            if (s.sheet == null || s.sheet == SheetKind.Items) resumeDetection()
            camera.rescan()
        }
        syncAr()   // AR resumes without a reset: markers and counts are kept (iOS :504)
    }

    /** Abandons a slow request (VLM can take up to 90 s): its late result is dropped via the generation, and its
     *  loading result screen closes. */
    private fun cancelProcessing() {
        if (s.phase == Phase.Idle) return
        modeGeneration++
        _state.update { it.copy(phase = Phase.Idle) }
        if (s.result is ScanResult.Pending) closeResult() else if (usesScanner) camera.rescan()
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
            resumeDetection()
        }
    }

    /** Detection pauses under sheets (the camera is covered) except the item list, whose Add Item reads the codes in view. */
    private fun presentSheet(k: SheetKind) {
        _state.update { it.copy(sheet = k) }
        if (!usesScanner || s.result != null) return
        if (k == SheetKind.Items) resumeDetection() else camera.pauseDetection()
    }

    // MARK: Item retrieval list

    private fun setItems(items: List<String>) {
        _state.update { it.copy(items = items) }   // at once; the repo echoes the same list
        viewModelScope.launch { catalog.setItems(items) }
    }

    private fun setNames(names: Map<String, String>) {
        _state.update { it.copy(itemNames = names) }   // at once; the repo echoes the same map
        viewModelScope.launch { catalog.setNames(names) }
    }

    /** iOS `addItemsInView`. */
    private fun addItemsInView() {
        val new = s.codesInView.filter { !s.items.lists(it) }.distinctBy(::codeKey)
        val first = new.firstOrNull()
            ?: return toast(if (s.codesInView.isEmpty()) "Point the camera at a code, then tap Add Item" else "Code already in list")
        setItems(s.items + new)
        toast(if (new.size == 1) "Scanned and added $first" else "Added ${new.size} codes")
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

    private fun setZoom(ratio: Float) {
        if (ratio == s.zoom) return
        _state.update { it.copy(zoom = ratio) }
        if (s.mode == ScanMode.DocAcq) document.zoom(ratio) else camera.zoom(ratio)
    }

    // MARK: Effects

    private fun toast(text: String) { _effects.trySend(ScannerEffect.Toast(text)) }

    private fun haptic() { if (s.prefs.sound) _effects.trySend(ScannerEffect.Haptic) }

    private companion object {
        const val IN_VIEW_MS = 1_000L
        const val TAG_GONE_MS = 2_000L
        /** Time for one camera pipeline to let the sensor go before the other binds (iOS handOffCamera's 300 ms). */
        const val HANDOFF_MS = 300L
    }
}
