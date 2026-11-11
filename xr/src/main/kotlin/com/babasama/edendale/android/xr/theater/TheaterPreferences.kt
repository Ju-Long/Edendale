package com.babasama.edendale.android.xr.theater

import android.content.Context
import android.content.SharedPreferences
import com.babasama.edendale.android.xr.SeatID
import com.babasama.edendale.android.xr.TheaterLights
import com.babasama.edendale.android.xr.TheaterPreferencesRules
import com.babasama.edendale.android.xr.TheaterRoom

/**
 * The theater's own memory: the last room, the house lights, and the chosen
 * seat per room. Device-local and never synced (ENHANCEMENT.md N-D10); the
 * keys and their forms are the base's `TheaterPreferencesRules`.
 */
internal class TheaterPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(TheaterPreferencesRules.PREFS_NAME, Context.MODE_PRIVATE)

    var room: TheaterRoom
        get() = TheaterRoom.fromId(prefs.getString(TheaterPreferencesRules.KEY_ROOM, null))
        set(value) = prefs.edit().putString(TheaterPreferencesRules.KEY_ROOM, value.id).apply()

    var lights: TheaterLights
        get() = TheaterLights.fromRaw(prefs.getString(TheaterPreferencesRules.KEY_LIGHTS, null))
        set(value) = prefs.edit().putString(TheaterPreferencesRules.KEY_LIGHTS, value.raw).apply()

    /** The viewer's choice in [room]; null means the room's best seat. */
    fun seat(room: TheaterRoom): SeatID? =
        prefs.getString(TheaterPreferencesRules.seatKey(room), null)?.let(SeatID::fromStorageValue)

    /** Choosing the best seat forgets the choice, as the visionOS theater does. */
    fun setSeat(room: TheaterRoom, seat: SeatID?) {
        val key = TheaterPreferencesRules.seatKey(room)
        if (seat == null) prefs.edit().remove(key).apply() else prefs.edit().putString(key, seat.storageValue).apply()
    }
}
