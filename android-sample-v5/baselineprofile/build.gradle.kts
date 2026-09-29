plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "io.packagex.visiondemo.baselineprofile"
    compileSdk = 36
    defaultConfig {
        // Macrobenchmark needs API 28+; the app itself is minSdk 29.
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    targetProjectPath = ":app"
}
kotlin { jvmToolchain(17) }

// The app ships arm64-v8a only, so profiles are generated on a connected physical device, not a managed emulator.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}

// Gradle 8 makes `assemble` build the artifacts of every visible configuration, and the plugin's
// `<variant>BaselineProfile` configuration's artifact is the on-device collection task. Hide it so
// `:baselineprofile:assemble` only builds the test APKs and never needs a device. `isVisible` is
// deprecated in Gradle 9, where `assemble` no longer builds visible configurations, so drop this then.
configurations.configureEach { if (name.endsWith("BaselineProfile")) isVisible = false }
