// The AR counting core: what is counted, decided from ARCore poses and decoded barcodes as plain data.
// No Android, so every rule runs and is tested on the JVM (spec: vision-sdk-android
// docs/superpowers/specs/2026-10-02-ar-session-counting-design.md, sections 5.1, 5.4, 5.6, 5.7).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { alias(libs.plugins.kotlin.jvm) }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    testImplementation(libs.junit)
}
