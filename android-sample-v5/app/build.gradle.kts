import java.util.Properties
plugins {
    alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization); alias(libs.plugins.ksp); alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
}
val secrets = Properties().apply { rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
fun secret(name: String) = System.getenv(name) ?: secrets.getProperty(name) ?: ""
// buildConfigField takes a Java source literal verbatim; a secret containing a quote or backslash
// would otherwise break (or inject into) the generated BuildConfig source.
fun String.toJavaStringLiteral() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val visionSdkAndroidDir = rootProject.file(providers.gradleProperty("visionSdkAndroidDir").getOrElse("../../vision-sdk-android"))

android {
    namespace = "io.packagex.visiondemo"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.vision_sdk_android"
        minSdk = 29; targetSdk = 36; versionCode = 1; versionName = "5.0"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "VISION_ENV", secret("VISION_ENV").ifEmpty { "staging" }.toJavaStringLiteral())
        buildConfigField("String", "STAGING_API_KEY", secret("STAGING_API_KEY").toJavaStringLiteral())
        buildConfigField("String", "PRODUCTION_API_KEY", secret("PRODUCTION_API_KEY").toJavaStringLiteral())
        buildConfigField("String", "IL_FEEDBACK_URL", secret("IL_FEEDBACK_URL").ifEmpty { "https://lvlm-api-567462092481.us-east1.run.app" }.toJavaStringLiteral())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Release signing comes from secrets.properties / env (RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD,
    // RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD). Without all four, release falls back to the debug keystore
    // so `assembleRelease` (and the baseline-profile/benchmark variants) still build and install locally.
    val releaseSigningNames = listOf("RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
    val releaseSigning = releaseSigningNames.map(::secret)
    val missingSigning = releaseSigningNames.filterIndexed { i, _ -> releaseSigning[i].isEmpty() }
    // Some but not all set is a misconfiguration, not a request for the debug keystore.
    if (missingSigning.isNotEmpty() && missingSigning.size < releaseSigningNames.size) {
        throw GradleException("Release signing partly configured; missing ${missingSigning.joinToString()}. Set all four RELEASE_* values or none.")
    }
    if (missingSigning.isNotEmpty()) logger.warn("RELEASE_* signing not configured; release is signed with the debug keystore")
    signingConfigs {
        if (missingSigning.isEmpty()) {
            create("release") {
                storeFile = rootProject.file(releaseSigning[0])
                storePassword = releaseSigning[1]
                keyAlias = releaseSigning[2]
                keyPassword = releaseSigning[3]
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/uvdoc"))
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // docscanner-release.aar bundles LiteRT 1.4.2's own runtime libs (byte-identical once stripped); keep one copy.
        jniLibs.pickFirsts += listOf("**/libtensorflowlite_jni.so", "**/libtensorflowlite_gpu_jni.so")
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { jvmToolchain(17) }

// Profiles are generated on a device by :baselineprofile (see README), not on every release build.
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
}

// UVDoc dewarp model lives in vision-sdk-android (15 MB); copy at build time instead of committing it.
val copyUvDoc by tasks.registering(Copy::class) {
    from(visionSdkAndroidDir.resolve("app/src/main/assets")) { include("uvdoc_fp16.tflite", "UVDoc-LICENSE.txt") }
    into(layout.buildDirectory.dir("generated/uvdoc"))
}
// Both come from a vision-sdk-android checkout; without them the build would fail later and obscurely
// (a NO-SOURCE copy, then a missing class or model at runtime).
val visionSdkInputs = listOf("app/src/main/assets/uvdoc_fp16.tflite", "app/libs/docscanner-release.aar").map { visionSdkAndroidDir.resolve(it) }
tasks.named("preBuild") {
    dependsOn(copyUvDoc)
    doFirst {
        val missing = visionSdkInputs.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing from vision-sdk-android (visionSdkAndroidDir = $visionSdkAndroidDir): " +
                    missing.joinToString { it.path } + ". Check out vision-sdk-android next to vision-sdk, or pass -PvisionSdkAndroidDir=<path>.",
            )
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui); implementation(libs.compose.material3); implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview); debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose); implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose); implementation(libs.lifecycle.process)
    implementation(libs.hilt.android); ksp(libs.hilt.compiler); implementation(libs.hilt.navigation.compose)
    implementation(libs.profileinstaller); baselineProfile(project(":baselineprofile"))
    implementation(libs.coroutines.android); implementation(libs.datastore.preferences); implementation(libs.serialization.json)
    implementation(libs.vision.sdk); implementation(libs.vision.barcode.scanner)
    implementation(libs.arcore)
    implementation(libs.camerax.core); implementation(libs.camerax.camera2); implementation(libs.camerax.lifecycle); implementation(libs.camerax.view)
    implementation(libs.litert); implementation(libs.litert.gpu); implementation(libs.litert.gpu.api); implementation(libs.mlkit.text)
    implementation(files(visionSdkAndroidDir.resolve("app/libs/docscanner-release.aar")))
    testImplementation(libs.junit); testImplementation(libs.coroutines.test); testImplementation(libs.turbine); testImplementation(libs.robolectric)
    androidTestImplementation(platform(libs.compose.bom)); androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
