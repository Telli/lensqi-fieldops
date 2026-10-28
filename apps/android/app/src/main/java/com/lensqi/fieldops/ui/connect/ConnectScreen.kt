package com.lensqi.fieldops.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lensqi.fieldops.data.api.DiagnosticSource
import com.lensqi.fieldops.ui.common.SourceBadge

/** Screen 0/1: point the app at the inspection API, prove it answers, then pick a fixture asset. */
@Composable
fun ConnectScreen(onInspectionCreated: (inspectionId: String) -> Unit, vm: ConnectViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("LensQi FieldOps", style = MaterialTheme.typography.headlineSmall) }
        item {
            OutlinedTextField(s.apiBase, vm::onBaseChanged, label = { Text("Inspection API") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.check() }, enabled = !s.checking) { Text("Check connection") }
                if (s.checking) CircularProgressIndicator(Modifier.padding(4.dp))
            }
        }
        s.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        s.health?.let { h ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Server ${h.status} · ${h.fixtures} fixture(s) · ${h.packs} knowledge pack(s)")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Diagnostics")
                            SourceBadge(h.diagnosticsMode)
                        }
                        if (h.diagnosticsMode != DiagnosticSource.LiveLocal)
                            Text("Probe results from this server are not live measurements.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (s.health != null) item {
            // Resume an inspection started earlier on this phone or in the browser.
            var existing by rememberSaveable { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(existing, { existing = it.trim() }, label = { Text("Open inspection (I-…)") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { onInspectionCreated(existing) }, enabled = existing.startsWith("I-")) { Text("Open") }
            }
        }
        items(s.fixtures, key = { it.fixtureId }) { f ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${f.fixtureId} · rev ${f.fixtureRevision}", style = MaterialTheme.typography.titleMedium)
                    Text(f.fixtureDescription, style = MaterialTheme.typography.bodySmall)
                    Text("twin ${f.sha256.take(12)}…", style = MaterialTheme.typography.labelSmall)
                    f.assets.forEach { a ->
                        Button(onClick = { vm.createInspection(f.fixtureId, a.assetId, onInspectionCreated) }, enabled = !s.creating, modifier = Modifier.fillMaxWidth()) { Text("Inspect ${a.displayName} (${a.assetId})") }
                    }
                }
            }
        }
    }
}
