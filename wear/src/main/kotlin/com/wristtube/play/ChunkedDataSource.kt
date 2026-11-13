package com.wristtube.play

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Reads a long media file as a series of short ranged requests.
 *
 * YouTube serves a single open-ended request ("Range: bytes=0-") at about 0.14 MB/s, below the
 * bitrate of its own 128 kbps audio, but serves bounded ranges of a few MB at full speed (measured:
 * 0.5-0.9 MB/s on the same connection). ExoPlayer's progressive loader opens one open-ended request
 * and reads it for the whole video, so this sits between the two: it asks for [chunkBytes] at a
 * time and opens the next range when one runs out. To ExoPlayer it's still one stream.
 */
@UnstableApi
class ChunkedDataSource(
    private val upstream: HttpDataSource,
    private val chunkBytes: Long,
) : DataSource {

    private var spec: DataSpec? = null
    private var position = 0L

    /** Absolute end of the data, exclusive, once known (from the first response's Content-Range). */
    private var end = UNKNOWN

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        position = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else UNKNOWN
        val first = openChunk()
        if (end == UNKNOWN) {
            val total = totalFromContentRange()
            end = when {
                total != null -> total
                // No Content-Range, but less than asked for: that was the rest of the file.
                first != C.LENGTH_UNSET.toLong() && first < chunkBytes -> position + first
                else -> UNKNOWN
            }
        }
        return if (end == UNKNOWN) C.LENGTH_UNSET.toLong() else end - dataSpec.position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (end != UNKNOWN && position >= end) return C.RESULT_END_OF_INPUT
        var read = upstream.read(buffer, offset, length)
        if (read == C.RESULT_END_OF_INPUT) {
            // This range is used up. Ask for the next one; an empty answer means the file ended.
            upstream.close()
            if (openChunk() == 0L) return C.RESULT_END_OF_INPUT
            read = upstream.read(buffer, offset, length)
            if (read == C.RESULT_END_OF_INPUT) return read
        }
        position += read
        return read
    }

    private fun openChunk(): Long {
        val base = spec ?: error("not open")
        val length = if (end != UNKNOWN) minOf(chunkBytes, end - position) else chunkBytes
        if (length <= 0) return 0
        Log.d(TAG, "range $position+$length of ${if (end == UNKNOWN) "?" else end} (${base.uri.host})")
        return upstream.open(base.buildUpon().setPosition(position).setLength(length).build())
    }

    /** "bytes 0-2097151/1918911389" -> 1918911389. */
    private fun totalFromContentRange(): Long? {
        val header = upstream.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value?.firstOrNull() ?: return null
        return header.substringAfterLast('/', "").trim().toLongOrNull()
    }

    override fun getUri() = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        spec = null
        upstream.close()
    }

    class Factory(private val upstream: HttpDataSource.Factory, private val chunkBytes: Long) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource(), chunkBytes)
    }

    private companion object {
        const val UNKNOWN = -1L
        const val TAG = "WristTube/Chunk"
    }
}
