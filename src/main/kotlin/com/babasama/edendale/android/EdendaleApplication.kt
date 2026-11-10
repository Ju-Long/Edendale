package com.babasama.edendale.android

import android.app.Application
import androidx.room.Room
import com.babasama.edendale.android.data.EdendaleDatabase
import com.babasama.edendale.android.data.LibraryRepository
import com.babasama.edendale.android.data.LocalDataStore
import com.babasama.edendale.android.data.TlsPinStore
import com.babasama.edendale.android.oauth.CloudAccounts
import com.babasama.edendale.android.sync.SyncCoordinator
import com.babasama.edendale.remote.RemoteTls
import com.babasama.edendale.sync.SyncLog

class EdendaleApplication : Application() {
    lateinit var database: EdendaleDatabase
        private set

    lateinit var libraryRepository: LibraryRepository
        private set

    /** Linked storage-provider accounts (H.6). */
    val cloudAccounts: CloudAccounts by lazy { CloudAccounts(this) }

    /**
     * Held for the life of the process: while the setting is off, nothing else
     * reaches the publisher's coroutines, and they'd be collected before the
     * setting is turned on.
     */
    private var watchNextPublisher: WatchNextPublisher? = null

    /** Settings → Android TV's Continue Watching on the home screen (I.3). */
    internal val watchNextSettings: WatchNextSettings by lazy { WatchNextSettings(this) }

    /** Server certificates the viewer approved (D10); the HTTP clients consult it on every handshake. */
    val tlsPins: TlsPinStore by lazy { TlsPinStore(this) }

    /** 27.1 sync on this device: the location, the triggers, and the device's portable records. */
    val sync: SyncCoordinator by lazy { SyncCoordinator(this) }

    override fun onCreate() {
        super.onCreate()
        RemoteTls.pins = tlsPins
        database = Room.databaseBuilder(
            this,
            EdendaleDatabase::class.java,
            "edendale.db"
        )
            .addMigrations(
                EdendaleDatabase.MIGRATION_1_2,
                EdendaleDatabase.MIGRATION_2_3,
                EdendaleDatabase.MIGRATION_3_4,
                EdendaleDatabase.MIGRATION_4_5,
            )
            .build()
        libraryRepository = LibraryRepository(this, database)
        // Sync logs carry counts and error classes only (SYNC-PROTOCOL.md §8.5), and only in debug builds.
        if (BuildConfig.DEBUG) SyncLog.sink = SyncLog.Sink { tag, message -> android.util.Log.d(tag, message) }
        LocalDataStore.syncRecorder = sync.recorder
        sync.start()
        // Only a TV has the home screen's Watch Next row; it stays empty until the setting is on.
        if (isTelevisionDevice()) {
            watchNextPublisher = WatchNextPublisher(this, libraryRepository, watchNextSettings).also { it.start() }
        }
    }
}
