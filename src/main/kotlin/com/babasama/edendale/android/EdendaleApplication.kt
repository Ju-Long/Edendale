package com.babasama.edendale.android

import android.app.Application
import androidx.room.Room
import com.babasama.edendale.android.data.EdendaleDatabase
import com.babasama.edendale.android.data.LibraryRepository

class EdendaleApplication : Application() {
    lateinit var database: EdendaleDatabase
        private set

    lateinit var libraryRepository: LibraryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(
            this,
            EdendaleDatabase::class.java,
            "edendale.db"
        )
            .addMigrations(EdendaleDatabase.MIGRATION_1_2)
            .build()
        libraryRepository = LibraryRepository(this, database)
    }
}
