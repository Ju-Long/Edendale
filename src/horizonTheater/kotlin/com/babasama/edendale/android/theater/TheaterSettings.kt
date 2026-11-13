package com.babasama.edendale.android.theater

import android.content.SharedPreferences
import com.babasama.edendale.android.xr.SeatID
import com.babasama.edendale.android.xr.TheaterLights
import com.babasama.edendale.android.xr.TheaterPreferencesRules
import com.babasama.edendale.android.xr.TheaterRoom as SharedRoom

/**
 * The theater's remembered choices (HORIZON.md G.14, G.15): the last room,
 * the house lights, and the seat chosen in each room, in android's
 * `theater` preferences file under android's keys (N-D10). Device-only and
 * never synced: they are choices about this headset's rooms, not about what
 * was watched. An absent or unreadable seat means the room's best seat.
 */
internal class TheaterSettings(private val preferences: SharedPreferences) {

    var room: TheaterRoom
        get() = TheaterRoom.fromId(preferences.getString(TheaterPreferencesRules.KEY_ROOM, null))
        set(value) = preferences.edit().putString(TheaterPreferencesRules.KEY_ROOM, value.id).apply()

    var lights: TheaterLights
        get() = TheaterLights.fromRaw(preferences.getString(TheaterPreferencesRules.KEY_LIGHTS, null))
        set(value) = preferences.edit().putString(TheaterPreferencesRules.KEY_LIGHTS, value.raw).apply()

    fun seat(room: TheaterRoom): SeatID? = preferences.getString(seatKey(room), null)?.let(SeatID::fromStorageValue)

    /** Remembers [seat] in [room]; null (the best seat) removes the choice. */
    fun setSeat(room: TheaterRoom, seat: SeatID?) {
        val editor = preferences.edit()
        if (seat == null) editor.remove(seatKey(room)) else editor.putString(seatKey(room), seat.storageValue)
        editor.apply()
    }

    /** android's key for the room with the same id (the Quest's [TheaterRoom] and android's share their three ids). */
    private fun seatKey(room: TheaterRoom): String = TheaterPreferencesRules.seatKey(SharedRoom.fromId(room.id))
}
