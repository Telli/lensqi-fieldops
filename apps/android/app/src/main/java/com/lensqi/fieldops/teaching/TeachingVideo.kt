package com.lensqi.fieldops.teaching

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

/** Reads encoded presentation timestamps, never inventing frames from the requested camera frame rate. */
fun inspectTeachingVideo(folder: File): VideoDetails {
    val video = File(folder, "video.mp4")
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(video.path)
        val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
        val index = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        require(index >= 0) { "The recording contains no video track." }
        extractor.selectTrack(index)
        val pts = mutableListOf<Long>()
        while (extractor.sampleTime >= 0) { pts += extractor.sampleTime; extractor.advance() }
        val ordered = pts.sorted()
        require(ordered.size > 1 && ordered.zipWithNext().all { (a, b) -> b > a }) { "The video has too few frames or duplicate timestamps." }
        val gaps = ordered.zipWithNext { a, b -> b - a }
        val median = gaps.sorted()[gaps.size / 2]
        File(folder, "frames.csv").bufferedWriter().use { out ->
            out.write("frame_index,presentation_time_us,relative_time_us\n")
            ordered.forEachIndexed { i, t -> out.write("$i,$t,${t - ordered.first()}\n") }
        }
        val format = formats[index]
        val retriever = MediaMetadataRetriever()
        val rotation = try { retriever.setDataSource(video.path); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0 } finally { retriever.release() }
        return VideoDetails(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), rotation,
            if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else ordered.last() - ordered.first(),
            ordered.size, ordered.first(), ordered.last(), median, gaps.max(), gaps.count { it > maxOf(median * 3, 100_000L) },
            formats.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true })
    } finally { extractor.release() }
}
