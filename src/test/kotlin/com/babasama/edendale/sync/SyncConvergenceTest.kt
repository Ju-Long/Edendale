package com.babasama.edendale.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SYNC-PROTOCOL.md §7.4: any set of devices that have read each other's
 * latest files hold the same records, whatever order they read them in, and
 * a second read changes nothing. Random record sets, random clocks (some far
 * behind), random read orders, seeded so a failure reproduces.
 */
class SyncConvergenceTest {

    private fun deviceId(index: Int) = "%08x-0000-4000-8000-%012x".format(index, index)

    private class Device(val info: SyncDeviceInfo, var ledger: SyncLedger, var clock: Long) {
        fun writeProgress(random: Random) {
            val (stamp, raised) = ledger.stamp(clock)
            val tmdbId = 1 + random.nextInt(6)
            val tv = random.nextBoolean()
            val record = if (random.nextInt(5) == 0) {
                ProgressRecord.tombstone(if (tv) SyncMediaType.TV else SyncMediaType.MOVIE, tmdbId, if (tv) 1 else null, if (tv) random.nextInt(3) else null, info.id, stamp)
            } else {
                ProgressRecord(
                    if (tv) SyncMediaType.TV else SyncMediaType.MOVIE, tmdbId, if (tv) 1 else null, if (tv) random.nextInt(3) else null,
                    position = random.nextDouble(0.0, 5_000.0), duration = 5_000.0, completed = random.nextBoolean(), device = info.id, updateTime = stamp,
                )
            }
            ledger = raised.writing(record)
            clock += 1 + random.nextInt(1_000)
        }

        fun writeMedia(random: Random) {
            val (stamp, raised) = ledger.stamp(clock)
            val record = UserMediaSyncRecord(
                if (random.nextBoolean()) SyncMediaType.TV else SyncMediaType.MOVIE, 1 + random.nextInt(6),
                favourite = random.nextBoolean(), watchlist = random.nextBoolean(),
                rating = if (random.nextBoolean()) null else (1 + random.nextInt(20)) / 2.0,
                device = info.id, updateTime = stamp,
            )
            ledger = raised.writing(record)
            clock += 1 + random.nextInt(1_000)
        }

        fun file() = ledger.file("Family", info)
    }

    @Test
    fun `devices converge regardless of read order, and a second read changes nothing`() {
        repeat(60) { seed ->
            val random = Random(seed)
            val deviceCount = 2 + random.nextInt(5)
            val base = 1_762_000_000_000L
            val devices = List(deviceCount) { index ->
                // Some clocks run minutes or hours behind; none more than a day ahead.
                val skew = if (random.nextInt(3) == 0) -random.nextLong(0, 6 * 3_600_000L) else random.nextLong(0, 3_600_000L)
                Device(SyncDeviceInfo(deviceId(index), "Device $index", "android", "27.1"), SyncLedger(), base + skew)
            }
            // A few rounds of local writes and partial reads, so highWater and ties come into play.
            repeat(3) {
                devices.forEach { device -> repeat(random.nextInt(4)) { if (random.nextBoolean()) device.writeProgress(random) else device.writeMedia(random) } }
                val files = devices.map { it.file() }
                devices.forEach { device ->
                    files.shuffled(random).filter { it.device.id != device.info.id && random.nextBoolean() }.forEach { file ->
                        device.ledger = device.ledger.merge(file, device.clock).ledger
                    }
                }
            }
            // Every device reads every other device's latest file, in its own random order.
            val latest = devices.map { it.file() }
            val now = devices.maxOf { it.clock }
            devices.forEach { device ->
                latest.shuffled(random).filter { it.device.id != device.info.id }.forEach { file ->
                    device.ledger = device.ledger.merge(file, now).ledger
                }
            }
            val expectedProgress = devices.first().ledger.progress
            val expectedMedia = devices.first().ledger.userMedia
            devices.forEach { device ->
                assertEquals(expectedProgress, device.ledger.progress, "seed $seed: progress differs on ${device.info.name}")
                assertEquals(expectedMedia, device.ledger.userMedia, "seed $seed: user media differs on ${device.info.name}")
                // Idempotent: reading everything again is a no-op.
                latest.forEach { file ->
                    val again = device.ledger.merge(file, now)
                    assertTrue(!again.changed, "seed $seed: a second read of ${file.device.name} changed ${device.info.name}")
                    assertEquals(device.ledger, again.ledger)
                }
                // The file written is a faithful copy of the ledger.
                val bytes = DeviceFileCodec.encode(device.file(), now)
                val back = DeviceFileCodec.decode(bytes, "Family", DeviceFile.fileName(device.info.id)) as DeviceFileCodec.Decoded.Valid
                assertEquals(device.ledger.progress, back.file.watchProgress.associateBy { it.key })
                assertEquals(device.ledger.userMedia, back.file.userMedia.associateBy { it.key })
            }
            // Every record present came from some device's own write with that device's stamp.
            expectedProgress.values.forEach { record -> assertTrue(devices.any { it.info.id == record.device }) }
        }
    }

    @Test
    fun `a device behind on its clock still orders its write after what it applied`() {
        val a = Device(SyncDeviceInfo(deviceId(1), "A", "android", "27.1"), SyncLedger(), 1_762_000_000_000L)
        val b = Device(SyncDeviceInfo(deviceId(2), "B", "androidtv", "27.1"), SyncLedger(), 1_762_000_000_000L - 3_600_000L)
        a.writeProgress(Random(1))
        b.ledger = b.ledger.merge(a.file(), b.clock).ledger
        val (stamp, _) = b.ledger.stamp(b.clock)
        assertTrue(stamp > a.ledger.highWater)
    }
}
