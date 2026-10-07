package com.babasama.edendale.android.data

import android.content.Context
import com.babasama.edendale.remote.TlsPins

/**
 * Server certificates approved on first use (H.3.4, D10), one fingerprint
 * per host and port, for WebDAV and S3 servers whose certificate the device
 * doesn't trust on its own. Not secret, but device-local and excluded from
 * backup and device transfer with the logins: a restored device approves
 * each server again.
 */
class TlsPinStore(context: Context) : TlsPins {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun pinned(host: String, port: Int): String? = preferences.getString(key(host, port), null)

    fun pin(host: String, port: Int, fingerprint: String) {
        preferences.edit().putString(key(host, port), fingerprint).commit()
    }

    fun remove(host: String, port: Int) {
        preferences.edit().remove(key(host, port)).commit()
    }

    companion object {
        /** Listed in backup_rules.xml and data_extraction_rules.xml. */
        const val FILE_NAME = "edendale_tls_pins"

        private fun key(host: String, port: Int) = "${host.lowercase()}|$port"
    }
}
