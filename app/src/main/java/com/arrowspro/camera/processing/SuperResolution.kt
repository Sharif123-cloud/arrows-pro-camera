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
 * Delegates tried in order: GPU → CPU
 * (NNAPI removed: Snapdragon 450 NNAPI implementation is unreliable)
 */
class SuperResolution(private val context: Context) {

    companion object {
        private const val TAG = "SuperResolution"
        private const val MODEL_FILE = "esrgan.tflite"
        // 128px tiles on Snapdragon 450 (1.8 GHz A53, 3 GB RAM).
        // 256px caused OOM; 128px keeps peak memory ~64 MB per tile pass.
        private const val TILE_SIZE = 128
        private const val TILE_OVERLAP = 8
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var isLoaded = false

    fun load(): Boolean {
        return try {
            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
            val options = Interpreter.Options().apply { numThreads = 4 }

            // Attempt GPU delegate.
            // IMPORTANT: GpuDelegate() can throw UnsatisfiedLinkError (a java.lang.Error,
            // NOT an Exception) on devices whose driver does not expose the required
            // OpenCL/Vulkan symbols.  Catching only Exception misses this and crashes the app.
            // Adreno 506 on Snapdragon 450 is known to hit this path.
            try {
                val gpu = GpuDelegate()
                options.addDelegate(gpu)
                gpuDelegate = gpu
                Log.i(TAG, "GPU delegate enabled (Adreno 506)")
            } catch (e: Throwable) {   // catches Error and Exception — intentional
                Log.w(TAG, "GPU delegate unavailable (${e.javaClass.simpleName}): ${e.message}. Falling back to CPU.")
                gpuDelegate = null
            }

            // Do NOT enable NNAPI: the Snapdragon 450 NNAPI driver is incomplete
            // and causes Interpreter() to throw or produce corrupt output.
            options.useNNAPI = false

            interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "SR model loaded — delegate: ${if (gpuDelegate != null) "GPU" else "CPU"}")
            isLoaded = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SR model: ${e.message}")
            false
        }
    }

    /**
     * Run 2× super-resolution on [src].
     * Falls back to bilinear scaling if the model is not loaded.
     */
    fun upscale(src: Bitmap): Bitmap {
        if (!isLoaded) {
            Log.w(TAG, "SR model not loaded — using bilinear fallback")
            return Bitmap.createScaledBitmap(src, src.width * 2, src.height * 2, true)
        }

        Log.i(TAG, "SR upscale: ${src.width}x${src.height}")

        val outW = src.width * 2
        val outH = src.height * 2
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)

        var y = 0
        while (y < src.height) {
            val tileH = minOf(TILE_SIZE, src.height - y)
            var x = 0
            while (x < src.width) {
                val tileW = minOf(TILE_SIZE, src.width - x)
                val tile = Bitmap.createBitmap(src, x, y, tileW, tileH)
                val srTile = runSingleTile(tile, tileW, tileH)
                canvas.drawBitmap(srTile, (x * 2).toFloat(), (y * 2).toFloat(), null)
                tile.recycle()
                srTile.recycle()
                x += TILE_SIZE - TILE_OVERLAP
            }
            y += TILE_SIZE - TILE_OVERLAP
        }

        Log.i(TAG, "SR upscale done: ${result.width}x${result.height}")
        return result
    }

    private fun runSingleTile(tile: Bitmap, w: Int, h: Int): Bitmap {
        val inputSize = w * h * 3
        val inputBuf = ByteBuffer.allocateDirect(inputSize * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        val pixels = IntArray(w * h)
        tile.getPixels(pixels, 0, w, 0, 0, w, h)
        for (px in pixels) {
            inputBuf.putFloat(((px shr 16) and 0xFF) / 255f)
            inputBuf.putFloat(((px shr 8)  and 0xFF) / 255f)
            inputBuf.putFloat((px          and 0xFF) / 255f)
        }
        inputBuf.rewind()

        val outW = w * 2; val outH = h * 2
        val outputBuf = ByteBuffer.allocateDirect(outW * outH * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        try {
            interpreter!!.run(inputBuf, outputBuf)
        } catch (e: Exception) {
            Log.w(TAG, "SR inference failed for tile: ${e.message} — using bilinear")
            return Bitmap.createScaledBitmap(tile, outW, outH, true)
        }

        outputBuf.rewind()
        val outPixels = IntArray(outW * outH)
        for (i in outPixels.indices) {
            val r = (outputBuf.float * 255).toInt().coerceIn(0, 255)
            val g = (outputBuf.float * 255).toInt().coerceIn(0, 255)
            val b = (outputBuf.float * 255).toInt().coerceIn(0, 255)
            outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also {
            it.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
        }
    }

    fun release() {
        interpreter?.close()
        gpuDelegate?.close()
        interpreter = null
        gpuDelegate = null
        isLoaded = false
    }
}
