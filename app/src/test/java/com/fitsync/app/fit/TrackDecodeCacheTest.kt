package com.fitsync.app.fit

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class TrackDecodeCacheTest {

    @Before
    fun setUp() {
        TrackDecodeCache.clearForTesting()
    }

    @Test
    fun decodeSummaryTrack_handlesNullBlankAndCachesResult() {
        assertThat(TrackDecodeCache.decodeSummaryTrack(null)).isEmpty()
        assertThat(TrackDecodeCache.decodeSummaryTrack("   ")).isEmpty()
        assertThat(TrackDecodeCache.decodeSummaryTrack("not-json")).isEmpty()

        val points = listOf(LatLng(30.1, 120.2), LatLng(30.2, 120.3), LatLng(30.3, 120.4))
        val json = TrackCodec.encodeTrack(points)

        val first = TrackDecodeCache.decodeSummaryTrack(json)
        val second = TrackDecodeCache.decodeSummaryTrack(json)

        assertThat(first).isEqualTo(points)
        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun decodeBestTrackCached_prefersDenseAndHitsCache() {
        val dense = List(120) { LatLng(30.0 + it * 0.001, 120.0 + it * 0.001) }
        val summary = List(10) { LatLng(30.0 + it * 0.01, 120.0 + it * 0.01) }
        val denseBase64 = TrackCodec.encodeDenseTrack(dense)
        val summaryJson = TrackCodec.encodeTrack(summary)

        val first = TrackDecodeCache.decodeBestTrackCached("act-1-2", denseBase64, summaryJson)
        val second = TrackDecodeCache.decodeBestTrackCached("act-1-2", denseBase64, summaryJson)

        assertThat(first).hasSize(120)
        assertThat(second).isSameInstanceAs(first)

        val fallback = TrackDecodeCache.decodeBestTrackCached("act-2-3", null, summaryJson)
        assertThat(fallback).hasSize(10)
    }

    @Test
    fun decodeProfileCached_hitsCacheAndHandlesBlank() {
        assertThat(TrackDecodeCache.decodeProfileCached("prof-0", null)).isEmpty()
        assertThat(TrackDecodeCache.decodeProfileCached("prof-0", "")).isEmpty()

        val samples = listOf(
            ProfileSample(seconds = 0, speedMps = 5.0, altitudeMeters = 10.0),
            ProfileSample(seconds = 60, speedMps = 8.5, altitudeMeters = 15.0),
        )
        val json = TrackCodec.encodeProfile(samples)

        val first = TrackDecodeCache.decodeProfileCached("prof-1", json)
        val second = TrackDecodeCache.decodeProfileCached("prof-1", json)

        assertThat(first).isEqualTo(samples)
        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun downsampleForPreview_keepsSmallTracksAndPreservesEndpoints() {
        val small = List(50) { LatLng(30.0 + it * 0.001, 120.0) }
        assertThat(TrackDecodeCache.downsampleForPreview(small, maxPoints = 500))
            .isSameInstanceAs(small)

        val large = List(2_000) { LatLng(30.0 + it * 0.0001, 120.0 + it * 0.0001) }
        val downsampled = TrackDecodeCache.downsampleForPreview(large, maxPoints = 500)

        assertThat(downsampled.size).isAtMost(500)
        assertThat(downsampled.size).isGreaterThan(2)
        assertThat(downsampled.first()).isEqualTo(large.first())
        assertThat(downsampled.last()).isEqualTo(large.last())
        // 严格保持索引递增顺序
        for (i in 1 until downsampled.size) {
            assertThat(downsampled[i].lat).isGreaterThan(downsampled[i - 1].lat)
        }
    }
}
