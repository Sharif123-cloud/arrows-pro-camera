package com.arrowspro.camera.processing

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SuperResolution — on-device 2× upscaling via TensorFlow Lite ESRGAN.
 *
 * Model: assets/esrgan.tflite
 *   Input:  [1, H, W, 3] FLOAT32 normalised 0–1
 *   Output: [1, H*2, W*2, 3] FLOAT32 normalised 0–1
 *
 * Delegates tried in order: NNAPI → GPU → CPU
 */
class SuperResolution(private val context: Context) {

    companion object {
        private const val TAG = "SuperResolution"
        private const val MODEL_FILE = "esrgan.tflite"
        // Tile size to avoid OOM on the Snapdragon 450 (1.8 GB usable RAM)
        private const val TILE_SIZE = 256
        private const val TILE_OVERLAP = 16
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var inputW = 0
    private var inputH = 0
    private var isLoaded = false

    fun load(): Boolean {
        return try {
            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
            val options = Interpreter.Options()

            // Try GPU delegate first
            try {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate!!)
                Log.i(TAG, "GPU delegate enabled")
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate unavailable, using CPU: ${e.message}")
                gpuDelegate = null
            }

            // NNAPI as fallback on Android 8+
            if (gpuDelegate == null) {
                options.useNNAPI = true
            }

            options.numThreads = 4
            interpreter = Interpreter(modelBuffer, options)

            // Probe input shape from model
            val inputTensor = interpreter!!.getInputTensor(0)
            inputH = inputTensor.shape()[1]
            inputW = inputTensor.shape()[2]
            Log.i(TAG, "SR model loaded — model tile: ${inputW}x${inputH}")
            isLoaded = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SR model: ${e.message}")
            false
        }
    }

    /**
     * Run 2× super-resolution on [src].
     * Falls back to Lanczos (Bitmap.createScaledBitmap) if the model is not loaded.
     */
    fun upscale(src: Bitmap): Bitmap {
        if (!isLoaded) {
            Log.w(TAG, "SR model not loaded — using bilinear fallback")
            return Bitmap.createScaledBitmap(src, src.width * 2, src.height * 2, true)
        }

        Log.i(TAG, "SR upscale start: ${src.width}x${src.height}")

        // Tile the image to avoid OOM
        val tileSize = TILE_SIZE
        val overlap = TILE_OVERLAP
        val outW = src.width * 2
        val outH = src.height * 2
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)

        var y = 0
        while (y < src.height) {
            val tileH = minOf(tileSize, src.height - y)
            var x = 0
            while (x < src.width) {
                val tileW = minOf(tileSize, src.width - x)
                val tile = Bitmap.createBitmap(src, x, y, tileW, tileH)
                val srTile = runSingleTile(tile, tileW, tileH)
                canvas.drawBitmap(srTile, (x * 2).toFloat(), (y * 2).toFloat(), null)
                tile.recycle(); srTile.recycle()
                x += tileSize - overlap
            }
            y += tileSize - overlap
        }

        Log.i(TAG, "SR upscale done: ${result.width}x${result.height}")
        return result
    }

    private fun runSingleTile(tile: Bitmap, w: Int, h: Int): Bitmap {
        val interp = interpreter ?: return Bitmap.createScaledBitmap(tile, w * 2, h * 2, true)

        // Resize tile to model's expected input if needed
        val input: Bitmap = if (w == inputW && h == inputH) tile
        else Bitmap.createScaledBitmap(tile, inputW, inputH, true)

        val inputBuf = bitmapToInputBuffer(input)

        val outH2 = inputH * 2
        val outW2 = inputW * 2
        val outputBuf = ByteBuffer.allocateDirect(outH2 * outW2 * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        interp.run(inputBuf, outputBuf)

        val output = outputBufferToBitmap(outputBuf, outW2, outH2)

        // Scale back to tile * 2 if we had to resize input
        return if (output.width == w * 2 && output.height == h * 2) output
        else Bitmap.createScaledBitmap(output, w * 2, h * 2, true)
    }

    private fun bitmapToInputBuffer(bmp: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(bmp.width * bmp.height * 3 * 4)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        pixels.forEach { px ->
            buf.putFloat(((px shr 16) and 0xFF) / 255f)   // R
            buf.putFloat(((px shr 8) and 0xFF) / 255f)    // G
            buf.putFloat((px and 0xFF) / 255f)             // B
        }
        buf.rewind()
        return buf
    }

    private fun outputBufferToBitmap(buf: ByteBuffer, w: Int, h: Int): Bitmap {
        buf.rewind()
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            val r = (buf.float.coerceIn(0f, 1f) * 255).toInt()
            val g = (buf.float.coerceIn(0f, 1f) * 255).toInt()
            val b = (buf.float.coerceIn(0f, 1f) * 255).toInt()
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    fun release() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}
