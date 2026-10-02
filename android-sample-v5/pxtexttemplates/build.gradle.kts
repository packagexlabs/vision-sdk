// PXTextTemplates SDK (Text Templates module), brought in from the standalone FieldPredictor project.
// Sources and public API are unchanged; dependencies come from this sample's version catalog.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.packagex.texttemplates"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // Prediction API base URL. Not part of the public PXConfiguration surface —
        // baked in at build time so the shipped SDK points at production by default.
        // Override for local/staging builds via the PX_BASE_URL env var or a
        // `-PpxBaseUrl=<url>` Gradle property (env var wins).
        val pxBaseUrl = (System.getenv("PX_BASE_URL")?.takeIf { it.isNotBlank() }
            ?: (project.findProperty("pxBaseUrl") as? String)?.takeIf { it.isNotBlank() }
            ?: "https://text-templates.web.app")
        buildConfigField("String", "PX_BASE_URL", "\"$pxBaseUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}
kotlin { jvmToolchain(17) }

dependencies {
    // Core
    api(libs.core.ktx)

    // EXIF — read orientation from encoded image bytes in predict(imageBytes),
    // so an EXIF-rotated JPEG orients like it does via predict(filePath) / iOS.
    implementation(libs.exifinterface)

    // Compose — needed only for the drop-in PXScannerView composable.
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.runtime)
    implementation(libs.lifecycle.runtime.ktx)

    // CameraX — part of the public API (FrameAnalyzer, PXScannerView).
    api(libs.camerax.core)
    api(libs.camerax.camera2)
    api(libs.camerax.lifecycle)
    api(libs.camerax.view)

    // ML Kit — part of the public API (predict(InputImage)).
    api(libs.mlkit.text.play)
    api(libs.mlkit.barcode)

    // Retrofit + OkHttp — the SDK owns its own network stack.
    api(libs.retrofit)
    api(libs.retrofit.gson)
    implementation(libs.okhttp)

    // Room — the SDK owns its own template cache.
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)

    // Coroutines
    api(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    // NOTE: this module has NO dependency on Hilt or javax.inject. Every class
    // uses a plain constructor and PXClient wires the graph by hand. The
    // consuming app owns Hilt.

    testImplementation(libs.junit)
}
