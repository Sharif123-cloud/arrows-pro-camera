package com.arrowspro.camera.processing

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * ImageFuser — merges an aligned burst into a single high-dynamic-range image.
 *
 * Strategy:
 *  1. Mean-stack all frames to suppress random noise (σ_noise / √N reduction).
 *  2. Apply Mertens exposure fusion (OpenCV) to reconstruct a well-exposed result
 *     from the underexposed burst, recovering highlights without clipping.
 *
 * Why Mertens?  It works on a single-EV burst (unlike true multi-EV HDR merge)
 * because different regions of each frame carry different valid luminance info
 * even at the same exposure.
 */
class ImageFuser {

    companion object {
        private const val TAG = "ImageFuser"
    }

    /**
     * Fuse [frames] (already aligned) into one Bitmap.
     */
    fun fuse(frames: List<Bitmap>): Bitmap {
        if (frames.isEmpty()) throw IllegalArgumentException("No frames to fuse")
        if (frames.size == 1) return frames[0]

        Log.i(TAG, "Fusing ${frames.size} frames via Mertens exposure fusion")

        // Convert to 32F Mat list
        val mats = frames.map { bitmapToFloat32(it) }

        // Mertens fusion (built into OpenCV)
        val merger = Photo.createMergeMertens(1f, 1f, 1f)  // contrast, saturation, exposure weights
        val fused32f = Mat()
        merger.process(mats, fused32f)

        // Convert result 32F → 8U
        val fused8u = Mat()
        fused32f.convertTo(fused8u, CvType.CV_8UC3, 255.0)

        // Add mean-stack noise reduction as a pre-pass when we have ≥4 frames
        val result = if (frames.size >= 4) {
            val meanDenoised = meanStack(mats)
            // Blend Mertens (HDR) with mean stack (low-noise) 70/30
            val blended = Mat()
            Core.addWeighted(fused8u, 0.7, meanDenoised, 0.3, 0.0, blended)
            meanDenoised.release()
            blended
        } else {
            fused8u
        }

        // Convert BGRA → RGBA for Bitmap
        val rgba = Mat()
        Imgproc.cvtColor(result, rgba, Imgproc.COLOR_BGR2RGBA)

        val output = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, output)

        // Release native memory
        mats.forEach { it.release() }
        fused32f.release(); fused8u.release(); result.release(); rgba.release()

        Log.i(TAG, "Fusion complete: ${output.width}x${output.height}")
        return output
    }

    /** Mean-stack a list of 32F mats for noise reduction. */
    private fun meanStack(mats: List<Mat>): Mat {
        val acc = Mat.zeros(mats[0].size(), mats[0].type())
        mats.forEach { Core.add(acc, it, acc) }
        Core.divide(acc, Scalar(mats.size.toDouble(), mats.size.toDouble(), mats.size.toDouble()), acc)
        val out8u = Mat()
        acc.convertTo(out8u, CvType.CV_8UC3, 255.0)
        acc.release()
        return out8u
    }

    /** Convert Bitmap → 32F BGR Mat (range 0–1) expected by MergeMertens. */
    private fun bitmapToFloat32(bmp: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        val f32 = Mat()
        bgr.convertTo(f32, CvType.CV_32FC3, 1.0 / 255.0)
        bgr.release()
        return f32
    }
}
