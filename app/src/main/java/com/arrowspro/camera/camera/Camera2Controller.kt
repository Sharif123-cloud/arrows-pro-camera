package com.arrowspro.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Camera2Controller — wraps the Camera2 API for the Arrows F-02L.
 *
 * Key behaviours:
 *  • Prefers RAW_SENSOR output; falls back to YUV_420_888 when RAW is unsupported.
 *  • Locks PDAF (phase-detect AF) before burst.
 *  • Captures 15–32 under-exposed frames (-1.0 to -1.5 EV).
 */
class Camera2Controller(private val context: Context) {

    companion object {
        private const val TAG = "Camera2Controller"
        const val DEFAULT_BURST_COUNT = 25
        const val DEFAULT_EV_COMPENSATION = -1.0f   // stops below metered
    }

    // ── Threading ──────────────────────────────────────────────────────────────
    private val cameraThread = HandlerThread("CameraThread").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor: Executor = Executors.newSingleThreadExecutor()

    // ── Camera state ───────────────────────────────────────────────────────────
    private lateinit var cameraManager: CameraManager
    private var cameraId: String = ""
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null

    var supportsRaw: Boolean = false
        private set

    // ── Output sizes ───────────────────────────────────────────────────────────
    private var rawSize: Size? = null
    private var yuvSize: Size? = null

    // ── Preview surface ref ────────────────────────────────────────────────────
    private var previewSurfaceRef: Surface? = null

    // ── Image readers ──────────────────────────────────────────────────────────
    private var rawReader: ImageReader? = null
    private var yuvReader: ImageReader? = null

    // ── Callbacks ──────────────────────────────────────────────────────────────
    var onBurstFrame: ((android.media.Image, Boolean) -> Unit)? = null  // image, isRaw
    var onError: ((String) -> Unit)? = null

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Opens the back-facing camera and prepares surfaces.
     * Returns true on success.
     */
    @SuppressLint("MissingPermission")
    suspend fun open(previewSurface: Surface, preferredPreviewSize: Size = Size(1920, 1080)): Boolean =
        withContext(Dispatchers.IO) {
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraId = selectCamera() ?: run {
                onError?.invoke("No suitable camera found")
                return@withContext false
            }
            characteristics = cameraManager.getCameraCharacteristics(cameraId)
            previewSurfaceRef = previewSurface

            // Detect RAW capability
            val caps = characteristics!!.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: intArrayOf()
            supportsRaw =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps
            Log.i(TAG, "RAW supported: $supportsRaw")

            // Resolve output sizes
            val map = characteristics!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            if (supportsRaw) {
                rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?.maxByOrNull { it.width.toLong() * it.height }
                Log.i(TAG, "RAW size: $rawSize")
            }
            yuvSize = map.getOutputSizes(ImageFormat.YUV_420_888)
                ?.maxByOrNull { it.width.toLong() * it.height }

            // Create ImageReaders
            setupImageReaders()

            // Open camera device
            val opened = CompletableDeferred<Boolean>()
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    opened.complete(true)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    opened.complete(false)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    opened.complete(false)
                    onError?.invoke("Camera open error: $error")
                }
            }, cameraHandler)

            if (!opened.await()) return@withContext false

            // Create capture session
            createCaptureSession(previewSurface)
        }

    /**
     * Lock PDAF then fire a burst of [count] frames at [evCompensation] EV.
     * Calls [onBurstFrame] for each captured frame.
     */
    suspend fun captureBurst(
        count: Int = DEFAULT_BURST_COUNT,
        evCompensation: Float = DEFAULT_EV_COMPENSATION
    ) = withContext(Dispatchers.IO) {
        val device = cameraDevice ?: run {
            onError?.invoke("Camera not open")
            return@withContext
        }
        val session = captureSession ?: run {
            onError?.invoke("Session not ready")
            return@withContext
        }

        // Step 1: Lock PDAF
        lockAF(session, device)

        // Step 2: Choose capture surface
        val targetSurface = if (supportsRaw && rawReader != null) {
            rawReader!!.surface
        } else {
            yuvReader!!.surface
        }

        val evRange = characteristics!!.get(
            CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE
        ) ?: Range(-12, 12)
        val evStep = characteristics!!.get(
            CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP
        )?.toFloat() ?: 0.5f
        val evSteps = (evCompensation / evStep).toInt()
            .coerceIn(evRange.lower, evRange.upper)

        val requests = (0 until count).map {
            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(targetSurface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                if (supportsRaw) {
                    set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                }
                set(CaptureRequest.JPEG_QUALITY, 95)
            }.build()
        }

        session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                Log.d(TAG, "Frame captured ts=${result.get(CaptureResult.SENSOR_TIMESTAMP)}")
            }
        }, cameraHandler)
    }

    fun close() {
        captureSession?.close()
        cameraDevice?.close()
        rawReader?.close()
        yuvReader?.close()
        cameraThread.quitSafely()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    fun startPreview(ev: Float = 0f) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurfaceRef ?: return
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            applyAutoAE(this, ev)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        session.setRepeatingRequest(request.build(), null, cameraHandler)
    }

    private fun selectCamera(): String? {
        val manager = cameraManager
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return null
    }

    private fun setupImageReaders() {
        if (supportsRaw && rawSize != null) {
            rawReader = ImageReader.newInstance(
                rawSize!!.width, rawSize!!.height,
                ImageFormat.RAW_SENSOR, 4
            ).apply {
                setOnImageAvailableListener({ reader ->
                    reader.acquireLatestImage()?.let { img ->
                        onBurstFrame?.invoke(img, true)
                    }
                }, cameraHandler)
            }
        }

        val size = yuvSize ?: Size(4000, 3000)
        yuvReader = ImageReader.newInstance(
            size.width, size.height,
            ImageFormat.YUV_420_888, 4
        ).apply {
            setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.let { img ->
                    onBurstFrame?.invoke(img, false)
                }
            }, cameraHandler)
        }
    }

    private suspend fun createCaptureSession(previewSurface: Surface): Boolean {
        val device = cameraDevice ?: return false
        val surfaces = buildList {
            add(previewSurface)
            rawReader?.surface?.let { add(it) }
            yuvReader?.surface?.let { add(it) }
        }

        val created = CompletableDeferred<Boolean>()
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                created.complete(true)
                startPreviewInternal(session, device)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                created.complete(false)
                onError?.invoke("Session configuration failed")
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val configs = surfaces.map { OutputConfiguration(it) }
            device.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    configs, cameraExecutor, callback
                )
            )
        } else {
            @Suppress("DEPRECATION")
            device.createCaptureSession(surfaces, callback, cameraHandler)
        }
        return created.await()
    }

    private fun startPreviewInternal(session: CameraCaptureSession, device: CameraDevice) {
        val surface = previewSurfaceRef ?: return
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            applyAutoAE(this, 0f)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        session.setRepeatingRequest(request.build(), null, cameraHandler)
    }

    private fun applyAutoAE(builder: CaptureRequest.Builder, ev: Float) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        val evStep =
            characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat()
                ?: 0.5f
        val steps = (ev / evStep).toInt()
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps)
    }

    /**
     * Trigger AF and wait for lock (PDAF).
     */
    private suspend fun lockAF(session: CameraCaptureSession, device: CameraDevice) {
        val surface = previewSurfaceRef ?: return
        val locked = CompletableDeferred<Unit>()
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
        }
        session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                req: CaptureRequest,
                result: TotalCaptureResult
            ) {
                val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                ) {
                    locked.complete(Unit)
                }
            }
        }, cameraHandler)
        // Give AF up to 2 seconds
        withTimeoutOrNull(2000) { locked.await() }
    }
}
