package io.packagex.texttemplates.sdk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.Room
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.mlkit.vision.common.InputImage
import io.packagex.texttemplates.aggregation.BarcodeAggregator
import io.packagex.texttemplates.aggregation.FrameAggregator
import io.packagex.texttemplates.aggregation.OcrConsensus
import io.packagex.texttemplates.camera.BlurDetector
import io.packagex.texttemplates.camera.FrameAnalyzer
import io.packagex.texttemplates.camera.IMUStabilityMonitor
import io.packagex.texttemplates.camera.LowLightTorchController
import io.packagex.texttemplates.BuildConfig
import io.packagex.texttemplates.camera.PerFrameOcrLog
import io.packagex.texttemplates.data.local.AppDatabase
import io.packagex.texttemplates.data.remote.ApiResult
import io.packagex.texttemplates.data.remote.BaseUrlInterceptor
import io.packagex.texttemplates.data.remote.IdentityHeaderInterceptor
import io.packagex.texttemplates.data.remote.PredictionApi
import io.packagex.texttemplates.data.repository.TemplateRepository
import io.packagex.texttemplates.extraction.BarcodeExtractor
import io.packagex.texttemplates.extraction.OcrExtractor
import io.packagex.texttemplates.extraction.models.BarcodeFrameResult
import io.packagex.texttemplates.extraction.models.OcrFrameResult
import io.packagex.texttemplates.prediction.PredictionEngine
import io.packagex.texttemplates.prediction.ProcessedTemplate
import io.packagex.texttemplates.prediction.RectD
import io.packagex.texttemplates.prediction.TemplateCandidate
import io.packagex.texttemplates.prediction.TemplateDetectionResult
import io.packagex.texttemplates.prediction.computeFocusRects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Stateful entry point to the on-device prediction pipeline. NOT a singleton
 * and NOT Hilt-dependent — construct one per configuration and hold it. Owns
 * its own network stack (OkHttp/Retrofit) and template cache (Room); the host
 * supplies only a [Context] and a [PXConfiguration].
 *
 * Integration modes mirror the iOS SDK:
 *  - One-Shot: [predict] a single image, then [repredict] against another
 *    loaded template.
 *  - Host-Frames / SDK-Camera: [makeScanSession]→ [PXScanSession] (fed frames
 *    directly, or via [PXScannerView]).
 *
 * Android note: the constructor takes a [Context] (needed for Room + motion
 * sensors); otherwise this matches iOS `init(configuration)`.
 */
class PXClient(
    context: Context,
    private val config: PXConfiguration,
    /** Room cache file name. Use a distinct name to run an isolated client (its
     *  own template cache + loaded pool) alongside another `PXClient` in the same
     *  process — e.g. a demo client separate from the host app's. */
    private val databaseName: String = "pxtexttemplates.db",
    /** Optional live source for the `X-User-Email` header, read on EVERY request.
     *  Supply this when the user can enter/change their email while the client is
     *  alive (e.g. a settings screen) — [PXConfiguration.userEmail] is a snapshot
     *  taken at construction and never updates. Return null/blank to omit the
     *  header. Defaults to the config snapshot. */
    private val userEmailProvider: (() -> String?)? = null,
) {
    private val appContext: Context = context.applicationContext

    // ---- Self-owned data layer (no Hilt) ----
    private val gson: Gson = GsonBuilder().serializeSpecialFloatingPointValues().create()

    private val baseUrlInterceptor = BaseUrlInterceptor().apply {
        // Base URL is baked in at build time (BuildConfig.PX_BASE_URL), not taken
        // from PXConfiguration. Override via the PX_BASE_URL env var / -PpxBaseUrl.
        baseUrl = BuildConfig.PX_BASE_URL.trim().trimEnd('/')
    }

    // Shared cross-platform network timeout policy: 10s per-stage (connect/read/
    // write) + 30s overall per call. Kept in lockstep with iOS
    // (URLSession request=10s idle, resource=30s overall) so both platforms fail
    // in the same window.
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(baseUrlInterceptor)
        .addInterceptor(
            IdentityHeaderInterceptor(config.apiKey, userEmailProvider ?: { config.userEmail })
        )
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val api: PredictionApi = Retrofit.Builder()
        .baseUrl("http://localhost/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(PredictionApi::class.java)

    private val db: AppDatabase = Room.databaseBuilder(
        appContext, AppDatabase::class.java, databaseName,
    ).fallbackToDestructiveMigration().build()

    private val repository = TemplateRepository(api, db.templateDao(), gson)

    // ---- Compute layer (shared) ----
    // debugEnabled: capture the per-prediction debug logs so each scan can be
    // stored (for repredict) and reported. The captured frame image is stripped
    // before persisting — the host supplies the image at report() time.
    private val predictionEngine = PredictionEngine().apply { debugEnabled = true }
    private val ocrExtractor = OcrExtractor()
    private val barcodeExtractor = BarcodeExtractor()

    /** On-disk scan cache (debug logs + retained extraction, no image) keyed by
     *  scanId; bounded LRU. Backs [repredict], [report], and [clearScanCache]. */
    private val scanStore = PXScanStore(appContext.filesDir)

    /** Shared across scan sessions so the debug surface sees per-frame OCR. */
    private val perFrameOcrLog = PerFrameOcrLog()

    // ---- Loaded template pool ----
    private val pool = LinkedHashMap<String, TemplateCandidate>()
    private var lockedId: String? = null
    private var lastDetection: TemplateDetectionResult? = null

    /** Serializes all access to the mutable pool state ([pool], [lockedId],
     *  [lastDetection]). `load`/`unload` mutate it on
     *  `Dispatchers.IO` while `predict`/`repredict`/`resolveTemplate` and the scan
     *  session's callbacks read/write it on `Dispatchers.Default`, so concurrent
     *  access is a data race without this. Critical sections are short and never
     *  suspend — network/OCR/engine work runs outside the lock. iOS gets this for
     *  free via `@MainActor`. */
    private val lock = Any()

    // =====================================================================
    // Template sync + pool management
    // =====================================================================

    /** Map an API failure to a symbolic error code, mirroring iOS
     *  (`server_error` when the server responded with a status, `network_error`
     *  when the request never reached it). The numeric HTTP status stays in the
     *  message text. */
    private fun apiErrorCode(httpCode: Int?): PXErrorCode =
        if (httpCode != null) PXErrorCode.SERVER_ERROR else PXErrorCode.NETWORK_ERROR

    /**
     * Heavy sync: server list + prefetch each template's raw/processed blobs.
     *
     * @param onProgress optional blob-prefetch progress `(completed, total)`
     *   (`total` = blobs to fetch this sync; not called when nothing needs
     *   fetching). **Fires on a background thread — marshal to your UI thread.**
     *   Use it to drive a determinate progress bar.
     *
     * Cancellation: cooperative — cancel the calling coroutine/scope and this
     * throws `CancellationException`, aborting further fetches. Blobs already
     * cached are kept.
     */
    suspend fun syncTemplates(
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
    ): PXTemplateSyncResult = withContext(Dispatchers.IO) {
        when (val r = repository.reloadAndCacheTemplates(onProgress)) {
            is ApiResult.Success ->
                PXTemplateSyncResult(r.data.total, r.data.added, r.data.refreshed, r.data.removed, r.data.prefetchFailed)
            is ApiResult.Error -> throw PXException(r.message, apiErrorCode(r.code))
        }
    }

    /**
     * Parse processed templates into the in-memory prediction pool.
     * - `null`  → all cached (detection across all).
     * - `[a]`   → locked (detection skipped).
     * - `[a,b]` → a subset.
     */
    suspend fun load(templateIds: List<String>? = null) = withContext(Dispatchers.IO) {
        val cached = repository.getCachedTemplatesSync()
        val nameById = cached.associate { it.id to it.name }
        val known = cached.map { it.id }
        val ids = templateIds ?: known
        // Fail fast against the local cache — load() only activates templates that
        // syncTemplates() has already synced (syncTemplates is the sole network
        // sync). Mirrors iOS: empty cache -> no_templates_available, an id absent
        // from the synced list -> template_not_found (no silent empty pool, no
        // hidden network round-trip for an unknown id).
        if (ids.isEmpty()) throw PXException(
            "No templates are cached. Call syncTemplates() before load().",
            PXErrorCode.NO_TEMPLATES_AVAILABLE,
        )
        val knownSet = known.toSet()
        val missing = ids.filter { it !in knownSet }
        if (missing.isNotEmpty()) throw PXException(
            "Template(s) not found in cache: ${missing.joinToString()}. Call syncTemplates() first.",
            PXErrorCode.TEMPLATE_NOT_FOUND,
        )
        // Parse in parallel; a bad blob is skipped for the "all" case, surfaced
        // for an explicit request. Warm the OCR/barcode models CONCURRENTLY so the
        // first predict()/scan after load() doesn't pay the one-time ML Kit
        // model-init cost — it overlaps the blob parse, so it adds no meaningful
        // wall-clock time. Best-effort (see prewarm()); never fails load().
        val loaded = coroutineScope {
            val warm = async(Dispatchers.Default) { prewarm() }
            val results = ids.map { id ->
                async(Dispatchers.IO) {
                    when (val proc = repository.getProcessedTemplate(id)) {
                        is ApiResult.Success -> id to TemplateCandidate(id, nameById[id] ?: "", proc.data)
                        is ApiResult.Error -> {
                            if (templateIds != null) throw PXException("Failed to load template $id: ${proc.message}", apiErrorCode(proc.code))
                            null
                        }
                    }
                }
            }.mapNotNull { it.await() }
            warm.await()
            results
        }
        synchronized(lock) {
            pool.clear()
            loaded.forEach { (id, cand) -> pool[id] = cand }
            // Best-effort load(null) can skip every template if all cached blobs fail
            // to fetch/parse — surface that rather than leaving a silent empty pool
            // (parity with iOS, which throws noTemplatesAvailable here). The explicit
            // path already threw on the first failure above, so it never reaches here empty.
            if (pool.isEmpty()) throw PXException(
                "No templates could be loaded (all cached blobs failed to fetch/parse).",
                PXErrorCode.NO_TEMPLATES_AVAILABLE,
            )
            lockedId = if (templateIds != null && templateIds.size == 1) templateIds.first() else null
        }
    }

    fun unload() {
        synchronized(lock) {
            pool.clear()
            lockedId = null
            lastDetection = null
        }
    }

    /** ids of the templates currently parsed into the active prediction pool.
     *  Mirrors iOS `getLoadedTemplateIds()`. */
    fun getLoadedTemplateIds(): List<String> = synchronized(lock) { pool.keys.toList() }

    /** id + name for every cached template, read fresh from the on-disk Room
     *  store on every call — so it reflects the persisted cache immediately, even
     *  on a cold start before any [syncTemplates]/[load] in this instance.
     *  `suspend` (not synchronous like iOS) because Room has no safe main-thread
     *  read — same platform-forced split as [clearTemplateCache]. */
    suspend fun getTemplates(): List<PXTemplateInfo> = withContext(Dispatchers.IO) {
        repository.getCachedTemplatesSync().map { PXTemplateInfo(it.id, it.name) }
    }

    /** Erase all locally cached templates: clear the on-disk template store and
     *  unload the in-memory pool. A later [syncTemplates] re-downloads from the
     *  backend. */
    suspend fun clearTemplateCache() = withContext(Dispatchers.IO) {
        repository.clearCache()
        unload()
    }

    /**
     * Warm the on-device OCR + barcode models on a tiny blank frame so the first
     * real prediction doesn't pay the one-time ML Kit model-init cost. Internal:
     * [load] calls this concurrently with the template parse, so the models are
     * warm by the time load() returns — hosts never call it directly. Idempotent
     * and independent of the template pool.
     */
    internal suspend fun prewarm() = withContext(Dispatchers.Default) {
        try {
            val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val img = InputImage.fromBitmap(bmp, 0)
            coroutineScope {
                val ocrDef = async { ocrExtractor.extract(img) }
                val bcDef = async { barcodeExtractor.extract(img) }
                ocrDef.await(); bcDef.await()
            }
            bmp.recycle()
        } catch (_: Exception) {
            // Non-fatal — warmup is best-effort.
        }
    }

    // =====================================================================
    // One-Shot prediction
    // =====================================================================

    /**
     * One-Shot over a still image on disk — the bridge-friendly entry point for
     * RN/Flutter (pass a file path / `content://` URI; nothing large crosses the
     * bridge but a string). [regionOfInterest] is 0–1 in the upright
     * frame (converted to pixels internally). Loads the image, then delegates to
     * [predict].
     */
    suspend fun predict(
        filePath: String,
        regionOfInterest: PXRegion? = null,
    ): PXPredictionResult = withContext(Dispatchers.IO) {
        val uri = if (filePath.contains("://")) Uri.parse(filePath) else Uri.fromFile(java.io.File(filePath))
        val image = try {
            InputImage.fromFilePath(appContext, uri)
        } catch (e: Exception) {
            throw PXException("Could not load image at $filePath: ${e.message}", PXErrorCode.IMAGE_DECODING_FAILED)
        }
        predict(image, regionOfInterest)
    }

    /**
     * One-Shot over encoded image bytes (JPEG/PNG) — e.g. from an RN/Flutter image
     * picker. [regionOfInterest] is 0–1 in the upright frame.
     *
     * Orientation: `BitmapFactory` decodes raw pixels and **ignores EXIF**, so by
     * default ([rotationDegrees] = null) the SDK reads the bytes' EXIF orientation
     * and applies it — an EXIF-rotated JPEG then orients the same way it does on
     * iOS (`UIImage`) and via [predict] `filePath` (ML Kit honors EXIF). Pass an
     * explicit [rotationDegrees] (0/90/180/270) to override the EXIF value when you
     * already know the rotation (e.g. bytes with no/inaccurate EXIF).
     */
    suspend fun predict(
        imageBytes: ByteArray,
        rotationDegrees: Int? = null,
        regionOfInterest: PXRegion? = null,
    ): PXPredictionResult = withContext(Dispatchers.Default) {
        val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw PXException("Could not decode image bytes", PXErrorCode.IMAGE_DECODING_FAILED)
        val rotation = rotationDegrees ?: exifRotationDegrees(imageBytes)
        val image = InputImage.fromBitmap(bmp, rotation)
        predict(image, regionOfInterest)
    }

    /** Read the EXIF orientation of encoded image bytes as clockwise degrees
     *  (0/90/180/270). Best-effort — 0 if there's no EXIF or it can't be read.
     *  Keeps [predict] `imageBytes` consistent with the EXIF-honoring `filePath`
     *  path and with iOS. */
    private fun exifRotationDegrees(bytes: ByteArray): Int = runCatching {
        val exif = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
        when (exif.getAttributeInt(
            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
        )) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    /**
     * One-Shot over an Android [Bitmap] — the natural type for a still image the
     * host already holds (a captured photo, a gallery/picker image, etc.).
     * [rotationDegrees] is the rotation needed to make the bitmap upright
     * (0/90/180/270). A bitmap straight from the camera is in the sensor's
     * landscape orientation, so pass its real rotation (a CameraX frame's
     * `imageInfo.rotationDegrees`, typically 90 on a portrait-held phone); use 0
     * only when the pixels are already upright. [regionOfInterest] is 0–1 in the
     * upright frame. The host needs no third-party imaging dependency.
     */
    suspend fun predict(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        regionOfInterest: PXRegion? = null,
    ): PXPredictionResult = withContext(Dispatchers.Default) {
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        predict(image, regionOfInterest)
    }

    /**
     * Internal core: the other `predict(...)` overloads decode the host's input
     * into the on-device image type and delegate here. Not part of the public API
     * — hosts pass a file path, encoded bytes, or a [Bitmap], never this type.
     * [regionOfInterest] is NORMALIZED 0–1 in the upright frame, denormalized to
     * pixels internally.
     */
    internal suspend fun predict(
        image: InputImage,
        regionOfInterest: PXRegion? = null,
    ): PXPredictionResult = withContext(Dispatchers.Default) {
        if (synchronized(lock) { pool.isEmpty() }) {
            throw PXException(
                "No templates are loaded. Call load(templateIds) before predict().",
                PXErrorCode.NOT_LOADED,
            )
        }
        val (ocr, barcodes) = coroutineScope {
            val ocrDef = async { ocrExtractor.extract(image) }
            val bcDef = async { barcodeExtractor.extract(image) }
            ocrDef.await() to bcDef.await()
        }
        val rotation = image.rotationDegrees
        val swap = rotation == 90 || rotation == 270
        val uprightW = if (swap) image.height else image.width
        val uprightH = if (swap) image.width else image.height
        val (previewRect, defaultInner) = computeFocusRects(uprightW.toDouble(), uprightH.toDouble())
        // Denormalize the 0–1 ROI into upright-frame pixels the engine consumes.
        val innerBox: RectD? = regionOfInterest?.let {
            RectD(
                (it.left * uprightW).toDouble(),
                (it.top * uprightH).toDouble(),
                ((it.right - it.left) * uprightW).toDouble(),
                ((it.bottom - it.top) * uprightH).toDouble(),
            )
        } ?: defaultInner

        // The trusted region actually used, normalized to the upright frame — so a
        // One-Shot host can draw the overlay from the result (parity with iOS +
        // the streaming RegionResolved event).
        val resolvedRoi = resolvedRegionOf(innerBox, uprightW, uprightH, host = regionOfInterest != null)

        val capture = ScanCapture(ocr, barcodes, uprightW, uprightH, 0, previewRect, innerBox)

        val resolved = resolveTemplate(ocr, barcodes)
            ?: run {
                // No confident match — still persist the scan so the host can
                // report it (detection failed) or repredict against a chosen id.
                val scanId = persistScan(templateId = null, capture = capture, debug = null)
                return@withContext PXPredictionResult(
                    templateId = null,
                    templateName = null,
                    predictions = emptyMap(),
                    detection = PXResultMapper.detection(synchronized(lock) { lastDetection }, null),
                    resolvedRegionOfInterest = resolvedRoi,
                    imageWidth = uprightW,
                    imageHeight = uprightH,
                    scanId = scanId,
                )
            }
        val (id, tmpl) = resolved
        // Run predict + capture its image-free debug snapshot atomically under the
        // engine lock, so a concurrent prediction (another predict, or a scan
        // capture) can't swap the shared debug state between them.
        // Read the engine's display-space detected barcodes under the SAME lock,
        // right after predict — so they're tied to this exact prediction and can't
        // be swapped by a concurrent predict/scan capture. Only populated because a
        // template was actually predicted; the ambiguous branch above returns an
        // empty list (the engine's lastDetectedBarcodes would be stale). Mirrors iOS.
        val (response, debug, detectedBarcodes) = synchronized(predictionEngine.engineLock) {
            val r = predictionEngine.predict(ocr, barcodes, tmpl, uprightW, uprightH, 0, previewRect, innerBox)
            val d = runCatching {
                predictionEngine.imageFreeDebugMap(id)?.let { PXScanStore.gson.toJsonTree(it).asJsonObject }
            }.getOrNull()
            Triple(r, d, predictionEngine.lastDetectedBarcodes)
        }
        val scanId = persistScan(templateId = id, capture = capture, debug = debug)
        // Read the name + detection together under the lock so a concurrent load()
        // can't swap the pool between the two reads.
        val (name, detection) = synchronized(lock) { pool[id]?.name to lastDetection }
        PXPredictionResult(
            templateId = id,
            templateName = name,
            predictions = PXResultMapper.predictions(response, uprightW, uprightH),
            detection = PXResultMapper.detection(detection, id),
            barcodes = PXResultMapper.barcodes(detectedBarcodes, uprightW, uprightH),
            resolvedRegionOfInterest = resolvedRoi,
            imageWidth = uprightW,
            imageHeight = uprightH,
            scanId = scanId,
        )
    }

    /** Persist a scan record (extraction + pre-captured image-free debug logs) and
     *  return its scanId. [debug] is captured by the caller **atomically with the
     *  prediction** (under `PredictionEngine.engineLock`), so it reflects that exact
     *  prediction rather than the shared engine's state at persist time. Best-effort
     *  — a storage failure never fails the prediction. */
    private fun persistScan(templateId: String?, capture: ScanCapture, debug: com.google.gson.JsonObject?): String {
        val scanId = java.util.UUID.randomUUID().toString()
        runCatching { scanStore.put(ScanRecord(scanId, templateId, System.currentTimeMillis(), capture, debug)) }
        return scanId
    }

    /** Build the customer-facing resolved region from the inner box actually used
     *  (in upright pixels), normalized to the frame. Non-null: falls back to a
     *  full-frame region when the inner box is null or the dims are non-positive.
     *  Mirrors iOS. */
    private fun resolvedRegionOf(
        innerBox: RectD?, frameWidth: Int, frameHeight: Int, host: Boolean,
    ): PXRegionOfInterest {
        val source = if (host) PXRegionOfInterest.Source.HOST else PXRegionOfInterest.Source.DEFAULT
        val box = innerBox
        val fw = frameWidth.toDouble(); val fh = frameHeight.toDouble()
        if (box == null || fw <= 0 || fh <= 0) {
            return PXRegionOfInterest(
                bounds = listOf(listOf(0f, 0f), listOf(1f, 1f)),
                source = source,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
            )
        }
        return PXRegionOfInterest(
            bounds = listOf(
                listOf((box.minX / fw).toFloat(), (box.minY / fh).toFloat()),
                listOf((box.maxX / fw).toFloat(), (box.maxY / fh).toFloat()),
            ),
            source = source,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
        )
    }

    /**
     * Re-run prediction on a stored scan against another loaded template — no
     * re-scan/OCR. Works for any scan still in the cache (not just the last).
     * Throws `no_retained_frame` if [scanId] was evicted/never stored, or
     * `template_not_found` if [templateId] isn't loaded.
     */
    suspend fun repredict(scanId: String, templateId: String): PXQuickResult = withContext(Dispatchers.Default) {
        val cap = scanStore.get(scanId)?.capture
            ?: throw PXException("No stored scan for id $scanId (evicted or never stored).", PXErrorCode.NO_RETAINED_FRAME)
        val cand = synchronized(lock) { pool[templateId] }
            ?: throw PXException("Template $templateId is not loaded.", PXErrorCode.TEMPLATE_NOT_FOUND)
        // predict + read the engine's display-space detected barcodes atomically
        // under the engine lock, so a concurrent prediction can't swap the shared
        // lastDetectedBarcodes between the two. Mirrors iOS.
        val (response, detectedBarcodes) = synchronized(predictionEngine.engineLock) {
            val r = predictionEngine.predict(
                cap.ocr, cap.barcodes, cand.template,
                frameWidth = cap.frameWidth, frameHeight = cap.frameHeight, rotationDegrees = cap.rotation,
                previewRect = cap.previewRect, innerBox = cap.innerBox,
            )
            r to predictionEngine.lastDetectedBarcodes
        }
        // Normalize + report dims against the reused scan capture's frame — the
        // space the retained engine geometry is in. Mirrors iOS `quick(...)`.
        PXQuickResult(
            templateId = templateId,
            templateName = cand.name,
            predictions = PXResultMapper.predictions(response, cap.frameWidth, cap.frameHeight),
            barcodes = PXResultMapper.barcodes(detectedBarcodes, cap.frameWidth, cap.frameHeight),
            imageWidth = cap.frameWidth,
            imageHeight = cap.frameHeight,
        )
    }

    /**
     * Report a stored scan to the backend for offline analysis. Sends the scan's
     * retained debug logs (internal — never exposed to the host) plus the [image]
     * the host supplies (encoded JPEG/PNG bytes) and an optional [reason]. The SDK
     * deliberately does NOT retain the image, so the host provides it here.
     * Throws `no_retained_frame` if [scanId] was evicted/never stored, or a
     * `network_error`/`server_error` on failure.
     */
    suspend fun report(scanId: String, image: ByteArray, reason: String? = null): Unit = withContext(Dispatchers.IO) {
        val record = scanStore.get(scanId)
            ?: throw PXException("No stored scan for id $scanId (evicted or never stored).", PXErrorCode.NO_RETAINED_FRAME)
        val payload = (record.debug?.deepCopy() ?: com.google.gson.JsonObject()).apply {
            addProperty("scanId", scanId)
            record.templateId?.let { addProperty("templateId", it) }
            reason?.let { addProperty("reason", it) }
            addProperty("frameImageB64", android.util.Base64.encodeToString(image, android.util.Base64.NO_WRAP))
            // ISO-8601 UTC string (matches iOS's ISO8601DateFormatter) so the
            // `reportedAt` wire type is uniform across platforms.
            addProperty("reportedAt", java.time.Instant.now().toString())
            addProperty("sdkVersion", PXTextTemplates.VERSION)
        }
        val map: Map<String, Any?> = PXScanStore.gson.fromJson(
            payload, object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type,
        )
        when (val r = repository.exportDebugData(map)) {
            is ApiResult.Success -> Unit
            is ApiResult.Error -> throw PXException(r.message, apiErrorCode(r.code))
        }
    }

    /**
     * Report a stored scan with an Android [Bitmap] (JPEG-encoded at quality 80
     * before upload). Convenience over [report] `image: ByteArray` for native
     * callers — mirrors iOS `report(scanId:image: UIImage)`.
     */
    suspend fun report(scanId: String, bitmap: Bitmap, reason: String? = null): Unit = withContext(Dispatchers.IO) {
        val baos = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        report(scanId, baos.toByteArray(), reason)
    }

    /** Delete all locally stored scans (debug logs + retained extraction) to free
     *  disk. [report] / [repredict] for those scanIds will then fail. */
    fun clearScanCache() = scanStore.clear()

    /** Number of scans currently held in the local cache (bounded, LRU). */
    fun getScanCount(): Int = scanStore.count()

    /** Resolve which loaded template a scan belongs to. Sync over the parsed
     *  pool. Null = empty pool or ambiguous (ranking in [lastDetection]). */
    private fun resolveTemplate(
        ocr: OcrFrameResult,
        barcodes: BarcodeFrameResult,
    ): Pair<String, ProcessedTemplate>? {
        // Snapshot lock state + the pool atomically, then work off the snapshot so a
        // concurrent load() can't mutate the pool mid-resolve. identifyTemplate is
        // CPU-heavy, so it runs outside the lock; lastDetection is written back under it.
        val (locked, candidates) = synchronized(lock) { lockedId to pool.values.toList() }
        locked?.let { id ->
            candidates.firstOrNull { it.id == id }?.let {
                synchronized(lock) { lastDetection = null }
                return id to it.template
            }
        }
        if (candidates.isEmpty()) { synchronized(lock) { lastDetection = null }; return null }
        if (candidates.size == 1) {
            synchronized(lock) { lastDetection = null }
            val c = candidates.first()
            return c.id to c.template
        }
        val detection = predictionEngine.identifyTemplate(ocr, barcodes, candidates)
        synchronized(lock) { lastDetection = detection }
        val chosen = detection.chosenId ?: return null
        val c = candidates.firstOrNull { it.id == chosen } ?: return null
        return chosen to c.template
    }

    // =====================================================================
    // Host-Frames / SDK-Camera
    // =====================================================================

    /**
     * Create a scan session over the loaded pool.
     *
     * @param regionOfInterest optional trusted region (zone 2), NORMALIZED 0–1
     *   (left/top/right/bottom, top-left origin, upright-frame coords), or null
     *   for the SDK default centred box. When set, the session emits the resolved
     *   [PXRegionOfInterest] via [PXScanEvent.RegionResolved] up front and honors
     *   it as prediction's zone-2 trusted area. Default null preserves the
     *   previous behavior.
     * @param configuration tunable per-session knobs (blur gate, single-shot).
     *   Mirrors iOS `makeScanSession(regionOfInterest:configuration:)`.
     */
    fun makeScanSession(
        regionOfInterest: PXRegion? = null,
        configuration: PXScanConfiguration = PXScanConfiguration(),
        onEvent: (PXScanEvent) -> Unit,
    ): PXScanSession {
        // Fail-fast on an empty pool (parity with iOS `makeScanSession`, which throws
        // `.notLoaded`). Host-Frames callers get the error immediately; the SDK-Camera
        // `PXScannerView` catches this and still mounts, emitting a Failed(not_loaded).
        if (synchronized(lock) { pool.isEmpty() }) throw PXException(
            "No templates are loaded. Call load(...) before makeScanSession().",
            PXErrorCode.NOT_LOADED,
        )
        val imu = IMUStabilityMonitor(appContext)
        val analyzer = FrameAnalyzer(
            ocrExtractor = ocrExtractor,
            barcodeExtractor = barcodeExtractor,
            blurDetector = BlurDetector(),
            imuMonitor = imu,
            perFrameOcrLog = perFrameOcrLog,
            torchController = LowLightTorchController(),
        )
        val aggregator = FrameAggregator(OcrConsensus(), BarcodeAggregator())
        return PXScanSession(
            frameAnalyzer = analyzer,
            frameAggregator = aggregator,
            imuMonitor = imu,
            predictionEngine = predictionEngine,
            resolveTemplate = ::resolveTemplate,
            isPoolLoaded = { synchronized(lock) { pool.isNotEmpty() } },
            lastDetectionProvider = { synchronized(lock) { lastDetection } },
            templateById = { id -> synchronized(lock) { pool[id]?.template } },
            templateName = { id -> synchronized(lock) { pool[id]?.name } },
            persistScan = { capture, templateId, debug -> persistScan(templateId, capture, debug) },
            loadScanCapture = { scanId -> scanStore.get(scanId)?.capture },
            regionOfInterest = regionOfInterest,
            configuration = configuration,
            onEvent = onEvent,
        )
    }

}
