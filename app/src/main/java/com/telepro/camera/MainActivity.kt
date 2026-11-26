package com.telepro.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var zoom = 1f
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF08090E.toInt()) }
        preview = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { text = "TELEPRO  •  AI CAMERA"; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; gravity = Gravity.CENTER; setPadding(8,16,8,12) }
        root.addView(status)
        val zoomRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(4,8,4,12) }
        listOf(1f, 2f, 3f, 5f).forEach { z ->
            val b = Button(this).apply { text = "${z.toInt()}x"; isAllCaps = false; setOnClickListener { setZoom(z) } }
            zoomRow.addView(b, LinearLayout.LayoutParams(0, 54, 1f))
        }
        root.addView(zoomRow)
        val shutter = Button(this).apply { text = "TAKE PHOTO"; textSize = 18f; isAllCaps = false; setOnClickListener { takePhoto() } }
        root.addView(shutter, LinearLayout.LayoutParams(-1, 72).apply { setMargins(28,4,28,24) })
        setContentView(root)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val p = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, capture)
                val max = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
                status.text = "TELEPRO  •  Hardware max ${"%.1f".format(max)}x"
                setZoom(1f)
            } catch (e: Exception) { status.text = "Camera could not start: ${e.localizedMessage}" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setZoom(requested: Float) {
        val c = camera ?: return
        val max = c.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        zoom = requested.coerceIn(1f, max)
        c.cameraControl.setZoomRatio(zoom)
        status.text = "TELEPRO  •  ${"%.1f".format(zoom)}x"
    }

    private fun takePhoto() {
        val imageCapture = capture ?: return
        val name = "TelePro_${System.currentTimeMillis()}"
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TelePro") }
        val output = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        imageCapture.takePicture(output, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) { Toast.makeText(this@MainActivity, "Saved to Pictures/TelePro", Toast.LENGTH_SHORT).show() }
            override fun onError(exception: ImageCaptureException) { Toast.makeText(this@MainActivity, "Capture failed: ${exception.message}", Toast.LENGTH_LONG).show() }
        })
    }
}
