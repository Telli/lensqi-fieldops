package com.lensqi.fieldops.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.lensqi.fieldops.data.api.InspectionStage
import com.lensqi.fieldops.ui.common.EvidenceBadge
import com.lensqi.fieldops.ui.common.stageLabel
import com.lensqi.fieldops.ui.common.stageState
import com.lensqi.fieldops.ui.theme.FoDark
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lensqi.fieldops.ui.common.Panel
import com.lensqi.fieldops.ui.common.PrimaryButton
import com.lensqi.fieldops.ui.common.SectionTitle
import com.lensqi.fieldops.ui.common.Tag
import com.lensqi.fieldops.ui.theme.Fo

/** One asset outside any inspection: how the site says it should be installed, and its inspection history. */
@Composable
fun AssetDetailScreen(vm: HomeViewModel, assetId: String, onBack: () -> Unit, onStart: (fixtureId: String, assetId: String) -> Unit, onOpen: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val fixture = s.fixtures.firstOrNull { f -> f.assets.any { it.assetId == assetId } }
    val asset = fixture?.assets?.firstOrNull { it.assetId == assetId }
    val links = fixture?.connections?.filter { it.fromAssetId == assetId || it.toAssetId == assetId }.orEmpty()
    val history = s.inspections.filter { it.assetId == assetId }
    val open = history.firstOrNull { it.stage != InspectionStage.VerifiedComplete && it.stage != InspectionStage.Inconclusive }
    LaunchedEffect(assetId, s.inspections.size) { vm.loadAssetPhoto(assetId) }
    Column {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(asset?.displayName ?: "Asset", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider(color = Fo.Border)
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                // The latest photo first, uncropped. Outlines are on the inspection's own page.
                Box(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(FoDark.Overlay)) {
                    val url = s.assetPhotos[assetId]
                    if (url != null) AsyncImage(url, "Latest photo", Modifier.fillMaxWidth().aspectRatio(4f / 3f), contentScale = ContentScale.Fit)
                    else Column(
                        Modifier.fillMaxWidth().aspectRatio(4f / 3f).clickable(enabled = fixture != null) { fixture?.let { onStart(it.fixtureId, assetId) } },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Outlined.CameraAlt, null, tint = FoDark.Muted, modifier = Modifier.size(36.dp))
                        Text(if (history.any { it.photos > 0 }) "Loading the latest photo…" else "No photos yet · scan it", color = FoDark.Muted, fontSize = 13.sp)
                    }
                    if (url != null) Text("Latest photo", fontSize = 11.sp, color = FoDark.Foreground,
                        modifier = Modifier.padding(12.dp).clip(RoundedCornerShape(50)).background(FoDark.Overlay.copy(alpha = 0.65f)).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            item {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(asset?.displayName ?: "Not in the site record", style = MaterialTheme.typography.headlineSmall)
                        asset?.let { Text(it.kind.replace('_', ' '), style = MaterialTheme.typography.bodySmall)
                            com.lensqi.fieldops.ui.common.TechDetails(listOf("Asset" to assetId, "Fixture" to fixture.fixtureId)) }
                    }
                    history.firstOrNull()?.let { EvidenceBadge(stageState(it.stage), stageLabel(it.stage)) }
                }
            }
            if (open != null) item { PrimaryButton("Continue the open inspection", { onOpen(open.inspectionId) }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Outlined.ArrowForward) }
            else if (fixture != null) item { PrimaryButton("Start an inspection", { onStart(fixture.fixtureId, assetId) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.QrCodeScanner) }
            item { SectionTitle("Expected installation state") }
            item {
                Panel {
                    if (links.isEmpty()) Text("The site record lists no connections for this asset.", style = MaterialTheme.typography.bodySmall)
                    links.forEachIndexed { i, c ->
                        if (i > 0) HorizontalDivider(color = Fo.Border)
                        val outgoing = c.fromAssetId == assetId
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.Cable, null, tint = Fo.Primary, modifier = Modifier.size(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (outgoing) "Port ${c.fromPortLabel} → ${vm.assetName(c.toAssetId) ?: c.toAssetId}" else "From ${vm.assetName(c.fromAssetId) ?: c.fromAssetId} port ${c.fromPortLabel}",
                                    fontWeight = FontWeight.Medium,
                                )
                                Text("Cable label ${c.cableLabel}", style = MaterialTheme.typography.bodySmall)
                            }
                            Tag(c.expectedState.replaceFirstChar { it.uppercase() }, Fo.Muted)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("From the site record (revision ${fixture?.fixtureRevision ?: "—"}). How it should be installed, not proof of how it is.", style = MaterialTheme.typography.bodySmall)
            }
            item { SectionTitle("History · ${history.size}") }
            if (history.isEmpty()) item { Text("Not inspected yet.", style = MaterialTheme.typography.bodySmall) }
            items(history.take(5), key = { it.inspectionId }) { h -> InspectionCard(h, asset?.displayName) { onOpen(it) } }
            item { Spacer(Modifier.width(1.dp).height(24.dp)) }
        }
    }
}
