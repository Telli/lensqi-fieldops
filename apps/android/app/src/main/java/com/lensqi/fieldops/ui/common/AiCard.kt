package com.lensqi.fieldops.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lensqi.fieldops.ui.theme.Fo

/** LensQi's mark: used only where the text is the model's read or the next step derived from it. */
@Composable
fun LensQiMark(size: Int = 28) {
    Box(
        Modifier.size(size.dp).background(Brush.linearGradient(listOf(Fo.Primary, Fo.Primary.copy(alpha = 0.55f))), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.AutoAwesome, "LensQi", tint = Fo.OnPrimary, modifier = Modifier.size((size * 0.5).dp)) }
}

/** The one AI card per screen: what LensQi sees, the next move, and the single action that does it. */
@Composable
fun AiCard(says: String?, title: String?, hint: String?, modifier: Modifier = Modifier, actions: (@Composable ColumnScope.() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Fo.Elevated, Fo.Surface)), RoundedCornerShape(26.dp))
            .border(1.dp, Fo.Border, RoundedCornerShape(26.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LensQiMark()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                says?.let { Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = Fo.Muted) }
                title?.let { Text(it, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold) }
                hint?.let { Text(it, fontSize = 12.sp, color = Fo.Muted) }
            }
        }
        actions?.let { Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = it) }
    }
}

/** Five quiet segments instead of a stepper. */
@Composable
fun ProgressSegments(step: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            progressSteps.forEachIndexed { k, _ ->
                Box(Modifier.weight(1f).height(4.dp).background(if (k < step) Fo.Primary else if (k == step) Fo.Primary.copy(alpha = 0.35f) else Fo.Border, RoundedCornerShape(2.dp)))
            }
        }
        Row {
            progressSteps.forEachIndexed { k, s ->
                Text(s.uppercase(), fontSize = 9.sp, letterSpacing = 0.8.sp, modifier = Modifier.weight(1f),
                    color = if (k < step) Fo.Foreground else if (k == step) Fo.Primary else Fo.Muted)
            }
        }
    }
}
