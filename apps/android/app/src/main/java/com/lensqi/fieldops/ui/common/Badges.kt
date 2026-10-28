package com.lensqi.fieldops.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lensqi.fieldops.data.api.DiagnosticSource
import com.lensqi.fieldops.ui.theme.Eyebrow
import com.lensqi.fieldops.ui.theme.Fo
import com.lensqi.fieldops.ui.theme.Mono

/** Plain pill, kept for existing call sites. */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelSmall,
        modifier = modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(50)).border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun EvidenceBadge(state: EvidenceState, label: String) {
    val (color, icon) = when (state) {
        EvidenceState.Unknown -> Fo.Muted to Icons.Outlined.RadioButtonUnchecked
        EvidenceState.Hypothesis -> Fo.Primary to Icons.Outlined.HelpOutline
        EvidenceState.EvidenceRequired -> Fo.Warning to Icons.Outlined.HelpOutline
        EvidenceState.HumanConfirmed -> Fo.Foreground to Icons.Outlined.PersonOutline
        EvidenceState.Verified -> Fo.Success to Icons.Outlined.VerifiedUser
        EvidenceState.Failed -> Fo.Failure to Icons.Outlined.HighlightOff
    }
    Row(
        Modifier.background(color.copy(alpha = if (state == EvidenceState.HumanConfirmed) 0.06f else 0.1f), RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/** Dashed mono badge (the design's "Simulated" chip). */
@Composable
fun SimBadge(text: String = "Simulated", color: Color = Fo.Muted) {
    Text(
        text.uppercase(), color = color, style = Mono.copy(fontSize = 10.sp, letterSpacing = 1.sp),
        modifier = Modifier.drawBehind {
            drawRoundRect(color.copy(alpha = 0.5f), cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))))
        }.padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Every probe result carries its source; a simulated or replayed result is never shown without this badge. */
@Composable
fun SourceBadge(source: DiagnosticSource, modifier: Modifier = Modifier) = Box(modifier) {
    when (source) {
        DiagnosticSource.LiveLocal -> SimBadge("Live", Fo.Success)
        DiagnosticSource.Simulated -> SimBadge("Simulated")
        DiagnosticSource.RecordedReplay -> SimBadge("Replay")
        DiagnosticSource.VendorReadOnly -> SimBadge("Vendor")
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, border: Color = Fo.Border, background: Color = Fo.Surface, padding: Int = 14, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(background, RoundedCornerShape(12.dp)).border(1.dp, border, RoundedCornerShape(12.dp)).padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
}

@Composable
fun SectionTitle(text: String, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = Eyebrow, modifier = Modifier.weight(1f))
        action?.invoke(this)
    }
}

@Composable
fun Kpi(label: String, value: String, tone: Color, modifier: Modifier = Modifier) {
    Panel(modifier, padding = 12) {
        Text(label.uppercase(), style = Eyebrow)
        Text(value, color = tone, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** One of the two independent checks. `pass` null = not run yet. */
@Composable
fun CheckRow(label: String, detail: String, pass: Boolean?, running: Boolean = false) {
    val (border, bg) = when {
        running || pass == null -> Fo.Border to Fo.Background
        pass -> Fo.Success.copy(alpha = 0.3f) to Fo.Success.copy(alpha = 0.05f)
        else -> Fo.Failure.copy(alpha = 0.4f) to Fo.Failure.copy(alpha = 0.1f)
    }
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(12.dp)).border(1.dp, border, RoundedCornerShape(12.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            running -> CircularProgressIndicator(Modifier.size(20.dp), color = Fo.Primary, strokeWidth = 2.dp)
            pass == null -> Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = Fo.Muted, modifier = Modifier.size(20.dp))
            pass -> Icon(Icons.Outlined.CheckCircle, null, tint = Fo.Success, modifier = Modifier.size(20.dp))
            else -> Icon(Icons.Outlined.HighlightOff, null, tint = Fo.Failure, modifier = Modifier.size(20.dp))
        }
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, busy: Boolean = false) {
    Button(onClick, modifier.fillMaxWidth().height(50.dp), enabled = enabled && !busy, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Fo.Primary, contentColor = Fo.OnPrimary, disabledContainerColor = Fo.Primary.copy(alpha = 0.35f), disabledContentColor = Fo.OnPrimary.copy(alpha = 0.7f))) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Fo.OnPrimary, strokeWidth = 2.dp)
        else icon?.let { Icon(it, null, Modifier.size(18.dp)) }
        Spacer(Modifier.width(8.dp)); Text(text, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    OutlinedButton(onClick, modifier.fillMaxWidth().height(46.dp), enabled = enabled, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Fo.Border), colors = ButtonDefaults.outlinedButtonColors(contentColor = Fo.Foreground)) {
        icon?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text)
    }
}

/** Identifiers, digests and policy text, kept out of the technician's way until asked for. */
@Composable
fun TechDetails(rows: List<Pair<String, String>>, title: String = "Technical details") {
    var open by remember { mutableStateOf(false) }
    Panel(padding = 0) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Fo.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null, tint = Fo.Muted, modifier = Modifier.rotate(if (open) 180f else 0f))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(k, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(110.dp))
                        Text(v, style = Mono, color = Fo.Foreground.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun Expandable(title: String, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Panel(padding = 0) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null, tint = Fo.Muted, modifier = Modifier.rotate(if (open) 180f else 0f))
        }
        AnimatedVisibility(open) { Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
    }
}

/** The design's logo mark, drawn with Compose primitives. */
@Composable
fun Dot(color: Color, size: Int = 6) = Box(Modifier.size(size.dp).background(color, RoundedCornerShape(50)))
