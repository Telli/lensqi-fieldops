package com.lensqi.fieldops.ui

import com.lensqi.fieldops.data.api.Fixture
import com.lensqi.fieldops.data.api.Recordings
import com.lensqi.fieldops.di.NetworkModule
import com.lensqi.fieldops.ui.home.assetFromScan
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A scanned label opens an inspection only for an asset the site record lists. */
class AssetScanTest {
    private val fixtures = NetworkModule.json().decodeFromString(ListSerializer(Fixture.serializer()), Recordings.text("fixtures"))
    private val asset = fixtures.first().assets.first()

    @Test fun `the asset id itself, or a URL or text carrying it as a whole token, names the asset`() {
        assertEquals(asset.assetId, assetFromScan(asset.assetId, fixtures)!!.assetId)
        assertEquals(asset.assetId, assetFromScan(" ${asset.assetId.lowercase()} ", fixtures)!!.assetId)
        assertEquals(asset.assetId, assetFromScan("https://site.example/assets/${asset.assetId}?rack=2", fixtures)!!.assetId)
        assertEquals(asset.assetId, assetFromScan("Rack 2 · ${asset.assetId} · bay 4", fixtures)!!.assetId)
        assertEquals(fixtures.first().fixtureId, assetFromScan(asset.assetId, fixtures)!!.fixtureId)
    }

    @Test fun `punctuation that ends a label or sentence is not part of the id`() {
        assertEquals(asset.assetId, assetFromScan("Asset: ${asset.assetId}.", fixtures)!!.assetId)
        assertEquals(asset.assetId, assetFromScan("${asset.assetId}_", fixtures)!!.assetId)
        assertEquals(asset.assetId, assetFromScan("-${asset.assetId}-", fixtures)!!.assetId)
        // Letters and digits stuck to the id are still part of the token.
        assertNull(assetFromScan("${asset.assetId}1.", fixtures))
        // An id that itself ends in punctuation is still found behind a sentence's full stop.
        val dashed = fixtures.map { f -> f.copy(assets = f.assets.map { it.copy(assetId = it.assetId + "-") }) }
        assertEquals(asset.assetId + "-", assetFromScan("Asset: ${asset.assetId}-.", dashed)!!.assetId)
        assertEquals(asset.assetId + "-", assetFromScan("(${asset.assetId}-)", dashed)!!.assetId)
    }

    @Test fun `anything the site record does not list is no asset, and a prefix is not a match`() {
        assertNull(assetFromScan("", fixtures))
        assertNull(assetFromScan("https://example.com/", fixtures))
        assertNull(assetFromScan(asset.assetId.dropLast(1), fixtures))
        assertNull(assetFromScan("${asset.assetId}X", fixtures))
        assertNull(assetFromScan(asset.assetId, emptyList()))
        // A code made of punctuation, or a very long one, is answered at once rather than peeled a character at a time.
        assertNull(assetFromScan(".".repeat(5000), fixtures))
        assertEquals(asset.assetId, assetFromScan("x".repeat(5000) + " " + asset.assetId + ".", fixtures)!!.assetId, "a long token is skipped, the id beside it is found")
        assertEquals(asset.assetId, assetFromScan("${asset.assetId}...", fixtures)!!.assetId)
    }
}
