# Android sample v5 — design

Date: 2026-09-28 · Branch: `demo-v5` (vision-sdk, off `origin/main`) · Folder: `android-sample-v5/`

## Goal

An Android port of the iOS "VisionSDK Demo v5" app (vision-sdk-ios, branch `demo-v5`), built with current
Android practice: Jetpack Compose, Google's recommended app architecture, modular Gradle build. It sits next
to the old XML `android-sample/` (SDK 0.0.2, AGP 7.4) and will replace it later.

Success: every mode Android supports behaves as it does in iOS v5 (list below), runs on the Honeywell CT47
(`23013B01AA`) and a current Pixel-class phone, and the build is reproducible from a clean checkout plus a
`publishToMavenLocal` of vision-sdk-android.

## Decisions (from the user)

- Visual design: the same Claude Design file as iOS ("VisionSDK Demo - PackageX v5"), PackageX tokens and fonts.
- Full behavioural parity with iOS v5, not design only.
- Dimensioning and Text Templates are hidden: Android has no SDK for them.
- SDK comes from `mavenLocal()` (vision-sdk-android `:VisionScanner:publishToMavenLocal`, `sdk.version` in its
  `gradle.properties`, currently `v2.7.0`). Not buildable by outsiders until switched to JitPack.
- AR Barcode and Document Acquisition are demo-app code in `vision-sdk-android/app`
  (`ui/activities/ar/*`, `ui/activities/document/*`), ported into feature modules like iOS `Ported/`.
- "Best Compose architecture", but not over-engineered: Google's recommended layering (UI state + ViewModel,
  repositories) in one Gradle module, Hilt for wiring, no convention plugins or module sprawl.

## Out of scope

- Dimensioning, Text Templates (no Android SDK).
- A "Local Models" build flavor: the Android SDK has no bundled-model source (iOS has `VSDK_LOCAL_MODELS` /
  `LocalPipeline`; Android has nothing equivalent). Add a `local` flavor when the SDK gains one.
- Publishing, Play Store, CI for this folder.

## Stack

Kotlin 2.2 · AGP 8.12 · Gradle version catalog · compileSdk/targetSdk 36, minSdk 24 (matches `:VisionScanner`) ·
Compose BOM 2025.11 + Material3 · Hilt (KSP) · `lifecycle-runtime-compose` (`collectAsStateWithLifecycle`) ·
coroutines/Flow · DataStore Preferences · ARCore (ported AR) · Tests: JUnit4, Turbine, coroutines-test,
Compose UI test.

## Layout

One `app` module, package by feature. Split into modules only if build times hurt.

```
android-sample-v5/app/src/main/java/io/packagex/visiondemo/
├── App.kt, MainActivity.kt        Hilt root, single activity, edge-to-edge
├── designsystem/                  PackageX tokens, fonts, PXButton, Badge, Segmented, ToggleRow,
│                                  CloseButton, Glass, Shutter, sheet scaffold
├── model/                         plain types: ScanMode, DocType, ModelSize, ScanResult, DetectedCode, ModelState
├── data/                          PreferencesRepository (DataStore), EntitlementRepository, ModelRepository,
│                                  ReportRepository, ItemCatalogRepository, OcrParser, ItemLabelFeedback
├── camera/                        CameraController: owns VisionCameraView, one camera owner at a time,
│                                  per-mode config, idle / thermal / lifecycle pause
├── scanner/                       camera screen, mode dial, chrome, viewfinder, result drawer, overlays,
│                                  ScannerViewModel (Barcode, QR, Vision Scanner, Price tag, Item retrieval)
├── ar/                            ported AR Barcode
├── document/                      ported Document Acquisition
└── settings/                      settings, doc type, models, item list sheets
```

Only `camera/`, `data/`, `ar/` and `document/` touch the SDK.

## Architecture (per Google's app architecture guide)

**UI layer.** Each feature has a `…Route` composable (gets the ViewModel via `hiltViewModel()`, collects
state with `collectAsStateWithLifecycle`) and a stateless `…Screen(state, onAction)` that previews and
screenshot-tests without Hilt. ViewModels expose one `StateFlow<UiState>` (immutable data class) and accept a
sealed `Action` interface (`onAction(action)`); one-off effects (toast, haptic, share sheet) go through a
`Channel` exposed as `Flow<Effect>`. No mutable state leaks out of a ViewModel.

**Screen composition.** One activity, one camera screen. The mode dial switches modes inside
`ScannerViewModel`; AR and Document swap the camera surface via `CameraController`'s ownership handoff rather than
navigating. Sheets are Material3 `ModalBottomSheet` driven by a `sheet: SheetKind?` field in UI state (swap =
dismiss, then present on `onDismissRequest` completion — the iOS swap bug avoided by construction). Only true
full-screen destinations (document page zoom viewer) use Navigation Compose with type-safe routes.

**Data layer.** Repositories wrap the SDK behind interfaces (`ModelRepository`, `EntitlementRepository`,
`ReportRepository`) with suspend/Flow APIs; SDK callbacks are adapted with `callbackFlow` /
`suspendCancellableCoroutine`. The API key and environment come from `BuildConfig`, filled from
`local.properties` / environment variables (`VISION_API_KEY`, `VISION_ENV`) — never committed.
Repositories are interfaces so ViewModel tests use small fakes.

**Camera layer.** `CameraController` (Hilt singleton) owns the one `VisionCameraView` and exposes
`StateFlow<CameraStatus>` plus `Flow<ScanEvent>` (codes, boxes, captured image, OCR result, errors). It
applies per-mode config (`setDetectionMode`, `setScanningMode`, `setMultipleScanEnabled`, focus region,
nthFrame), and a single `CameraOwner` (Scanner | AR | Document | None) so two pipelines never hold the sensor.
The composable embeds the view with `AndroidView` and never configures it.

**Concurrency.** ViewModels use `viewModelScope`; results from slow work carry the mode generation and are
dropped if the mode changed (iOS `modeGeneration`). SDK threads never touch state directly.

## Behaviour to match iOS v5

- Modes: Barcode (single/multi), QR, Vision Scanner (on-device / cloud / hybrid, doc types SL, BOL, IL, wild
  card), Price tag, Item retrieval, Document Acquisition, AR Barcode. Dim/TT hidden from the dial.
- Single-code mode decodes only inside the viewfinder brackets and draws no boxes; boxes only in multi-code
  and Vision Scanner; box captions without a "Vision" prefix; "Code detected" only when the code is in frame.
- nthFrame: 7 for barcode/QR/price/OCR, 2 for item retrieval.
- Entitlement gate for Price tag / Item retrieval using the SDK's cached license; default-deny until checked.
- On-device model states (not downloaded / downloading % / downloaded / loaded / failed) with the same
  prompts; loading spinner only while VLM / on-device extraction runs; cancel for slow requests.
- OCR result drawer: per-DocType order/labels, sections, tables, `validated_by` colours, report-an-error,
  item-label feedback, copy (clipboard marked sensitive), last-result thumbnail that re-pauses the camera.
- AR: pause/resume without losing counts, Items sheet naming codes while AR runs (thread-safe catalog),
  VIO warm-up reset per session, lower capture resolution for heat.
- Document: auto/manual toggle, live boundary, Original/Final views, zoomable page, retake keeps pages after
  export, PDF export via share sheet.
- Heat: idle pause after 90 s, thermal pause (`PowerManager` thermal status ≥ SEVERE), detection paused
  under results and sheets, camera released on `ON_STOP` and restarted on `ON_START`; a paused camera shows
  the blurred last frame, tap anywhere resumes. No pause dialog.
- Camera permission requested in-app; denied state with "Open Settings".

## Error handling

SDK errors map to a sealed `ScanError` in `data/`; the ViewModel turns them into the same alerts/toasts
as iOS ("Download failed. Check the connection.", "Still too hot…"). Network failures leave cached
entitlement untouched. No silent guards: every blocked action gives feedback.

## Testing

- Unit: `OcrParser`, `ItemLabelFeedback`, model-state transitions, mode-generation dropping, camera-owner
  handoff (fake SDK view), ViewModels with fakes + Turbine.
- UI: a few Compose tests for mode switching, sheets and the result drawer.
- Device: `./gradlew :app:installDebug` on the CT47 and a phone; manual pass over the behaviour list above.

## Build and run

```
# 1. SDK into mavenLocal (from vision-sdk-android, JDK 17)
./gradlew :vision-native:publishToMavenLocal :zbarscanner:publishToMavenLocal :VisionScanner:publishToMavenLocal
# 2. Sample (from vision-sdk/android-sample-v5)
echo "VISION_API_KEY=…" >> local.properties
./gradlew :app:installDebug
```
