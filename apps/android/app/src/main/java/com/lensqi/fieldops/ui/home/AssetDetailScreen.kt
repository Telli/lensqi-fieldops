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
    Column {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Asset", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider(color = Fo.Border)
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(Modifier.padding(top = 12.dp)) {
                    Text(assetId, color = Fo.Primary, fontSize = 12.sp)
                    Text(asset?.displayName ?: "Not in the site record", style = MaterialTheme.typography.headlineSmall)
                    asset?.let { Text("${it.kind.replace('_', ' ')} · ${fixture.fixtureId}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (fixture != null) item { PrimaryButton("Start an inspection", { onStart(fixture.fixtureId, assetId) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.QrCodeScanner) }
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
                                Text("Cable label ${c.cableLabel} · ${c.connectionId}", style = MaterialTheme.typography.bodySmall)
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
            items(history.take(10), key = { it.inspectionId }) { h -> InspectionCard(h, asset?.displayName) { onOpen(it) } }
            item { Spacer(Modifier.width(1.dp).height(24.dp)) }
        }
    }
}
