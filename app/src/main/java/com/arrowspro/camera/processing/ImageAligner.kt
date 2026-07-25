package com.arrowspro.camera.processing

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.ORB
import org.opencv.features2d.DescriptorMatcher
import org.opencv.imgproc.Imgproc

/**
 * ImageAligner — aligns a burst of Bitmaps to a reference frame.
 *
 * Algorithm:
 *  1. Convert each frame to greyscale.
 *  2. Detect ORB keypoints and compute descriptors.
 *  3. Match descriptors against the reference frame with BFMatcher + Hamming distance.
 *  4. Filter matches with Lowe's ratio test.
 *  5. Compute homography (RANSAC).
 *  6. Warp each frame to the reference coordinate space.
 */
class ImageAligner {

    companion object {
        private const val TAG = "ImageAligner"
        private const val MAX_FEATURES = 2000
        private const val RATIO_THRESH = 0.75f
        private const val RANSAC_THRESH = 4.0
    }

    private val orb: ORB = ORB.create(MAX_FEATURES)
    private val matcher: DescriptorMatcher =
        DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)

    /**
     * Align [frames] to frames[0] (the reference).
     * Returns a list of aligned Bitmaps (same length as input).
     */
    fun align(frames: List<Bitmap>): List<Bitmap> {
        if (frames.size < 2) return frames

        val reference = frames[0]
        val refMat = bitmapToGrey(reference)
        val (refKp, refDesc) = detectAndCompute(refMat)

        if (refDesc.empty()) {
            Log.w(TAG, "Reference frame has no descriptors — returning unaligned")
            return frames
        }

        return frames.mapIndexed { idx, bitmap ->
            if (idx == 0) {
                bitmap  // reference frame — no warp needed
            } else {
                alignSingle(bitmap, refKp, refDesc, reference.width, reference.height)
            }
        }
    }

    private fun alignSingle(
        src: Bitmap,
        refKp: MatOfKeyPoint,
        refDesc: Mat,
        outW: Int,
        outH: Int
    ): Bitmap {
        val srcMat = bitmapToGrey(src)
        val (srcKp, srcDesc) = detectAndCompute(srcMat)

        if (srcDesc.empty()) {
            Log.w(TAG, "Source frame has no descriptors — returning original")
            return src
        }

        // Match
        val matchesList = ArrayList<MatOfDMatch>()
        matcher.knnMatch(srcDesc, refDesc, matchesList, 2)

        // Ratio test
        val goodMatches = matchesList.filter {
            val arr = it.toArray()
            arr.size >= 2 && arr[0].distance < RATIO_THRESH * arr[1].distance
        }.flatMap { it.toList() }

        if (goodMatches.size < 10) {
            Log.w(TAG, "Too few matches (${goodMatches.size}) — returning original")
            return src
        }

        // Build point correspondences
        val srcPoints = MatOfPoint2f(*goodMatches.map { m ->
            srcKp.toArray()[m.queryIdx].pt
        }.toTypedArray())
        val refPoints = MatOfPoint2f(*goodMatches.map { m ->
            refKp.toArray()[m.trainIdx].pt
        }.toTypedArray())

        // Homography
        val homography = Calib3d.findHomography(srcPoints, refPoints, Calib3d.RANSAC, RANSAC_THRESH)

        if (homography.empty()) {
            Log.w(TAG, "Homography failed — returning original")
            return src
        }

        // Warp full colour frame
        val srcColour = Mat()
        Utils.bitmapToMat(src, srcColour)
        val warped = Mat()
        Imgproc.warpPerspective(
            srcColour, warped, homography,
            Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE
        )

        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warped, result)

        // Release native memory
        srcColour.release(); warped.release()
        return result
    }

    private fun bitmapToGrey(bmp: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val grey = Mat()
        Imgproc.cvtColor(rgba, grey, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        return grey
    }

    private fun detectAndCompute(grey: Mat): Pair<MatOfKeyPoint, Mat> {
        val kp = MatOfKeyPoint()
        val desc = Mat()
        orb.detectAndCompute(grey, Mat(), kp, desc)
        grey.release()
        return Pair(kp, desc)
    }

    fun release() {
        orb.also { } // OpenCV objects are GC'd
    }
}
