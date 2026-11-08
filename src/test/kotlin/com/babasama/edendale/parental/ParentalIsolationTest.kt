package com.babasama.edendale.parental

import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.handoff.AccountHandoff
import com.babasama.edendale.handoff.HandoffV2
import com.babasama.edendale.sync.DeviceFile
import com.babasama.edendale.sync.DeviceFileCodec
import com.babasama.edendale.sync.ProgressRecord
import com.babasama.edendale.sync.SyncDeviceInfo
import com.babasama.edendale.sync.SyncMediaType
import com.babasama.edendale.sync.SyncProvider
import com.babasama.edendale.sync.UserMediaSyncRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * FEATURES-27.1.md §2 item 4, §5.1, and §6.1: parental settings never go into
 * the sync files or the handoff. The device file and the offer are built the
 * way the app builds them and their field names are checked, at every level.
 */
class ParentalIsolationTest {
    private val device = SyncDeviceInfo("0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a", "Phone", "android", "27.1")
    private val account = AccountHandoff.Account(MediaSourceKind.GOOGLE_DRIVE, "subject", "me@example.com", "Me", "refresh", listOf("https://www.googleapis.com/auth/drive.file"), null)
    private val login = AccountHandoff.Login(MediaSourceKind.WEBDAV, "nas.local", 443, "me", "secret", certificateFingerprint = "ab:cd")

    @Test
    fun theDeviceFileCarriesNoParentalField() {
        val stamp = 1_762_000_000_000L
        val file = DeviceFile(
            "Family",
            device,
            listOf(
                ProgressRecord(SyncMediaType.MOVIE, 603, position = 120.0, duration = 8_160.0, completed = false, device = device.id, updateTime = stamp),
                ProgressRecord(SyncMediaType.TV, 1396, season = 1, episode = 2, position = 10.0, duration = 2_700.0, completed = true, device = device.id, updateTime = stamp),
            ),
            listOf(UserMediaSyncRecord(SyncMediaType.TV, 1396, favourite = true, rating = 9.0, review = "Great", device = device.id, updateTime = stamp)),
        )
        val json = parse(DeviceFileCodec.encode(file))
        assertEquals(setOf("format", "space", "device", "updateTime", "watchProgress", "userMedia"), json.keys)
        assertEquals(setOf("id", "name", "platform", "app"), json["device"]!!.jsonObject.keys)
        assertNoParentalKey(json)
    }

    @Test
    fun theHandoffOfferCarriesNoParentalField() {
        val location = HandoffV2.LocationPayload(SyncProvider.GOOGLE_DRIVE, "Family", account = account)
        val offer = HandoffV2.offerFor(location, listOf(account), listOf(login), senderPlatform = "android", receiverPlatform = "androidtv")
        val json = parse(HandoffV2.encodeOffer(offer))
        assertEquals(setOf("type", "status", "location", "accounts", "logins"), json.keys)
        assertNoParentalKey(json)

        val toPhone = parse(HandoffV2.encodeOffer(HandoffV2.offerFor(location, listOf(account), listOf(login), "android", "ios")))
        assertEquals(setOf("type", "status", "location", "accounts", "logins"), toPhone.keys)
        assertNoParentalKey(toPhone)
        assertNoParentalKey(parse(HandoffV2.encodeOffer(HandoffV2.Offer.DECLINED)))
    }

    private fun parse(bytes: ByteArray): JsonObject = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject

    private fun assertNoParentalKey(element: JsonElement, path: String = "") {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                assertFalse(key in FORBIDDEN_KEYS, "'$path/$key' is a parental field")
                assertFalse("parental" in key.lowercase(), "'$path/$key' names the parental controls")
                assertNoParentalKey(value, "$path/$key")
            }
            is JsonArray -> element.forEachIndexed { index, value -> assertNoParentalKey(value, "$path[$index]") }
            else -> Unit
        }
    }

    private companion object {
        val FORBIDDEN_KEYS = setOf(
            "parental", "pin", "pinHash", "salt", "ceiling", "policy", "youngAudienceFriendly", "young_audience_friendly",
            "hideRestrictedTitles", "blockTrailersAndLinks", "lockSettings", "lockLibraryEdits", "certifications", "lockedUntil",
        )
    }
}
