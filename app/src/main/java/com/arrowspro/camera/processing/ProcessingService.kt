package com.arrowspro.camera.processing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.arrowspro.camera.MainActivity
import com.arrowspro.camera.R
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * ProcessingService — runs the full computational photography pipeline in the background.
 *
 * Pipeline:
 *  1. Align frames (ORB + homography)
 *  2. Fuse frames (mean stack + Mertens)
 *  3. Optional super-resolution (ESRGAN TFLite)
 *  4. Save JPEG (95% quality) to DCIM/ArrowsProCamera/
 *
 * Reports progress 0–100 via [onProgress] callback (main thread).
 */
class ProcessingService : Service() {

    companion object {
        private const val TAG = "ProcessingService"
        private const val CHANNEL_ID = "arrows_pro_processing"
        private const val NOTIF_ID = 1001

        const val EXTRA_BURST_COUNT = "burst_count"
        const val EXTRA_USE_SR = "use_sr"
    }

    inner class ProcessingBinder : Binder() {
        fun getService(): ProcessingService = this@ProcessingService
    }

    private val binder = ProcessingBinder()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var aligner: ImageAligner
    private lateinit var fuser: ImageFuser
    private lateinit var sr: SuperResolution

    var onProgress: ((Int, String) -> Unit)? = null
    var onComplete: ((File?) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        aligner = ImageAligner()
        fuser   = ImageFuser()
        sr      = SuperResolution(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Initialising…", 0))
        return START_NOT_STICKY
    }

    /**
     * Process [frames] through the full pipeline.
     * @param useSuperResolution whether to run ESRGAN upscaling
     */
    fun process(frames: List<Bitmap>, useSuperResolution: Boolean) {
        scope.launch {
            try {
                report(5, "Aligning ${frames.size} frames…")

                // Step 1: Alignment
                val aligned = aligner.align(frames)
                report(35, "Fusing ${aligned.size} aligned frames…")

                // Step 2: Fusion
                val fused = fuser.fuse(aligned)
                report(65, if (useSuperResolution) "Running AI upscaling…" else "Saving image…")

                // Step 3: Super-resolution
                val finalBitmap = if (useSuperResolution) {
                    if (!sr.load()) {
                        Log.w(TAG, "SR model not loaded — skipping")
                        fused
                    } else {
                        val result = sr.upscale(fused)
                        fused.recycle()
                        result
                    }
                } else {
                    fused
                }
                report(90, "Saving JPEG…")

                // Step 4: Save
                val file = saveBitmap(finalBitmap)
                finalBitmap.recycle()

                report(100, "Done!")
                withContext(Dispatchers.Main) { onComplete?.invoke(file) }
                stopForeground(true)
                stopSelf()

            } catch (e: Exception) {
                Log.e(TAG, "Processing pipeline failed", e)
                withContext(Dispatchers.Main) { onComplete?.invoke(null) }
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private fun report(progress: Int, message: String) {
        Log.i(TAG, "[$progress%] $message")
        updateNotification(message, progress)
        scope.launch(Dispatchers.Main) { onProgress?.invoke(progress, message) }
    }

    private fun saveBitmap(bitmap: Bitmap): File {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(getExternalFilesDir(Environment.DIRECTORY_DCIM), "ArrowsProCamera")
        } else {
            @Suppress("DEPRECATION")
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "ArrowsProCamera")
        }
        dir.mkdirs()

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "APCam_$ts.jpg")

        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
        }
        Log.i(TAG, "Saved: ${file.absolutePath}")
        return file
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Photo Processing",
                NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "Arrows Pro Camera — computational photography pipeline"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(message: String, progress: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Arrows Pro Camera")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(message: String, progress: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(message, progress))
    }

    override fun onDestroy() {
        scope.cancel()
        sr.release()
        super.onDestroy()
    }
}
