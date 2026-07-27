package com.babasama.edendale.android

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.babasama.edendale.AndroidEdendaleCore
import com.babasama.edendale.android.data.LocalDataStore
import com.babasama.edendale.android.data.TmdbSessionStore
import com.babasama.edendale.tmdb.TmdbSession
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class TmdbAccountPhase {
    LOADING,
    SIGNED_OUT,
    STARTING,
    AWAITING_APPROVAL,
    CONNECTING,
    CONNECTED,
}

data class TmdbAccountUiState(
    val phase: TmdbAccountPhase = TmdbAccountPhase.LOADING,
    val canConnect: Boolean = false,
    val approvalUrl: String? = null,
    val requestToken: String? = null,
    val accountLabel: String? = null,
    val lastSyncStatus: String? = null,
    val errorMessage: String? = null,
)

class TmdbAccountViewModel(application: Application) : AndroidViewModel(application) {
    private val accountApi = AndroidEdendaleCore.accountApi()
    private val strings = AppStrings(application)
    private val dataStore = LocalDataStore((application as EdendaleApplication).database)
    private val sessionStore = TmdbSessionStore(application)
    private var session: TmdbSession? = null
    private var syncInProgress = false

    var state by mutableStateOf(TmdbAccountUiState(canConnect = accountApi.isConfigured))
        private set

    init {
        viewModelScope.launch {
            val stored = try {
                sessionStore.load()
            } catch (error: Exception) {
                state = state.copy(
                    phase = TmdbAccountPhase.SIGNED_OUT,
                    errorMessage = strings.savedSignInUnreadable,
                )
                return@launch
            }
            if (stored == null) {
                state = state.copy(phase = TmdbAccountPhase.SIGNED_OUT)
            } else {
                session = stored.session
                state = state.copy(
                    phase = TmdbAccountPhase.CONNECTED,
                    accountLabel = stored.accountLabel,
                )
                sync(stored.session)
            }
        }
        viewModelScope.launch {
            dataStore.edits.collectLatest {
                delay(5_000)
                session?.let { current -> sync(current) }
            }
        }
    }

    fun beginConnect(openApprovalPage: (String) -> Unit) {
        if (!state.canConnect || state.phase == TmdbAccountPhase.STARTING) return
        viewModelScope.launch {
            state = state.copy(phase = TmdbAccountPhase.STARTING, errorMessage = null)
            try {
                val request = accountApi.createRequestToken()
                state = state.copy(
                    phase = TmdbAccountPhase.AWAITING_APPROVAL,
                    requestToken = request.requestToken,
                    approvalUrl = request.approvalUrl,
                )
                openApprovalPage(request.approvalUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state = state.copy(
                    phase = TmdbAccountPhase.SIGNED_OUT,
                    errorMessage = error.message ?: strings.connectionStartFailed,
                )
            }
        }
    }

    fun reportBrowserLaunchFailure() {
        state = state.copy(
            errorMessage = strings.noBrowser,
        )
    }

    fun completeConnect() {
        val requestToken = state.requestToken ?: return
        viewModelScope.launch {
            state = state.copy(phase = TmdbAccountPhase.CONNECTING, errorMessage = null)
            try {
                val sessionId = accountApi.createSession(requestToken)
                val account = accountApi.accountDetails(sessionId)
                val connected = TmdbSession(sessionId = sessionId, accountId = account.id)
                val label = account.username?.takeIf { it.isNotBlank() } ?: account.name
                sessionStore.save(connected, label)
                session = connected
                state = state.copy(
                    phase = TmdbAccountPhase.CONNECTED,
                    requestToken = null,
                    approvalUrl = null,
                    accountLabel = label,
                )
                sync(connected)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state = state.copy(
                    phase = TmdbAccountPhase.AWAITING_APPROVAL,
                    errorMessage = strings.approvalRejected,
                )
            }
        }
    }

    fun cancelConnect() {
        state = state.copy(
            phase = TmdbAccountPhase.SIGNED_OUT,
            requestToken = null,
            approvalUrl = null,
            errorMessage = null,
        )
    }

    fun syncNow() {
        session?.let { current -> viewModelScope.launch { sync(current) } }
    }

    fun signOut() {
        val current = session
        session = null
        state = TmdbAccountUiState(
            phase = TmdbAccountPhase.SIGNED_OUT,
            canConnect = accountApi.isConfigured,
        )
        viewModelScope.launch {
            sessionStore.clear()
            if (current != null) runCatching { accountApi.deleteSession(current.sessionId) }
        }
    }

    private suspend fun sync(current: TmdbSession) {
        if (syncInProgress || !state.canConnect) return
        syncInProgress = true
        try {
            val result = dataStore.syncWithTmdb(current)
            val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            state = state.copy(
                lastSyncStatus = strings.syncStatus(time, result.pushed, result.pulled),
                errorMessage = null,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state = state.copy(errorMessage = error.message ?: strings.syncFailed)
        } finally {
            syncInProgress = false
        }
    }
}
