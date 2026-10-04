package com.babasama.edendale.android

import android.app.Application
import androidx.room.Room
import com.babasama.edendale.android.data.EdendaleDatabase
import com.babasama.edendale.android.data.LibraryRepository
import com.babasama.edendale.android.oauth.CloudAccounts

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

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(
            this,
            EdendaleDatabase::class.java,
            "edendale.db"
        )
            .addMigrations(EdendaleDatabase.MIGRATION_1_2, EdendaleDatabase.MIGRATION_2_3)
            .build()
        libraryRepository = LibraryRepository(this, database)
        // Only a TV has the home screen's Watch Next row; it stays empty until the setting is on.
        if (isTelevisionDevice()) {
            watchNextPublisher = WatchNextPublisher(this, libraryRepository, watchNextSettings).also { it.start() }
        }
    }
}
