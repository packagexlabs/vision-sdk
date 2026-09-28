import java.util.Properties
plugins {
    alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization); alias(libs.plugins.ksp); alias(libs.plugins.hilt)
}
val secrets = Properties().apply { rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
fun secret(name: String) = System.getenv(name) ?: secrets.getProperty(name) ?: ""
val visionSdkAndroidDir = rootProject.file(providers.gradleProperty("visionSdkAndroidDir").getOrElse("../../vision-sdk-android"))

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
