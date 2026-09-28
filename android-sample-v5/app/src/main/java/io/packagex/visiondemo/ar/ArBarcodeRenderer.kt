package io.packagex.visiondemo.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/** Snapshot of scanner state pushed to the UI each frame. */
data class ScannerStatus(
    val trackingText: String,
    /** 0 = tracking well, 1 = degraded (warn), 2 = not tracking (error). */
    val trackingLevel: Int,
    val mapReady: Boolean,
    val markerCount: Int,
    val lastPayload: String?,
    /** Per-barcode marker counts (payload → number of physical instances). */
    val counts: List<PayloadCount>,
)

/**
 * GL renderer that drives the ARCore session: draws the camera background,
 * feeds CPU images to ML Kit, places payload-keyed anchors via hit tests, and
 * draws them at their projected screen position.
 */
class ArBarcodeRenderer(
    private val barcodeProcessor: BarcodeProcessor,
    density: Float,
    private val onStatus: (ScannerStatus) -> Unit,
    private val onDebug: ((ScreenDebug?) -> Unit)? = null,
) : GLSurfaceView.Renderer {
    @Volatile
    var session: Session? = null

    private val markerRenderer = MarkerGlRenderer(density)

    /** Draw ARCore's planes + feature points on the overlay (debug). */
    @Volatile
    var showTrackingDebug = false
    private var debugSent = false

    /**
     * SKU -> item name, edited from the Items sheet on the main thread while this
     * renderer reads it on the GL thread (iOS ScannerController's locked catalog).
     */
    val catalog = CatalogSnapshot()

    @Volatile
    var clearRequested = false

    private val backgroundRenderer = BackgroundRenderer()

    /**
     * How a marker's anchor is bound to the world.
     *
     * TRACKABLE (`hit.createAnchor()`) attaches to the Plane or Point that was
     * hit. ARCore docs: an anchor created on a Trackable "follows the attached
     * Trackable when it moves through space" — and ARCore refits planes
     * constantly (they grow, merge, get subsumed). For a barcode stuck to a
     * static carton that is spurious motion: we want the marker fixed in the
     * world, not welded to ARCore's evolving guess at the surface.
     *
     * WORLD (`session.createAnchor(hit.hitPose)`) pins the pose in world space.
     * It still receives world-map corrections, but does not chase plane refits.
     *
     * Switch here and compare DriftDiag output.
     */
    private val anchorMode = AnchorMode.WORLD

    private enum class AnchorMode { TRACKABLE, WORLD }

    private fun anchorAt(
        hit: HitResult,
        pose: com.google.ar.core.Pose,
    ): Anchor =
        when (anchorMode) {
            AnchorMode.TRACKABLE -> hit.trackable.createAnchor(pose)
            AnchorMode.WORLD -> session?.createAnchor(pose) ?: hit.trackable.createAnchor(pose)
        }

    /**
     * Per-axis median of the candidate's accumulated hit positions.
     *
     * The candidate gate already demands 3-7 agreeing sightings before a
     * marker exists — but the marker used to be born at the LAST single hit,
     * discarding the other samples. That is why ReprojDiag showed ~2.3cm in
     * the first two seconds settling to ~1.1cm: one hit's noise, then
     * ARCore refining. The median is robust to one bad sample and lands the
     * marker where the sightings agree from the start.
     */
    private fun consensus(
        samples: ArrayDeque<FloatArray>,
        out: FloatArray,
    ): Float {
        val n = samples.size
        val axis = FloatArray(n)
        for (a in 0 until 3) {
            var i = 0
            for (sm in samples) axis[i++] = sm[a]
            axis.sort()
            out[a] = if (n % 2 == 1) axis[n / 2] else (axis[n / 2 - 1] + axis[n / 2]) * 0.5f
        }
        // spread = worst sample's distance from the median, for BirthDiag
        var worst = 0f
        for (sm in samples) {
            val dx = sm[0] - out[0]
            val dy = sm[1] - out[1]
            val dz = sm[2] - out[2]
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d > worst) worst = d
        }
        return worst
    }

    private val consensusPos = FloatArray(3)

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var viewportChanged = false
    private var textureSet = false

    // The zbarscanner tracker is tuned for a continuous stream — feed frames
    // as fast as the single-flight busy gate allows.
    private var lastScanUptimeMs = 0L
    private val scanIntervalMs = 33L

    // One marker per *physical* barcode: identity is payload + world
    // position, so two copies of the same label each get their own marker.
    // The Anchor keeps each marker locked in place even while out of view.
    // Position is MEASURED at birth (median of agreeing real hits) and then
    // never moved by this code — only ARCore's own map correction, and the
    // STOPPED re-home in markerPosition, can shift it. The old heal/adopt/
    // refine paths that relocated markers were built for guessed depth and
    // could move a correct marker onto its neighbour; they are gone.
    private class MarkerRecord(
        val payload: String,
        var anchor: Anchor,
        val format: String,
        var fromPlane: Boolean,
        var trackable: com.google.ar.core.Trackable?,
    ) {
        val bornMs = SystemClock.uptimeMillis()

        /** Last time a detection claimed this marker; dedup keeps the most recent. */
        var lastSeenMs = bornMs

        /** World position when this marker was first localized. */
        val birthPos = FloatArray(3)
        var hasBirth = false

        /** Times this marker was deliberately re-anchored (STOPPED re-home only). */
        var reanchorCount = 0
        val lastPos = FloatArray(3)
        var hasPos = false

        /** Last drawn screen position; sub-threshold moves snap back to it. */
        var screenX = 0f
        var screenY = 0f
        var hasScreen = false
    }

    private val maxSamples = 7

    // A single detection can carry 20cm+ of lateral error right after a pan
    // (the zbarscanner tracker's smoothed box lags in image space), so a new
    // marker needs several agreeing sightings before it's created. Transient
    // spikes never confirm and expire instead of becoming ghost markers.
    private class Candidate(
        val payload: String,
    ) {
        val positions = ArrayDeque<FloatArray>()
        var lastSeenNs = 0L
    }

    private val candidates = ArrayList<Candidate>()
    private val candidateConfirmCount = 3
    private val candidateTimeoutNs = 1_500_000_000L

    private val markers = ArrayList<MarkerRecord>()
    private var lastPayload: String? = null

    /** Set on create or re-sighting; the per-value dedup runs once on the next frame, not every frame. */
    private var mergePending = false

    // Placement is gated until ARCore has actually mapped the area (steady
    // tracking + at least one substantial plane). Before that, world
    // positions wobble 10-30cm and no matching logic can hold identity.
    // Latches true once reached, until the next session.resume()
    // ([WarmUpGate.onSessionStart], iOS fix d9fb1d1).
    val warmUp = WarmUpGate(minFrames = 60)
    private val mapReady: Boolean get() = warmUp.ready

    // Detections come back 100-300ms after their frame was captured (native
    // pipeline + tracker smoothing). Hit-testing them through the *current*
    // camera would smear anchors along the device's motion path, so each
    // batch carries the inverse view-projection of its capture-time camera
    // and is cast as a world-space ray instead of a screen point.
    private class DetectionBatch(
        val detections: List<Detection>,
        val captureTimestampNs: Long,
        /**
         * Inverse view-projection of the camera AT CAPTURE TIME. Decodes land
         * 100-300ms after their frame; unprojecting through the *current*
         * camera smears every placement along the device's motion path.
         */
        val invViewProj: FloatArray,
    )

    private val pendingBatches = ConcurrentLinkedQueue<DetectionBatch>()

    private val hitKindCounts = HashMap<String, Int>()
    private var lastHitStatMs = 0L

    // Reprojection error: perpendicular distance from a claimed marker to the
    // capture-time ray through its fresh detection, in metres. This is "how
    // far is the marker from where the barcode actually is" — the metric
    // that decides whether it lands on the right carton. Unlike DriftDiag's
    // world-frame displacement, ARCore map corrections cancel out of it.
    // Only claimed pairs are measured: a marker >assignRadius off is never
    // paired and shows up as a duplicate CREATE instead, so watch counts too.
    private var reprojN = 0
    private var reprojSumM = 0f
    private var reprojMaxM = 0f
    private var reprojDistSumM = 0f
    private var lastReprojLogMs = 0L

    // Camera motion gating: during fast movement the tracker's smoothed
    // boxes lag the true barcode position in the image, so placement is
    // paused and re-anchoring requires a near-still camera.
    private var prevCamPos: FloatArray? = null
    private var prevCamQuat: FloatArray? = null
    private var prevFrameTimeNs = 0L
    private var translationSpeedMps = 0f
    private var rotationRateDps = 0f

    // Timestamp of the last frame where the camera was NOT near-still.
    // Detections are only trusted if the camera has been continuously still
    // since before their frame was captured — that also guarantees ARCore
    // world-map corrections can't put the ray in a stale world frame.
    private var lastUnstableNs = 0L

    /** Last frame the camera exceeded cameraModerate (0.4 m/s, 60 deg/s). */
    private var lastImmoderateNs = 0L

    /**
     * A/B: which motion bound gates placement.
     *
     * The "continuously still since capture" rule (cameraStable: 0.15 m/s,
     * 20 deg/s) had three justifications. Two are gone — the batch carries its
     * own capture-time pose, and placement now unprojects from the raw
     * (unsmoothed) decode box instead of the Kalman/One-Euro-smoothed one
     * (see BarcodeProcessor), so that box's pan-direction lag no longer feeds
     * into where the ray is cast. The smoothing tracker itself is still there
     * — `boundingBoxF` still lags and is still what steady-state UI should
     * draw — placement just no longer reads it. What remains here is motion
     * blur on the decoder's centroid and rolling-shutter skew vs ARCore's
     * frame timestamp (~20-30 ms readout: at 60 deg/s and 1 m that is 2-3 cm
     * at the far image edge). At 20 deg/s a normal handheld sweep (30-90
     * deg/s) placed nothing until the operator froze. The 3-sample median
     * absorbs part of the blur.
     *
     * Ship only if ReprojDiag max stays <= ~2.7 cm and BirthDiag spread stays
     * ~sub-cm; otherwise flip back. The presentation dead-band (see
     * [projectMarkers]) gates on the stricter cameraNearStill, not this.
     */
    private val moderateMotionGate = true

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val worldPoint = FloatArray(4)
    private val clipPoint = FloatArray(4)
    private val pointIn = FloatArray(2)
    private val pointOut = FloatArray(2)
    private val tmp4a = FloatArray(4)
    private val tmp4b = FloatArray(4)
    private val rayOrigin = FloatArray(3)
    private val rayDirection = FloatArray(3)

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?,
    ) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
        markerRenderer.createOnGlThread()
        textureSet = false
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int,
    ) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = session ?: return

        if (!textureSet) {
            session.setCameraTextureName(backgroundRenderer.textureId)
            textureSet = true
        }
        if (viewportChanged) {
            // Portrait-locked activity: display rotation 0.
            session.setDisplayGeometry(android.view.Surface.ROTATION_0, viewportWidth, viewportHeight)
            viewportChanged = false
        }

        val frame: Frame =
            try {
                session.update()
            } catch (e: CameraNotAvailableException) {
                return
            }
        val camera = frame.camera
        // One view/projection per frame; maybeAnalyze, projectMarkers and the
        // debug overlay all read these (same camera, same frame).
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100f)

        backgroundRenderer.draw(frame)

        if (clearRequested) {
            clearRequested = false
            markers.forEach { it.anchor.detach() }
            markers.clear()
            candidates.clear()
            hitKindCounts.clear()
            lastPayload = null
        }

        if (camera.trackingState == TrackingState.TRACKING) {
            updateMapReadiness(session)
            updateMotionEstimate(frame, camera)
            maybeAnalyze(frame, camera)
            drainDetections(frame, camera)
            markerRenderer.draw(projectMarkers(camera), viewportWidth, viewportHeight)
        } else {
            // Tracking loss invalidates the last known pose. Treat it as
            // maximal instability so the "camera has been stable since
            // capture" check in drainDetections can't pass once tracking
            // resumes, and drop batches decoded before/during the gap - their
            // capture-time ray is no longer trustworthy. Markers are hidden
            // (not drawn from stale cached positions) rather than redrawn.
            lastUnstableNs = frame.timestamp
            lastImmoderateNs = frame.timestamp
            pendingBatches.clear()
        }

        if (showTrackingDebug) {
            onDebug?.invoke(buildTrackingDebug(session, frame, camera))
            debugSent = true
        } else if (debugSent) {
            onDebug?.invoke(null)
            debugSent = false // clear the last painted geometry once
        }
        maybePushStatus(camera)
    }

    // Status is human-readable text; rebuilding it per frame cost a map + sort +
    // Handler post + three TextView relayouts at display rate to redraw the same
    // strings. Allocation-free change detection, then build only on change.
    private var lastStatusKey = 0

    private fun maybePushStatus(camera: Camera) {
        val text = trackingText(camera)
        var key = text.hashCode()
        key = key * 31 + markers.size
        key = key * 31 + (lastPayload?.hashCode() ?: 0)
        for (m in markers) key = key * 31 + m.payload.hashCode()
        if (key == lastStatusKey) return
        lastStatusKey = key
        onStatus(
            ScannerStatus(
                trackingText = text,
                trackingLevel = trackingLevel(camera),
                mapReady = mapReady,
                markerCount = markers.size,
                lastPayload = lastPayload,
                counts =
                    markers
                        .groupBy { it.payload }
                        .map { (payload, list) -> PayloadCount(payload, list[0].format, list.size) }
                        .sortedByDescending { it.count },
            ),
        )
    }

    // MARK: Detection

    private fun updateMapReadiness(session: Session) {
        if (mapReady) return
        warmUp.onTrackedFrame()
        // The warm-up earns its keep: VIO scale converges over the first 1-2s of
        // motion. The old ">0.05m² plane" requirement did not — carton faces are
        // vertical, ARCore finds vertical planes slowly, and a feature-point hit
        // is already a real measurement. Gating placement on a plane just delayed
        // the first marker for no accuracy gain.
        if (!mapReady) return
        android.util.Log.d("MarkerDiag", "MAP-READY")
    }

    private fun updateMotionEstimate(
        frame: Frame,
        camera: Camera,
    ) {
        val pose = camera.pose
        val pos = floatArrayOf(pose.tx(), pose.ty(), pose.tz())
        val quat = pose.rotationQuaternion
        val prevPos = prevCamPos
        val prevQuat = prevCamQuat
        val dtSec = (frame.timestamp - prevFrameTimeNs) / 1e9f
        if (prevPos != null && prevQuat != null && dtSec in 1e-4f..0.5f) {
            val dx = pos[0] - prevPos[0]
            val dy = pos[1] - prevPos[1]
            val dz = pos[2] - prevPos[2]
            translationSpeedMps = sqrt(dx * dx + dy * dy + dz * dz) / dtSec
            // Full relative rotation angle from the pose quaternions:
            // 2*acos(|dot|). A forward-vector dot product only sees tilt/pan
            // and misses roll around the camera's own forward axis entirely.
            var dot = 0f
            for (i in 0 until 4) dot += quat[i] * prevQuat[i]
            dot = kotlin.math.abs(dot).coerceIn(0f, 1f)
            rotationRateDps = Math.toDegrees(2.0 * kotlin.math.acos(dot.toDouble())).toFloat() / dtSec
        }
        prevCamPos = pos
        prevCamQuat = quat
        prevFrameTimeNs = frame.timestamp
        if (!cameraStable) lastUnstableNs = frame.timestamp
        if (!cameraModerate) lastImmoderateNs = frame.timestamp
    }

    /** Still enough to trust a fresh hit for (re)anchoring. */
    private val cameraStable: Boolean
        get() = translationSpeedMps < 0.15f && rotationRateDps < 20f

    /**
     * Essentially motionless, not merely "stable" — the screen dead-band
     * (see [projectMarkers]) must only hold position here. `cameraStable`
     * (0.15 m/s / 20 deg/s) is a slow pan, and holding the drawn position
     * through a slow pan is exactly what made markers creep for several
     * frames and then step once the accumulated error cleared the band.
     */
    private val cameraNearStill: Boolean
        get() = translationSpeedMps < 0.02f && rotationRateDps < 2f

    /** Moving moderately — new markers OK, moving existing ones is not. */
    private val cameraModerate: Boolean
        get() = translationSpeedMps < 0.40f && rotationRateDps < 60f

    private fun maybeAnalyze(
        frame: Frame,
        camera: Camera,
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - lastScanUptimeMs < scanIntervalMs || barcodeProcessor.isBusy) return
        val image =
            try {
                frame.acquireCameraImage()
            } catch (e: Exception) {
                // NotYetAvailable, ResourceExhausted, DeadlineExceeded
                return
            }
        lastScanUptimeMs = now
        val captureNs = frame.timestamp
        // Snapshot the capture-time view-projection now — by the time the
        // decode lands this pose is gone. Fresh arrays: the batch outlives
        // this frame and crosses to the decode callback's thread.
        val captureVP = FloatArray(16)
        Matrix.multiplyMM(captureVP, 0, projMatrix, 0, viewMatrix, 0)
        val captureInv = FloatArray(16)
        if (!Matrix.invertM(captureInv, 0, captureVP, 0)) {
            image.close()
            return
        }
        barcodeProcessor.process(image) { detections ->
            pendingBatches.add(DetectionBatch(detections, captureNs, captureInv))
        }
    }

    // Exclusive one-to-one assignment radius (detection ray → marker). Every
    // marker is born from measured geometry now, so one tight radius: it must
    // stay above still-camera noise (~1-2cm) and below copy spacing (~10cm).
    private val assignRadiusLocalizedM = 0.12f

    // Candidate clustering radius. Tight: still-camera lateral noise is
    // ~1-2cm, and real copies are ≥10cm apart — 8cm separates them cleanly.
    private val candidateMatchRadiusM = 0.08f

    private fun mergeSiblingMarkers() {
        if (markers.size < 2) return
        var i = 0
        while (i < markers.size) {
            var j = i + 1
            while (j < markers.size) {
                val a = markers[i]
                val b = markers[j]
                // One marker per barcode value (same as the iOS demo): same-payload
                // markers are deduplicated regardless of distance, keeping the most
                // recently seen; on a tie keep the newer (b).
                if (a.payload == b.payload) {
                    if (a.lastSeenMs > b.lastSeenMs) {
                        b.anchor.detach()
                    } else {
                        a.anchor.detach()
                        markers[i] = b
                    }
                    markers.removeAt(j)
                    android.util.Log.d(
                        "MarkerDiag",
                        "MERGE payload=${a.payload.hashCode()} markers=${markers.size}",
                    )
                    continue
                }
                j++
            }
            i++
        }
    }

    private fun drainDetections(
        frame: Frame,
        camera: Camera,
    ) {
        // Positions were refreshed by last frame's projectMarkers, so a marker
        // created last frame is comparable now.
        if (mergePending) {
            mergeSiblingMarkers()
            mergePending = false
        }
        candidates.removeAll { frame.timestamp - it.lastSeenNs > candidateTimeoutNs }

        while (true) {
            val batch = pendingBatches.poll() ?: break
            // Trust a batch only if the camera has been continuously still
            // since before its frame was captured: then the capture ray and
            // the current ray coincide, the tracker's boxes aren't lagging,
            // and ARCore world-map corrections can't skew the unprojection.
            if (frame.timestamp - batch.captureTimestampNs > 500_000_000L) continue
            val motionCutoffNs = if (moderateMotionGate) lastImmoderateNs else lastUnstableNs
            if (motionCutoffNs >= batch.captureTimestampNs) continue

            val invViewProj = batch.invViewProj

            val placed = ArrayList<PlacedDetection>(batch.detections.size)
            for (det in batch.detections) {
                pointIn[0] = det.rawX
                pointIn[1] = det.rawY
                frame.transformCoordinates2d(
                    Coordinates2d.IMAGE_PIXELS,
                    pointIn,
                    Coordinates2d.VIEW,
                    pointOut,
                )
                val vx = pointOut[0]
                val vy = pointOut[1]
                if (vx < 0 || vy < 0 || vx > viewportWidth || vy > viewportHeight) continue
                if (!computeWorldRay(invViewProj, vx, vy)) continue

                val hit = bestHit(frame)
                // How often does real geometry exist? Removing Instant
                // Placement trades "always places something (often wrong)"
                // for "places only what it can measure" — this quantifies
                // the cost of that trade.
                hitKindCounts.merge(
                    hit?.trackable?.javaClass?.simpleName ?: "NONE",
                    1,
                    Int::plus,
                )
                if (SystemClock.uptimeMillis() - lastHitStatMs > 3000) {
                    lastHitStatMs = SystemClock.uptimeMillis()
                    android.util.Log.d("HitDiag", "hits=$hitKindCounts")
                    hitKindCounts.clear()
                }
                if (hit == null) continue
                val h = hit.hitPose
                // Reject hits far beyond scanning range — bad geometry.
                val hdx = h.tx() - rayOrigin[0]
                val hdy = h.ty() - rayOrigin[1]
                val hdz = h.tz() - rayOrigin[2]
                if (sqrt(hdx * hdx + hdy * hdy + hdz * hdz) > 3.0f) continue
                placed.add(PlacedDetection(det, hit, rayOrigin.clone(), rayDirection.clone()))
            }
            assignAndPlace(placed)
        }
    }

    private class PlacedDetection(
        val det: Detection,
        val hit: HitResult,
        val rayO: FloatArray,
        val rayD: FloatArray,
    )

    /**
     * Exclusive one-to-one assignment of a batch's detections to markers.
     * Same-payload detections in one batch are distinct physical copies by
     * construction (the scanner reported them as separate boxes), so each
     * marker can be claimed by at most one of them — a sighting of copy B
     * can never drag or steal copy A's marker.
     */
    private fun assignAndPlace(placed: List<PlacedDetection>) {
        if (placed.isEmpty()) return

        data class Pairing(
            val p: PlacedDetection,
            val m: MarkerRecord,
            val lateral: Float,
        )

        val pairings = ArrayList<Pairing>()
        for (p in placed) {
            val payload = p.det.payload
            for (m in markers) {
                if (m.payload != payload) continue
                val pos = markerPosition(m) ?: continue
                val lateral = lateralToRay(p.rayO, p.rayD, pos[0], pos[1], pos[2])
                if (lateral <= assignRadiusLocalizedM) pairings.add(Pairing(p, m, lateral))
            }
        }
        pairings.sortBy { it.lateral }

        val claimedMarkers = HashSet<MarkerRecord>()
        val assignedDets = HashSet<PlacedDetection>()
        for (pair in pairings) {
            if (pair.m in claimedMarkers || pair.p in assignedDets) continue
            claimedMarkers.add(pair.m)
            assignedDets.add(pair.p)
            pair.m.lastSeenMs = SystemClock.uptimeMillis()
            mergePending = true
            reprojN++
            reprojSumM += pair.lateral
            reprojDistSumM += pair.p.hit.distance
            if (pair.lateral > reprojMaxM) reprojMaxM = pair.lateral
        }
        val nowMs = SystemClock.uptimeMillis()
        if (reprojN > 0 && nowMs - lastReprojLogMs > 2000) {
            lastReprojLogMs = nowMs
            android.util.Log.d(
                "ReprojDiag",
                "n=%d mean=%.1fcm max=%.1fcm dist=%.2fm gate=%s".format(
                    reprojN,
                    reprojSumM / reprojN * 100f,
                    reprojMaxM * 100f,
                    reprojDistSumM / reprojN,
                    if (moderateMotionGate) "moderate" else "stable",
                ),
            )
            reprojN = 0
            reprojSumM = 0f
            reprojMaxM = 0f
            reprojDistSumM = 0f
        }

        // Anything unassigned is a fresh sighting >12cm from every same-payload
        // marker: let the candidate gate decide whether it becomes a marker.
        // Duplicates, if they ever happen, are visible and clearable; the old
        // cap/adopt/absorb paths that "fixed" them could move a correct marker
        // onto its neighbour, which is the one failure the product forbids.
        for (p in placed) {
            if (p in assignedDets) continue
            if (!mapReady || !(if (moderateMotionGate) cameraModerate else cameraStable)) continue
            accumulateCandidate(p)
        }
    }

    /**
     * Unprojects a view point through the capture-time camera into a world
     * ray (fills [rayOrigin]/[rayDirection]).
     */
    private fun computeWorldRay(
        invViewProj: FloatArray,
        vx: Float,
        vy: Float,
    ): Boolean {
        val ndcX = 2f * vx / viewportWidth - 1f
        val ndcY = 1f - 2f * vy / viewportHeight

        tmp4a[0] = ndcX
        tmp4a[1] = ndcY
        tmp4a[2] = -1f
        tmp4a[3] = 1f
        Matrix.multiplyMV(tmp4b, 0, invViewProj, 0, tmp4a, 0)
        if (tmp4b[3] == 0f) return false
        val nx = tmp4b[0] / tmp4b[3]
        val ny = tmp4b[1] / tmp4b[3]
        val nz = tmp4b[2] / tmp4b[3]

        tmp4a[0] = ndcX
        tmp4a[1] = ndcY
        tmp4a[2] = 1f
        tmp4a[3] = 1f
        Matrix.multiplyMV(tmp4b, 0, invViewProj, 0, tmp4a, 0)
        if (tmp4b[3] == 0f) return false
        val fx = tmp4b[0] / tmp4b[3]
        val fy = tmp4b[1] / tmp4b[3]
        val fz = tmp4b[2] / tmp4b[3]

        val dx = fx - nx
        val dy = fy - ny
        val dz = fz - nz
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-6f) return false
        rayOrigin[0] = nx
        rayOrigin[1] = ny
        rayOrigin[2] = nz
        rayDirection[0] = dx / len
        rayDirection[1] = dy / len
        rayDirection[2] = dz / len
        return true
    }

    /**
     * Real geometry only — never Instant Placement.
     *
     * The previous version had a real feature-point hit in hand, took its
     * *measured* distance to seed `hitTestInstantPlacement`, and then returned
     * the guess and discarded the measurement. Instant Placement is ARCore's
     * documented fallback for when you have neither depth nor planes; it is
     * defined to approximate, and with this device's depth pipeline broken its
     * `approximateDistanceMeters` degraded to the hardcoded 0.5f below. That
     * wrong depth is a lateral error as soon as the camera translates — the
     * marker drift this app was built around.
     *
     * A Point hit is ARCore's own feature point, triangulated across the whole
     * session trajectory with bundle adjustment. Prefer a measurement over a
     * guess, and place nothing when there is no measurement.
     *
     * `frame.hitTest` returns hits sorted by increasing distance from the
     * camera, so the first one that is real geometry (Plane/DepthPoint/Point)
     * is the *nearest* one — take it in a single pass. Grouping by trackable
     * type first (Plane, then DepthPoint, then Point, each `firstOrNull` over
     * the whole list) used to let a farther Plane win over a nearer
     * DepthPoint or Point it skipped past.
     */
    private fun bestHit(frame: Frame): HitResult? {
        // Planes go PAUSED while ARCore re-evaluates them; a hit on a paused
        // trackable is stale geometry and a bad place to be born.
        val hits =
            frame
                .hitTest(rayOrigin, 0, rayDirection, 0)
                .filter { it.trackable.trackingState == TrackingState.TRACKING }
        return hits.firstOrNull { hit ->
            isPlaneHit(hit) || hit.trackable is com.google.ar.core.DepthPoint || hit.trackable is Point
        }
    }

    /**
     * The marker's current world position, resilient to trackable churn:
     * ARCore pauses/discards young planes and Instant Placement points
     * during mapping, which must not make markers vanish. While TRACKING the
     * anchor pose is cached; while PAUSED the cache is used; if the
     * trackable dies (STOPPED) the marker is silently re-anchored as a plain
     * world anchor at the cached position.
     */
    private fun markerPosition(m: MarkerRecord): FloatArray? {
        when (m.anchor.trackingState) {
            TrackingState.TRACKING -> {
                val p = m.anchor.pose
                m.lastPos[0] = p.tx()
                m.lastPos[1] = p.ty()
                m.lastPos[2] = p.tz()
                m.hasPos = true
            }
            TrackingState.STOPPED ->
                if (m.hasPos) {
                    session?.let { s ->
                        runCatching {
                            val a =
                                s.createAnchor(
                                    com.google.ar.core.Pose
                                        .makeTranslation(m.lastPos[0], m.lastPos[1], m.lastPos[2]),
                                )
                            m.anchor.detach()
                            m.anchor = a
                            m.trackable = null
                            m.reanchorCount++
                        } // NotTrackingException while PAUSED: keep the old anchor, retry next frame
                    }
                }
            else -> {}
        }
        return if (m.hasPos) m.lastPos else null
    }

    private fun isPlaneHit(hit: HitResult): Boolean {
        val t = hit.trackable
        return t is Plane && t.isPoseInPolygon(hit.hitPose)
    }

    /**
     * Lateral (perpendicular-to-ray) distance from a world point to a ray.
     * Depth-independent, so noisy depth can't make the same barcode look
     * like a new copy. Returns MAX_VALUE for points behind the origin.
     */
    private fun lateralToRay(
        o: FloatArray,
        d: FloatArray,
        px: Float,
        py: Float,
        pz: Float,
    ): Float {
        val vx = px - o[0]
        val vy = py - o[1]
        val vz = pz - o[2]
        val along = vx * d[0] + vy * d[1] + vz * d[2]
        if (along <= 0f) return Float.MAX_VALUE
        val lx = vx - along * d[0]
        val ly = vy - along * d[1]
        val lz = vz - along * d[2]
        return sqrt(lx * lx + ly * ly + lz * lz)
    }

    private fun accumulateCandidate(p: PlacedDetection) {
        val det = p.det
        val hit = p.hit
        val h = hit.hitPose
        val pos = floatArrayOf(h.tx(), h.ty(), h.tz())
        val nowNs = prevFrameTimeNs

        val cand =
            candidates.firstOrNull { c ->
                c.payload == det.payload &&
                    lateralToRay(
                        p.rayO,
                        p.rayD,
                        c.positions.last()[0],
                        c.positions.last()[1],
                        c.positions.last()[2],
                    ) < candidateMatchRadiusM
            }
        if (cand == null) {
            candidates.add(
                Candidate(det.payload).also {
                    it.positions.addLast(pos)
                    it.lastSeenNs = nowNs
                },
            )
            return
        }
        cand.positions.addLast(pos)
        if (cand.positions.size > maxSamples) cand.positions.removeFirst()
        cand.lastSeenNs = nowNs
        if (cand.positions.size < candidateConfirmCount) return

        candidates.remove(cand)

        // Where the agreeing sightings say the barcode is — not where the
        // most recent single hit says.
        val spreadM = consensus(cand.positions, consensusPos)
        val birthPose =
            com.google.ar.core
                .Pose(consensusPos, h.rotationQuaternion)
        android.util.Log.d(
            "BirthDiag",
            "payload=%d n=%d spread=%.1fcm dist=%.2fm".format(
                det.payload.hashCode(),
                cand.positions.size,
                spreadM * 100f,
                hit.distance,
            ),
        )

        // Born at the consensus of the agreeing sightings, as a WORLD anchor.
        markers.add(
            MarkerRecord(
                det.payload,
                anchorAt(hit, birthPose),
                det.format,
                isPlaneHit(hit),
                hit.trackable,
            ).also {
                // Record birth position now, not lazily on the next DriftDiag
                // log — lazy init captured wherever the marker happened to be
                // when logDrift first ran (up to 2s later), silently hiding
                // that much drift from the very metric meant to catch it.
                System.arraycopy(consensusPos, 0, it.birthPos, 0, 3)
                it.hasBirth = true
            },
        )
        lastPayload = det.payload
        mergePending = true
        android.util.Log.d(
            "MarkerDiag",
            "CREATE payload=${det.payload.hashCode()} markers=${markers.size} type=${hit.trackable.javaClass.simpleName}",
        )
    }

    // MARK: Projection

    private var lastDriftLogMs = 0L

    /**
     * Cumulative displacement of each marker from where it was first placed.
     *
     * Distinguishes the two causes that look identical on screen: a marker we
     * deliberately moved (reanchorCount rising) versus an anchor sliding under
     * us while nothing re-anchored it (drift proper).
     */
    private fun logDrift() {
        val now = SystemClock.uptimeMillis()
        if (now - lastDriftLogMs < 2000 || markers.isEmpty()) return
        lastDriftLogMs = now
        val parts =
            markers.mapNotNull { m ->
                if (!m.hasPos || !m.hasBirth) return@mapNotNull null
                val dx = m.lastPos[0] - m.birthPos[0]
                val dy = m.lastPos[1] - m.birthPos[1]
                val dz = m.lastPos[2] - m.birthPos[2]
                val cm = sqrt(dx * dx + dy * dy + dz * dz) * 100f
                val kind = m.trackable?.javaClass?.simpleName ?: "World"
                "%s:%.1fcm/r%d/%s".format(m.payload.take(6), cm, m.reanchorCount, kind)
            }
        if (parts.isNotEmpty()) {
            android.util.Log.d("DriftDiag", "mode=$anchorMode ${parts.joinToString(" ")}")
        }
    }

    private val screenDeadbandPx = 4f

    // ---- tracking-geometry debug overlay ---------------------------------
    private val dbgLocal = FloatArray(3)
    private val dbgWorld = FloatArray(3)
    private val maxDebugPoints = 300
    private val dbgPoints = FloatArray(maxDebugPoints * 3)

    /** Projects a world point with the matrices already in viewMatrix/projMatrix. */
    private fun projectToScreen(
        x: Float,
        y: Float,
        z: Float,
        out: FloatArray,
        at: Int,
    ): Boolean {
        worldPoint[0] = x
        worldPoint[1] = y
        worldPoint[2] = z
        worldPoint[3] = 1f
        Matrix.multiplyMV(clipPoint, 0, viewMatrix, 0, worldPoint, 0)
        Matrix.multiplyMV(worldPoint, 0, projMatrix, 0, clipPoint, 0)
        val w = worldPoint[3]
        if (w <= 0f) return false
        out[at] = (worldPoint[0] / w + 1f) / 2f * viewportWidth
        out[at + 1] = (1f - worldPoint[1] / w) / 2f * viewportHeight
        return true
    }

    /**
     * Debug-only, so per-frame allocation is acceptable here. Plane polygons
     * come from Plane.getPolygon() (x,z pairs in the plane's local frame,
     * about centerPose); the point cloud from Frame.acquirePointCloud()
     * (x,y,z,confidence quads), which must be released.
     */
    private fun buildTrackingDebug(
        session: Session,
        frame: Frame,
        camera: Camera,
    ): ScreenDebug {
        val planes = ArrayList<FloatArray>()
        for (plane in session.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            val poly = plane.polygon ?: continue
            val nVerts = poly.limit() / 2
            if (nVerts < 3) continue
            val screen = FloatArray(nVerts * 2)
            val pose = plane.centerPose
            var ok = true
            for (v in 0 until nVerts) {
                dbgLocal[0] = poly.get(v * 2)
                dbgLocal[1] = 0f
                dbgLocal[2] = poly.get(v * 2 + 1)
                pose.transformPoint(dbgLocal, 0, dbgWorld, 0)
                if (!projectToScreen(dbgWorld[0], dbgWorld[1], dbgWorld[2], screen, v * 2)) {
                    // vertex behind the camera (floor planes usually extend behind us):
                    // reuse the previous vertex rather than losing the whole polygon
                    if (v == 0) {
                        ok = false
                        break
                    }
                    screen[v * 2] = screen[v * 2 - 2]
                    screen[v * 2 + 1] = screen[v * 2 - 1]
                }
            }
            if (ok) planes.add(screen)
        }

        var count = 0
        try {
            frame.acquirePointCloud().use { pc ->
                val buf = pc.points
                val n = buf.limit() / 4
                var i = 0
                while (i < n && count < maxDebugPoints) {
                    val x = buf.get(i * 4)
                    val y = buf.get(i * 4 + 1)
                    val z = buf.get(i * 4 + 2)
                    val conf = buf.get(i * 4 + 3)
                    if (projectToScreen(x, y, z, dbgPoints, count * 3)) {
                        dbgPoints[count * 3 + 2] = conf
                        count++
                    }
                    i++
                }
            }
        } catch (_: Exception) {
            // point cloud not available this frame — planes alone still draw
        }
        return ScreenDebug(planes, dbgPoints.copyOf(count * 3), count)
    }

    private fun projectMarkers(camera: Camera): List<ScreenMarker> {
        if (markers.isEmpty()) return emptyList()
        logDrift()

        val result = ArrayList<ScreenMarker>(markers.size)
        val names = catalog.get()
        for (record in markers) {
            val pos = markerPosition(record) ?: continue
            worldPoint[0] = pos[0]
            worldPoint[1] = pos[1]
            worldPoint[2] = pos[2]
            worldPoint[3] = 1f
            Matrix.multiplyMV(clipPoint, 0, viewMatrix, 0, worldPoint, 0)
            Matrix.multiplyMV(worldPoint, 0, projMatrix, 0, clipPoint, 0)
            val w = worldPoint[3]
            if (w <= 0f) continue // behind the camera
            val ndcX = worldPoint[0] / w
            val ndcY = worldPoint[1] / w
            if (ndcX < -1.1f || ndcX > 1.1f || ndcY < -1.1f || ndcY > 1.1f) continue
            var sx = (ndcX + 1f) / 2f * viewportWidth
            var sy = (1f - ndcY) / 2f * viewportHeight
            // Presentation-only dead-band: per-frame VIO orientation noise is
            // a few px of wobble at the marker. Below the threshold, keep
            // drawing where we drew last frame. The world anchor is untouched;
            // real motion (camera pan, correction) clears the band at once.
            if (cameraNearStill &&
                record.hasScreen &&
                kotlin.math.abs(sx - record.screenX) < screenDeadbandPx &&
                kotlin.math.abs(sy - record.screenY) < screenDeadbandPx
            ) {
                sx = record.screenX
                sy = record.screenY
            } else {
                record.screenX = sx
                record.screenY = sy
                record.hasScreen = true
            }
            // ponytail: O(n²) instance count; markers stay in the tens.
            var count = 0
            for (other in markers) if (other.payload == record.payload) count++
            val label = names[record.payload] ?: record.payload
            result.add(ScreenMarker(x = sx, y = sy, label = label, format = record.format, count = count, bornMs = record.bornMs))
        }
        return result
    }

    private fun trackingText(camera: Camera): String =
        when (camera.trackingState) {
            TrackingState.TRACKING -> if (mapReady) "Tracking" else "Sweep to map area…"
            TrackingState.PAUSED ->
                when (camera.trackingFailureReason) {
                    com.google.ar.core.TrackingFailureReason.EXCESSIVE_MOTION -> "Slow down"
                    com.google.ar.core.TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark"
                    com.google.ar.core.TrackingFailureReason.INSUFFICIENT_FEATURES -> "Low detail"
                    com.google.ar.core.TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
                    else -> "Initializing…"
                }
            TrackingState.STOPPED -> "Stopped"
        }

    private fun trackingLevel(camera: Camera): Int =
        when (camera.trackingState) {
            TrackingState.TRACKING -> if (mapReady) 0 else 1
            TrackingState.PAUSED ->
                when (camera.trackingFailureReason) {
                    com.google.ar.core.TrackingFailureReason.CAMERA_UNAVAILABLE -> 2
                    else -> 1
                }
            TrackingState.STOPPED -> 2
        }
}
