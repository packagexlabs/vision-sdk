# VisionSDK v5 Sample (android-sample-v5)

A Jetpack Compose sample app demonstrating VisionSDK v2.7.0, ported feature-for-feature from
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
./gradlew :vision-native:publishToMavenLocal :VisionScanner:publishLocal
```

`:VisionScanner:publishLocal` does a clean + `assembleRelease` + publish, so it's the reliable way
to get a fresh `com.packagexlabs:VisionScanner:v2.7.0` artifact. `vision-native` publishes
alongside it. Both land under `~/.m2/repository/com/packagexlabs/`.

**`vision-barcode-scanner` (`com.packagexlabs:vision-barcode-scanner:3.0.0-1730`)** is a private
artifact and isn't produced by the `vision-sdk-android` build above — it needs to already be in
your `mavenLocal()` (published from its own repo) or resolvable from PackageX's private JitPack
with a `JITPACK_TOKEN` environment variable. This sample's `settings.gradle.kts` adds
`https://jitpack.io` unauthenticated, so without a mavenLocal copy you'll need to wire the same
Basic-auth credential block `vision-sdk-android/settings.gradle.kts` uses for its own JitPack
resolution.

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
```

## Modes

Barcode (single/multi), QR, Vision Scanner (on-device / cloud / hybrid OCR — SL, BOL, IL, wild
card), Price tag, Item retrieval, Document Acquisition, AR Barcode. Dimensioning and Text
Templates are not in this sample's mode dial (see *Known gaps*).

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
- **`io.packagex.visionsdk.native/`** — `DocumentNative.kt`: the SDK v2.7.0 AAR strips
  `io.packagex.visionsdk.native`'s `DocumentResampleNative`/`DocumentEnhanceNative` Kotlin wrappers
  around `libvision_native.so`'s NEON document kernels (added upstream after that release cut).
  This sample carries its own copies with the same JNI signatures so dewarp/enhance still work.
  **Delete this file** once a released SDK version ships those wrappers again — the build then
  fails with a duplicate-class error, which is the signal to remove it.

## Known gaps

- **Dimensioning** and **Text Templates** are not implemented — hidden from the mode dial and not
  wired to any camera owner.
- **Local Models** management (a standalone on-device model browser/downloader screen, as some iOS
  demo builds have) is not implemented; on-device OCR models still download/load through
  `ModelRepository`, just without a dedicated management screen.

## Testing

- Unit (`:app:testDebugUnitTest`, JVM + Robolectric): `OcrParser`, `ItemLabelFeedback`, model-state
  transitions, mode-generation dropping, camera-ownership handoff (fake SDK view),
  `ScannerViewModel` with fakes + Turbine, `PausePolicy` with `runTest` virtual time.
- Instrumented (`:app:connectedDebugAndroidTest`, needs a connected arm64 device): a handful of
  Compose tests for mode switching, sheets, and the result drawer.
- Manual device pass: install on a physical device and walk the "Behaviour to match iOS v5" list in
  `docs/superpowers/specs/2026-09-28-android-sample-v5-design.md`.
