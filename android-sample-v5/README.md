# VisionSDK v5 Sample (android-sample-v5)

Installs as **Label Scanner** (`io.vision_sdk_android`), the same name and application ID as the internal
vision-sdk-android demo, so it replaces that app on a device. The Kotlin code package stays `io.packagex.visiondemo`.

A Jetpack Compose sample app demonstrating VisionSDK v2.8.0-local (the build that reads barcodes with
BarcodeScannerApp's engine), ported feature-for-feature from
`vision-sdk-ios`'s v5 demo. Single Gradle module (`:app`), Hilt DI, unidirectional data flow.

## Requirements

- An **arm64-v8a device** (physical, API 29+ / Android 10+). The SDK ships `arm64-v8a` binaries
  only (`ndk.abiFilters`), so there is no emulator or x86_64 path.
- **JDK 17** to run Gradle.
- A checkout of `vision-sdk-android` as a sibling of this repo's parent directory (see
  *Consuming vision-sdk-android locally* below) — needed for the SDK build and two assets this
  repo doesn't commit.

## 1. Publish the SDK to mavenLocal

This sample consumes VisionSDK from `mavenLocal()`, not JitPack. From your `vision-sdk-android`
checkout:

```bash
export JAVA_HOME=<path to a JDK 17>
cd vision-sdk-android
git checkout feature/barcode-engine-swap   # the engine swap, until it is merged to main
./gradlew -Psdk.version=v2.8.0-local :vision-native:publishToMavenLocal :VisionScanner:publishLocal
```

`:VisionScanner:publishLocal` does a clean + `assembleRelease` + publish, so it's the reliable way
to get a fresh `com.packagexlabs:VisionScanner:v2.8.0-local` artifact (`-Psdk.version` overrides
`gradle.properties` without editing it). `vision-native` publishes alongside it at the same version.
Both land under `~/.m2/repository/com/packagexlabs/`.

**The barcode engine (`com.packagexlabs:barcode-scanner:0.2.1`, with `barcode-pipeline(-android)`)**
is BarcodeScannerApp's scanner: VisionScanner v2.8.0-local reads every barcode with it, and this
sample's AR Barcode feeds it ARCore frames directly. It isn't produced by the build above either;
publish it from a BarcodeScannerApp checkout first
(`./gradlew :shared:publishToMavenLocal :scanner:publishToMavenLocal`, see its README). It ships
arm64-v8a only, like this sample.

## 2. Secrets

```bash
cp secrets.properties.example secrets.properties
# fill in STAGING_API_KEY (and PRODUCTION_API_KEY if you'll test that environment)
```

`secrets.properties` is git-ignored and feeds `BuildConfig` via `app/build.gradle.kts`. It supplies:

- `VISION_ENV` — `staging` or `production` (defaults to `staging`)
- `STAGING_API_KEY` / `PRODUCTION_API_KEY`
- `IL_FEEDBACK_URL` — defaults to the shared item-label feedback endpoint if unset

Every value can also come from an environment variable of the same name, which takes precedence
over `secrets.properties` (useful for CI). An empty key isn't a build error: the app launches and
shows "Add STAGING_API_KEY to secrets.properties" instead of silently failing SDK calls.

`local.properties` (git-ignored, not committed) must point `sdk.dir` at your Android SDK.

## 3. vision-sdk-android assets (uvdoc model, docscanner AAR)

Two binaries aren't committed to this repo — copied/referenced at build time instead:

- `uvdoc_fp16.tflite` (~15 MB, the UVDoc dewarp model) is copied from
  `vision-sdk-android/app/src/main/assets/` into `build/generated/uvdoc/` by the `copyUvDoc`
  Gradle task (wired into `preBuild`).
- `docscanner-release.aar` is referenced directly from
  `vision-sdk-android/app/libs/docscanner-release.aar`.

Both resolve through the Gradle property `visionSdkAndroidDir`, which defaults to
`../../vision-sdk-android` relative to this directory (`android-sample-v5/`) — i.e. a sibling of
this repo's parent directory. Override it if your `vision-sdk-android` checkout lives elsewhere:

```bash
./gradlew :app:assembleDebug -PvisionSdkAndroidDir=/path/to/vision-sdk-android
```

## Build, run, test

```bash
export JAVA_HOME=<path to a JDK 17>
./gradlew :app:testDebugUnitTest :app:assembleDebug   # unit tests + debug build
./gradlew :app:installDebug                           # install on a connected arm64 device
./gradlew :app:connectedDebugAndroidTest               # instrumented tests, needs a connected device
./gradlew :app:assembleRelease :baselineprofile:assemble # release build + profile/benchmark test APKs
# :baselineprofile:assemble also builds the app's plugin-added nonMinifiedRelease and benchmarkRelease variants
```

## Release build

```bash
./gradlew :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew :app:installRelease
```

The `release` build type is minified and resource-shrunk with R8 (full mode), not debuggable, and
uses `proguard-android-optimize.txt` plus `app/proguard-rules.pro`. Most keep rules come from the
libraries' own consumer rules (VisionScanner, the barcode engine — its JNI class and LiteRT —, ARCore,
ML Kit, Hilt, DataStore, kotlinx.serialization); `proguard-rules.pro` adds only what nothing else
keeps — the JNI-bound `io.packagex.visionsdk.native.*` wrappers, the rule-less local docscanner AAR
and LiteRT for document dewarp.
`app/build/outputs/mapping/release/mapping.txt` deobfuscates release stack traces
(`retrace mapping.txt stacktrace.txt`).

**Signing.** Release signing reads four values, from environment variables or `secrets.properties`
(environment wins, same as the API keys):

| Name | Value |
|---|---|
| `RELEASE_STORE_FILE` | keystore path, relative to `android-sample-v5/` or absolute |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias |
| `RELEASE_KEY_PASSWORD` | key password |

If all four are empty, release (and the baseline-profile/benchmark variants derived from it)
is signed with the **debug keystore** (the build logs a warning), so `assembleRelease` works on any
machine. Setting only some of the four fails the build. Such an APK installs
over a debug build from the same machine but isn't fit for distribution. Never commit a keystore or
its passwords; `secrets.properties` is git-ignored and `secrets.properties.example` lists the names
with empty values.

## Local Models build

The Android counterpart of the iOS "VisionSDK Demo v5 (Local Models)" scheme: the `localRelease` build
type is `release` plus every on-device OCR model the Models sheet offers (shipping label micro/large,
BOL large, item label large, document classification micro/large, and the key-value micro model BOL and
item label use). Nothing is downloaded; the default `release` build is unchanged.

```bash
./gradlew :app:assembleLocalRelease -PvisionSdkAndroidDir=<vision-sdk-android>   # app/build/outputs/apk/localRelease/app-localRelease.apk
./gradlew :app:installLocalRelease  -PvisionSdkAndroidDir=<vision-sdk-android>
```

- **Build time.** `fetchBundledModels` (`app/bundled-models.gradle.kts`) makes the SDK's own
  `v1/sdk/connect` call per model with `<VISION_ENV>_API_KEY`, decrypts the download link the way the SDK
  does (with the environment keys in the vision-sdk-android checkout's `VisionSDK.kt`) and downloads the
  payload as served — still encrypted. Each download is checked against the server's MD5 of the decrypted
  model. Payloads are cached in `build/bundled-models/` (git-ignored, never commit them) and fetched again
  only when the server has a new version. Needs network and the API key at build time.
- **First launch.** `BundledModels.kt` hands each payload to `ModelManager.installBundledModel()`, which
  unwraps its key and re-encrypts it with the device key exactly like a download, into the same place.
  This takes a while once; later launches skip installed versions (and put back a model the SDK deleted).
  The active document type's model is then loaded up front, as `preloadBundledModel()` does on iOS; the
  Vision Scanner never prompts for a download.
- The bundled models are a fixed version; "Check for updates" in the Models sheet still works and would
  download a newer one.

## Baseline profiles and startup benchmarks

`:baselineprofile` (a `com.android.test` module using the `androidx.baselineprofile` plugin) drives
the release app with UiAutomator: cold start → module cards → Barcode camera → open and close Settings →
QR code → Vision Scanner → Price tag → AR Item Count → Document Acquisition → back to Barcode (each from its
module card, via the camera's back arrow). CAMERA is
granted with `pm grant` first. **AR Barcode is skipped**: ARCore start-up depends on Google Play
Services for AR being installed and current on the device and is not reliable under automation.

The generated profile is committed at `app/src/release/generated/baselineProfiles/baseline-prof.txt`
and packaged into release builds (no `startup-prof.txt`: the journey goes well past startup, so it
isn't collected as a startup/dex-layout profile);
`androidx.profileinstaller` installs it on devices where Play doesn't. Generation is manual
(`automaticGenerationDuringBuild = false`), so regenerate after significant UI or startup changes,
with one arm64 device connected (the app has no emulator ABI) and unlocked:

```bash
./gradlew :app:generateReleaseBaselineProfile     # runs BaselineProfileGenerator on the device
```

It builds the `nonMinifiedRelease` variant the plugin adds, collects the profile and writes it into
`app/src/release/generated/baselineProfiles/`. Commit the result.

Startup benchmark (`StartupBenchmarks`: cold start to the module cards, `CompilationMode.None()`
vs `Partial(BaselineProfileMode.Require)`, 10 iterations each) runs against the `benchmarkRelease`
variant:

```bash
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
```

Results (`timeToInitialDisplayMs` min/median/max) print in the test output and land as JSON under
`baselineprofile/build/outputs/connected_android_test_additional_output/`. Both tasks reinstall the
app, replacing whatever build of `io.vision_sdk_android` was on the device.

## Modes

The app opens on module cards (design v6): SCAN CODES — Barcode (single/multi), QR, Price tag,
AR Item Count (item retrieval), AR Barcode; CAPTURE DATA — Vision Scanner (on-device / cloud / hybrid
OCR — SL, BOL, IL, wild card), Document Acquisition. Each card opens its own camera; the back arrow (or
system Back) returns to the cards and releases the camera. A single Barcode/QR read shows as a code card
over the camera; every other result is full screen. Dimensioning and Text Templates are not in this
sample (see *Known gaps*).

Barcode and QR code read ~3840x2160 frames with BarcodeScannerApp's engine (VisionSDK v2.8.0-local).
With Multiple scan and Show boxes on, the SDK draws the boxes itself with the engine's overlay
(yellow while a code is being read, green with its text once read, gliding with the label and
fading out); the app draws none there. Vision Scanner's boxes are still the app's own. AR Barcode's
items list shows the engine's symbology ids (`code128`, `qrcode`, …).

## Architecture

- **`camera/`** — `CameraController` is the single owner of the SDK's `VisionCameraView`; modes
  hand off ownership (`CameraOwner`: `Scanner` / `Ar` / `Document` / `None`) rather than each
  holding their own camera instance. `PausePolicy` centralizes idle-timeout (90 s), thermal
  (`PowerManager` thermal status), and lifecycle (background/foreground) pausing behind one
  `paused: StateFlow<Boolean>`.
- **`scanner/`** — `ScannerViewModel` + `ScannerUiState` (single state object, unidirectional:
  `ScannerAction` in, state + one-shot `ScannerEffect`s out). `ScannerRules.kt` holds the pure
  decision functions (`activeModel`, `cloudSelected`, `noCodeCopy`, …) ported from iOS
  `DemoModel`/`Sheets.swift`, kept separate from the ViewModel for testability. `DocumentFlow` is
  Document Acquisition's slice of the same ViewModel (pages, capture, PDF export), since it shares
  `ScannerUiState` but owns a different camera pipeline (CameraX, not the SDK's).
- **`document/`** — Document Acquisition's own CameraX pipeline, dewarp (UVDoc), enhancement and
  PDF export. Independent of `camera/CameraController` by design (`CameraOwner.Document`).
- **`ar/`** — AR Barcode: ARCore session, marker rendering, and the thread-safe item catalog used
  by the Items sheet while AR is scanning.
- **`data/`** — repositories (`ExtractionRepository`, `ModelRepository`, `ReportRepository`,
  `EntitlementRepository`, `ItemCatalogRepository`, `PreferencesRepository`/DataStore) and the SDK
  response parsers (`OcrParser`, `JsonFields`).
- **`model/`** — plain data types (`ScanMode`, `DocType`, `ScannerConfig`, …) with no Android or
  SDK dependencies, so they're cheap to unit test.
- **`designsystem/`** — tokens, type, and shared composables (`SheetScaffold`, `Shutter`, …).
- **`di/`** — Hilt modules (`AppModule`, `DataModule`) binding the repository interfaces to their
  SDK-backed implementations; tests substitute fakes (`fakes/Fakes.kt`) instead.
- **`io.packagex.visionsdk.native/`** — a stop-gap copy of two SDK wrappers; see *Known gaps*.

## Known gaps

- **Dimensioning** and **Text Templates** are not implemented — hidden from the mode dial and not
  wired to any camera owner.
- **Local Models builds** (iOS builds that bundle every on-device model and load the active one up
  front) have no Android counterpart. Model management itself is there: Settings › Models lists
  each on-device model with download / load / unload / delete and an update check.
- **SDK native wrappers carried by the sample** (`io.packagex.visionsdk.native/DocumentNative.kt`):
  the SDK AAR (v2.7.0 and v2.8.0-local) strips `DocumentResampleNative`/`DocumentEnhanceNative`, the Kotlin wrappers
  around `libvision_native.so`'s NEON document kernels (release minify, no keep rule). The sample
  carries copies with the same JNI signatures so dewarp/enhance still use NEON. **Delete this
  file** once a released SDK version ships those wrappers again — the build then fails with a
  duplicate-class error, which is the signal to remove it.

## Testing

- Unit (`:app:testDebugUnitTest`, JVM + Robolectric): `OcrParser`, `ItemLabelFeedback`, model-state
  transitions, mode-generation dropping, camera-ownership handoff (fake SDK view),
  `ScannerViewModel` with fakes + Turbine, `PausePolicy` with `runTest` virtual time.
- Instrumented (`:app:connectedDebugAndroidTest`, needs a connected arm64 device): a handful of
  Compose tests for mode switching, sheets, and the result drawer.
- Manual device pass: install on a physical device and walk the "Behaviour to match iOS v5" list in
  `docs/superpowers/specs/2026-09-28-android-sample-v5-design.md`.
