# Vision SDK Android v2.7.0 — API facts for android-sample-v5

Source: `vision-sdk-android` (`VisionScanner/src/main/java/io/packagex/visionsdk/`), public API dump
`VisionScanner/api/VisionScanner.api`. Line numbers as of 2026-09-28.

## Publishing / consuming

- `com.packagexlabs:VisionScanner:v2.7.0` depends on `com.packagexlabs:vision-native:v2.7.0` (arm64-v8a only —
  no emulator) and `com.packagexlabs:vision-barcode-scanner:3.0.0-1730` (already in `~/.m2`).
- Publish: `./gradlew :vision-native:publishToMavenLocal :VisionScanner:publishLocal` (JDK 17).
- Consumer repos: `mavenLocal()`, `google()`, `mavenCentral()`, `maven("https://jitpack.io")`
  (transitives `com.github.asadullahilyas:AndroidSecurity`, `HandyUtils`, `com.github.tony19:named-regexp`).
- `minSdk 24` but `VisionSDK.initialize` throws `AndroidSDKLevelNotSupported` below API 29 → sample minSdk 29.

## Init

```kotlin
VisionSDK.getInstance().initialize(context, Environment.STAGING)   // DEV, STAGING, SANDBOX, PRODUCTION (QA unusable)
ModelManager.initialize(context) { lifecycleListener(l); maxConcurrentDownloads(2); enableLogging(true) }  // throws if called twice
ModelManager.isInitialized(); ModelManager.getInstance()
```
No global API key: every call takes `apiKey: String? = null, token: String? = null`.

## VisionCameraView (FrameLayout; `VisionCameraView(context)`)

```kotlin
fun configure(detectionMode: DetectionMode, scanningMode: ScanningMode, isMultipleScanEnabled: Boolean)
suspend fun enablePriceTagMode(apiKey: String? = null, token: String? = null)       // entitlement check; throws PriceTagNotEligible
suspend fun enableItemRetrievalMode(apiKey: String? = null, token: String? = null)  // throws ItemRetrievalNotEligible
fun setDetectionMode(DetectionMode); fun setScanningMode(ScanningMode); fun setMultipleScanEnabled(Boolean)
fun setFlashTurnedOn(Boolean); fun setCameraSettings(CameraSettings); fun setObjectDetectionConfiguration(ObjectDetectionConfiguration)
fun setScannerCallback(ScannerCallback); fun setCameraLifecycleCallback(CameraLifecycleCallback)
fun addCameraStateListener(CameraStateListener); fun currentCameraState(): CameraState
fun startCamera(); fun isCameraStarted(): Boolean; fun stopCamera()   // stop auto on detach
fun rescan(); fun pauseDetection(); fun resumeDetection()
fun enableTapToFocus(); fun enablePinchPanToZoom(); fun setZoomRatio(Float); fun getMaxZoomRatioAvailable(): Float?
fun setFocusPoint(x: Float, y: Float)   // 0..1
fun capture()                           // OCR/Photo → onImageCaptured; Barcode/QR → capture next; PriceTag/ItemRetrieval → nothing
fun getFocusRegionManager(): FocusRegionManager   // only after onCameraStarted; .setFocusSettings(FocusSettings)
```

- `DetectionMode.{Barcode, QRCode, BarcodeOrQRCode, OCR, Photo}` (PriceTag/ItemRetrieval internal → use enable*Mode).
- `ScanningMode.{Manual, Auto}`.
- `CameraSettings(nthFrameToProcess = 10, cameraLensFace = CameraLensFace.Back, orientationMode = CameraOrientationMode.AUTO)`.
- `FocusSettings(context, focusImage, focusImageRect: RectF /* view px */, shouldDisplayFocusImage = false,
  shouldScanInFocusImageRect = false, …, showCodeBoundariesInMultipleScan = true, …, showDocumentBoundaries = true, …)`.
  With `shouldScanInFocusImageRect = true` a code is kept only if its box is fully inside the rect.
- `ObjectDetectionConfiguration(isTextIndicationOn, isBarcodeOrQRCodeIndicationOn, isDocumentIndicationOn,
  secondsToWaitBeforeDocumentCapture = 3, isImageSharpnessIndicationOn)`.

## Callbacks (all default `{}`)

```kotlin
interface ScannerCallback {
  fun onIndications(barcodeDetected: Boolean, qrCodeDetected: Boolean, textDetected: Boolean, documentDetected: Boolean)
  fun onIndicationsBoundingBoxes(barcodeBoundingBoxes: List<ScannedCodeResult>, qrCodeBoundingBoxes: List<ScannedCodeResult>, documentBoundingBox: Rect?)
  fun onScanResult(barcodeList: List<ScannedCodeResult>)
  fun onFailure(exception: VisionSDKException)
  fun onImageSharpnessScore(imageSharpnessScore: Double)
  fun onImageCaptured(bitmap: Bitmap, scannedCodeResults: List<ScannedCodeResult>, imageSharpnessScore: Float)
  fun onPriceTagResult(priceTagData: PriceTagData)          // (productSKU, productPrice, boundingBox: Rect)
  fun onItemRetrievalResult(scannedCodeResults: ScannedCodeResult)
}
interface CameraLifecycleCallback { fun onCameraStarted(); fun onCameraStopped() }
data class ScannedCodeResult(scannedCode: String, boundingBox: Rect, symbology: BarcodeSymbology, gs1ExtractedInfo: Map<String,String>?,
                             normalizedBoundingBox: RectF, angleDeg: Float, ocrText: String?)
```
`VisionSDKException(errorCode, errorMessage, …)`: 1–5 No*Detected (→ `rescan()`), 7 PriceTagNotEligible,
12 ItemRetrievalNotEligible, 23 RootDeviceDetected, 25 OnDeviceOCRDownloadingFailed, 36 InternetRequired…,
38 CallStartCameraOrRescanBeforeCapture, 46 SubscriptionExpired, 50 BlurImageDetected, 51 ModelDownloadCancelled.

## Models (ModelManager)

```kotlin
sealed class OCRModule(open var modelSize: ModelSize?) { ShippingLabel(size, ShippingLabelOptions(parseRecipientAddress, parseSenderAddress));
  BillOfLading(size, BillOfLadingOptions()); ItemLabel(size, ItemLabelOptions()); DocumentClassification(size) }
enum class ModelSize { Nano, Micro, Small, Medium, Large, XLarge }
// supported: SL Nano/Micro/Large · BOL Large · IL Large · DC Micro/Large
suspend fun downloadModel(module, apiKey, token = null, platformType = Native, progressListener: ((DownloadProgress) -> Unit)?)
suspend fun loadModel(module, apiKey, token = null, platformType = Native, executionProvider = NNAPI)
suspend fun findDownloadedModel(module): ModelInfo?; fun isModelLoaded(module): Boolean
suspend fun checkModelUpdates(module, apiKey, …, updateIfAvailable = false): ModelUpdateInfo
fun cancelDownload(module): Boolean; suspend fun deleteModel(module): Boolean; fun unloadModel(module): Boolean
```

## Extraction

- On device: `OnDeviceOCRManager(context, module).makePrediction(module, bitmap, codes): String` (JSON; models via ModelManager).
- Cloud (`ApiManager()`), all `suspend …Sync(): String` JSON:
  `shippingLabelApiCallSync(apiKey, token, bitmap, shouldResizeImage, barcodeList, locationId, recipient, sender, options, metadata)`,
  `billOfLadingApiCallSync(apiKey, token, bitmap, true, barcodeList)`, `itemLabelApiCallSync(apiKey, token, bitmap, true)`,
  `documentClassificationApiCallSync(apiKey, token, bitmap, true)`, `vlmApiCallSync(apiKey, token, bitmap, false, prompt)`,
  `shippingLabelMatchingApiSync(…, onDeviceResponse)`, `itemLabelMatchingApiCallSync(…, onDeviceResponse)`.
- Wild card = DocumentClassification, then the module it reports.
- Report: `ApiManager().reportIssueSuspend(context, apiKey, null, Native, modelToReport, report, customData, image): ReportResult`
  with `SLModelToReport`, `BOLModelToReport`, `ILModelToReport`, `DCModelToReport`.

## Entitlement

No public check. `enablePriceTagMode(apiKey)` / `enableItemRetrievalMode(apiKey)` *is* the check (cached license
first, network refresh on miss); catch `VisionSDKException`.

## AR / Document (demo code in `vision-sdk-android/app/src/main/java/io/vision_sdk_android/ui/activities/`)

- `ar/`: ArScannerActivity (428), ArBarcodeRenderer (1112), BackgroundRenderer (121), BarcodeProcessor (169, uses
  `com.packagexlabs.visionbarcodescanner.VisionBarcodeScanner` directly), MarkerGlRenderer (196), MarkerOverlayView (95),
  SessionRecorder (161). ARCore `com.google.ar:core:1.56.0`, GLES 2.0. No VisionSDK classes.
- `document/`: DocumentCaptureActivity (686, own CameraX pipeline + `com.packagex.docscanner` from
  `app/libs/docscanner-release.aar`), DocumentReviewActivity (367), DocumentSession (104), DocumentDewarpModel
  (221, GMS TFLite + asset `uvdoc_fp16.tflite`), DocumentDewarp (183, `DocumentResampleNative`), DocumentEnhancer
  (257, `DocumentEnhanceNative`), DocumentTextLayer (77, ML Kit text), DocumentPdf (54), DocumentQuality (82), DocumentPage (39).

## Secrets pattern in the old app

`app/build.gradle.kts:11-20`: `secrets.properties` (git-ignored) or env var → `buildConfigField`. The old
MainActivity hard-codes a key at :349 — do not copy.
