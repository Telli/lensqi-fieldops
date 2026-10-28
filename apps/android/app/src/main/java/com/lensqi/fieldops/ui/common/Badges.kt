package com.lensqi.fieldops.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lensqi.fieldops.data.api.DiagnosticSource
import com.lensqi.fieldops.ui.theme.Provenance

@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Every probe result carries its source; a simulated or replayed result is never shown without this badge. */
@Composable
fun SourceBadge(source: DiagnosticSource, modifier: Modifier = Modifier) = when (source) {
    DiagnosticSource.LiveLocal -> Tag("LIVE", Provenance.Measured, modifier)
    DiagnosticSource.Simulated -> Tag("SIMULATED", Provenance.Simulated, modifier)
    DiagnosticSource.RecordedReplay -> Tag("REPLAY", Provenance.Simulated, modifier)
    DiagnosticSource.VendorReadOnly -> Tag("VENDOR READ-ONLY", Provenance.Measured, modifier)
}
