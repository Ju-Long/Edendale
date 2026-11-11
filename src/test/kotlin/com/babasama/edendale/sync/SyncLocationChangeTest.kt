package com.babasama.edendale.sync

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R.3, FEATURES-27.1.md §5.1 and SYNC-PROTOCOL.md §4 "One location per
 * device": choosing another location keeps the old one's files and offers to
 * delete this device's file there; the app never offers to delete the file
 * it is about to write.
 */
class SyncLocationChangeTest {
    private val drive = SyncLocation(SyncProvider.GOOGLE_DRIVE, "Family", accountKey = "google:me")
    private val nas = SyncLocation(SyncProvider.WEBDAV, "Family", host = "nas.local", port = 5006, path = "davs://nas.local:5006/dav/")
    private val share = SyncLocation(SyncProvider.SMB, "Family", host = "mac-studio.local", path = "smb://mac-studio.local/Public/")

    @Test
    fun `the same storage, folder, and space are the same files`() {
        assertTrue(drive.sameFiles(drive.copy(googleFolderId = "1AbC")))
        assertTrue(nas.sameFiles(nas.copy(host = "NAS.local")))
        assertFalse(drive.sameFiles(drive.copy(space = "Kids")))
        assertFalse(drive.sameFiles(drive.copy(accountKey = "google:other")))
        assertFalse(nas.sameFiles(nas.copy(path = "davs://nas.local:5006/dav/Edendale-old/")))
        assertFalse(nas.sameFiles(nas.copy(port = 443)))
        assertFalse(nas.sameFiles(nas.copy(provider = SyncProvider.SFTP)))
    }

    @Test
    fun `a replaced location is remembered until a later choice uses it again`() {
        // First location: nothing to leave behind.
        assertNull(SyncLocation.previousAfter(current = null, next = drive, remembered = null))
        // Saving the same location again, or learning its Drive folder ID, leaves nothing behind.
        assertNull(SyncLocation.previousAfter(drive, drive.copy(googleFolderId = "1AbC"), remembered = null))
        // Another location replaces it: the old one is remembered.
        assertEquals(drive, SyncLocation.previousAfter(drive, nas, remembered = null))
        // The latest one replaced wins over an older unanswered one, as on Apple.
        assertEquals(nas, SyncLocation.previousAfter(nas, share, remembered = drive))
        // Going back to the remembered location: the one just left is offered instead, never the one in use.
        assertEquals(nas, SyncLocation.previousAfter(nas, drive, remembered = drive))
        // Turned off, then on again at the remembered location: nothing to offer.
        assertNull(SyncLocation.previousAfter(current = null, next = drive, remembered = drive))
        // Turned off, then on at a third location: the remembered one is still offered.
        assertEquals(drive, SyncLocation.previousAfter(current = null, next = share, remembered = drive))
        // Re-saving the current location keeps the remembered one.
        assertEquals(drive, SyncLocation.previousAfter(nas, nas, remembered = drive))
    }

    @Test
    fun `deleting this device's file in the old location leaves the new one and the other devices alone`() = runBlocking {
        val phone = SyncDeviceInfo("0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a", "Phone", "android", "27.1")
        val tv = SyncDeviceInfo("0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b", "TV", "androidtv", "27.1")
        val old = MemorySyncStorage()
        val new = MemorySyncStorage()
        val store = MemorySyncStore()
        store.write(1_762_000_000_000) { stamp -> UserMediaSyncRecord(SyncMediaType.MOVIE, 603, favourite = true, device = phone.id, updateTime = stamp) }
        old.put("Family", DeviceFile.fileName(tv.id), DeviceFileCodec.encode(DeviceFile("Family", tv, emptyList(), emptyList())))
        SyncEngine(old, store, phone, "Family") { 1_762_000_010_000 }.sync()
        store.dirty = true
        SyncEngine(new, store, phone, "Family") { 1_762_000_020_000 }.sync()
        assertNotNull(old.spaces.getValue("Family")[DeviceFile.fileName(phone.id)])

        SyncEngine(old, store, phone, "Family").removeOwnFile()

        assertNull(old.spaces.getValue("Family")[DeviceFile.fileName(phone.id)])
        assertNotNull(old.spaces.getValue("Family")[DeviceFile.fileName(tv.id)])
        assertNotNull(new.spaces.getValue("Family")[DeviceFile.fileName(phone.id)])
        // The local copy is untouched.
        assertTrue(store.current.userMedia.getValue("movie:603").favourite)
    }
}
