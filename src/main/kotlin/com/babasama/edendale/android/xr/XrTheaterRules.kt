package com.babasama.edendale.android.xr

/**
 * Which devices get the Theater chip (ENHANCEMENT.md N-D2). The theater is a
 * feature split that Play installs only on devices declaring the spatial
 * feature; a debug install of the base APK alone has the feature but not the
 * split, and a phone has neither. Resolving the activity proves nothing,
 * because the split's manifest is merged into the base's.
 */
object XrTheaterRules {
    /** `PackageManager.FEATURE_XR_API_SPATIAL`: the device runs Android XR's spatial APIs. */
    const val FEATURE_SPATIAL = "android.software.xr.api.spatial"

    /** The feature module's name, as `ApplicationInfo.splitNames` reports it. */
    const val SPLIT_NAME = "xr"

    /** The theater activity, named as a string because the base cannot see the split's classes. */
    const val ACTIVITY_CLASS = "com.babasama.edendale.android.xr.theater.XrTheaterActivity"

    fun offers(hasSpatialFeature: Boolean, installedSplits: Collection<String>?, hasVideo: Boolean): Boolean =
        hasSpatialFeature && installedSplits?.contains(SPLIT_NAME) == true && hasVideo
}
