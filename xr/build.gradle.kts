// The Android XR immersive theater: a Play Feature Delivery module that Play
// installs only on devices with android.software.xr.api.spatial (see its
// manifest). It holds the Jetpack XR libraries, the three rooms, and the
// theater activity; the pure placement rules and the handover live in the base
// so the hermetic JVM suite covers them (ENHANCEMENT.md Section N).
// The root project applies the Android and Kotlin plugins itself, which puts
// them on every subproject's classpath already, so they are applied here by id
// alone: a second request with a version is refused by Gradle.
plugins {
    id("com.android.dynamic-feature")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.babasama.edendale.android.xr"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":"))
    // SceneCore brings ARCore for Jetpack XR (the head pose) and, through it,
    // Google's ARCore SDK for phones, which runs on Google Play Services for
    // AR. The theater exists only where the OpenXR runtime does, so that
    // runtime and the SDK stay out (ENHANCEMENT.md Section N, decision N-D4).
    // SceneCore also declares a newer Media3 than the base ships; the base
    // owns Media3, and the feature must not carry a second copy (N-D8).
    listOf(libs.androidx.xr.scenecore, libs.androidx.xr.runtime, libs.androidx.xr.arcore).forEach { library ->
        implementation(library) {
            exclude(group = "androidx.media3")
            exclude(group = "androidx.xr.arcore", module = "arcore-play-services")
            exclude(group = "com.google.ar", module = "core")
        }
    }
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.media3.exoplayer)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
