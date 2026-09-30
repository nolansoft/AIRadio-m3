// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.repository.mapper

import com.nolansoftware.airadio.data.database.entity.RecentlyPlayedEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pure-logic unit tests for [RecentlyPlayedEntity.toStationDomain].
 *
 * Bug #3 root cause: `getRecentlyPlayedStations()` INNER JOINed against
 * `stations`, and `runSync()` calls `StationDao.clearAllStations()` on every
 * sync, wiping any station not in the new top-N. Orphaned `recently_played`
 * rows then failed the JOIN and disappeared from Home's carousel.
 *
 * Fix mirrors the favorites fix (MIGRATION_2_3): denormalize the station
 * snapshot into `RecentlyPlayedEntity` so the DAO can read directly from
 * `recently_played` (no JOIN). These tests guard the mapper that does
 * that denormalized-row → domain conversion. They don't need Room because
 * the mapper is pure field-for-field.
 */
class RecentlyPlayedMapperTest {

    @Test
    fun toStationDomain_copiesAllStationFields() {
        val entity = RecentlyPlayedEntity(
            stationuuid = "abc-123",
            played_time = 1_700_000_000_000L,
            name = "AI Radio Test",
            url = "https://example.com/stream",
            url_resolved = "https://example.com/stream-resolved",
            favicon = "https://example.com/favicon.png",
            country = "United States",
            countrycode = "US",
            language = "english",
            tags = "jazz,smooth",
            codec = "MP3",
            bitrate = 192,
            votes = 1234,
            lastchecktime = 1_699_999_999_000L,
        )

        val station = entity.toStationDomain()

        assertEquals("abc-123", station.stationuuid)
        assertEquals("AI Radio Test", station.name)
        assertEquals("https://example.com/stream", station.url)
        assertEquals("https://example.com/stream-resolved", station.urlResolved)
        assertEquals("https://example.com/favicon.png", station.favicon)
        assertEquals("United States", station.country)
        assertEquals("US", station.countryCode)
        assertEquals("english", station.language)
        assertEquals("jazz,smooth", station.tags)
        assertEquals("MP3", station.codec)
        assertEquals(192, station.bitrate)
        assertEquals(1234, station.votes)
        assertEquals(1_699_999_999_000L, station.lastCheckTime)
    }

    @Test
    fun toStationDomain_doesNotExposePlayedTimeOnStationDomain() {
        // The mapper deliberately does NOT expose `played_time` on the Station
        // domain — `played_time` stays in RecentlyPlayedEntity for ORDER BY
        // but isn't part of the Station shape. Locking it down so a future
        // refactor can't accidentally surface a timestamp that callers don't
        // expect (e.g. HomeScreen's carousel, PlayerScreen).
        val entity = RecentlyPlayedEntity(
            stationuuid = "x",
            played_time = 42L,
            name = "n",
            url = "u",
            url_resolved = "ur",
            favicon = "f",
            country = "c",
            countrycode = "cc",
            language = "l",
            tags = "t",
            codec = "co",
            bitrate = 0,
            votes = 0,
            lastchecktime = 0L,
        )
        val station = entity.toStationDomain()
        // Station is a data class with 13 declared properties (no playedTime).
        // Java reflection sees 13 backing fields plus a couple of synthetic
        // extras (`$stable` from Kotlin metadata, `CREATOR` from `@Parcelize`)
        // — filter those to get just the Station fields.
        val fieldNames = station::class.java.declaredFields
            .map { it.name }
            .filter { !it.startsWith("$") && it != "CREATOR" }
            .toSet()
        assertEquals(
            "Station has 13 domain fields, none of them playedTime",
            setOf(
                "stationuuid", "name", "url", "urlResolved", "favicon", "country",
                "countryCode", "language", "tags", "codec", "bitrate", "votes",
                "lastCheckTime",
            ),
            fieldNames,
        )
        assertFalse(
            "playedTime must NOT appear on Station domain",
            fieldNames.any { it.contains("played", ignoreCase = true) }
        )
    }

    @Test
    fun toStationDomain_handlesEmptyStringsAndZeros() {
        // Edge case: API occasionally returns empty strings / zero numerics
        // for stations in the back of the top-N. The mapper must not crash
        // or filter these out — they're still valid Station rows.
        val entity = RecentlyPlayedEntity(
            stationuuid = "",
            played_time = 0L,
            name = "",
            url = "",
            url_resolved = "",
            favicon = "",
            country = "",
            countrycode = "",
            language = "",
            tags = "",
            codec = "",
            bitrate = 0,
            votes = 0,
            lastchecktime = 0L,
        )
        val station = entity.toStationDomain()
        assertEquals("", station.stationuuid)
        assertEquals("", station.name)
        assertEquals(0, station.bitrate)
        assertEquals(0L, station.lastCheckTime)
    }
}
