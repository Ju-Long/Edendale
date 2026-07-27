package com.babasama.edendale

/** Human-readable platform name used by diagnostics and tests. */
val platformName: String = "Android"

/** Android core identity and build-time capability checks. */
object EdendaleCore {
    const val moduleName: String = "EdendaleAndroid"

    /** App version string; kept in sync with Android's versionName. */
    const val version: String = "0.26"

    val description: String
        get() = "$moduleName core running on $platformName"

    /** True when a TMDB credential was provided via <root>/secrets.json. */
    fun hasTmdbCredentials(): Boolean =
        TmdbSecrets.readAccessToken.isNotBlank() || TmdbSecrets.apiKey.isNotBlank()
}
