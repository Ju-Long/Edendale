package com.babasama.edendale.android.player

import java.util.Locale

/**
 * Row labels for the Video Track and Audio Track pickers (B.3), pure so the
 * JVM suite covers them. A row reads `name (language) — detail`: the track's
 * own name, its language unless the name already says it, then the
 * resolution or the channel layout.
 */
internal object TrackLabels {

    /** "Mono", "Stereo", "5.1", "7.1", or "N ch"; null when the count is unknown. */
    fun channels(count: Int, mono: String, stereo: String, other: (Int) -> String): String? = when {
        count <= 0 -> null
        count == 1 -> mono
        count == 2 -> stereo
        count == 6 -> "5.1"
        count == 8 -> "7.1"
        else -> other(count)
    }

    /** The language's name in [displayLocale] ("English"), or null for none or "und". */
    fun languageName(code: String?, displayLocale: Locale): String? {
        val tag = code?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) } ?: return null
        val name = Locale.forLanguageTag(tag).getDisplayLanguage(displayLocale).takeIf { it.isNotBlank() } ?: tag
        return name.replaceFirstChar { it.titlecase(displayLocale) }
    }

    /**
     * The track's name with " (Language)" appended unless the name already
     * mentions the language — by its display name anywhere, or by its code as a
     * whole word, so "en" never matches inside "Lenses". A track with no name
     * falls back to its language, then to [fallback] ("Track 2").
     */
    fun named(label: String?, languageCode: String?, languageName: String?, fallback: String): String {
        val name = label?.trim()?.takeIf { it.isNotEmpty() } ?: return languageName ?: fallback
        if (languageName == null) return name
        val code = languageCode?.trim()?.takeIf { it.isNotEmpty() }
        val mentionsName = name.contains(languageName, ignoreCase = true)
        val mentionsCode = code != null && name.split(Regex("[^\\p{L}\\p{N}-]+")).any { it.equals(code, ignoreCase = true) }
        return if (mentionsName || mentionsCode) name else "$name ($languageName)"
    }

    fun audio(label: String?, languageCode: String?, languageName: String?, channels: String?, fallback: String): String =
        named(label, languageCode, languageName, fallback) + (channels?.let { " — $it" } ?: "")

    fun video(label: String?, languageCode: String?, languageName: String?, width: Int, height: Int, fallback: String): String =
        named(label, languageCode, languageName, fallback) + if (width > 0 && height > 0) " — $width×$height" else ""
}
