package com.lensqi.fieldops.ui.home

import com.lensqi.fieldops.data.api.Fixture

/** The site-record asset a scanned label names: its fixture and asset id. */
data class ScannedAsset(val fixtureId: String, val assetId: String, val displayName: String)

/**
 * Matches a scanned QR or barcode payload to an asset on a site record. The payload is the asset id itself, or text or a
 * URL that contains it as a whole token ("https://site/assets/LAB-SW-01", "LAB-SW-01 rack 2"). Only ids from the site
 * records count: the app never invents an asset from a code. The longest id wins when one is a prefix of another.
 */
private val EDGE_PUNCTUATION = setOf('.', '-', '_')

/** [token] with zero or more edge punctuation characters removed from its start and from its end, in every combination. */
internal fun peeled(token: String): List<String> {
    val fronts = generateSequence(token) { t -> t.takeIf { it.isNotEmpty() && it.first() in EDGE_PUNCTUATION }?.drop(1) }
    return fronts.flatMap { f -> generateSequence(f) { t -> t.takeIf { it.isNotEmpty() && it.last() in EDGE_PUNCTUATION }?.dropLast(1) } }
        .filter { it.isNotEmpty() }.distinct().toList()
}

fun assetFromScan(payload: String, fixtures: List<Fixture>): ScannedAsset? {
    val text = payload.trim()
    if (text.isEmpty()) return null
    // Punctuation that ends a sentence or a label ("Asset: LAB-SW-01.") is not part of the id, but an id may itself end in
    // one of these characters ("RACK-A-"), so every way of peeling them off either end is tried, not just the bare core.
    val raw = text.split(Regex("[^A-Za-z0-9._-]+")).filter { it.isNotEmpty() }
    val tokens = raw.flatMap(::peeled).map { it.lowercase() }.toSet()
    return fixtures.flatMap { f -> f.assets.map { a -> ScannedAsset(f.fixtureId, a.assetId, a.displayName) } }
        .filter { it.assetId.lowercase() == text.lowercase() || it.assetId.lowercase() in tokens }
        .maxByOrNull { it.assetId.length }
}
