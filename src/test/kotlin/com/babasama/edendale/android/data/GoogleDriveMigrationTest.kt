package com.babasama.edendale.android.data

import com.babasama.edendale.connectors.GoogleDriveConnector
import com.babasama.edendale.connectors.GoogleDriveMigration
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.MemorySecretStore
import com.babasama.edendale.connectors.SourceUrl
import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.oauth.CloudAccountVault
import com.babasama.edendale.oauth.CloudProviders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D20 (H.9.4, H.9.T3), Apple's `GoogleDriveMigrationTests`: the move to
 * `drive.file` is a clean break. Google accounts from before it (`openid
 * email drive.readonly`, keyed by the ID token's `sub`) go, and so does every
 * Google Drive source that isn't "Videos you chose" or that one of them
 * read. Other providers' accounts and sources, and accounts and sources from
 * a `drive.file` build, stay.
 */
class GoogleDriveMigrationTest {

    private val readonlyScopes = listOf("openid", "email", "https://www.googleapis.com/auth/drive.readonly")

    private val oldGoogle = CloudAccount(MediaSourceKind.GOOGLE_DRIVE, "110169484474386276334", "me@example.com", "Me", "r-old", readonlyScopes)
    private val newGoogle = CloudAccount(MediaSourceKind.GOOGLE_DRIVE, "01234567890123456789", "me@example.com", "Me", "r-new", CloudProviders.GOOGLE_SCOPES)
    private val oneDrive = CloudAccount(MediaSourceKind.ONE_DRIVE, "user-9", "me@contoso.com", "Me", "r-ms", CloudProviders.ONE_DRIVE_SCOPES)
    private val dropbox = CloudAccount(MediaSourceKind.DROPBOX, "dbid:x", "me@example.com", "Me", "r-db", emptyList())

    private fun source(url: String, kind: MediaSourceKind?, accountKey: String? = null) =
        LibraryFolderEntity(treeUri = url, displayName = url, addedAtEpochMillis = 0, kind = kind?.raw, accountKey = accountKey)

    @Test
    fun `old Google accounts go and every other account stays`() {
        val vault = CloudAccountVault(MemorySecretStore())
        listOf(oldGoogle, newGoogle, oneDrive, dropbox).forEach(vault::save)
        // An account handed to a TV by an older phone has the old scopes too.
        val stale = oldGoogle.copy(subject = "2", scopes = emptyList())
        vault.save(stale)

        val outdated = GoogleDriveMigration.outdatedAccounts(vault.all())
        assertEquals(setOf(oldGoogle.key, stale.key), outdated.map { it.key }.toSet())
        // Old and new keys never collide: sub and permissionId are different subjects.
        assertTrue(oldGoogle.key != newGoogle.key)

        outdated.forEach { vault.remove(it.kind, it.key) }
        assertEquals(setOf(newGoogle, oneDrive, dropbox), vault.all().toSet())
        // A second launch finds nothing more, so the notice shows once.
        assertTrue(GoogleDriveMigration.outdatedAccounts(vault.all()).isEmpty())
    }

    @Test
    fun `old Google Drive sources go and Videos you chose stays`() {
        val oldFolder = source(SourceUrl.accountItem(MediaSourceKind.GOOGLE_DRIVE, oldGoogle.key, listOf("m0"), "Movies"), MediaSourceKind.GOOGLE_DRIVE, oldGoogle.key)
        val sharedDrive = source(
            SourceUrl.accountItem(MediaSourceKind.GOOGLE_DRIVE, oldGoogle.key, listOf("d2"), "Team Films", query = listOf("drive" to "d2")),
            MediaSourceKind.GOOGLE_DRIVE,
            oldGoogle.key,
        )
        // A Drive folder another device's migration already stripped of its account, and a row with no kind
        // stored, which is read from the URL's scheme.
        val orphan = source(SourceUrl.accountItem(MediaSourceKind.GOOGLE_DRIVE, "gone", listOf("root"), "My Drive"), MediaSourceKind.GOOGLE_DRIVE)
        val unkinded = source(SourceUrl.accountItem(MediaSourceKind.GOOGLE_DRIVE, oldGoogle.key, listOf("m1"), "Shows"), null, oldGoogle.key)
        val chosen = source(GoogleDriveConnector.chosenFolderUrl(newGoogle.key), MediaSourceKind.GOOGLE_DRIVE, newGoogle.key)
        // "Videos you chose" read through an old account can't stay either.
        val chosenByOld = source(GoogleDriveConnector.chosenFolderUrl(oldGoogle.key), MediaSourceKind.GOOGLE_DRIVE, oldGoogle.key)
        val others = listOf(
            source(SourceUrl.accountItem(MediaSourceKind.ONE_DRIVE, oneDrive.key, listOf("drive", "item"), "Films"), MediaSourceKind.ONE_DRIVE, oneDrive.key),
            source(SourceUrl.accountItem(MediaSourceKind.DROPBOX, dropbox.key, listOf("id:1"), "Films"), MediaSourceKind.DROPBOX, dropbox.key),
            source("smb://nas/films/", MediaSourceKind.SMB, "nas"),
            source("content://com.android.externalstorage.documents/tree/primary%3AMovies", MediaSourceKind.LOCAL),
        )
        val folders = listOf(oldFolder, sharedDrive, orphan, unkinded, chosen, chosenByOld) + others

        val outdated = SourceScanRules.outdatedGoogleDriveSources(folders, setOf(oldGoogle.key))
        assertEquals(listOf(oldFolder, sharedDrive, orphan, unkinded, chosenByOld), outdated)

        // Once the old account is gone, its folders still go by their URLs alone.
        assertEquals(listOf(oldFolder, sharedDrive, orphan, unkinded), SourceScanRules.outdatedGoogleDriveSources(folders, emptySet()))
        // After the migration, a launch removes nothing.
        assertTrue(SourceScanRules.outdatedGoogleDriveSources(listOf(chosen) + others, emptySet()).isEmpty())
    }
}
