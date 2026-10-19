package com.babasama.edendale.android.data

import android.content.Context

/**
 * Google Drive library state that stays on this device (H.9.4): each
 * source's hidden file IDs (D21), and whether the one-time "Link Google Drive
 * Again" notice is still to be shown (D20). Library data, not secrets, but
 * excluded from backup and device transfer with the logins, so a removed
 * video's ID never leaves the device. Blocking disk access: call from a
 * background thread.
 */
class GoogleDriveStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The Drive file IDs removed from the source at [treeUri] on this device. */
    fun hidden(treeUri: String): Set<String> = preferences.getStringSet(hiddenKey(treeUri), null).orEmpty().toSet()

    /** Replaces the source's hidden IDs with [transform] of them; one update at a time, so removals don't race. */
    @Synchronized
    fun updateHidden(treeUri: String, transform: (Set<String>) -> Set<String>) {
        val ids = transform(hidden(treeUri))
        preferences.edit().apply {
            if (ids.isEmpty()) remove(hiddenKey(treeUri)) else putStringSet(hiddenKey(treeUri), ids.toSet())
        }.commit()
    }

    /** A removed source forgets what it hid. */
    fun clear(treeUri: String) = updateHidden(treeUri) { emptySet() }

    /** Set by the launch that removed Google Drive accounts or sources from before `drive.file`; the notice clears it. */
    var needsRelinkNotice: Boolean
        get() = preferences.getBoolean(RELINK_NOTICE_KEY, false)
        set(value) {
            preferences.edit().putBoolean(RELINK_NOTICE_KEY, value).commit()
        }

    companion object {
        /** Listed in backup_rules.xml and data_extraction_rules.xml. */
        const val FILE_NAME = "edendale_google_drive"

        private const val RELINK_NOTICE_KEY = "relink-notice"

        private fun hiddenKey(treeUri: String) = "hidden|$treeUri"
    }
}
