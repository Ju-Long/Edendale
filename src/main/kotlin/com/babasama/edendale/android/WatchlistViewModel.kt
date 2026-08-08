package com.babasama.edendale.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.babasama.edendale.android.data.LocalDataStore
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.UserMediaRecord
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The titles the user saved for later. Watchlist state lives in the local
 * `user_media` store and is mirrored to a connected TMDB account by the same
 * cloud-priority sync path as favourites and ratings (see [LocalDataStore] and
 * `UserMediaSyncEngine`): a signed-in launch pulls the account's list and adopts
 * it, while an unconfirmed local change is preserved until it is pushed. The tab
 * that shows this list appears only while the list is non-empty.
 */
class WatchlistViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = LocalDataStore((application as EdendaleApplication).database)

    /** Watchlisted titles, most recently added first. */
    val items: StateFlow<List<UserMediaRecord>> = dataStore.observeUserMedia()
        .map { records ->
            records.filter { it.watchlist }
                .sortedByDescending { it.watchlistUpdatedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(ref: MediaRef) {
        viewModelScope.launch { dataStore.setWatchlist(ref, false) }
    }
}
