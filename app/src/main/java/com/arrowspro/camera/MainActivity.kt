package com.arrowspro.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import com.arrowspro.camera.databinding.ActivityMainBinding
import com.arrowspro.camera.ui.ViewfinderFragment
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS = 100
        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // initLocal() is the correct API when using the Maven Central embedded AAR
        // (org.opencv:opencv:4.9.0). initDebug() targets the now-removed OpenCV Manager
        // app and silently fails on modern devices, causing SIGSEGV on the first OpenCV call.
        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV native library failed to load")
            Toast.makeText(
                this,
                "Camera engine failed to start — OpenCV load error. Please reinstall.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        Log.i(TAG, "OpenCV loaded successfully")

        // Keep screen on while the viewfinder is visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (allPermissionsGranted()) {
            launchViewfinder()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
        }
    }

    private fun launchViewfinder() {
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, ViewfinderFragment())
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && allPermissionsGranted()) {
            launchViewfinder()
        } else {
            Toast.makeText(
                this,
                "Camera and storage permissions are required.",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
