package com.lensqi.fieldops.ui.inspection

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lensqi.fieldops.data.api.Segmentation
import com.lensqi.fieldops.domain.CocoRle

/** The original photo with SAM's masks and boxes drawn in original-pixel coordinates, scaled to fit the width. */
@Composable
fun SegmentationOverlay(photo: Bitmap, segmentation: Segmentation, modifier: Modifier = Modifier) {
    val image = remember(photo) { photo.asImageBitmap() }
    val masks = remember(segmentation) { segmentation.masks.map { m -> maskBitmap(m.rle.counts, m.rle.size[0], m.rle.size[1]) to m } }
    Canvas(modifier.fillMaxWidth().aspectRatio(photo.width.toFloat() / photo.height)) {
        val scale = size.width / photo.width
        drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        for ((bitmap, m) in masks) {
            drawImage(
                bitmap,
                dstOffset = IntOffset((m.left * scale).toInt(), (m.top * scale).toInt()),
                dstSize = IntSize(((m.right - m.left) * scale).toInt(), ((m.bottom - m.top) * scale).toInt()),
            )
        }
        for (b in segmentation.boxes) {
            drawRect(
                Color(0xFFFF5050),
                topLeft = Offset((b.left * scale).toFloat(), (b.top * scale).toFloat()),
                size = Size(((b.right - b.left) * scale).toFloat(), ((b.bottom - b.top) * scale).toFloat()),
                style = Stroke(width = 3f),
            )
        }
    }
}

private fun maskBitmap(counts: String, height: Int, width: Int): ImageBitmap {
    val bits = CocoRle.decode(counts, height, width)
    val pixels = IntArray(bits.size) { if (bits[it]) 0x78FF5050 else 0 }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
