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
    }
}
