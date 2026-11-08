pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Edendale"

// The Android XR immersive theater (ENHANCEMENT.md Section N) ships as a Play
// Feature Delivery module installed only on devices with the spatial feature,
// so phones and TVs never carry its rooms or renderer.
include(":xr")
