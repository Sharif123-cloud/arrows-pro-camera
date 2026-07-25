package com.arrowspro.camera.camera

import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Accumulates raw Image buffers from the burst stream and converts them to
 * Bitmap for the processing pipeline.
 */
class BurstCaptureManager {

    companion object {
        private const val TAG = "BurstCaptureManager"
    }

    private val frames = CopyOnWriteArrayList<Bitmap>()
    private var expectedCount = 0
    private var receivedCount = 0
    private var isRawMode = false

    var onBurstComplete: ((List<Bitmap>, Boolean) -> Unit)? = null

    fun start(count: Int, rawMode: Boolean) {
        frames.clear()
        expectedCount = count
        receivedCount = 0
        isRawMode = rawMode
        Log.i(TAG, "Burst started: count=$count raw=$rawMode")
    }

    /**
     * Called by Camera2Controller for each frame received.
     * Converts the Image to a Bitmap and stores it.
     * Thread-safe — called from camera handler thread.
     */
    fun onFrame(image: Image, isRaw: Boolean) {
        try {
            val bitmap = if (isRaw) {
                imageYuvToBitmap(image)  // RAW is converted by DNG writer; here we decode to Bitmap for alignment
            } else {
                imageYuvToBitmap(image)
            }
            if (bitmap != null) {
                frames.add(bitmap)
                receivedCount++
                Log.d(TAG, "Frame $receivedCount/$expectedCount captured (${bitmap.width}x${bitmap.height})")
            }
        } finally {
            image.close()
        }

        if (receivedCount >= expectedCount) {
            Log.i(TAG, "Burst complete: ${frames.size} frames")
            onBurstComplete?.invoke(frames.toList(), isRawMode)
        }
    }

    fun reset() {
        frames.clear()
        receivedCount = 0
        expectedCount = 0
    }

    // ── YUV_420_888 → Bitmap ──────────────────────────────────────────────────

    private fun imageYuvToBitmap(image: Image): Bitmap? {
        return try {
            when (image.format) {
                android.graphics.ImageFormat.YUV_420_888 -> yuvToBitmap(image)
                android.graphics.ImageFormat.RAW_SENSOR  -> rawSensorToBitmap(image)
                else -> {
                    Log.w(TAG, "Unknown format: ${image.format}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame conversion failed", e)
            null
        }
    }

    private fun yuvToBitmap(image: Image): Bitmap {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(
            nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null
        )
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 95, out)
        val jpegBytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    /**
     * Convert RAW_SENSOR image to Bitmap via a basic demosaic.
     * In production, a full DNG write is preferred; this is used for alignment.
     */
    private fun rawSensorToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        val bitmap = Bitmap.createBitmap(width / 2, height / 2, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width / 2 * (height / 2))

        buffer.rewind()
        val rowData = ByteArray(rowStride)

        for (row in 0 until height / 2) {
            // Skip two source rows per output row (simple 2x2 binning)
            buffer.position(row * 2 * rowStride)
            buffer.get(rowData, 0, minOf(rowStride, rowData.size))

            for (col in 0 until width / 2) {
                val byteIdx = col * 2 * pixelStride
                if (byteIdx + 1 < rowData.size) {
                    val raw16 = ((rowData[byteIdx + 1].toInt() and 0xFF) shl 8) or
                            (rowData[byteIdx].toInt() and 0xFF)
                    val grey = (raw16 shr 6) and 0xFF   // 10-bit → 8-bit
                    pixels[row * (width / 2) + col] =
                        (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
                }
            }
        }
        bitmap.setPixels(pixels, 0, width / 2, 0, 0, width / 2, height / 2)
        return bitmap
    }
}
