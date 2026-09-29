# Android sample v5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Jetpack Compose Android port of the iOS "VisionSDK Demo v5" app in `vision-sdk/android-sample-v5/`.

**Architecture:** One `app` module, package by feature. Hilt wires repositories (SDK behind interfaces) into
`@HiltViewModel`s that expose one immutable `StateFlow<UiState>` and take a sealed `Action`; stateless
`…Screen(state, onAction)` composables render it. A singleton `CameraController` owns the one
`VisionCameraView` and enforces a single camera owner (Scanner | AR | Document).

**Tech Stack:** Kotlin 2.2.21, AGP 8.12.1, Compose BOM 2025.11.01 + Material3, Hilt 2.57 (KSP), lifecycle 2.10,
coroutines 1.10, DataStore 1.1, ARCore 1.56.0, CameraX 1.5.1, GMS TFLite 16.5.0, ML Kit text 16.0.1,
tests: JUnit4, Turbine 1.2, coroutines-test, Compose ui-test.

**Spec:** `docs/superpowers/specs/2026-09-28-android-sample-v5-design.md`
**SDK facts (read first):** `docs/superpowers/plans/android-sdk-reference.md`
**Behaviour source of truth:** iOS app at `vision-sdk-ios/VisionSDK Demo/VisionSDK Demo v5/` (branch `demo-v5`).
Each UI/logic task names the iOS file it ports; match its behaviour, strings and layout.

## Global Constraints

- Folder `android-sample-v5/` in repo `vision-sdk`, branch `demo-v5`. Do not touch `android-sample/`.
- Package / applicationId `io.packagex.visiondemo`; app label "VisionSDK v5".
- compileSdk 36, targetSdk 36, **minSdk 29** (the SDK throws below API 29); JVM 17; arm64 device only (SDK ships arm64-v8a only).
- SDK from `mavenLocal()`: `com.packagexlabs:VisionScanner:v2.7.0` (+ `vision-native`, `vision-barcode-scanner:3.0.0-1730`).
- Secrets never committed (public repo): `STAGING_API_KEY`, `PRODUCTION_API_KEY`, `VISION_ENV` from env var or git-ignored
  `android-sample-v5/secrets.properties` → `BuildConfig`. Empty key = app shows "Add STAGING_API_KEY to secrets.properties".
- Large binaries are not copied into this repo: `docscanner-release.aar` and `uvdoc_fp16.tflite` are referenced from
  `visionSdkAndroidDir` (Gradle property, default `../../vision-sdk-android`).
- Dimensioning and Text Templates are not in the mode dial.
- No pause dialog: a paused camera shows the blurred last frame; tap anywhere resumes.
- Build/run with `JAVA_HOME` = a JDK 17 (e.g. `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`).
- Every commit ends with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`; check `git diff --cached` for keys before each commit.

## Review Focus

- A mode switch while a cloud/on-device request is in flight → the late result must be dropped (Task 7 test `lateResultAfterModeSwitchIsDropped`).
- Swapping sheets (e.g. sign-out-style flows, Settings → Models) → the next sheet must actually appear (Task 9 test `sheetSwapPresentsNext`).
- App backgrounded during AR / Document / scanner → camera released, restarted on return, even from an idle pause (Task 6 test `foregroundResumesFromAnyPause`).
- Single-code mode with a code outside the brackets → no decode, no box, no "Code detected" (Task 6 test `singleModeRestrictsToFrame`).
- Empty or missing API key → clear message instead of a crash or silent failure (Task 1 test `missingKeyIsReported`).

---

### Task 1: Project scaffold, secrets, Hilt app shell

**Files:**
- Create: `android-sample-v5/settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`,
  `gradle/wrapper/*` (copy from `vision-sdk-android/gradle/wrapper/`), `gradlew`, `gradlew.bat`, `.gitignore`,
  `secrets.properties.example`, `README.md`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/io/packagex/visiondemo/App.kt`,
  `MainActivity.kt`, `data/Secrets.kt`, `app/src/main/res/values/strings.xml`, `res/values/themes.xml`
- Test: `app/src/test/java/io/packagex/visiondemo/data/SecretsTest.kt`

**Interfaces:**
- Produces: `data class Secrets(val apiKey: String, val environment: String)` with `val isMissing: Boolean` and
  `val missingMessage: String`; `@Provides Secrets` in `di/AppModule.kt`; `@HiltAndroidApp class App` that calls
  `VisionSDK.getInstance().initialize(this, env)` and `ModelManager.initialize(this)` once.

- [ ] **Step 1: settings + catalog**

`settings.gradle.kts`:
```kotlin
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenLocal(); google(); mavenCentral(); maven("https://jitpack.io") }
}
rootProject.name = "VisionSDKv5"
include(":app")
```
`gradle/libs.versions.toml` (versions from Tech Stack):
```toml
[versions]
agp = "8.12.1"
kotlin = "2.2.21"
ksp = "2.2.21-2.0.4"
composeBom = "2025.11.01"
activityCompose = "1.12.0"
lifecycle = "2.10.0"
hilt = "2.57.2"
hiltNavigationCompose = "1.3.0"
coroutines = "1.10.2"
datastore = "1.1.7"
serialization = "1.9.0"
visionSdk = "v2.7.0"
visionBarcodeScanner = "3.0.0-1730"
arcore = "1.56.0"
camerax = "1.5.1"
tflite = "16.5.0"
mlkitText = "16.0.1"
junit = "4.13.2"
turbine = "1.2.1"

[libraries]
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material-icons = { module = "androidx.compose.material:material-icons-extended" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-process = { module = "androidx.lifecycle:lifecycle-process", version.ref = "lifecycle" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version.ref = "hiltNavigationCompose" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
vision-sdk = { module = "com.packagexlabs:VisionScanner", version.ref = "visionSdk" }
vision-barcode-scanner = { module = "com.packagexlabs:vision-barcode-scanner", version.ref = "visionBarcodeScanner" }
arcore = { module = "com.google.ar:core", version.ref = "arcore" }
camerax-core = { module = "androidx.camera:camera-core", version.ref = "camerax" }
camerax-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "camerax" }
camerax-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camerax" }
camerax-view = { module = "androidx.camera:camera-view", version.ref = "camerax" }
tflite-java = { module = "com.google.android.gms:play-services-tflite-java", version.ref = "tflite" }
tflite-gpu = { module = "com.google.android.gms:play-services-tflite-gpu", version.ref = "tflite" }
mlkit-text = { module = "com.google.mlkit:text-recognition", version.ref = "mlkitText" }
junit = { module = "junit:junit", version.ref = "junit" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```
Root `build.gradle.kts`: `plugins { alias(libs.plugins.android.application) apply false; …each plugin apply false }`.
`.gitignore`: `.gradle/`, `build/`, `local.properties`, `secrets.properties`, `.idea/`, `*.iml`.
`secrets.properties.example`:
```
VISION_ENV=staging
STAGING_API_KEY=
PRODUCTION_API_KEY=
```

- [ ] **Step 2: app/build.gradle.kts**

```kotlin
import java.util.Properties
plugins {
    alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization); alias(libs.plugins.ksp); alias(libs.plugins.hilt)
}
val secrets = Properties().apply { rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
fun secret(name: String) = System.getenv(name) ?: secrets.getProperty(name) ?: ""
val visionSdkAndroidDir = file(providers.gradleProperty("visionSdkAndroidDir").getOrElse("../../vision-sdk-android"))

android {
    namespace = "io.packagex.visiondemo"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.packagex.visiondemo"
        minSdk = 29; targetSdk = 36; versionCode = 1; versionName = "5.0"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "VISION_ENV", "\"${secret("VISION_ENV").ifEmpty { "staging" }}\"")
        buildConfigField("String", "STAGING_API_KEY", "\"${secret("STAGING_API_KEY")}\"")
        buildConfigField("String", "PRODUCTION_API_KEY", "\"${secret("PRODUCTION_API_KEY")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/uvdoc"))
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { jvmToolchain(17) }

// UVDoc dewarp model lives in vision-sdk-android (15 MB); copy at build time instead of committing it.
val copyUvDoc by tasks.registering(Copy::class) {
    from(visionSdkAndroidDir.resolve("app/src/main/assets")) { include("uvdoc_fp16.tflite", "UVDoc-LICENSE.txt") }
    into(layout.buildDirectory.dir("generated/uvdoc"))
}
tasks.named("preBuild") { dependsOn(copyUvDoc) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui); implementation(libs.compose.material3); implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview); debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose); implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose); implementation(libs.lifecycle.process)
    implementation(libs.hilt.android); ksp(libs.hilt.compiler); implementation(libs.hilt.navigation.compose)
    implementation(libs.coroutines.android); implementation(libs.datastore.preferences); implementation(libs.serialization.json)
    implementation(libs.vision.sdk); implementation(libs.vision.barcode.scanner)
    implementation(libs.arcore)
    implementation(libs.camerax.core); implementation(libs.camerax.camera2); implementation(libs.camerax.lifecycle); implementation(libs.camerax.view)
    implementation(libs.tflite.java); implementation(libs.tflite.gpu); implementation(libs.mlkit.text)
    implementation(files(visionSdkAndroidDir.resolve("app/libs/docscanner-release.aar")))
    testImplementation(libs.junit); testImplementation(libs.coroutines.test); testImplementation(libs.turbine)
    androidTestImplementation(platform(libs.compose.bom)); androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
```

- [ ] **Step 3: Write the failing test**

`app/src/test/java/io/packagex/visiondemo/data/SecretsTest.kt`:
```kotlin
package io.packagex.visiondemo.data
import org.junit.Assert.*
import org.junit.Test
class SecretsTest {
    @Test fun missingKeyIsReported() {
        val s = Secrets(apiKey = "", environment = "staging")
        assertTrue(s.isMissing)
        assertEquals("Add STAGING_API_KEY to secrets.properties", s.missingMessage)
    }
    @Test fun presentKeyIsNotMissing() = assertFalse(Secrets("key_x", "production").isMissing)
    @Test fun pickSelectsKeyForEnvironment() {
        assertEquals("p", Secrets.pick("production", staging = "s", production = "p").apiKey)
        assertEquals("s", Secrets.pick("staging", staging = "s", production = "p").apiKey)
    }
}
```

- [ ] **Step 4: Run it to see it fail**

Run: `cd android-sample-v5 && ./gradlew :app:testDebugUnitTest --tests '*SecretsTest'`
Expected: FAIL, unresolved reference `Secrets`.

- [ ] **Step 5: Implement**

`data/Secrets.kt`:
```kotlin
package io.packagex.visiondemo.data
data class Secrets(val apiKey: String, val environment: String) {
    val isMissing get() = apiKey.isBlank()
    val missingMessage get() = "Add ${environment.uppercase()}_API_KEY to secrets.properties"
    companion object {
        fun pick(env: String, staging: String, production: String) =
            Secrets(if (env == "production") production else staging, env)
    }
}
```
`di/AppModule.kt`:
```kotlin
@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun secrets() = Secrets.pick(BuildConfig.VISION_ENV, BuildConfig.STAGING_API_KEY, BuildConfig.PRODUCTION_API_KEY)
    @Provides @Singleton fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
```
`App.kt`:
```kotlin
@HiltAndroidApp
class App : Application() {
    @Inject lateinit var secrets: Secrets
    override fun onCreate() {
        super.onCreate()
        VisionSDK.getInstance().initialize(this, if (secrets.environment == "production") Environment.PRODUCTION else Environment.STAGING)
        if (!ModelManager.isInitialized()) ModelManager.initialize(this) { maxConcurrentDownloads(2) }
    }
}
```
`MainActivity.kt`: `@AndroidEntryPoint class MainActivity : ComponentActivity()` with `enableEdgeToEdge()` and
`setContent { Text("VisionSDK v5") }` (replaced in Task 8). Manifest: `android:name=".App"`, camera permission,
`<uses-feature android:name="android.hardware.camera.ar" android:required="false"/>`,
`<meta-data android:name="com.google.ar.core" android:value="optional"/>`, portrait `MainActivity`, theme
`Theme.Material3.DayNight.NoActionBar` parent.

- [ ] **Step 6: Run tests and build**

Run: `./gradlew :app:testDebugUnitTest --tests '*SecretsTest' :app:assembleDebug`
Expected: PASS, `app/build/outputs/apk/debug/app-debug.apk` exists. If `VisionScanner:v2.7.0` doesn't resolve, publish it
first: `cd ../../vision-sdk-android && ./gradlew :vision-native:publishToMavenLocal :VisionScanner:publishLocal`.

- [ ] **Step 7: Commit**

`README.md`: the Build and run block from the spec, plus `cp secrets.properties.example secrets.properties`.
```bash
git add android-sample-v5 && git diff --cached | grep -iE "key_[0-9a-f]{6}" && echo "KEY LEAK" ; \
git commit -m "feat(android-sample-v5): Compose + Hilt scaffold on SDK v2.7.0"
```

---

### Task 2: Design system

**Files:**
- Create: `designsystem/Tokens.kt`, `Type.kt`, `Theme.kt`, `Components.kt` (PXButton, Badge, Segmented, ToggleRow,
  SectionLabel, LinkLabel, CloseButton, Glass modifier), `Shutter.kt`, `SheetScaffold.kt`
- Create: `app/src/main/res/font/montserrat.ttf`, `inter.ttf`, `dm_mono_medium.ttf` (copy from iOS `Fonts/`; lowercase names), `OFL.txt` → `app/src/main/assets/licenses/OFL.txt`
- Test: `app/src/test/java/io/packagex/visiondemo/designsystem/TokensTest.kt`

**Interfaces:**
- Produces: `object PX { val Purple, PurpleDark, Lilac, Neon, Green, Red, RedText, Ink, Text2, Muted, Hairline, Surface, SwitchOff, Glass: Color }`,
  `Montserrat`, `Inter`, `DmMono: FontFamily`, `@Composable PXButton(title, kind = PXButtonKind.Primary, height = 54.dp, onClick)`,
  `Badge(text, tone)`, `<T> Segmented(items: List<Pair<String,T>>, selection: T, onSelect: (T)->Unit, disabled: Set<T> = emptySet())`,
  `ToggleRow(title, desc, checked, onCheckedChange)`, `CloseButton(onClick)`, `Modifier.glass(radius = 22.dp)`,
  `Shutter(ringColor, dimmed, onTap, onLongPress)`, `SheetScaffold(title, onClose, content)`, `VisionTheme(content)`.

- [ ] **Step 1: Failing test** — hex values match iOS `UI/Theme.swift`:
```kotlin
class TokensTest {
    @Test fun tokensMatchIos() {
        assertEquals(Color(0xFF7420E2), PX.Purple); assertEquals(Color(0xFF47EAE2), PX.Neon)
        assertEquals(Color(0xFF101023), PX.Ink); assertEquals(Color(0xFF983B3B), PX.RedText)
        assertEquals(PX.Ink.copy(alpha = 0.5f), PX.Glass)
    }
}
```
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*TokensTest'` → FAIL (unresolved `PX`).
- [ ] **Step 3: Implement** — port iOS `UI/Theme.swift` 1:1:
  - `Tokens.kt`: every color from iOS `extension Color` (0x7420E2 Purple, 0x231773 PurpleDark, 0xDBCAF1 Lilac, 0x47EAE2 Neon, 0x1BF2A3 Green,
    0xF25252 Red, 0x983B3B RedText, 0x101023 Ink, 0x3E3E4D Text2, 0x6C6C77 Muted, 0xEBEDF2 Hairline, 0xF9F9F9 Surface, 0xC7C7CB SwitchOff, Glass = Ink 50%).
  - `Type.kt`: `FontFamily(Font(R.font.montserrat, FontWeight.SemiBold))` etc.; helpers `montserrat(size, weight = SemiBold)`, `inter(size, weight = Normal)`, `mono(size)` returning `TextStyle`.
  - `Components.kt`: PXButton 54dp tall radius 12, primary purple/white, secondary purple outline, tertiary text; Badge tones
    (success D1FCEC/1B8A63, brand EFE5FC/Purple, neutral Surface/Text2, danger F4D4D4/RedText) 22dp min height capsule;
    Segmented grey track Surface + Hairline border, selected segment Ink with Neon text, 44dp min height; ToggleRow with
    Material3 `Switch` tinted Purple and a bottom Hairline divider; CloseButton 32dp Surface circle in a 44dp touch target,
    content description "Close"; `Modifier.glass()` = Glass fill + `Modifier.blur` is NOT used (blur blurs content) — use
    translucent fill only.
  - `SheetScaffold`: header row (title montserrat 20, CloseButton), then scrollable content with 20dp horizontal padding — the
    iOS `SheetHost` layout.
  - `Shutter.kt`: port iOS `Shutter` from `UI/CameraScreen.swift` (ring color, dimmed alpha 0.4, tap + long-press via `combinedClickable`).
  - `VisionTheme`: `MaterialTheme(colorScheme = lightColorScheme(primary = PX.Purple, surface = Color.White, onSurface = PX.Ink))`.
- [ ] **Step 4: Run** tests → PASS; `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(android-sample-v5): PackageX design system`.

---

### Task 3: Domain types and per-mode scanner config

**Files:**
- Create: `model/Types.kt`, `model/ScannerConfig.kt`
- Test: `app/src/test/java/io/packagex/visiondemo/model/ScannerConfigTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class ScanMode(val label: String) { Barcode("Barcode"), QR("QR code"), Ocr("Vision Scanner"), Price("Price tag"),
      Retrieval("Item retrieval"), Ar("AR Barcode"), DocAcq("Document Acquisition") }
  val ScanMode.isCode: Boolean; val ScanMode.isDocument: Boolean; val ScanMode.gated: Boolean; val ScanMode.zooms: List<Float>
  val ScanMode.viewfinder: Rect?   // design artboard 390×844 dp, from iOS Types.swift
  enum class DocType(val label: String) { SL, BOL, IL, DC, VLM, Tire, IdCard, Plate }
  enum class ModelSize { Micro, Large }; enum class Processing { Cloud, Device }
  enum class SheetKind { Settings, DocType, Items, ArItems, Models }
  enum class Phase { Idle, Scanning, Processing }
  sealed interface ModelState { NotDownloaded; data class Downloading(val progress: Float); Downloaded; Loaded; Failed }
  data class DetectedCode(val value: String, val symbology: String, val box: android.graphics.Rect)
  data class ScannerConfig(val detection: DetectionMode?, val multiple: Boolean, val nthFrame: Int,
      val restrictToFrame: Boolean, val showBoxes: Boolean, val needsEntitlement: Boolean)
  fun scannerConfig(mode: ScanMode, multi: Boolean, showBoxesPref: Boolean): ScannerConfig
  ```
  `detection == null` means the mode uses `enablePriceTagMode` / `enableItemRetrievalMode` (Price/Retrieval) or does not use the scanner (AR/DocAcq).

- [ ] **Step 1: Failing test**
```kotlin
class ScannerConfigTest {
    @Test fun singleModeRestrictsToFrameAndHidesBoxes() {
        val c = scannerConfig(ScanMode.Barcode, multi = false, showBoxesPref = true)
        assertTrue(c.restrictToFrame); assertFalse(c.showBoxes); assertEquals(7, c.nthFrame)
        assertEquals(DetectionMode.Barcode, c.detection)
    }
    @Test fun multiModeShowsBoxesAndScansWholeFrame() {
        val c = scannerConfig(ScanMode.Barcode, multi = true, showBoxesPref = true)
        assertFalse(c.restrictToFrame); assertTrue(c.showBoxes); assertTrue(c.multiple)
    }
    @Test fun visionScannerShowsBoxes() = assertTrue(scannerConfig(ScanMode.Ocr, false, true).showBoxes)
    @Test fun boxesOffWhenPrefOff() = assertFalse(scannerConfig(ScanMode.Barcode, true, false).showBoxes)
    @Test fun retrievalEveryOtherFrameAndGated() {
        val c = scannerConfig(ScanMode.Retrieval, false, true)
        assertEquals(2, c.nthFrame); assertTrue(c.needsEntitlement); assertNull(c.detection)
    }
    @Test fun priceTagSeventhFrame() = assertEquals(7, scannerConfig(ScanMode.Price, false, true).nthFrame)
    @Test fun dialHasNoDimOrTextTemplates() =
        assertEquals(listOf("Barcode", "QR code", "Vision Scanner", "Price tag", "Item retrieval", "AR Barcode", "Document Acquisition"),
                     ScanMode.entries.map { it.label })
}
```
- [ ] **Step 2: Run** → FAIL (unresolved).
- [ ] **Step 3: Implement** `ScannerConfig.kt`:
```kotlin
fun scannerConfig(mode: ScanMode, multi: Boolean, showBoxesPref: Boolean): ScannerConfig = when (mode) {
    ScanMode.Barcode, ScanMode.QR -> ScannerConfig(
        detection = if (mode == ScanMode.Barcode) DetectionMode.Barcode else DetectionMode.QRCode,
        multiple = multi, nthFrame = 7, restrictToFrame = !multi,       // single mode: only inside the brackets
        showBoxes = showBoxesPref && multi, needsEntitlement = false)
    ScanMode.Ocr -> ScannerConfig(DetectionMode.OCR, false, 7, restrictToFrame = false, showBoxes = showBoxesPref, needsEntitlement = false)
    ScanMode.Price -> ScannerConfig(null, false, 7, false, false, needsEntitlement = true)
    ScanMode.Retrieval -> ScannerConfig(null, true, 2, false, false, needsEntitlement = true)
    ScanMode.Ar, ScanMode.DocAcq -> ScannerConfig(null, false, 7, false, false, false)
}
```
`Types.kt`: port `isCode`, `isDocument`, `gated` (Price, Retrieval), `zooms` (code modes 1/2/3, Ocr/DocAcq 1/1.5/2) and
`viewfinder` rects verbatim from iOS `Model/Types.swift` (Barcode/Price 20,318,350,122; QR 45,262,300,300; Ocr 48,190,294,400;
DocAcq 20,190,350,400); DocType labels from the same file.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(android-sample-v5): scan modes and per-mode scanner config`.

---

### Task 4: OCR result parser and item-label feedback payload

**Files:**
- Create: `data/OcrParser.kt`, `data/JsonFields.kt`, `data/ItemLabelFeedback.kt`, `model/OcrResult.kt`
- Test: `app/src/test/java/io/packagex/visiondemo/data/OcrParserTest.kt`, `ItemLabelFeedbackTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class OcrField(val id: String, val key: String, val label: String, val value: String, val section: String?,
                      val vertices: List<List<Double>>?, val validatedBy: List<String>)
  data class OcrTable(val title: String, val headers: List<String>, val rows: List<List<String>>)
  data class OcrResult(val docType: DocType, val fields: List<OcrField>, val tables: List<OcrTable>, val primary: OcrField?, val rawJson: String)
  object OcrParser { fun parse(json: String, type: DocType): OcrResult; fun message(json: String): String?; fun documentClass(json: String): String? }
  object ItemLabelFeedback { data class Entry(val edited: String, val thumbs: Boolean?); fun payload(r: OcrResult, entries: Map<String, Entry>, nowSeconds: Double): JsonObject }
  ```
  Use `kotlinx.serialization.json` (`Json.parseToJsonElement`).

- [ ] **Step 1: Failing tests**
```kotlin
class OcrParserTest {
    private val sl = """{"data":{"inference":{"tracking_number":"1Z999","provider_name":"UPS","weight":"2 lb",
        "recipient":{"name":"Ada","address":{"line1":"1 Main St","postal_code":"10001"}},
        "raw_text":"noise","confidence":0.9}}}"""
    @Test fun slOrderLabelsAndPrimary() {
        val r = OcrParser.parse(sl, DocType.SL)
        assertEquals("1Z999", r.primary?.value)
        assertEquals(listOf("Tracking No.", "Courier", "Weight"), r.fields.take(3).map { it.label })
        assertTrue(r.fields.none { it.key == "raw_text" || it.key == "confidence" })
        assertEquals("Receiver", r.fields.first { it.key == "line1" }.section?.substringBefore(" ·"))
        assertEquals("Street", r.fields.first { it.key == "line1" }.label)
    }
    @Test fun tablesBecomeHeaderAndRows() {
        val bol = """{"data":{"inference":{"tables":[{"item":"Box","qty":"2"},{"item":"Crate","qty":"1"}]}}}"""
        val t = OcrParser.parse(bol, DocType.BOL).tables.single()
        assertEquals(listOf("Item", "Qty"), t.headers); assertEquals(listOf(listOf("Box", "2"), listOf("Crate", "1")), t.rows)
    }
    @Test fun messageAndClass() {
        assertEquals("bad", OcrParser.message("""{"message":"bad"}"""))
        assertEquals("shipping_label", OcrParser.documentClass("""{"data":{"inference":{"document_class":"shipping_label"}}}"""))
    }
    @Test fun invalidJsonGivesEmptyResult() = assertTrue(OcrParser.parse("not json", DocType.SL).fields.isEmpty())
}
class ItemLabelFeedbackTest {
    @Test fun correctionIsFlagged() {
        val r = OcrParser.parse("""{"data":{"inference":{"item_name":"Soap"}}}""", DocType.IL)
        val f = r.fields.single()
        val p = ItemLabelFeedback.payload(r, mapOf(f.id to ItemLabelFeedback.Entry("Soap bar", thumbs = false)), nowSeconds = 1.0)
        val e = p["feedback_data"]!!.jsonArray.single().jsonObject
        assertEquals("Soap bar", e["corrected_value"]!!.jsonPrimitive.content)
        assertEquals(true, e["has_correction"]!!.jsonPrimitive.boolean)
        assertEquals("down", e["feedback_thumbs"]!!.jsonPrimitive.content)
    }
}
```
Before writing the implementation, read iOS `Model/OCRParser.swift` and `Model/ItemLabelFeedback.swift` fully and
confirm these expectations match its behaviour (labels, noise keys, section naming "Receiver · …", wrapper stripping,
alphabetical fallback, humanize("qty") = "Qty"). If the iOS output differs, change the test to match iOS, not the reverse.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** by porting `OCRParser.swift` (noise set, `order`, `labels`, `primaryKey`, `walk`, wrapper strip,
  `additional_attributes` lift, ranking) and `JSONFields` helpers (`humanize`, `string`) and `ItemLabelFeedback.payload`
  (entity_id `"$section|$key|$i"`, entity_name, field_type nonSpatial/number/text, thumbs up/down/none,
  is_validated_by_barcode, spatial_info vertices top_left/top_right/bottom_left/bottom_right).
  Also port `submit(...)`: multipart POST to `ItemLabelFeedback.server + "/submit-feedback"` with `image` (JPEG 0.8) and
  `feedback_data`, using `HttpURLConnection` on `Dispatchers.IO` (no new HTTP dependency); `server` = `BuildConfig.IL_FEEDBACK_URL`
  (add `buildConfigField` from secret `IL_FEEDBACK_URL`, default `https://lvlm-api-567462092481.us-east1.run.app`).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(android-sample-v5): OCR parser and item-label feedback`.

---

### Task 5: Repositories (SDK behind interfaces)

**Files:**
- Create: `data/PreferencesRepository.kt`, `data/EntitlementRepository.kt`, `data/ModelRepository.kt`,
  `data/ExtractionRepository.kt`, `data/ReportRepository.kt`, `data/ItemCatalogRepository.kt`, `data/ScanError.kt`,
  `di/DataModule.kt`
- Test: `app/src/test/java/io/packagex/visiondemo/data/ScanErrorTest.kt`, `app/src/test/java/io/packagex/visiondemo/fakes/Fakes.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class Prefs(val mode: ScanMode = ScanMode.Barcode, val multi: Boolean = false, val showBoxes: Boolean = true,
      val docType: DocType = DocType.SL, val modelSize: ModelSize = ModelSize.Micro, val processing: Processing = Processing.Cloud,
      val autoCapture: Boolean = false, val sound: Boolean = true, val parseRecipient: Boolean = false, val parseSender: Boolean = false)
  interface PreferencesRepository { val prefs: Flow<Prefs>; suspend fun update(transform: (Prefs) -> Prefs) }
  interface EntitlementRepository { suspend fun check(view: VisionCameraView, mode: ScanMode): Result<Unit> }  // wraps enablePriceTagMode / enableItemRetrievalMode
  interface ModelRepository { val states: StateFlow<Map<Pair<DocType, ModelSize>, ModelState>>; suspend fun refresh()
      suspend fun download(t: DocType, s: ModelSize, thenLoad: Boolean); suspend fun load(t: DocType, s: ModelSize); fun cancel(t: DocType, s: ModelSize)
      fun unload(t: DocType, s: ModelSize); suspend fun delete(t: DocType, s: ModelSize); suspend fun checkUpdates(): String }
  interface ExtractionRepository { suspend fun extract(bitmap: Bitmap, codes: List<ScannedCodeResult>, type: DocType, processing: Processing, size: ModelSize): String }  // JSON
  interface ReportRepository { suspend fun report(r: OcrResult, fields: Set<String>, message: String, image: Bitmap?): Result<Unit> }
  interface ItemCatalogRepository { val names: StateFlow<Map<String, String>>; suspend fun name(sku: String, name: String); suspend fun remove(sku: String) }
  sealed class ScanError(val title: String, val message: String) { companion object { fun from(e: Throwable): ScanError } }
  ```
- Consumes: `Secrets` (Task 1), `DocType/ModelSize/OcrResult` (Tasks 3–4).

- [ ] **Step 1: Failing test** for the error mapping (the only non-trivial pure logic here):
```kotlin
class ScanErrorTest {
    @Test fun noNetworkReadsLikeIos() = assertEquals("Download failed. Check the connection.",
        ScanError.from(java.net.UnknownHostException()).message)
    @Test fun entitlementIsNotLicensed() = assertEquals("Not enabled for this key",
        ScanError.from(VisionSDKException.PriceTagNotEligible("x")).title)
    @Test fun blurAsksToRetake() = assertEquals("Image too blurry",
        ScanError.from(VisionSDKException.BlurImageDetected).title)
}
```
(Check `VisionSDKException` subclass constructors in `VisionScanner/src/main/java/io/packagex/visionsdk/exceptions/VisionSDKException.kt`
and adjust the construction calls — not the expectations.)
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement**
  - `PreferencesRepository` over `preferencesDataStore("v5")`, keys `v5.pref.<field>`.
  - `EntitlementRepository`: `runCatching { if (mode == Price) view.enablePriceTagMode(secrets.apiKey) else view.enableItemRetrievalMode(secrets.apiKey) }`.
  - `ModelRepository`: map DocType→`OCRModule` (SL→ShippingLabel(size, ShippingLabelOptions(parseRecipient, parseSender)),
    BOL→BillOfLading(Large), IL→ItemLabel(Large), DC→DocumentClassification(size)); sizes Micro→`ModelSize.Micro`, Large→`Large`;
    `download` sets `Downloading(p)` from `progressListener` and `Downloaded`/`Failed`; `load` uses `ExecutionProvider.CPU`
    (as the old app); `refresh` from `findDownloadedModel`/`isModelLoaded`. Rows = iOS `modelRows` (SL, BOL, IL, DC × Micro/Large, only supported combos).
  - `ExtractionRepository`: Device → `OnDeviceOCRManager(context, module).makePrediction(module, bitmap, codes, secrets.apiKey, null)`;
    Cloud → `ApiManager().<type>ApiCallSync(secrets.apiKey, null, bitmap, …)`; VLM/Tire/IdCard/Plate → `vlmApiCallSync` with the
    prompts from iOS `Model/VLMPrompts.swift` (port to `data/VlmPrompts.kt`); wild card = DC first then the reported module.
    All on `Dispatchers.Default`.
  - `ReportRepository`: `ApiManager().reportIssueSuspend(context, secrets.apiKey, null, PlatformType.Native, modelToReport(r), message + " · " + fields.joined, customData = null, image = image?.scaledTo(1000))`
    with `SLModelToReport`/`BOLModelToReport`/`ILModelToReport`/`DCModelToReport` filled from the parsed fields.
  - `ItemCatalogRepository`: DataStore-backed `Map<String,String>` (JSON string pref).
  - `ScanError.from`: `UnknownHostException`/`IOException` → "Download failed. Check the connection."; `PriceTagNotEligible`/`ItemRetrievalNotEligible`/`SubscriptionExpired` → title "Not enabled for this key";
    `BlurImageDetected` → "Image too blurry" / "Hold steady and try again."; others → "Scanner error" / `errorMessage`.
  - `Fakes.kt` (test source set): `FakePreferences`, `FakeModels`, `FakeExtraction(result: String, delayMs: Long)`, `FakeReport`, `FakeEntitlement(allowed: Boolean)`.
  - `DataModule`: `@Binds` each interface to its implementation.
- [ ] **Step 4: Run** → PASS; `:app:assembleDebug` builds.
- [ ] **Step 5: Commit** `feat(android-sample-v5): repositories over the SDK`.

---

### Task 6: CameraController (ownership, per-mode config, pause policy)

**Files:**
- Create: `camera/CameraController.kt`, `camera/PausePolicy.kt`, `camera/ScanEvent.kt`
- Test: `app/src/test/java/io/packagex/visiondemo/camera/PausePolicyTest.kt`, `CameraOwnershipTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class CameraOwner { None, Scanner, Ar, Document }
  sealed interface ScanEvent { data class Codes(val codes: List<ScannedCodeResult>); data class Boxes(val codes: List<ScannedCodeResult>, val doc: Rect?)
      data class Indications(val barcode: Boolean, val qr: Boolean, val text: Boolean, val document: Boolean)
      data class Captured(val bitmap: Bitmap, val codes: List<ScannedCodeResult>, val sharpness: Float)
      data class PriceTag(val data: PriceTagData); data class Retrieved(val code: ScannedCodeResult); data class Failure(val e: VisionSDKException); data object Started }
  class PausePolicy(scope: CoroutineScope, idleTimeoutMs: Long = 90_000) {
      val paused: StateFlow<Boolean>; fun userActive(); fun thermal(status: Int); fun lifecycle(foreground: Boolean); fun resume(): Boolean /* false if critical heat */ }
  @Singleton class CameraController @Inject constructor(@ApplicationContext ctx: Context, scope: CoroutineScope) {
      val view: VisionCameraView; val events: SharedFlow<ScanEvent>; val owner: StateFlow<CameraOwner>; val policy: PausePolicy
      fun claim(owner: CameraOwner)                      // stops the scanner camera unless owner == Scanner
      fun apply(config: ScannerConfig, frame: RectF?, scanning: ScanningMode)
      fun pauseDetection(); fun resumeDetection(); fun capture(); fun rescan(); fun torch(on: Boolean); fun zoom(ratio: Float) }
  ```
- Consumes: `ScannerConfig` (Task 3).

- [ ] **Step 1: Failing tests**
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class PausePolicyTest {
    @Test fun idlePausesAfter90s() = runTest {
        val p = PausePolicy(backgroundScope, 90_000); p.userActive()
        advanceTimeBy(89_000); assertFalse(p.paused.value)
        advanceTimeBy(2_000); assertTrue(p.paused.value)
    }
    @Test fun activityResetsIdle() = runTest {
        val p = PausePolicy(backgroundScope, 90_000); p.userActive(); advanceTimeBy(60_000); p.userActive(); advanceTimeBy(60_000)
        assertFalse(p.paused.value)
    }
    @Test fun severeHeatPauses() = runTest {
        val p = PausePolicy(backgroundScope); p.thermal(PowerManager.THERMAL_STATUS_SEVERE); assertTrue(p.paused.value)
    }
    @Test fun criticalHeatRefusesResume() = runTest {
        val p = PausePolicy(backgroundScope); p.thermal(PowerManager.THERMAL_STATUS_CRITICAL); assertFalse(p.resume()); assertTrue(p.paused.value)
    }
    @Test fun foregroundResumesFromAnyPause() = runTest {
        val p = PausePolicy(backgroundScope, 90_000); p.userActive(); advanceTimeBy(91_000)   // idle-paused
        p.lifecycle(foreground = false); p.lifecycle(foreground = true)
        assertFalse(p.paused.value)
    }
    @Test fun backgroundPauses() = runTest {
        val p = PausePolicy(backgroundScope); p.lifecycle(foreground = false); assertTrue(p.paused.value)
    }
}
```
`CameraOwnershipTest` — extract the owner rule as a pure function and test it:
```kotlin
class CameraOwnershipTest {
    @Test fun arStopsScanner() = assertTrue(scannerMustStop(CameraOwner.Ar))
    @Test fun documentStopsScanner() = assertTrue(scannerMustStop(CameraOwner.Document))
    @Test fun scannerKeepsIt() = assertFalse(scannerMustStop(CameraOwner.Scanner))
    @Test fun singleModeRestrictsToFrame() {
        val fs = focusSettingsFor(scannerConfig(ScanMode.Barcode, multi = false, showBoxesPref = true), RectF(0f, 100f, 300f, 200f))
        assertTrue(fs.restrict); assertFalse(fs.showBoxes); assertEquals(RectF(0f, 100f, 300f, 200f), fs.rect)
    }
}
```
where `internal fun scannerMustStop(o: CameraOwner) = o != CameraOwner.Scanner` and
`internal data class FocusSpec(val rect: RectF, val restrict: Boolean, val showBoxes: Boolean)`,
`internal fun focusSettingsFor(c: ScannerConfig, frame: RectF?): FocusSpec` (rect empty when `!c.restrictToFrame`).
(`RectF` is an Android class: add `testOptions { unitTests.isReturnDefaultValues = true }` is NOT enough for RectF equality —
use a plain `data class Box(l,t,r,b)` in `FocusSpec` and convert to `RectF` only inside `CameraController`.)
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement**
  - `PausePolicy`: `paused` MutableStateFlow; idle `Job` restarted by `userActive()` (skipped while paused); `thermal(status)`
    pauses at ≥ `THERMAL_STATUS_SEVERE`, remembers `critical` at ≥ `THERMAL_STATUS_CRITICAL`; `lifecycle(false)` pauses and
    cancels idle; `lifecycle(true)` calls `resume()`; `resume()` returns false while critical, else unpauses and restarts idle.
  - `CameraController`: creates `VisionCameraView(ctx)` once; `setScannerCallback` forwards every callback into
    `events` (`MutableSharedFlow(extraBufferCapacity = 16)`, `tryEmit`); `setCameraLifecycleCallback.onCameraStarted` re-applies
    the last `FocusSpec` via `getFocusRegionManager().setFocusSettings(FocusSettings(ctx, focusImageRect = spec.rect.toRectF(),
    shouldScanInFocusImageRect = spec.restrict, showCodeBoundariesInMultipleScan = spec.showBoxes, showDocumentBoundaries = false))`
    and emits `Started`. `apply()`: `configure(detection, scanning, multiple)`, `setCameraSettings(CameraSettings(nthFrameToProcess = nthFrame, orientationMode = CameraOrientationMode.PORTRAIT))`,
    `enableTapToFocus()`, `enablePinchPanToZoom()`, focus spec if started. `claim(owner)`: if `scannerMustStop(owner)` → `stopCamera()`,
    else `startCamera()` when not paused. `policy.paused` collector: paused → `stopCamera()`, resumed → `startCamera()` if owner == Scanner.
  - Thermal: register `PowerManager.addThermalStatusListener(ctx.mainExecutor) { policy.thermal(it) }` in `init`.
  - Lifecycle: `ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e -> if (e == ON_STOP) policy.lifecycle(false) else if (e == ON_START) policy.lifecycle(true) })`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(android-sample-v5): camera controller with single owner and heat/idle/background pause`.

---

### Task 7: ScannerViewModel (state, actions, effects)

**Files:**
- Create: `scanner/ScannerUiState.kt`, `scanner/ScannerAction.kt`, `scanner/ScannerViewModel.kt`, `model/ScanResult.kt`
- Test: `app/src/test/java/io/packagex/visiondemo/scanner/ScannerViewModelTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  sealed interface ScanResult { data class Codes(val codes: List<DetectedCode>); data class Ocr(val result: OcrResult, val image: Bitmap?)
      data class Price(val sku: String, val price: String); data class Retrieval(val found: List<String>, val missing: List<String>)
      data class Ar(val rows: List<Triple<String, String, Int>>); data class Document(val pageCount: Int) }
  data class ScannerUiState(val prefs: Prefs = Prefs(), val phase: Phase = Phase.Idle, val result: ScanResult? = null,
      val lastResult: Pair<ScanMode, ScanResult>? = null, val sheet: SheetKind? = null, val pendingSheet: SheetKind? = null,
      val codeInFrame: Boolean = false, val boxes: List<DetectedCode> = emptyList(), val paused: Boolean = false,
      val gated: Boolean = false, val entitlementChecking: Boolean = false, val models: Map<Pair<DocType, ModelSize>, ModelState> = emptyMap(),
      val alert: Alert? = null, val torch: Boolean = false, val permissionDenied: Boolean = false, val missingKey: String? = null)
  data class Alert(val title: String, val message: String, val actions: List<AlertAction>)
  data class AlertAction(val label: String, val kind: PXButtonKind = PXButtonKind.Primary, val action: ScannerAction)
  sealed interface ScannerAction { data class SetMode(val m: ScanMode); data object Shutter; data object CloseResult; data object ReopenLast
      data class OpenSheet(val k: SheetKind); data object DismissSheet; data object SheetDismissed; data object Resume; data object UserActive
      data class UpdatePrefs(val t: (Prefs) -> Prefs); data object ToggleTorch; data object ToggleAuto; data class Report(val fields: Set<String>, val message: String)
      data object CancelProcessing; data object DismissAlert; data class PermissionResult(val granted: Boolean) /* + model actions */ }
  sealed interface ScannerEffect { data class Toast(val text: String); data object Haptic }
  @HiltViewModel class ScannerViewModel @Inject constructor(camera: CameraController, prefs: PreferencesRepository, models: ModelRepository,
      extraction: ExtractionRepository, report: ReportRepository, entitlement: EntitlementRepository, catalog: ItemCatalogRepository, secrets: Secrets) : ViewModel() {
      val state: StateFlow<ScannerUiState>; val effects: Flow<ScannerEffect>; fun onAction(a: ScannerAction) }
  ```
  Expose the camera as `internal val camera: Camera` so tests can reach the fake. To make the ViewModel testable without a real camera, `CameraController` gets an interface `Camera` (`events`, `policy.paused`,
  `claim`, `apply`, `capture`, `pauseDetection`, `resumeDetection`, `rescan`, `torch`) implemented by `CameraController`; bind it in `DataModule`; tests use `FakeCamera`.

- [ ] **Step 1: Failing tests** (Turbine + `StandardTestDispatcher`, `Dispatchers.setMain`)
```kotlin
class ScannerViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private fun vm(extraction: FakeExtraction = FakeExtraction("""{"data":{"inference":{"tracking_number":"1Z"}}}""", 0)) =
        ScannerViewModel(FakeCamera(), FakePreferences(), FakeModels(), extraction, FakeReport(), FakeEntitlement(true), FakeCatalog(), Secrets("k", "staging"))

    @Test fun lateResultAfterModeSwitchIsDropped() = runTest {
        val v = vm(FakeExtraction("""{"data":{"inference":{"tracking_number":"1Z"}}}""", delayMs = 5_000))
        v.onAction(ScannerAction.SetMode(ScanMode.Ocr)); advanceUntilIdle()
        (v.camera as FakeCamera).emit(ScanEvent.Captured(fakeBitmap(), emptyList(), 1f))
        advanceTimeBy(1_000); v.onAction(ScannerAction.SetMode(ScanMode.Barcode)); advanceUntilIdle()
        assertNull(v.state.value.result); assertEquals(Phase.Idle, v.state.value.phase)
    }
    @Test fun sheetPausesDetectionAndCloseResumes() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.OpenSheet(SheetKind.Settings)); advanceUntilIdle(); assertTrue(cam.detectionPaused)
        v.onAction(ScannerAction.DismissSheet); v.onAction(ScannerAction.SheetDismissed); advanceUntilIdle(); assertFalse(cam.detectionPaused)
    }
    @Test fun reopenLastPausesCamera() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        cam.emit(ScanEvent.Codes(listOf(code("123")))); advanceUntilIdle()
        v.onAction(ScannerAction.CloseResult); advanceUntilIdle(); assertFalse(cam.detectionPaused)
        v.onAction(ScannerAction.ReopenLast); advanceUntilIdle(); assertTrue(cam.detectionPaused); assertNotNull(v.state.value.result)
    }
    @Test fun gatedModeDeniedShowsGate() = runTest {
        val v = ScannerViewModel(FakeCamera(), FakePreferences(), FakeModels(), FakeExtraction("{}", 0), FakeReport(), FakeEntitlement(false), FakeCatalog(), Secrets("k", "staging"))
        v.onAction(ScannerAction.SetMode(ScanMode.Price)); advanceUntilIdle(); assertTrue(v.state.value.gated)
    }
    @Test fun missingKeyIsShown() = runTest {
        val v = ScannerViewModel(FakeCamera(), FakePreferences(), FakeModels(), FakeExtraction("{}", 0), FakeReport(), FakeEntitlement(true), FakeCatalog(), Secrets("", "staging"))
        advanceUntilIdle(); assertEquals("Add STAGING_API_KEY to secrets.properties", v.state.value.missingKey)
    }
    @Test fun onDeviceWithoutModelPrompts() = runTest {
        val v = vm(); v.onAction(ScannerAction.UpdatePrefs { it.copy(processing = Processing.Device) }); v.onAction(ScannerAction.SetMode(ScanMode.Ocr))
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        assertEquals("On-device model not loaded", v.state.value.alert?.title)
    }
}
```
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** — port the logic of iOS `Model/DemoModel.swift` (read it fully first):
  - `modeGeneration: Int` bumped on `SetMode`; every async completion checks `gen == modeGeneration` (iOS `runOCR`, `addDocPage`).
  - `SetMode`: reset result/boxes/codeInFrame, `camera.claim(owner)` (Ar/DocAcq → their owner, else Scanner), `camera.apply(scannerConfig(...), frameRect, scanning)`,
    gated modes run `entitlement.check` (state `entitlementChecking`, `gated = failure`), default-deny until it returns.
  - Events: `Codes` → if single mode show result + haptic, else accumulate; `Boxes` → `codeInFrame` = any box inside the frame
    rect (single mode) and `boxes` only when `config.showBoxes`; `Captured` → `Phase.Processing`, `extraction.extract` then
    `OcrParser.parse` → `ScanResult.Ocr`; `Failure` → `rescan()` for codes 1–5 else `ScanError.from` alert.
  - `show(result)`: 380 ms success flash then `result = r`, `camera.pauseDetection()`, `lastResult = mode to r` (iOS `show`/`present`).
  - `ReopenLast` routes through the same `present(r)`.
  - Sheets: `OpenSheet(k)` when a sheet is up sets `pendingSheet = k` and `sheet = null`; `SheetDismissed` presents `pendingSheet`
    if set, else resumes detection (the iOS `presentAfterDismiss` rule, built in).
  - `camera.policy.paused` → `state.paused`; `Resume` → `camera.policy.resume()` or toast "Still too hot. Let the phone cool down first.".
  - `UserActive` on every action → `policy.userActive()`.
  - Model prompt alert text from iOS `ensureModelReady`: title "On-device model not loaded", message "<Doc> · <size> is <why>. Load it to extract on this device.",
    actions Download and load / Load model, "Use Cloud instead", "Cancel".
  - Loading: `Phase.Processing` while extraction runs; `CancelProcessing` bumps generation and returns to Idle.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(android-sample-v5): scanner view model`.

---

### Task 8: Camera screen UI

**Files:**
- Create: `scanner/ScannerRoute.kt`, `scanner/ScannerScreen.kt`, `scanner/CameraSurface.kt`, `scanner/Chrome.kt`
  (top bar, mode dial, context chips, viewfinder brackets, hint, shutter row with last-result thumbnail, zoom presets, torch),
  `scanner/Overlays.kt` (GateCard, NoPermission, AlertCard, processing spinner, flash, boxes overlay), `scanner/Permission.kt`
- Modify: `MainActivity.kt` → `setContent { VisionTheme { ScannerRoute() } }`
- Test: `app/src/androidTest/java/io/packagex/visiondemo/scanner/ScannerScreenTest.kt`

**Interfaces:**
- Consumes: `ScannerUiState`, `ScannerAction` (Task 7), design system (Task 2), `CameraController.view` (Task 6).
- Produces: `@Composable ScannerScreen(state: ScannerUiState, cameraView: @Composable () -> Unit, onAction: (ScannerAction) -> Unit)`.

- [ ] **Step 1: Failing UI test**
```kotlin
class ScannerScreenTest {
    @get:Rule val rule = createComposeRule()
    @Test fun pausedShowsNoDialogAndTapResumes() {
        var got: ScannerAction? = null
        rule.setContent { VisionTheme { ScannerScreen(ScannerUiState(paused = true), cameraView = {}) { got = it } } }
        rule.onNodeWithText("Resume camera").assertDoesNotExist()
        rule.onNodeWithContentDescription("Camera paused").performClick()
        assertEquals(ScannerAction.Resume, got)
    }
    @Test fun dialHasSevenModes() {
        rule.setContent { VisionTheme { ScannerScreen(ScannerUiState(), cameraView = {}) {} } }
        listOf("Barcode", "QR code", "Vision Scanner", "Price tag", "Item retrieval", "AR Barcode", "Document Acquisition")
            .forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("Dimensioning").assertDoesNotExist()
    }
    @Test fun codeDetectedOnlyWhenInFrame() {
        rule.setContent { VisionTheme { ScannerScreen(ScannerUiState(codeInFrame = false), cameraView = {}) {} } }
        rule.onNodeWithText("Code detected", substring = true).assertDoesNotExist()
    }
}
```
- [ ] **Step 2: Run** `./gradlew :app:connectedDebugAndroidTest` on the CT47 (`ANDROID_SERIAL=23013B01AA`) → FAIL.
- [ ] **Step 3: Implement** — port iOS `UI/CameraScreen.swift` and `UI/Overlays.swift` layout, strings and hints:
  - `ScannerRoute`: `hiltViewModel()`, `collectAsStateWithLifecycle()`, collect `effects` in `LaunchedEffect` (Toast via `SnackbarHostState`,
    haptic via `LocalHapticFeedback`), camera permission via `rememberLauncherForActivityResult(RequestPermission())`.
  - `CameraSurface`: `AndroidView(factory = { controller.view.also { (it.parent as? ViewGroup)?.removeView(it) } })`; when `state.paused`
    wrap in `Modifier.blur(24.dp)` (API 31+; on 29–30 use an Ink 70% scrim) and overlay a full-screen `Box` with
    `semantics { contentDescription = "Camera paused"; role = Role.Button }` and `clickable { onAction(Resume) }`. AR/Document
    owners render their own surface (Tasks 11–12) and show Ink when paused.
  - Viewfinder rects: design artboard 390×844 scaled to screen via `BoxWithConstraints`; report the rect in view px to the ViewModel
    (`ScannerAction.FrameChanged(RectF)` — add it to `ScannerAction` and pass to `camera.apply`).
  - Boxes overlay: `Canvas` drawing `state.boxes` (captions = symbology without any "Vision" prefix).
  - Last-result thumbnail → `ReopenLast`; shutter tap → `Shutter`, long-press → `ToggleAuto`.
- [ ] **Step 4: Run** UI tests → PASS; `./gradlew :app:installDebug` on the CT47, check Barcode single/multi, QR, torch, zoom.
- [ ] **Step 5: Commit** `feat(android-sample-v5): camera screen`.

---

### Task 9: Sheets (settings, doc type, models, items) and sheet swapping

**Files:**
- Create: `settings/SheetHost.kt`, `settings/SettingsSheet.kt`, `settings/DocTypeSheet.kt`, `settings/ModelsSheet.kt`, `settings/ItemsSheet.kt`
- Test: `app/src/androidTest/java/io/packagex/visiondemo/settings/SheetHostTest.kt`

**Interfaces:**
- Consumes: `ScannerUiState.sheet/pendingSheet/prefs/models`, actions `OpenSheet`, `DismissSheet`, `SheetDismissed`, `UpdatePrefs` (+ model actions `DownloadModel(t,s)`, `LoadModel`, `UnloadModel`, `DeleteModel`, `CancelDownload`, `CheckUpdates`; item actions `AddItem(sku)`, `RemoveItem(sku)` — add to `ScannerAction`).
- Produces: `@Composable SheetHost(state, onAction)` — a `ModalBottomSheet` per `state.sheet`; `onDismissRequest` → `DismissSheet`;
  after the hide animation (`sheetState.hide()` completion / `LaunchedEffect(state.sheet == null)`) → `SheetDismissed`.

- [ ] **Step 1: Failing test**
```kotlin
class SheetHostTest {
    @get:Rule val rule = createComposeRule()
    @Test fun sheetSwapPresentsNext() {
        var state by mutableStateOf(ScannerUiState(sheet = SheetKind.Settings))
        rule.setContent { VisionTheme { SheetHost(state) { a ->
            state = when (a) { is ScannerAction.OpenSheet -> state.copy(sheet = null, pendingSheet = a.k)
                               ScannerAction.SheetDismissed -> state.copy(sheet = state.pendingSheet, pendingSheet = null)
                               else -> state } } } }
        rule.onNodeWithText("Models").performClick()          // row in Settings that opens the Models sheet
        rule.waitUntil(3_000) { rule.onAllNodesWithText("On-device models").fetchSemanticsNodes().isNotEmpty() }
    }
}
```
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** — port iOS `UI/Sheets.swift` (Settings, DocType, Items; skip TT/Dim/Templates) with `SheetScaffold`;
  detents: DocType/Items/ArItems `skipPartiallyExpanded = false`, others full. Settings rows: processing (Cloud / On device),
  model size, parse recipient/sender, show boxes, sound, auto capture, "Models" row → `OpenSheet(Models)`, AR debug toggles.
  ModelsSheet: one row per supported (DocType, size) with state badge and Download/Load/Unload/Delete/Cancel, "Check for updates".
- [ ] **Step 4: Run** → PASS; on device open each sheet, swap Settings → Models, dismiss.
- [ ] **Step 5: Commit** `feat(android-sample-v5): sheets`.

---

### Task 10: Result drawer, report, item-label feedback

**Files:**
- Create: `scanner/ResultDrawer.kt`, `scanner/ReportCard.kt`, `scanner/FeedbackPanel.kt`, `scanner/ImageViewer.kt`
- Test: `app/src/androidTest/java/io/packagex/visiondemo/scanner/ResultDrawerTest.kt`

**Interfaces:**
- Consumes: `ScanResult`, `OcrResult`, actions `CloseResult`, `Report(fields, message)`, `SendFeedback(entries)` (add), `Copy(text)` (add; ViewModel copies with
  `ClipData` + `ClipDescription.EXTRA_IS_SENSITIVE = true`).
- Produces: `@Composable ResultDrawer(result: ScanResult, expanded: Boolean, onAction)`.

- [ ] **Step 1: Failing test**
```kotlin
class ResultDrawerTest {
    @get:Rule val rule = createComposeRule()
    @Test fun ocrPrimaryAndFieldsShown() {
        val r = OcrParser.parse("""{"data":{"inference":{"tracking_number":"1Z9","provider_name":"UPS"}}}""", DocType.SL)
        rule.setContent { VisionTheme { ResultDrawer(ScanResult.Ocr(r, null), expanded = true) {} } }
        rule.onNodeWithText("1Z9").assertExists(); rule.onNodeWithText("Courier").assertExists()
    }
    @Test fun reportNeedsAField() {
        var got: ScannerAction? = null
        val r = OcrParser.parse("""{"data":{"inference":{"tracking_number":"1Z9"}}}""", DocType.SL)
        rule.setContent { VisionTheme { ReportCard(r, onAction = { got = it }, onClose = {}) } }
        rule.onNodeWithText("Submit").performClick(); assertNull(got)
        rule.onNodeWithText("Select at least one field").assertExists()
    }
}
```
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** — port iOS `UI/ResultDrawer.swift`, `UI/Overlays.swift` (`ReportCard`), `UI/ImageViewer.swift`:
  drawer collapsed/expanded with drag handle; code list with copy; OCR primary field large, sections, tables, `validated_by`
  colours, image with field boxes, Report button, IL feedback (edit + thumbs, submit), Price and Retrieval layouts, Scan next / Close.
- [ ] **Step 4: Run** → PASS; on device: Vision Scanner cloud SL capture → drawer → report → close.
- [ ] **Step 5: Commit** `feat(android-sample-v5): result drawer, report and item-label feedback`.

---

### Task 11: AR Barcode port

**Files:**
- Create: `ar/ArSurface.kt` (Compose host), `ar/ArController.kt` (session lifecycle, was ArScannerActivity logic), and ported
  `ar/ArBarcodeRenderer.kt`, `ar/BackgroundRenderer.kt`, `ar/BarcodeProcessor.kt`, `ar/MarkerGlRenderer.kt`, `ar/MarkerOverlayView.kt`
  (copy from `vision-sdk-android/app/src/main/java/io/vision_sdk_android/ui/activities/ar/`, package → `io.packagex.visiondemo.ar`;
  skip `SessionRecorder.kt`)
- Modify: `ScannerViewModel` (AR result rows, ArItems sheet), `Chrome.kt` (AR chip → `OpenSheet(ArItems)`)
- Test: `app/src/test/java/io/packagex/visiondemo/ar/ArSessionRulesTest.kt`

**Interfaces:**
- Produces: `class ArController(ctx) { val counts: StateFlow<List<PayloadCount>>; fun attach(view: GLSurfaceView); fun pause(); fun resume(); fun detach(); var catalog: Map<String,String> /* @Volatile snapshot */; fun clear() }`,
  `@Composable ArSurface(controller: ArController, paused: Boolean)`.

- [ ] **Step 1: Failing test** — the two rules the iOS review found:
```kotlin
class ArSessionRulesTest {
    @Test fun warmUpResetsOnNewSession() {
        val g = WarmUpGate(minFrames = 60); repeat(60) { g.onTrackedFrame() }; assertTrue(g.ready)
        g.onSessionStart(); assertFalse(g.ready)
    }
    @Test fun catalogReadIsASnapshot() {
        val c = CatalogSnapshot(); c.set(mapOf("A" to "Apple")); val seen = c.get(); c.set(emptyMap()); assertEquals("Apple", seen["A"])
    }
}
```
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement**
  - Extract the renderer's warm-up (`mapReady` / tracked frame count) into `WarmUpGate` and call `onSessionStart()` on every
    `session.resume()` after `attach` — matches iOS fix d9fb1d1.
  - `CatalogSnapshot` = `@Volatile var map` with `get()/set()`; renderer reads it on the GL thread.
  - `ArController` holds the ARCore `Session` (config from ArScannerActivity: focus AUTO, update BLOCKING, planes H+V, light OFF,
    depth AUTOMATIC when supported, camera config filter TARGET_FPS_30, and choose the lowest camera config ≥ 1280 px wide for heat).
    `ArCoreApk.getInstance().requestInstall` from the Activity on first AR entry; unsupported → toast "AR isn't supported on this device".
  - `ArSurface`: `AndroidView { GLSurfaceView(it) }`, `DisposableEffect` → `attach`/`detach`; `paused` → `pause()`/`resume()` without clearing.
  - Result drawer shows payload counts (`ScanResult.Ar`); ArItems sheet names codes via `ItemCatalogRepository` while AR runs.
  - `CameraController.claim(Ar)` before attach; `claim(Scanner)` on leaving.
- [ ] **Step 4: Run** tests → PASS; on a phone with ARCore: markers appear, leave AR and return (no early drift), open Items sheet
  and rename while scanning, background/foreground.
- [ ] **Step 5: Commit** `feat(android-sample-v5): AR Barcode`.

---

### Task 12: Document Acquisition port

**Files:**
- Create: `document/DocumentSurface.kt` (Compose host for the CameraX + DocumentAnalyzer pipeline from DocumentCaptureActivity),
  `document/DocumentController.kt`, `document/DocumentReview.kt` (Compose rewrite of DocumentReviewActivity), and ported
  `DocumentSession.kt`, `DocumentDewarpModel.kt`, `DocumentDewarp.kt`, `DocumentEnhancer.kt`, `DocumentTextLayer.kt`,
  `DocumentPdf.kt`, `DocumentQuality.kt`, `DocumentPage.kt` (copy from `vision-sdk-android/app/.../ui/activities/document/`, package → `io.packagex.visiondemo.document`)
- Modify: `ScannerViewModel` (pages, auto/manual, export flag, retake), `ResultDrawer.kt` (document layout)
- Test: `app/src/test/java/io/packagex/visiondemo/document/DocumentPagesTest.kt`

**Interfaces:**
- Produces: `class DocumentPages { val pages: List<DocumentPage>; var exported: Boolean; fun add(p: DocumentPage); fun retake(dropLast: Boolean); fun reset() }`,
  `class DocumentController(ctx) { val quad: StateFlow<DocumentQuad?>; var auto: Boolean; fun bind(owner: LifecycleOwner, preview: PreviewView); fun capture(); fun unbind() }`.

- [ ] **Step 1: Failing test**
```kotlin
class DocumentPagesTest {
    @Test fun retakeAfterExportKeepsPages() {
        val d = DocumentPages(); d.add(page(1)); d.add(page(2)); d.exported = true
        d.retake(dropLast = true); d.add(page(3))
        assertEquals(listOf(1, 3), d.pages.map { it.index })
    }
    @Test fun captureAfterExportStartsNewDocument() {
        val d = DocumentPages(); d.add(page(1)); d.exported = true; d.add(page(2))
        assertEquals(listOf(2), d.pages.map { it.index })
    }
}
```
(`page(i)` builds a `DocumentPage` with a 1×1 bitmap and `index = i`; add an `index` field if `DocumentPage` lacks one.)
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement**
  - `DocumentPages`: `add` clears when `exported` then sets `exported = false`; `retake` drops last and sets `exported = false` (iOS fix).
  - `DocumentController`: CameraX pipeline and `DocumentAnalyzer` from DocumentCaptureActivity, live quad as `StateFlow`; auto mode
    captures when the quad is steady (same thresholds as the activity); manual = shutter.
  - `DocumentSurface`: `AndroidView { PreviewView(it) }` + `Canvas` quad outline (2 dp Neon border, 12 % Neon fill — iOS design).
  - Review: Original / Final toggle, zoomable page (`Modifier.pointerInput` + `detectTransformGestures`), Retake, Export PDF via
    `FileProvider` share intent (declare the provider in the manifest with `res/xml/file_paths.xml` → `cache-path name="docs" path="docs/"`).
  - `CameraController.claim(Document)` before binding; unbind and `claim(Scanner)` on leaving; unbind while paused.
- [ ] **Step 4: Run** tests → PASS; on device: auto + manual capture, boundary, 2 pages, export, retake keeps page 1.
- [ ] **Step 5: Commit** `feat(android-sample-v5): Document Acquisition`.

---

### Task 13: Device pass and README

**Files:**
- Modify: `android-sample-v5/README.md`

- [ ] **Step 1:** `JAVA_HOME=<jdk17> ./gradlew :app:testDebugUnitTest :app:assembleDebug` → all tests PASS, build succeeds.
- [ ] **Step 2:** `./gradlew :app:connectedDebugAndroidTest` on the CT47 → PASS.
- [ ] **Step 3:** Install on the CT47 and a phone; walk the spec's "Behaviour to match iOS v5" list item by item; fix any
  failure in the owning task's files with a test that reproduces it first.
- [ ] **Step 4:** README: requirements (arm64 device, API 29+, JDK 17), publish SDK to mavenLocal, `secrets.properties`,
  `visionSdkAndroidDir`, run, known gaps (no Dimensioning / Text Templates / Local Models).
- [ ] **Step 5: Commit** `docs(android-sample-v5): README and device pass fixes`.
