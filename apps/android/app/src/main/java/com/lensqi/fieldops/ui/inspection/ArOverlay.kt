package com.lensqi.fieldops.ui.inspection

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lensqi.fieldops.data.api.Fixture
import com.lensqi.fieldops.data.api.ObservationStatus
import com.lensqi.fieldops.data.api.ProposedNextAction
import com.lensqi.fieldops.data.api.SegmentBox
import com.lensqi.fieldops.data.api.Segmentation
import com.lensqi.fieldops.data.api.VisualAssessment
import com.lensqi.fieldops.data.api.VisualChangeAnalysis
import com.lensqi.fieldops.data.api.VisualChangeVerdict
import com.lensqi.fieldops.domain.CocoRle
import com.lensqi.fieldops.ui.common.featureName
import com.lensqi.fieldops.ui.common.sentence
import com.lensqi.fieldops.ui.theme.Fo
import com.lensqi.fieldops.ui.theme.FoDark

/** A label pinned to a part SAM outlined. Tags exist only where a real outline exists; nothing is placed by guesswork. */
data class ArTag(val concept: String, val label: String, val tone: Color, val detail: String, val box: SegmentBox, val cropFile: String? = null)

/** The assessment reduced to findings that cite this photo; null when none does. Other photos' findings never describe it. */
fun assessmentForPhoto(assessment: VisualAssessment?, photoEvidenceId: String?): VisualAssessment? {
    if (assessment == null || photoEvidenceId == null) return null
    val cited = assessment.hypotheses.filter { photoEvidenceId in it.evidenceIds }
    return if (cited.isEmpty()) null else assessment.copy(hypotheses = cited)
}

/** The fixture's outline prompts, repair target last: the server compares later photos against the last outline. */
fun outlineOrder(fixture: Fixture?, assessment: VisualAssessment?): List<String> {
    val prompts = fixture?.visualRequirements?.mapNotNull { it.segmentationPrompt }.orEmpty()
    val target = assessment?.requestedSegmentationConcept?.takeIf { it in prompts } ?: prompts.lastOrNull()
    return prompts.filter { it != target } + listOfNotNull(target)
}

/** The outlined part the AI wants a better view of, if it asked for one. */
fun targetConcept(fixture: Fixture?, assessment: VisualAssessment?): String? {
    if (assessment?.nextAction != ProposedNextAction.RequestView) return null
    assessment.requestedSegmentationConcept?.let { return it }
    val unclear = assessment.hypotheses.firstOrNull { it.status != ObservationStatus.Observed }
    return fixture?.visualRequirements?.firstOrNull { it.requirementId == unclear?.featureId }?.segmentationPrompt
}

/** One tag per outline: the AI's finding for the matching checklist item, or the change verdict after a repair. */
fun arTags(fixture: Fixture?, assessment: VisualAssessment?, outlines: List<Segmentation>, change: VisualChangeAnalysis? = null): List<ArTag> =
    outlines.mapNotNull { g ->
        val box = g.boxes.maxByOrNull { it.confidence ?: 0.0 }
            ?: g.masks.firstOrNull()?.let { SegmentBox(it.objectId, it.left, it.top, it.right, it.bottom, it.confidence) } ?: return@mapNotNull null
        val req = fixture?.visualRequirements?.firstOrNull { it.segmentationPrompt == g.concept }
        val name = req?.let { featureName(it.requirementId) } ?: sentence(g.concept)
        val cropFile = g.crop?.file
        if (change != null && change.photoEvidenceId == g.photoEvidenceId && change.targetPrompt == g.concept) {
            val (tone, status) = when (change.verdict) {
                VisualChangeVerdict.Changed -> Fo.Success to "changed since before"
                VisualChangeVerdict.Unscorable -> Fo.Warning to "not compared"
                else -> Fo.Failure to "no change"
            }
            return@mapNotNull ArTag(g.concept, "$name · $status", tone, change.reason, box, cropFile)
        }
        val h = assessment?.hypotheses?.firstOrNull { it.featureId == req?.requirementId && (it.evidenceIds.isEmpty() || g.photoEvidenceId in it.evidenceIds) }
            ?: return@mapNotNull ArTag(g.concept, "$name · AI outline", Fo.Primary, "SAM outlined the ${g.concept}. This is the model's interpretation, not a measurement.", box, cropFile)
        val (tone, status) = when (h.status) {
            ObservationStatus.Observed -> Fo.Primary to "AI: visible"
            ObservationStatus.Suspected -> Fo.Primary to "AI: likely"
            ObservationStatus.Unknown -> Fo.Warning to "not clear"
            ObservationStatus.Conflicting -> Fo.Failure to "conflicting"
        }
        ArTag(g.concept, "$name · $status", tone, h.reason, box, cropFile)
    }

/**
 * The captured photo with the AI's outlines on its original pixels and numbered markers pinned to each part.
 * The overlay labels the still it was computed from; it is never projected onto a moving camera feed.
 */
@Composable
fun ArOverlay(photo: Bitmap, outlines: List<Segmentation>, tags: List<ArTag>, guide: String? = null, guideLabel: String = "Target region", modifier: Modifier = Modifier) {
    val image = remember(photo) { photo.asImageBitmap() }
    val masks = remember(outlines, tags) {
        outlines.flatMap { g ->
            val tone = tags.firstOrNull { it.concept == g.concept }?.tone ?: Fo.Primary
            g.masks.map { m -> maskBitmap(m.rle.counts, m.rle.size[0], m.rle.size[1], tone) to m }
        }
    }
    var selected by remember(photo) { mutableStateOf<String?>(null) }
    var hidden by remember(photo) { mutableStateOf(false) }
    val guideBox = guide?.let { c -> tags.firstOrNull { it.concept == c }?.box }
    val active = tags.firstOrNull { it.concept == selected }

    Column(modifier.fillMaxWidth().background(FoDark.Overlay)) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(photo.width.toFloat() / photo.height).clickable { selected = null }) {
            val scale = constraints.maxWidth.toFloat() / photo.width
            val dpPerPx = maxWidth.value / constraints.maxWidth
            Canvas(Modifier.matchParentSize()) {
                drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                if (!hidden) {
                    for ((bitmap, m) in masks) drawImage(
                        bitmap,
                        dstOffset = IntOffset((m.left * scale).toInt(), (m.top * scale).toInt()),
                        dstSize = IntSize(((m.right - m.left) * scale).toInt(), ((m.bottom - m.top) * scale).toInt()),
                    )
                    val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 9f))
                    for (t in tags) drawRect(
                        t.tone.copy(alpha = if (selected == null || selected == t.concept) 1f else 0.35f),
                        topLeft = Offset((t.box.left * scale).toFloat(), (t.box.top * scale).toFloat()),
                        size = Size(((t.box.right - t.box.left) * scale).toFloat(), ((t.box.bottom - t.box.top) * scale).toFloat()),
                        style = Stroke(width = if (selected == t.concept) 4.dp.toPx() else 3.dp.toPx(), pathEffect = dash),
                    )
                    guideBox?.let { b ->
                        val l = (b.left * scale).toFloat(); val t = (b.top * scale).toFloat(); val r = (b.right * scale).toFloat(); val btm = (b.bottom * scale).toFloat()
                        val shade = FoDark.Overlay.copy(alpha = 0.6f)
                        drawRect(shade, Offset(0f, 0f), Size(size.width, t))
                        drawRect(shade, Offset(0f, btm), Size(size.width, size.height - btm))
                        drawRect(shade, Offset(0f, t), Size(l, btm - t))
                        drawRect(shade, Offset(r, t), Size(size.width - r, btm - t))
                        drawRect(Fo.Primary, Offset(l, t), Size(r - l, btm - t), style = Stroke(width = 2.dp.toPx(), pathEffect = dash))
                    }
                }
            }
            if (!hidden) {
                tags.forEachIndexed { idx, t ->
                    val rawX = (t.box.left * scale * dpPerPx).toFloat() - 18f
                    val rawY = (t.box.top * scale * dpPerPx).toFloat() - 18f
                    val x = rawX.coerceIn(6f, (maxWidth.value - 42f).coerceAtLeast(6f))
                    val y = rawY.coerceIn(6f, (maxHeight.value - 42f).coerceAtLeast(6f))
                    val isSelected = selected == t.concept
                    Box(
                        Modifier.offset(x = x.dp, y = y.dp).size(36.dp)
                            .background(FoDark.Overlay.copy(alpha = 0.95f), CircleShape)
                            .border(if (isSelected) 2.5.dp else 2.dp, if (isSelected) FoDark.Foreground else t.tone, CircleShape)
                            .clickable { selected = if (isSelected) null else t.concept },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${idx + 1}", color = t.tone, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (tags.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(FoDark.Overlay).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("AI outlines · captured photo", color = FoDark.Muted, fontSize = 11.sp)
                    TextButton(onClick = { hidden = !hidden }) {
                        Text(if (hidden) "Show outlines" else "Hide outlines", color = Fo.Primary, fontSize = 12.sp)
                    }
                }
                if (guideBox != null) {
                    Text(guideLabel, color = Fo.Primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEachIndexed { idx, t ->
                        Chip("${idx + 1} · ${t.label}", t.tone, Modifier.clickable { selected = if (selected == t.concept) null else t.concept }, selected == t.concept)
                    }
                }
                if (active != null) {
                    Column(
                        Modifier.fillMaxWidth().background(Fo.Surface.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                            .border(1.dp, active.tone.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(active.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = FoDark.Foreground)
                            Text("Close", fontSize = 11.sp, color = Fo.Primary, modifier = Modifier.clickable { selected = null }.padding(4.dp))
                        }
                        Text(active.detail, fontSize = 12.sp, color = FoDark.Muted)
                    }
                } else {
                    Text("Select a numbered part to read the finding.", fontSize = 11.sp, color = FoDark.Muted)
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, tone: Color, modifier: Modifier = Modifier, selected: Boolean = false) {
    Row(
        modifier.background(Fo.Background.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).border(if (selected) 2.dp else 1.dp, tone.copy(alpha = if (selected) 0.9f else 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).background(tone, CircleShape))
        Text(text, color = tone, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun maskBitmap(counts: String, height: Int, width: Int, tone: Color): ImageBitmap {
    val bits = CocoRle.decode(counts, height, width)
    val fill = tone.copy(alpha = 0.34f).toArgb()
    val pixels = IntArray(bits.size) { if (bits[it]) fill else 0 }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
