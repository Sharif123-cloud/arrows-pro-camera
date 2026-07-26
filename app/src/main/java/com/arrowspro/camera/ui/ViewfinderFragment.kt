package com.arrowspro.camera.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.IBinder
import android.util.Size
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.arrowspro.camera.R
import com.arrowspro.camera.camera.BurstCaptureManager
import com.arrowspro.camera.camera.Camera2Controller
import com.arrowspro.camera.databinding.FragmentViewfinderBinding
import com.arrowspro.camera.processing.ProcessingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ViewfinderFragment : Fragment() {

    private var _binding: FragmentViewfinderBinding? = null
    private val binding get() = _binding!!

    // CRITICAL FIX: Camera2Controller MUST NOT be initialized at field-declaration time
    // because requireContext() / context is null until onAttach().  Calling requireContext()
    // during class-field initialization (before onAttach) throws:
    //   IllegalStateException: Fragment not attached to a context
    // which is the primary crash seen on first launch.
    private lateinit var camera: Camera2Controller
    private val burstManager = BurstCaptureManager()
    private var processingService: ProcessingService? = null
    private var isBound = false

    // Settings (loaded from SharedPreferences)
    private var burstCount = 15   // Reduced default for 3 GB Snapdragon 450 (OOM safety)
    private var evComp = -1.0f
    private var useSR = true

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as ProcessingService.ProcessingBinder
            processingService = b.getService().also { svc ->
                svc.onProgress = { progress, message ->
                    binding.progressBar.progress = progress
                    binding.tvStatus.text = message
                    binding.progressContainer.visibility = View.VISIBLE
                }
                svc.onComplete = { file ->
                    binding.progressContainer.visibility = View.GONE
                    binding.btnShutter.isEnabled = true
                    if (file != null) {
                        Toast.makeText(requireContext(), "Saved: ${file.name}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), "Processing failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            processingService = null
            isBound = false
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Safe: context is guaranteed non-null here
        camera = Camera2Controller(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentViewfinderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadSettings()

        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                startCamera(Surface(surface))
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        binding.btnShutter.setOnClickListener { onShutter() }

        binding.btnSettings.setOnClickListener {
            parentFragmentManager.commit {
                replace(R.id.fragment_container, SettingsFragment())
                addToBackStack(null)
            }
        }

        binding.sliderEV.addOnChangeListener { _, value, _ ->
            evComp = value
            camera.startPreview(value)
        }

        // Bind to processing service
        val intent = Intent(requireContext(), ProcessingService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startCamera(surface: Surface) {
        CoroutineScope(Dispatchers.Main).launch {
            val ok = camera.open(surface, Size(1920, 1080))
            if (!ok) {
                Toast.makeText(requireContext(), "Camera failed to open", Toast.LENGTH_SHORT).show()
            }
        }
        // Wire burst manager
        burstManager.onBurstComplete = { frames, isRaw ->
            if (isBound) {
                requireContext().startService(
                    Intent(requireContext(), ProcessingService::class.java)
                )
                processingService?.process(frames, useSR)
            }
        }
        camera.onBurstFrame = { image, isRaw ->
            burstManager.onFrame(image, isRaw)
        }
    }

    private fun onShutter() {
        binding.btnShutter.isEnabled = false
        burstManager.start(burstCount, camera.supportsRaw)
        CoroutineScope(Dispatchers.Main).launch {
            camera.captureBurst(burstCount, evComp)
        }
    }

    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        // Cap burst count at 15 for 3 GB device; 25 × 12 MP × 4 bytes = 1.2 GB → OOM
        burstCount = prefs.getInt("burst_count", 15).coerceAtMost(15)
        evComp = prefs.getFloat("ev_comp", -1.0f)
        useSR = prefs.getBoolean("use_sr", true)
    }

    override fun onDestroyView() {
        if (isBound) requireContext().unbindService(serviceConnection)
        camera.close()
        _binding = null
        super.onDestroyView()
    }
}
