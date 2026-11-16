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

// HORIZON.md B.14: android's :xr feature module (the Android XR immersive
// theater, ENHANCEMENT.md Section N; its sources arrive in xr/ with the merges)
// is not part of this branch's build. Jetpack XR needs Google's spatial
// runtime, which Horizon OS doesn't have, a Quest's theater is Section G's
// (src/horizonTheater), and the Meta Horizon Store takes one APK with no
// feature splits. android has `include(":xr")` here.
