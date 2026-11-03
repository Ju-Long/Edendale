package com.babasama.edendale.android.sync

import com.babasama.edendale.android.EdendaleApplication
import com.babasama.edendale.android.data.ServerLoginStore
import com.babasama.edendale.android.data.SmbCredentialsStore
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.Sftp
import com.babasama.edendale.connectors.SourceUrl
import com.babasama.edendale.handoff.AccountHandoff
import com.babasama.edendale.handoff.HandoffV2
import com.babasama.edendale.sync.SpaceName
import com.babasama.edendale.sync.SyncLocation
import com.babasama.edendale.sync.SyncLog
import com.babasama.edendale.sync.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The sync location as a handoff payload and back (SYNC-PROTOCOL.md §9.6,
 * FEATURES-27.1.md §6.1): the sender attaches the account or login behind
 * its location, the receiver validates and stores what arrives and turns
 * sync on. A folder location travels as provider and space only; the
 * receiver picks its own folder. Logins move only inside the handoff.
 */
class SyncHandoff(private val app: EdendaleApplication) {
    private val serverLogins by lazy { ServerLoginStore(app) }
    private val smbCredentials by lazy { SmbCredentialsStore(app) }

    /** This device's location with its login attached, for the offer; null when sync is off. */
    suspend fun locationPayload(): HandoffV2.LocationPayload? = withContext(Dispatchers.IO) {
        val location = app.sync.settings.location ?: return@withContext null
        val kind = location.provider.kind
        when (location.provider) {
            SyncProvider.FOLDER -> HandoffV2.LocationPayload(SyncProvider.FOLDER, location.space)
            SyncProvider.GOOGLE_DRIVE, SyncProvider.ONE_DRIVE, SyncProvider.DROPBOX -> {
                val account = app.cloudAccounts.vault.account(kind!!, location.accountKey.orEmpty())
                HandoffV2.LocationPayload(
                    location.provider, location.space,
                    account = account?.let { AccountHandoff.Account(it) },
                    googleAccountHint = account?.email,
                    googleFolderId = location.googleFolderId,
                )
            }
            SyncProvider.SMB -> {
                val host = location.host.orEmpty()
                val login = smbCredentials.getCredentials(host)?.let { (user, password) -> AccountHandoff.Login(MediaSourceKind.SMB, host, null, user, password) }
                    ?: AccountHandoff.Login(MediaSourceKind.SMB, host, null, "", "")
                HandoffV2.LocationPayload(SyncProvider.SMB, location.space, login = login, path = location.path)
            }
            SyncProvider.WEBDAV, SyncProvider.SFTP -> {
                val host = location.host.orEmpty()
                val saved = serverLogins.get(kind!!, host, location.port) ?: return@withContext null
                val port = location.port ?: if (kind == MediaSourceKind.SFTP) Sftp.DEFAULT_PORT else null
                val login = AccountHandoff.Login(
                    kind, host, port, saved.user, saved.password,
                    hostKeyFingerprint = if (kind == MediaSourceKind.SFTP) app.libraryRepository.sshHostKeys.pinned(host, port ?: Sftp.DEFAULT_PORT) else null,
                    certificateFingerprint = if (kind == MediaSourceKind.WEBDAV) app.tlsPins.pinned(host, location.port ?: 443) else null,
                )
                // On the wire the folder is a credential-free URL (Apple's spelling): the SFTP path becomes `sftp://host[:port]/path/`.
                val url = if (kind == MediaSourceKind.SFTP) Sftp.url(host, port ?: Sftp.DEFAULT_PORT, location.path ?: "/", isDirectory = true) else location.path
                HandoffV2.LocationPayload(location.provider, location.space, login = login, path = url)
            }
        }
    }

    /** The other linked accounts on this device, for the sender's tick list. */
    suspend fun otherAccounts(): List<AccountHandoff.Account> = withContext(Dispatchers.IO) {
        runCatching { app.cloudAccounts.vault.all() }.getOrDefault(emptyList()).map { AccountHandoff.Account(it) }
    }

    /** The saved server logins on this device, for the sender's tick list. */
    suspend fun otherLogins(): List<AccountHandoff.Login> = withContext(Dispatchers.IO) {
        listOf(MediaSourceKind.SMB, MediaSourceKind.SFTP, MediaSourceKind.WEBDAV, MediaSourceKind.S3).flatMap { kind ->
            runCatching { app.libraryRepository.handedOffLogins(kind) }.getOrDefault(emptyList())
        }
    }

    /** What the receiver still has to do by hand after an offer was stored. */
    sealed interface FollowUp {
        /** A folder location: pick a folder here and use [space]. */
        data class PickFolder(val space: String) : FollowUp

        /** A Google Drive location from another ecosystem: sign in to Google as [hint], then use the folder. */
        data class GoogleSignIn(val space: String, val hint: String?, val folderId: String?) : FollowUp
    }

    data class Received(val result: HandoffV2.ResultMessage, val followUp: FollowUp?)

    /**
     * §9.6 step 4: validates each item (a token refresh, or a connection
     * with the saved credential), stores what validates, and turns sync on
     * at the location when its login made it. A Google location from
     * another ecosystem, or a folder location, is left for the viewer to
     * finish ([FollowUp]).
     */
    suspend fun receive(offer: HandoffV2.Offer): Received = withContext(Dispatchers.IO) {
        val stored = mutableListOf<String>()
        val failed = mutableListOf<HandoffV2.ResultMessage.Failure>()
        var followUp: FollowUp? = null

        suspend fun storeAccount(account: AccountHandoff.Account): Boolean =
            runCatching { app.cloudAccounts.adoptHandedOffAccount(account.cloudAccount) }.fold(
                { stored += "account:${account.kind.raw}:${account.subject}"; true },
                { failed += HandoffV2.ResultMessage.Failure("account:${account.kind.raw}:${account.subject}", SyncLog.describe(it)); false },
            )

        suspend fun storeLogin(login: AccountHandoff.Login): Boolean {
            val result = app.libraryRepository.adoptHandedOffLogin(login)
            return if (result.stored) {
                stored += "login:${login.kind.raw}:${login.host}"
                true
            } else {
                failed += HandoffV2.ResultMessage.Failure("login:${login.kind.raw}:${login.host}", result.message)
                false
            }
        }

        offer.accounts.forEach { storeAccount(it) }
        offer.logins.forEach { storeLogin(it) }

        offer.location?.let { payload ->
            val space = SpaceName.normalize(payload.space)
            if (space == null) {
                failed += HandoffV2.ResultMessage.Failure("location", "space")
                return@let
            }
            when (payload.provider) {
                SyncProvider.FOLDER -> {
                    followUp = FollowUp.PickFolder(space)
                    stored += "location"
                }
                SyncProvider.GOOGLE_DRIVE, SyncProvider.ONE_DRIVE, SyncProvider.DROPBOX -> {
                    val account = payload.account
                    if (account != null && account.kind == payload.provider.kind && storeAccount(account)) {
                        app.sync.enable(SyncLocation(payload.provider, space, accountKey = account.cloudAccount.key, googleFolderId = payload.googleFolderId))
                        stored += "location"
                    } else if (payload.provider == SyncProvider.GOOGLE_DRIVE && account == null) {
                        followUp = FollowUp.GoogleSignIn(space, payload.googleAccountHint, payload.googleFolderId)
                        stored += "location"
                    } else {
                        failed += HandoffV2.ResultMessage.Failure("location", "account")
                    }
                }
                SyncProvider.SMB, SyncProvider.WEBDAV, SyncProvider.SFTP -> {
                    val login = payload.login
                    val path = payload.path
                    if (login == null || login.kind != payload.provider.kind || path.isNullOrBlank()) {
                        failed += HandoffV2.ResultMessage.Failure("location", "login")
                        return@let
                    }
                    val adopted = if (login.user.isEmpty() && login.password.isEmpty() && login.kind == MediaSourceKind.SMB) true else storeLogin(login)
                    if (adopted) {
                        val port = login.port ?: if (login.kind == MediaSourceKind.SFTP) Sftp.DEFAULT_PORT else SourceUrl.port(path)
                        // An SFTP folder arrives as `sftp://host[:port]/path/`; the storage wants the absolute path.
                        val folder = if (login.kind == MediaSourceKind.SFTP && path.startsWith("sftp://", ignoreCase = true)) Sftp.path(path) else path
                        app.sync.enable(SyncLocation(payload.provider, space, host = login.host, port = port, path = folder))
                        stored += "location"
                    } else {
                        failed += HandoffV2.ResultMessage.Failure("location", "login")
                    }
                }
            }
        }
        Received(HandoffV2.ResultMessage(stored, failed), followUp)
    }
}
