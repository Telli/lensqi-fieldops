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

/** Asset ids are short; a longer token is not one, and peeling it would only cost time on the scan screen. */
private const val MAX_TOKEN = 64
/** At most this many punctuation characters are peeled from each end: a label's full stop, a bracket's dash, no more. */
private const val MAX_PEEL = 3

/**
 * [token] with up to [MAX_PEEL] edge punctuation characters removed from its start and from its end, in every combination.
 * Bounded, so a code made of punctuation cannot stall the scan screen (the scanner's listener runs on the main thread).
 */
internal fun peeled(token: String): List<String> {
    if (token.length > MAX_TOKEN) return emptyList()
    val fronts = generateSequence(token) { t -> t.takeIf { it.isNotEmpty() && it.first() in EDGE_PUNCTUATION }?.drop(1) }.take(MAX_PEEL + 1)
    return fronts.flatMap { f -> generateSequence(f) { t -> t.takeIf { it.isNotEmpty() && it.last() in EDGE_PUNCTUATION }?.dropLast(1) }.take(MAX_PEEL + 1) }
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

/** A printed token that looks like an asset id: letters, a dash or underscore, then more id characters ("LAB-SW-01", "RACK_2a"). */
private val ID_LIKE = Regex("[A-Za-z]+[-_][A-Za-z0-9_-]+")

/**
 * The longest id-looking token in [line], or null when the line carries none. Lets the scanner show the read most likely
 * to be the label's id ("LAB-SW-0l", mis-read with a letter l) when no line names a recorded asset. Bounded like [peeled]:
 * a token longer than [MAX_TOKEN] is not an id and is skipped.
 */
fun looksLikeId(line: String): String? =
    ID_LIKE.findAll(line).map { it.value }.filter { it.length <= MAX_TOKEN }.maxByOrNull { it.length }

/**
 * The first recognised text line that names an asset on a site record, by the same rule as a scanned code
 * ([assetFromScan]: whole token, longest id wins within a line). Lines that name nothing are skipped, so a label's
 * "ASSET TAG" heading does not hide the id printed under it.
 */
fun assetFromText(lines: List<String>, fixtures: List<Fixture>): ScannedAsset? =
    lines.firstNotNullOfOrNull { assetFromScan(it, fixtures) }
