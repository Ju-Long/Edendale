package com.babasama.edendale.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SYNC-PROTOCOL.md Appendix A.1 (M1–M8), copied from `main` at commit
 * c620b21, plus the file-format rules of §5 and §6 the vectors lean on.
 * Every branch runs these natively.
 */
class SyncVectorsTest {

    private val a = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a"
    private val b = "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b"
    private val now = 1_793_577_600_000L // 2026-11-02T00:00:00Z

    private fun movie(position: Double, device: String, updateTime: Long, tmdbId: Int = 603) =
        ProgressRecord(SyncMediaType.MOVIE, tmdbId, position = position, duration = 8_160.0, completed = false, device = device, updateTime = updateTime)

    private fun ledgerWith(vararg progress: ProgressRecord, media: List<UserMediaSyncRecord> = emptyList()) = SyncLedger(
        progress = progress.associateBy { it.key },
        userMedia = media.associateBy { it.key },
        highWater = (progress.map { it.updateTime } + media.map { it.updateTime }).maxOrNull() ?: 0,
    )

    @Test
    fun `M1 newest wins`() {
        val local = ledgerWith(movie(100.0, a, 1_762_000_000_000))
        val incoming = movie(200.0, b, 1_762_000_001_000)
        val merged = local.merge(listOf(incoming), emptyList(), now)
        assertEquals(incoming, merged.ledger.progress["movie:603"])
        assertEquals(setOf("movie:603"), merged.changedProgress)
        assertEquals(1_762_000_001_000, merged.ledger.highWater)
    }

    @Test
    fun `M2 tie-break on the device ID, in either order`() {
        val fromB = UserMediaSyncRecord(SyncMediaType.TV, 1399, favourite = true, device = b, updateTime = 1_762_000_002_000)
        val fromA = UserMediaSyncRecord(SyncMediaType.TV, 1399, favourite = false, watchlist = true, device = a, updateTime = 1_762_000_002_000)
        val localB = ledgerWith(media = listOf(fromB)).merge(emptyList(), listOf(fromA), now)
        assertEquals(fromA, localB.ledger.userMedia["tv:1399"])
        assertTrue(localB.changed)
        val localA = ledgerWith(media = listOf(fromA)).merge(emptyList(), listOf(fromB), now)
        assertEquals(fromA, localA.ledger.userMedia["tv:1399"])
        assertFalse(localA.changed)
    }

    @Test
    fun `M3 a tombstone wins and is kept and written`() {
        val rated = UserMediaSyncRecord(SyncMediaType.TV, 1399, rating = 8.0, device = a, updateTime = 1_762_000_003_000)
        val tombstone = UserMediaSyncRecord(SyncMediaType.TV, 1399, device = b, updateTime = 1_762_000_004_000)
        assertTrue(tombstone.isTombstone)
        val merged = ledgerWith(media = listOf(rated)).merge(emptyList(), listOf(tombstone), now)
        assertEquals(tombstone, merged.ledger.userMedia["tv:1399"])
        val device = SyncDeviceInfo(a, "Phone", "android", "27.1")
        // The key is present, not removed, and the file written out carries the tombstone.
        val written = DeviceFileCodec.encode(merged.ledger.file("Family", device), now = 1_762_000_005_000)
        val back = assertIs<DeviceFileCodec.Decoded.Valid>(DeviceFileCodec.decode(written, "Family", DeviceFile.fileName(a)))
        assertEquals(listOf(tombstone), back.file.userMedia)
        // A year on, the store still holds it; only the written file may leave it out (§6.3).
        assertEquals(tombstone, merged.ledger.merge(emptyList(), emptyList(), now).ledger.userMedia["tv:1399"])
    }

    @Test
    fun `M4 the far-future guard`() {
        val local = ledgerWith(movie(100.0, a, 1_762_000_000_000))
        val farAhead = movie(300.0, b, 1_793_750_400_000) // 48 hours ahead
        val merged = local.merge(listOf(farAhead), emptyList(), now)
        assertEquals(100.0, merged.ledger.progress["movie:603"]?.position)
        assertEquals(local.highWater, merged.ledger.highWater)
        assertEquals(1, merged.farFutureSkipped)
        assertFalse(merged.changed)

        val exactly24h = movie(300.0, b, 1_793_664_000_000)
        val applied = local.merge(listOf(exactly24h), emptyList(), now)
        assertEquals(exactly24h, applied.ledger.progress["movie:603"])
        assertEquals(0, applied.farFutureSkipped)

        val oneMore = movie(300.0, b, 1_793_664_000_000 + 1)
        val refused = local.merge(listOf(oneMore), emptyList(), now)
        assertEquals(1, refused.farFutureSkipped)
        assertEquals(100.0, refused.ledger.progress["movie:603"]?.position)
    }

    @Test
    fun `M5 monotonic stamping`() {
        val behind = SyncLedger(highWater = 1_762_000_005_000)
        val (stamp, raised) = behind.stamp(now = 1_762_000_000_000)
        assertEquals(1_762_000_005_001, stamp)
        assertEquals(1_762_000_005_001, raised.highWater)
        val (ahead, _) = behind.stamp(now = 1_762_000_009_000)
        assertEquals(1_762_000_009_000, ahead)
    }

    @Test
    fun `M6 invalid stamps count as zero`() {
        val fileB = """{"format":1,"space":"Family","device":{"id":"$b","name":"TV","platform":"androidtv","app":"27.1"},"updateTime":0,
            "watchProgress":[{"type":"movie","tmdbId":603,"position":1,"duration":2,"completed":false,"device":"$b","updateTime":1500000000}],"userMedia":[]}"""
        val fileA = """{"format":1,"space":"Family","device":{"id":"$a","name":"Phone","platform":"android","app":"27.1"},"updateTime":0,
            "watchProgress":[{"type":"movie","tmdbId":604,"position":1,"duration":2,"completed":false,"device":"$a"}],"userMedia":[]}"""
        val decodedB = assertIs<DeviceFileCodec.Decoded.Valid>(DeviceFileCodec.decode(fileB.toByteArray(), "Family", DeviceFile.fileName(b)))
        val decodedA = assertIs<DeviceFileCodec.Decoded.Valid>(DeviceFileCodec.decode(fileA.toByteArray(), "Family", DeviceFile.fileName(a)))
        assertEquals(0L, decodedB.file.watchProgress.single().updateTime)
        assertEquals(0L, decodedA.file.watchProgress.single().updateTime)

        // They lose to any local record for the same key…
        val local = ledgerWith(movie(100.0, a, 1_762_000_000_000))
        val first = local.merge(decodedB.file, now)
        assertEquals(100.0, first.ledger.progress["movie:603"]?.position)
        assertEquals(local.highWater, first.ledger.highWater)
        // …are stored when the key is absent, and never raise highWater.
        val second = first.ledger.merge(decodedA.file, now)
        assertEquals(0L, second.ledger.progress["movie:604"]?.updateTime)
        assertEquals(local.highWater, second.ledger.highWater)
    }

    @Test
    fun `M7 skipped files leave the store alone`() {
        val device = SyncDeviceInfo(a, "Phone", "android", "27.1")
        val good = DeviceFileCodec.encode(DeviceFile("Family", device, listOf(movie(1.0, a, 1_762_000_000_000)), emptyList()))
        // Named B.json but device.id is A's.
        assertEquals(DeviceFileCodec.SkipReason.WRONG_DEVICE_ID, skipReason(DeviceFileCodec.decode(good, "Family", DeviceFile.fileName(b))))
        // format 2.
        val format2 = good.decodeToString().replace("\"format\":1", "\"format\":2").toByteArray()
        assertEquals(DeviceFileCodec.SkipReason.NEWER_FORMAT, skipReason(DeviceFileCodec.decode(format2, "Family", DeviceFile.fileName(a))))
        // 9 MiB long.
        assertEquals(DeviceFileCodec.SkipReason.TOO_LARGE, skipReason(DeviceFileCodec.decode(good, "Family", DeviceFile.fileName(a), byteCount = 9L shl 20)))
        assertEquals(DeviceFileCodec.SkipReason.TOO_LARGE, skipReason(DeviceFileCodec.decode(ByteArray(9 shl 20), "Family", DeviceFile.fileName(a))))
        // "space": "Other" inside the Family folder.
        val other = DeviceFileCodec.encode(DeviceFile("Other", device, emptyList(), emptyList()))
        assertEquals(DeviceFileCodec.SkipReason.WRONG_SPACE, skipReason(DeviceFileCodec.decode(other, "Family", DeviceFile.fileName(a))))
        // The store and highWater are unchanged by any of them: nothing was merged.
        val local = ledgerWith(movie(100.0, a, 1_762_000_000_000))
        assertEquals(local, local.merge(emptyList(), emptyList(), now).ledger)
    }

    private fun skipReason(decoded: DeviceFileCodec.Decoded): DeviceFileCodec.SkipReason = assertIs<DeviceFileCodec.Decoded.Skipped>(decoded).reason

    @Test
    fun `M8 order independence and idempotence`() {
        val fileA = DeviceFile(
            "Family", SyncDeviceInfo(a, "Phone", "android", "27.1"),
            listOf(movie(100.0, a, 1_762_000_000_000)),
            listOf(UserMediaSyncRecord(SyncMediaType.TV, 1399, rating = 8.0, device = a, updateTime = 1_762_000_003_000)),
        )
        val fileB = DeviceFile(
            "Family", SyncDeviceInfo(b, "TV", "androidtv", "27.1"),
            listOf(movie(200.0, b, 1_762_000_001_000)),
            listOf(UserMediaSyncRecord(SyncMediaType.TV, 1399, device = b, updateTime = 1_762_000_004_000)),
        )
        val ab = SyncLedger().merge(fileA, now).ledger.merge(fileB, now).ledger
        val ba = SyncLedger().merge(fileB, now).ledger.merge(fileA, now).ledger
        assertEquals(ab, ba)
        assertEquals(200.0, ab.progress["movie:603"]?.position)
        assertTrue(ab.userMedia["tv:1399"]!!.isTombstone)
        // The second read changes nothing.
        val again = ab.merge(fileA, now)
        assertFalse(again.changed)
        assertEquals(ab, again.ledger)
        val againB = again.ledger.merge(fileB, now)
        assertFalse(againB.changed)
        assertEquals(ab, againB.ledger)
    }

    // MARK: - File format (§1.2, §5, §6)

    @Test
    fun `a device file round-trips with only the defined fields`() {
        val device = SyncDeviceInfo(a, "Living Room TV", "tvos", "27.1")
        val file = DeviceFile(
            "Family", device,
            listOf(
                movie(1234.5, a, 1_762_041_600_000),
                ProgressRecord(SyncMediaType.TV, 1399, 1, 2, cleared = true, device = b, updateTime = 1_762_041_600_001),
            ),
            listOf(UserMediaSyncRecord(SyncMediaType.TV, 1399, favourite = true, rating = 8.5, device = b, updateTime = 1_762_038_000_000)),
        )
        val bytes = DeviceFileCodec.encode(file)
        val text = bytes.decodeToString()
        assertTrue(text.startsWith("{\"format\":1"), text)
        assertTrue("\"updateTime\":1762041600001" in text, text)
        assertFalse("\"position\":null" in text)
        assertFalse(text.contains("﻿"))
        val back = assertIs<DeviceFileCodec.Decoded.Valid>(DeviceFileCodec.decode(bytes, "Family", DeviceFile.fileName(a)))
        assertEquals(file, back.file)
        assertEquals("tv:1399:1:2", file.watchProgress[1].key)
        assertEquals("movie:603", file.watchProgress[0].key)
    }

    @Test
    fun `unknown fields are ignored and null equals absent`() {
        val text = """{"format":1,"space":"Family","device":{"id":"$a","name":"P","platform":"android","app":"x","extra":1},"updateTime":null,"future":true,
            "watchProgress":[{"type":"tv","tmdbId":1399,"season":0,"episode":3,"position":0,"duration":1.5,"completed":true,"cleared":null,"device":"$a","updateTime":1762000000000,"note":"ignored"}],
            "userMedia":[{"type":"movie","tmdbId":603,"favourite":false,"watchlist":true,"rating":null,"review":null,"device":"$a","updateTime":1762000000000}]}"""
        val decoded = assertIs<DeviceFileCodec.Decoded.Valid>(DeviceFileCodec.decode(text.toByteArray(), "Family", DeviceFile.fileName(a)))
        assertEquals("tv:1399:0:3", decoded.file.watchProgress.single().key)
        assertNull(decoded.file.userMedia.single().rating)
        assertTrue(decoded.file.device.platform in SyncDeviceInfo.PLATFORMS)
        // An unknown platform still reads; the UI shows "another device" for it.
        val unknown = assertIs<DeviceFileCodec.Decoded.Valid>(DeviceFileCodec.decode(text.replace("\"platform\":\"android\"", "\"platform\":\"fridge\"").toByteArray(), "Family", DeviceFile.fileName(a)))
        assertFalse(unknown.file.device.platform in SyncDeviceInfo.PLATFORMS)
    }

    @Test
    fun `records that break a rule skip the whole file`() {
        fun file(progress: String = "", media: String = "") =
            """{"format":1,"space":"Family","device":{"id":"$a","name":"P","platform":"android","app":"x"},"updateTime":0,"watchProgress":[$progress],"userMedia":[$media]}""".toByteArray()
        val cases = listOf(
            // A fractional tmdbId; a season on a movie; an episode without a season; a negative position; a zero duration.
            file(progress = """{"type":"movie","tmdbId":603.5,"position":1,"duration":2,"completed":false,"device":"$a","updateTime":1762000000000}"""),
            file(progress = """{"type":"movie","tmdbId":603,"season":1,"position":1,"duration":2,"completed":false,"device":"$a","updateTime":1762000000000}"""),
            file(progress = """{"type":"tv","tmdbId":603,"episode":1,"position":1,"duration":2,"completed":false,"device":"$a","updateTime":1762000000000}"""),
            file(progress = """{"type":"movie","tmdbId":603,"position":-1,"duration":2,"completed":false,"device":"$a","updateTime":1762000000000}"""),
            file(progress = """{"type":"movie","tmdbId":603,"position":1,"duration":0,"completed":false,"device":"$a","updateTime":1762000000000}"""),
            // A tombstone with a position; 0 and 1 as booleans; a missing device.
            file(progress = """{"type":"movie","tmdbId":603,"cleared":true,"position":1,"device":"$a","updateTime":1762000000000}"""),
            file(progress = """{"type":"movie","tmdbId":603,"position":1,"duration":2,"completed":1,"device":"$a","updateTime":1762000000000}"""),
            file(progress = """{"type":"movie","tmdbId":603,"position":1,"duration":2,"completed":false,"updateTime":1762000000000}"""),
            // A rating off the half-step grid, a rating of 0, a review too long, a duplicate key.
            file(media = """{"type":"movie","tmdbId":603,"favourite":false,"watchlist":false,"rating":7.3,"review":null,"device":"$a","updateTime":1}"""),
            file(media = """{"type":"movie","tmdbId":603,"favourite":false,"watchlist":false,"rating":0,"review":null,"device":"$a","updateTime":1}"""),
            file(media = """{"type":"movie","tmdbId":603,"favourite":false,"watchlist":false,"rating":null,"review":"${"x".repeat(10_001)}","device":"$a","updateTime":1}"""),
            file(media = """{"type":"movie","tmdbId":603,"favourite":true,"watchlist":false,"rating":null,"review":null,"device":"$a","updateTime":1},{"type":"movie","tmdbId":603,"favourite":false,"watchlist":false,"rating":null,"review":null,"device":"$a","updateTime":2}"""),
        )
        cases.forEachIndexed { index, bytes ->
            assertIs<DeviceFileCodec.Decoded.Skipped>(DeviceFileCodec.decode(bytes, "Family", DeviceFile.fileName(a)), "case $index")
        }
        // Valid ratings are 0.5 … 10.0 in half steps.
        assertTrue(DeviceFileCodec.isValidRating(0.5))
        assertTrue(DeviceFileCodec.isValidRating(10.0))
        assertFalse(DeviceFileCodec.isValidRating(10.5))
        assertFalse(DeviceFileCodec.isValidRating(7.25))
        // Not JSON, and a lower format.
        assertEquals(DeviceFileCodec.SkipReason.NOT_JSON, skipReason(DeviceFileCodec.decode("nope".toByteArray(), "Family", DeviceFile.fileName(a))))
        assertEquals(DeviceFileCodec.SkipReason.OLDER_FORMAT, skipReason(DeviceFileCodec.decode(file().decodeToString().replace("\"format\":1", "\"format\":0").toByteArray(), "Family", DeviceFile.fileName(a))))
        // 100,001 records (short device strings keep the file under the byte limit, so the record limit is what trips).
        val many = (1..100_001).joinToString(",") { """{"type":"tv","tmdbId":$it,"favourite":true,"watchlist":true,"device":"d"}""" }
        assertEquals(DeviceFileCodec.SkipReason.TOO_MANY_RECORDS, skipReason(DeviceFileCodec.decode(file(media = many), "Family", DeviceFile.fileName(a))))
    }

    @Test
    fun `old tombstones may be left out of the written file`() {
        val device = SyncDeviceInfo(a, "P", "android", "27.1")
        val year = DeviceFileCodec.TOMBSTONE_RETENTION_MILLIS
        val old = UserMediaSyncRecord(SyncMediaType.MOVIE, 1, device = a, updateTime = now - year - 1)
        val recent = UserMediaSyncRecord(SyncMediaType.MOVIE, 2, device = a, updateTime = now - year + 1)
        val value = UserMediaSyncRecord(SyncMediaType.MOVIE, 3, favourite = true, device = a, updateTime = now - 2 * year)
        val bytes = DeviceFileCodec.encode(DeviceFile("S", device, emptyList(), listOf(old, recent, value)), now)
        val back = assertIs<DeviceFileCodec.Decoded.Valid>(DeviceFileCodec.decode(bytes, "S", DeviceFile.fileName(a)))
        assertEquals(listOf(recent, value), back.file.userMedia)
    }

    @Test
    fun `space names and device IDs follow section 3`() {
        assertEquals("Family", SpaceName.normalize("  Family "))
        assertNull(SpaceName.normalize(""))
        assertNull(SpaceName.normalize("."))
        assertNull(SpaceName.normalize(".."))
        assertNull(SpaceName.normalize("a/b"))
        assertNull(SpaceName.normalize("a:b"))
        assertNull(SpaceName.normalize("a\tb"))
        assertNull(SpaceName.normalize("x".repeat(65)))
        assertEquals("x".repeat(64), SpaceName.normalize("x".repeat(64)))
        assertEquals("日本の家族", SpaceName.normalize("日本の家族"))
        assertTrue(SyncDeviceInfo.isDeviceId(a))
        assertFalse(SyncDeviceInfo.isDeviceId(a.uppercase()))
        assertFalse(SyncDeviceInfo.isDeviceId("not-a-uuid"))
        assertEquals(a, DeviceFile.deviceId("$a.json"))
        assertNull(DeviceFile.deviceId("$a.json.tmp"))
        assertNull(DeviceFile.deviceId("notes.json"))
    }

    @Test
    fun `the backoff schedule is 1, 2, 4, 8 minutes capped at an hour`() {
        assertEquals(0L, SyncBackoff.delayMillis(0))
        assertEquals(60_000L, SyncBackoff.delayMillis(1))
        assertEquals(120_000L, SyncBackoff.delayMillis(2))
        assertEquals(240_000L, SyncBackoff.delayMillis(3))
        assertEquals(480_000L, SyncBackoff.delayMillis(4))
        assertEquals(3_600_000L, SyncBackoff.delayMillis(7))
        assertEquals(3_600_000L, SyncBackoff.delayMillis(40))
    }
}
