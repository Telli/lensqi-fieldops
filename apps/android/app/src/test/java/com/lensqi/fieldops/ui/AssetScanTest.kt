package com.lensqi.fieldops.ui

import com.lensqi.fieldops.data.api.Fixture
import com.lensqi.fieldops.data.api.Recordings
import com.lensqi.fieldops.di.NetworkModule
import com.lensqi.fieldops.ui.home.assetFromScan
import com.lensqi.fieldops.ui.home.assetFromText
import com.lensqi.fieldops.ui.home.looksLikeId
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A scanned label opens an inspection only for an asset the site record lists. */
class AssetScanTest {
    private val fixtures = NetworkModule.json().decodeFromString(ListSerializer(Fixture.serializer()), Recordings.text("fixtures"))
    private val asset = fixtures.first().assets.first()

    @Test fun `a serial barcode cannot hide a known second barcode or OCR read`() {
        val unrelated = listOf("serial:9988")
        assertNull(com.lensqi.fieldops.ui.home.matchingRead(unrelated, fixtures))
        assertEquals("LAB-SW-01", com.lensqi.fieldops.ui.home.matchingRead(unrelated + "LAB-SW-01", fixtures)?.second?.assetId)
        assertEquals("LAB-SW-01", assetFromText(listOf("ASSET", "LAB-SW-01"), fixtures)?.assetId)
    }

    @Test fun `scanner closes once after its last frame and refuses new frames after disposal`() {
        var closes = 0
        val life = com.lensqi.fieldops.ui.home.ScanLifetime { closes++ }
        assertEquals(true, life.begin())
        life.dispose()
        assertEquals(false, life.begin())
        assertEquals(0, closes)
        life.finish()
        assertEquals(false, life.begin())
        life.dispose()
        assertEquals(1, closes)
    }

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

    @Test fun `a printed label is read line by line and the line carrying the id names the asset`() {
        val label = listOf("ASSET TAG", "LAB-SW-01", "Rack 2")
        val found = assetFromText(label, fixtures)
        assertNotNull(found, "the id line names the asset even behind a heading")
        assertEquals("LAB-SW-01", found!!.assetId)
        assertEquals(fixtures.first { f -> f.assets.any { it.assetId == "LAB-SW-01" } }.fixtureId, found.fixtureId)
        // Punctuation printed after the id is not part of it, as on a scanned code.
        assertEquals("LAB-SW-01", assetFromText(listOf("Asset: LAB-SW-01."), fixtures)!!.assetId)
        // The first line that names an asset wins when a label carries two.
        assertEquals("LAB-NODE-A", assetFromText(listOf("Rack", "LAB-NODE-A", "LAB-SW-01"), fixtures)!!.assetId)
    }

    @Test fun `a mis-read id names nothing but is still the best read to show`() {
        // OCR took the digit 1 for the letter l: no asset, so the scan goes on, and the read is shown so the technician sees why.
        assertNull(assetFromText(listOf("ASSET TAG", "LAB-SW-0l", "Rack 2"), fixtures))
        assertEquals("LAB-SW-0l", looksLikeId("LAB-SW-0l"))
        assertEquals("LAB-SW-0l", com.lensqi.fieldops.ui.home.likelyIdLine(listOf("Single-mode networking description", "LAB-SW-0l", "Rack 2")))
        assertEquals("LAB-SW-01", looksLikeId("Asset: LAB-SW-01."), "edge punctuation is not part of the id-looking token")
        assertEquals("RACK_2a-01", looksLikeId("see RACK_2a-01 and A-1"), "the longest id-looking token is the read")
        assertNull(looksLikeId("ASSET TAG"))
        assertNull(looksLikeId("Rack 2"))
        assertNull(looksLikeId("2026-11-03"), "a token must start with letters")
        assertNull(looksLikeId("A-" + "x".repeat(200)), "a long token is not an id")
    }

    @Test fun `printed text matches nothing without site records or with an empty label`() {
        assertNull(assetFromText(listOf("ASSET TAG", "LAB-SW-01"), emptyList()))
        assertNull(assetFromText(emptyList(), fixtures))
        assertNull(assetFromText(listOf("", "   "), fixtures))
    }
}
