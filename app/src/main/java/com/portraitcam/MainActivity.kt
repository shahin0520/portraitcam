package com.portraitcam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.graphics.Color
import android.hardware.camera2.CameraCaptureSession
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private val YELLOW = 0xFFFFD60A.toInt()
    private val DIM = 0xB3FFFFFF.toInt()
    private val filterNames = listOf("NONE", "VIVID", "WARM", "COOL", "MONO", "FILM")
    private val qualNames = listOf("FHD", "HD", "UHD")

    private lateinit var preview: PreviewView
    private lateinit var ivFreeze: ImageView
    private lateinit var grid: View
    private lateinit var tvFlash: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvHdr: TextView
    private lateinit var tvNr: TextView
    private lateinit var tvAi: TextView
    private lateinit var tvEnh: TextView
    private lateinit var brightSeek: SeekBar
    private lateinit var brightRow: View
    private lateinit var detailSeek: SeekBar
    private lateinit var detailRow: View
    private lateinit var tvGrid: TextView
    private lateinit var tvFilter: TextView
    private lateinit var tvRes: TextView
    private lateinit var tvPhone: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvStab: TextView
    private lateinit var tvQual: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvBusy: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvNight: TextView
    private lateinit var tvPhoto: TextView
    private lateinit var tvPortrait: TextView
    private lateinit var tvVideo: TextView
    private lateinit var gallery: ImageView
    private lateinit var blurSeek: SeekBar
    private lateinit var beautySeek: SeekBar
    private lateinit var blurRow: View
    private lateinit var beautyRow: View
    private lateinit var shutter: View
    private lateinit var chips: List<Pair<TextView, Float>>

    private var provider: ProcessCameraProvider? = null
    private var extMgr: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var stateLd: LiveData<CameraState>? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private var flash = ImageCapture.FLASH_MODE_OFF
    private var mode = 1            // 0 NIGHT, 1 PHOTO, 2 PORTRAIT, 3 VIDEO
    private var hdrMode = 1          // 0 OFF, 1 AUTO, 2 ON
    private var nrMode = 1           // 0 OFF, 1 ON, 2 MAX
    private var aiMode = 2           // 0 OFF, 1 FAST, 2 HQ
    private val sr by lazy { SuperRes(this) }
    private var ladder: List<Pair<Boolean, Int>> = listOf(Pair(false, 0))
    private var ladderIdx = 0
    private var activeStab = "OFF"
    private var fpsLow = 0
    @Volatile private var measuredFps = 0f
    @Volatile private var fpsT0 = 0L
    @Volatile private var fpsCount = 0
    private var gridOn = false
    private var timerSec = 0
    private var filterIdx = 0
    private var resIdx = 1
    private var useNative = false
    private var nativeMode = false
    private var fps60 = true
    private var stabOn = true
    private var videoQ = 0
    private var busy = false
    private var lastUri: Uri? = null
    private val io = Executors.newSingleThreadExecutor()          // ক্যামেরার ছবি তোলা
    private val procExec = Executors.newSingleThreadExecutor()    // ব্যাকগ্রাউন্ডে ছবি প্রসেস
    private val miscExec = Executors.newSingleThreadExecutor()
    private val jobsPending = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var jobStatus = ""
    private lateinit var tvJobs: TextView
    private val main = Handler(Looper.getMainLooper())

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (cameraGranted()) {
            if (provider == null) startCamera()
        } else {
            Toast.makeText(this, "ক্যামেরা পারমিশন লাগবে", Toast.LENGTH_LONG).show()
        }
    }

    private fun cameraGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun audioGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            initUi()
        } catch (e: Throwable) {
            showFatal(e)
        }
    }

    private fun showFatal(e: Throwable) {
        val tv = TextView(this)
        tv.setTextColor(Color.WHITE)
        tv.textSize = 12f
        tv.setPadding(24, 64, 24, 24)
        tv.setTextIsSelectable(true)
        tv.text = "অ্যাপ চালু করতে সমস্যা হয়েছে। এই লেখার স্ক্রিনশট পাঠাও:\n\n" + android.util.Log.getStackTraceString(e)
        val sc = android.widget.ScrollView(this)
        sc.setBackgroundColor(Color.BLACK)
        sc.addView(tv)
        setContentView(sc)
    }

    private fun initUi() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        preview.scaleType = PreviewView.ScaleType.FIT_START
        ivFreeze = findViewById(R.id.ivFreeze)
        grid = findViewById(R.id.grid)
        tvFlash = findViewById(R.id.tvFlash)
        tvTimer = findViewById(R.id.tvTimer)
        tvHdr = findViewById(R.id.tvHdr)
        tvNr = findViewById(R.id.tvNr)
        tvAi = findViewById(R.id.tvAi)
        tvEnh = findViewById(R.id.tvEnh)
        brightSeek = findViewById(R.id.brightSeek)
        brightRow = findViewById(R.id.brightRow)
        detailSeek = findViewById(R.id.detailSeek)
        detailRow = findViewById(R.id.detailRow)
        tvGrid = findViewById(R.id.tvGrid)
        tvFilter = findViewById(R.id.tvFilter)
        tvRes = findViewById(R.id.tvRes)
        tvPhone = findViewById(R.id.tvPhone)
        tvFps = findViewById(R.id.tvFps)
        tvStab = findViewById(R.id.tvStab)
        tvQual = findViewById(R.id.tvQual)
        tvInfo = findViewById(R.id.tvInfo)
        tvInfo.text = "DEPTH\nTEST"
        tvInfo.setOnClickListener { showCameraReport() }
        tvInfo.visibility = View.GONE
        tvBusy = findViewById(R.id.tvBusy)
        tvCount = findViewById(R.id.tvCount)
        tvNight = findViewById(R.id.tvNight)
        tvPhoto = findViewById(R.id.tvPhoto)
        tvPortrait = findViewById(R.id.tvPortrait)
        tvVideo = findViewById(R.id.tvVideo)
        tvVideo.visibility = View.GONE
        tvJobs = findViewById(R.id.tvJobs)
        gallery = findViewById(R.id.gallery)
        blurSeek = findViewById(R.id.blurSeek)
        beautySeek = findViewById(R.id.beautySeek)
        blurRow = findViewById(R.id.blurRow)
        beautyRow = findViewById(R.id.beautyRow)
        shutter = findViewById(R.id.shutter)
        gallery.clipToOutline = true
        gallery.outlineProvider = ViewOutlineProvider.BACKGROUND

        chips = listOf(
            findViewById<TextView>(R.id.z05) to 0.5f,
            findViewById<TextView>(R.id.z1) to 1f,
            findViewById<TextView>(R.id.z2) to 2f,
            findViewById<TextView>(R.id.z3) to 3f,
            findViewById<TextView>(R.id.z5) to 5f
        )
        chips.forEach { (v, r) -> v.setOnClickListener { setZoom(r) } }

        tvNight.setOnClickListener { setMode(0) }
        tvPhoto.setOnClickListener { setMode(1) }
        tvPortrait.setOnClickListener { setMode(2) }
        tvVideo.setOnClickListener { setMode(3) }

        tvFlash.setOnClickListener {
            if (mode == 3) {
                flash = if (flash == ImageCapture.FLASH_MODE_ON) ImageCapture.FLASH_MODE_OFF else ImageCapture.FLASH_MODE_ON
                camera?.cameraControl?.enableTorch(flash == ImageCapture.FLASH_MODE_ON)
            } else {
                flash = when (flash) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    else -> ImageCapture.FLASH_MODE_OFF
                }
                imageCapture?.flashMode = flash
            }
            updateTop()
        }
        tvTimer.setOnClickListener {
            timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
            updateTop()
        }
        tvHdr.setOnClickListener { hdrMode = (hdrMode + 1) % 3; updateTop(); bind() }
        tvNr.setOnClickListener { nrMode = (nrMode + 1) % 3; updateTop(); bind() }
        tvAi.setOnClickListener { aiMode = (aiMode + 1) % 3; updateTop() }
        tvEnh.setOnClickListener { if (recording == null) pickImage.launch("image/*") }
        tvGrid.setOnClickListener { gridOn = !gridOn; updateTop() }
        tvFilter.setOnClickListener { filterIdx = (filterIdx + 1) % filterNames.size; updateTop() }
        tvRes.setOnClickListener { resIdx = (resIdx + 1) % 3; updateTop(); bind() }
        tvPhone.setOnClickListener { useNative = !useNative; updateTop(); bind() }
        tvFps.setOnClickListener {
            if (recording == null) { fps60 = !fps60; ladderIdx = 0; updateTop(); bind() }
        }
        tvStab.setOnClickListener {
            if (recording == null) { stabOn = !stabOn; ladderIdx = 0; updateTop(); bind() }
        }
        tvQual.setOnClickListener {
            if (recording == null) { videoQ = (videoQ + 1) % qualNames.size; updateTop(); bind() }
        }
        findViewById<View>(R.id.flip).setOnClickListener {
            if (recording != null) return@setOnClickListener
            lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
            bind()
        }
        shutter.setOnClickListener { onShutter() }
        gallery.setOnClickListener {
            lastUri?.let {
                val type = contentResolver.getType(it) ?: "image/*"
                startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(it, type)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }

        val sgd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val z = camera?.cameraInfo?.zoomState?.value ?: return true
                setZoom(z.zoomRatio * d.scaleFactor)
                return true
            }
        })
        var downX = 0f
        var multi = false
        preview.setOnTouchListener { v, e ->
            sgd.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; multi = false }
                MotionEvent.ACTION_POINTER_DOWN -> multi = true
                MotionEvent.ACTION_UP -> {
                    if (!multi && !sgd.isInProgress) {
                        val dx = e.x - downX
                        if (abs(dx) > 220f) {
                            setMode((mode + (if (dx < 0) 1 else -1)).coerceIn(0, 2))
                        } else if (e.eventTime - e.downTime < 250) {
                            val pt = preview.meteringPointFactory.createPoint(e.x, e.y)
                            camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                            v.performClick()
                        }
                    }
                }
            }
            true
        }

        updateTop()
        updateModeUi()
        main.postDelayed(fpsTick, 1000)
        miscExec.execute { Pipeline.warmUp() }
        if (cameraGranted()) {
            startCamera()
            if (!audioGranted()) permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        } else {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- UI ----------

    private fun setMode(m: Int) {
        if (m == mode || recording != null) return
        if (mode == 3) camera?.cameraControl?.enableTorch(false)
        mode = m
        if (mode == 3 && flash == ImageCapture.FLASH_MODE_AUTO) flash = ImageCapture.FLASH_MODE_OFF
        ladderIdx = 0
        updateModeUi()
        updateTop()
        bind()
    }

    private fun updateModeUi() {
        tvNight.setTextColor(if (mode == 0) YELLOW else DIM)
        tvPhoto.setTextColor(if (mode == 1) YELLOW else DIM)
        tvPortrait.setTextColor(if (mode == 2) YELLOW else DIM)
        tvVideo.setTextColor(if (mode == 3) YELLOW else DIM)
        blurRow.visibility = if (mode == 2 && !nativeMode) View.VISIBLE else View.GONE
        beautyRow.visibility = if (mode == 1 || mode == 2) View.VISIBLE else View.GONE
        val v = mode == 3
        listOf(tvHdr, tvNr, tvAi, tvEnh, tvFilter, tvRes, tvPhone).forEach { it.visibility = if (v) View.GONE else View.VISIBLE }
        brightRow.visibility = if (v) View.GONE else View.VISIBLE
        detailRow.visibility = if (v) View.GONE else View.VISIBLE
        listOf(tvFps, tvStab, tvQual).forEach { it.visibility = if (v) View.VISIBLE else View.GONE }
        shutter.setBackgroundResource(
            if (v) (if (recording != null) R.drawable.shutter_stop else R.drawable.shutter_video)
            else R.drawable.shutter
        )
    }

    private fun tint(v: TextView, on: Boolean) = v.setTextColor(if (on) YELLOW else Color.WHITE)

    private fun updateTop() {
        tvFlash.text = "FLASH\n" + when {
            mode == 3 -> if (flash == ImageCapture.FLASH_MODE_ON) "TORCH" else "OFF"
            flash == ImageCapture.FLASH_MODE_AUTO -> "AUTO"
            flash == ImageCapture.FLASH_MODE_ON -> "ON"
            else -> "OFF"
        }
        tvTimer.text = "TIMER\n" + (if (timerSec == 0) "OFF" else "${timerSec}s")
        tvHdr.text = "HDR\n" + listOf("OFF", "AUTO", "ON")[hdrMode]
        tvNr.text = "NR\n" + listOf("OFF", "ON", "MAX")[nrMode]
        tvAi.text = "AI ZOOM\n" + listOf("OFF", "FAST", "HQ")[aiMode]
        tvEnh.text = "ENHANCE\nGALLERY"
        tvGrid.text = "GRID\n" + (if (gridOn) "ON" else "OFF")
        tvFilter.text = "FILTER\n" + filterNames[filterIdx]
        tvRes.text = "RES\n" + listOf("12MP", "16MP", "MAX")[resIdx]
        tvPhone.text = "PHONE\n" + (if (useNative) "ON" else "OFF")
        tvFps.text = "FPS\n" + (if (fps60) "60" else "30")
        tvStab.text = "STAB\n" + (if (stabOn) activeStab else "OFF")
        tvQual.text = "QUALITY\n" + qualNames[videoQ]
        tint(tvFlash, flash != ImageCapture.FLASH_MODE_OFF)
        tint(tvTimer, timerSec != 0)
        tint(tvHdr, hdrMode != 0)
        tint(tvNr, nrMode != 0)
        tint(tvAi, aiMode != 0)
        tint(tvEnh, false)
        tint(tvGrid, gridOn)
        tint(tvFilter, filterIdx != 0)
        tint(tvRes, resIdx == 2)
        tint(tvPhone, useNative)
        tint(tvFps, fps60)
        tint(tvStab, stabOn)
        tint(tvQual, false)
        grid.visibility = if (gridOn) View.VISIBLE else View.GONE
    }

    private fun fmt(r: Float): String {
        val t = String.format(Locale.US, "%.1f", r)
        return (if (t.endsWith(".0")) t.dropLast(2) else t) + "x"
    }

    private fun updateZoomUi(ratio: Float) {
        var best: TextView? = null
        var bd = 99f
        chips.forEach { (v, r) ->
            if (v.visibility == View.VISIBLE) {
                val d = abs(ratio - r)
                if (d < bd) { bd = d; best = v }
            }
        }
        chips.forEach { (v, r) ->
            val on = v === best
            v.setTextColor(if (on) YELLOW else Color.WHITE)
            v.text = if (on) fmt(ratio) else if (r < 1f) ".5" else "${r.toInt()}x"
            v.textSize = if (on) 14f else 13f
        }
    }

    private fun setBusy(msg: String?) {
        if (msg == null) {
            tvBusy.visibility = View.GONE
        } else {
            tvBusy.text = msg
            tvBusy.visibility = View.VISIBLE
        }
    }

    // ---------- Camera ----------

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = f.get()
            provider = p
            try {
                val ef = ExtensionsManager.getInstanceAsync(this, p)
                ef.addListener({
                    try { extMgr = ef.get() } catch (e: Exception) { extMgr = null }
                    bind()
                }, ContextCompat.getMainExecutor(this))
            } catch (e: Throwable) {
                bind()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        val base = CameraSelector.Builder().requireLensFacing(lens).build()
        var ok = false
        nativeMode = false
        if (mode == 3) {
            imageCapture = null
            ok = bindVideo(p, base)
        } else {
            videoCapture = null
            val portrait = mode == 2
            val extMode = if (portrait) ExtensionMode.BOKEH else ExtensionMode.HDR
            val wantNative = useNative && mode != 0 && (portrait || hdrMode != 0)
            val em = extMgr
            if (wantNative && em != null) {
                try {
                    if (em.isExtensionAvailable(base, extMode)) {
                        ok = bindUse(p, em.getExtensionEnabledCameraSelector(base, extMode), true)
                        nativeMode = ok
                    }
                } catch (e: Throwable) {
                    ok = false
                    nativeMode = false
                }
            }
            if (!ok) ok = bindUse(p, base, false)
        }
        if (!ok) {
            Toast.makeText(this, "ক্যামেরা চালু হয়নি", Toast.LENGTH_LONG).show()
            return
        }
        updateModeUi()
        preview.post {
            val ratio = if (mode == 3) 16f / 9f else 4f / 3f
            val lp = grid.layoutParams
            lp.height = (preview.width * ratio).toInt()
            grid.layoutParams = lp
            val z = camera?.cameraInfo?.zoomState?.value
            val mn = z?.minZoomRatio ?: 1f
            val mx = z?.maxZoomRatio ?: 1f
            chips.forEach { (v, r) ->
                v.visibility = when {
                    r < 1f -> if (mn < 0.9f) View.VISIBLE else View.GONE
                    r == 1f -> View.VISIBLE
                    else -> if (mx >= r) View.VISIBLE else View.GONE
                }
            }
            updateZoomUi(1f)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun bindUse(p: ProcessCameraProvider, sel: CameraSelector, ext: Boolean): Boolean {
        return try {
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val b = ImageCapture.Builder().setFlashMode(flash)
            if (!ext) {
                val target = when (resIdx) {
                    0 -> Size(4000, 3000)
                    1 -> Size(4640, 3480)
                    else -> Size(8200, 6200)
                }
                b.setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                b.setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                )
                // ক্যামেরার নিজের ডিফল্ট নয়েজ রিডাকশন রাখা হলো (ডিটেইল বাঁচাতে)
                if (false) try {
                    val infos = sel.filter(p.availableCameraInfos)
                    if (infos.isNotEmpty()) {
                        val c2 = Camera2CameraInfo.from(infos[0])
                        val nm = c2.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                        val wantNr = if (nrMode == 0) CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
                        else CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
                        val useNr = if (nm != null && nm.contains(wantNr)) wantNr
                        else if (nm != null && nm.contains(CameraMetadata.NOISE_REDUCTION_MODE_FAST)) CameraMetadata.NOISE_REDUCTION_MODE_FAST
                        else -1
                        if (useNr >= 0) {
                            Camera2Interop.Extender(b).setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, useNr)
                        }
                    }
                } catch (e: Throwable) { }
            }
            imageCapture = b.build()
            p.unbindAll()
            camera = p.bindToLifecycle(this, sel, pv, imageCapture)
            true
        } catch (e: Throwable) {
            false
        }
    }

    // ---------- ক্যামেরা/ডেপথ পরীক্ষা ----------

    private fun showCameraReport() {
        setBusy("ফোনের ক্যামেরা পরীক্ষা হচ্ছে…")
        miscExec.execute {
            val text = try { buildCameraReport() } catch (e: Throwable) { "Error: ${e.message}" }
            runOnUiThread {
                setBusy(null)
                AlertDialog.Builder(this)
                    .setTitle("Camera / Depth report")
                    .setMessage(text)
                    .setPositiveButton("Copy") { _, _ ->
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("camera-report", text))
                        Toast.makeText(this, "কপি হয়েছে। চ্যাটে পেস্ট করো", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    private fun buildCameraReport(): String {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        sb.append("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Camera IDs: ${cm.cameraIdList.joinToString(",")}\n")
        if (Build.VERSION.SDK_INT >= 30) {
            try { sb.append("Concurrent sets: ${cm.concurrentCameraIds}\n") } catch (e: Throwable) { }
        }
        for (id in cm.cameraIdList) {
            try {
                val c = cm.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    else -> "external"
                }
                val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    else -> "?"
                }
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
                val capNames = caps.joinToString(",") {
                    when (it) {
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI"
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST"
                        else -> it.toString()
                    }
                }
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString("/") ?: "?"
                val size = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val fmts = map?.outputFormats?.toList() ?: emptyList()
                val depth16 = fmts.contains(ImageFormat.DEPTH16)
                val cloud = fmts.contains(ImageFormat.DEPTH_POINT_CLOUD)
                val depthJpeg = Build.VERSION.SDK_INT >= 29 && fmts.contains(ImageFormat.DEPTH_JPEG)
                val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList() ?: emptyList()
                val eis = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList() ?: emptyList()
                val fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.filter { it.upper >= 60 }?.joinToString(",") ?: ""
                sb.append("\n[ID $id] $facing, level=$level\n")
                sb.append("  caps=$capNames\n")
                sb.append("  sensor=${size?.width}x${size?.height}, focal=$focal\n")
                sb.append("  DEPTH16=$depth16, POINT_CLOUD=$cloud, DEPTH_JPEG=$depthJpeg\n")
                sb.append("  OIS_modes=$ois, EIS_modes=$eis, fps60_ranges=[$fps]\n")
                if (Build.VERSION.SDK_INT >= 28) {
                    val phys = c.physicalCameraIds
                    if (phys.isNotEmpty()) sb.append("  physical=${phys.joinToString(",")}\n")
                }
            } catch (e: Throwable) {
                sb.append("\n[ID $id] error: ${e.message}\n")
            }
        }
        return sb.toString()
    }

    // ---------- ভিডিও (৬০fps + OIS/EIS, নিজে থেকে সেরা কম্বিনেশন খোঁজে) ----------

    private val fpsTick = object : Runnable {
        override fun run() {
            if (mode == 3) {
                val m = measuredFps.toInt()
                tvFps.text = "FPS\n" + (if (fps60) "60" else "30") + (if (m > 0) " ($m)" else "")
                if (recording == null && fps60 && m > 0) {
                    if (m < 50) {
                        fpsLow++
                        if (fpsLow >= 2) { fpsLow = 0; advanceLadder() }
                    } else {
                        fpsLow = 0
                    }
                }
            }
            main.postDelayed(this, 1000)
        }
    }

    private fun advanceLadder() {
        if (recording != null || mode != 3) return
        if (ladderIdx + 1 < ladder.size) {
            ladderIdx++
            Toast.makeText(this, "৬০fps-এর জন্য স্টেবিলাইজেশনের অন্য কম্বিনেশন চেষ্টা হচ্ছে…", Toast.LENGTH_LONG).show()
            bind()
        } else if (fps60) {
            if (videoQ == 2) {
                videoQ = 0
                ladderIdx = 0
                Toast.makeText(this, "UHD-তে ৬০fps চলে না, FHD করা হলো", Toast.LENGTH_LONG).show()
            } else {
                fps60 = false
                ladderIdx = 0
                Toast.makeText(this, "এই ফোন ৬০fps দিচ্ছে না, ৩০fps করা হলো", Toast.LENGTH_LONG).show()
            }
            updateTop()
            bind()
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun bindVideo(p: ProcessCameraProvider, base: CameraSelector): Boolean {
        return try {
            var fpsRange: Range<Int>? = null
            var oisSup = false
            val eisCand = ArrayList<Int>()
            val infos = base.filter(p.availableCameraInfos)
            if (infos.isNotEmpty()) {
                val c2 = Camera2CameraInfo.from(infos[0])
                val ranges = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                if (ranges != null) fpsRange = ranges.filter { it.upper >= 60 }.maxByOrNull { it.lower }
                val om = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                oisSup = om?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
                val vm = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                if (vm != null) {
                    if (vm.contains(2)) eisCand.add(2)   // 2 = PREVIEW_STABILIZATION (নতুন EIS)
                    if (vm.contains(1)) eisCand.add(1)   // 1 = ON (সাধারণ EIS)
                }
            }
            // চেষ্টার তালিকা: সেরা থেকে সাধারণ
            val lad = ArrayList<Pair<Boolean, Int>>()
            if (stabOn) {
                for (e in eisCand) lad.add(Pair(oisSup, e))
                if (oisSup) lad.add(Pair(true, 0))
                if (oisSup) for (e in eisCand) lad.add(Pair(false, e))
            }
            lad.add(Pair(false, 0))
            ladder = lad.distinct()
            if (ladderIdx >= ladder.size) ladderIdx = ladder.size - 1
            val useOis = ladder[ladderIdx].first
            val eisMode = ladder[ladderIdx].second
            activeStab = when {
                useOis && eisMode != 0 -> "OIS+EIS"
                useOis -> "OIS"
                eisMode != 0 -> "EIS"
                else -> "OFF"
            }

            val qualities = when (videoQ) {
                2 -> listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                1 -> listOf(Quality.HD, Quality.SD)
                else -> listOf(Quality.FHD, Quality.HD, Quality.SD)
            }
            val use60 = fps60 && fpsRange != null
            val bitrate = when (videoQ) {
                2 -> if (use60) 60_000_000 else 40_000_000
                1 -> if (use60) 14_000_000 else 9_000_000
                else -> if (use60) 26_000_000 else 16_000_000
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(qualities, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                )
                .setTargetVideoEncodingBitRate(bitrate)
                .build()
            val vb = VideoCapture.Builder(recorder)
            if (use60) vb.setTargetFrameRate(fpsRange!!)
            val ex = Camera2Interop.Extender(vb)
            if (use60) ex.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange!!)
            if (useOis) ex.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
            if (eisMode != 0) ex.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eisMode)
            // আসল fps মাপা
            fpsT0 = 0L
            fpsCount = 0
            measuredFps = 0f
            fpsLow = 0
            ex.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                    if (fpsT0 == 0L) {
                        fpsT0 = ts
                        fpsCount = 0
                    } else {
                        fpsCount++
                        val dt = ts - fpsT0
                        if (dt >= 1_000_000_000L) {
                            measuredFps = fpsCount * 1_000_000_000f / dt
                            fpsT0 = ts
                            fpsCount = 0
                        }
                    }
                }
            })
            val vc = vb.build()
            videoCapture = vc
            val pv = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build()
                )
                .build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            p.unbindAll()
            val cam = p.bindToLifecycle(this, base, pv, vc)
            camera = cam
            cam.cameraControl.enableTorch(flash == ImageCapture.FLASH_MODE_ON)

            stateLd?.removeObservers(this)
            val ld = cam.cameraInfo.cameraState
            stateLd = ld
            ld.observe(this) { st ->
                if (st.error != null && mode == 3 && recording == null) {
                    main.post { advanceLadder() }
                }
            }
            updateTop()
            val info = "৬০fps: " + (if (fpsRange != null) "হ্যাঁ" else "না") +
                ", OIS: " + (if (oisSup) "হ্যাঁ" else "না") +
                ", EIS: " + (if (eisCand.isNotEmpty()) "হ্যাঁ" else "না") +
                " | এখন চালু: " + activeStab
            Toast.makeText(this, info, Toast.LENGTH_LONG).show()
            true
        } catch (e: Throwable) {
            Toast.makeText(this, "ভিডিও চালু হয়নি: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun toggleRecord() {
        val vc = videoCapture ?: return
        val rec = recording
        if (rec != null) {
            rec.stop()
            return
        }
        val name = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PortraitCam")
        }
        val outOpts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv).build()
        var pr = vc.output.prepareRecording(this, outOpts)
        if (audioGranted()) pr = pr.withAudioEnabled()
        recording = pr.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> {
                    updateModeUi()
                    setBusy("● 00:00")
                }
                is VideoRecordEvent.Status -> {
                    val s = ev.recordingStats.recordedDurationNanos / 1_000_000_000L
                    setBusy(String.format(Locale.US, "● %02d:%02d", s / 60, s % 60))
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    setBusy(null)
                    updateModeUi()
                    if (ev.hasError()) {
                        Toast.makeText(this, "ভিডিও সেভ হয়নি (কোড ${ev.error})", Toast.LENGTH_LONG).show()
                    } else {
                        val uri = ev.outputResults.outputUri
                        lastUri = uri
                        try {
                            gallery.setImageBitmap(contentResolver.loadThumbnail(uri, Size(160, 160), null))
                        } catch (e: Throwable) { }
                        Toast.makeText(this, "ভিডিও সেভ হয়েছে", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun setZoom(r: Float) {
        val z = camera?.cameraInfo?.zoomState?.value ?: return
        val v = r.coerceIn(z.minZoomRatio, z.maxZoomRatio)
        camera?.cameraControl?.setZoomRatio(v)
        updateZoomUi(v)
    }

    // ---------- ছবি তোলা ----------

    private fun onShutter() {
        if (busy) return
        if (mode != 3 && jobsPending.get() >= 3) {
            Toast.makeText(this, "আগের ছবিগুলো প্রসেস হচ্ছে, একটু অপেক্ষা করো", Toast.LENGTH_SHORT).show()
            return
        }
        if (mode == 3) {
            if (recording != null || timerSec == 0) {
                toggleRecord()
            } else {
                busy = true
                countdown(timerSec)
            }
            return
        }
        if (timerSec > 0) {
            busy = true
            countdown(timerSec)
        } else {
            startCapture()
        }
    }

    private fun countdown(n: Int) {
        if (n <= 0) {
            tvCount.visibility = View.GONE
            busy = false
            if (mode == 3) toggleRecord() else startCapture()
            return
        }
        tvCount.visibility = View.VISIBLE
        tvCount.text = n.toString()
        main.postDelayed({ countdown(n - 1) }, 1000)
    }

    private fun fail(msg: String?) {
        busy = false
        setBusy(null)
        Toast.makeText(this, "ছবি তোলা যায়নি: $msg", Toast.LENGTH_LONG).show()
    }

    // দৃশ্যের গড় আলো আর অন্ধকার/উজ্জ্বল অংশের শতাংশ
    private fun sceneStats(): Triple<Int, Float, Float>? {
        return try {
            val b = preview.bitmap ?: return null
            val sw = b.width
            val rh = min(b.height, sw * 4 / 3)
            var s = 0L
            var dk = 0
            var br = 0
            var n = 0
            for (yy in 0 until 32) for (xx in 0 until 32) {
                val p = b.getPixel(xx * sw / 32, yy * rh / 32)
                val l = ((((p shr 16) and 255) * 77) + (((p shr 8) and 255) * 150) + ((p and 255) * 29)) shr 8
                s += l
                if (l < 35) dk++
                if (l > 225) br++
                n++
            }
            b.recycle()
            Triple((s / n).toInt(), dk / n.toFloat(), br / n.toFloat())
        } catch (e: Throwable) {
            null
        }
    }

    private fun startCapture() {
        val cam = camera
        val ic = imageCapture
        if (cam == null || ic == null) return
        busy = true
        setBusy("ছবি তোলা হচ্ছে… ফোন স্থির রাখো")
        shutter.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).withEndAction {
            shutter.animate().scaleX(1f).scaleY(1f).setDuration(80)
        }
        val zoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        val native = nativeMode
        val exp = cam.cameraInfo.exposureState
        val step = try { exp.exposureCompensationStep.toFloat() } catch (e: Throwable) { 0f }
        val rng = exp.exposureCompensationRange
        val maxIdx = if (exp.isExposureCompensationSupported && step > 0f) min(rng.upper, -rng.lower) else 0
        val twoEv = if (maxIdx > 0) min(maxIdx, Math.round(2f / step)) else 0
        val oneEv = if (maxIdx > 0) min(maxIdx, Math.round(1f / step)) else 0
        val flashUsed = flash != ImageCapture.FLASH_MODE_OFF
        val stats = if (!native && !flashUsed) sceneStats() else null
        val dark = stats != null && mode != 0 && stats.first < 70
        val hdrWanted = !native && hdrMode != 0 && mode != 0 &&
            (hdrMode == 2 || (stats != null && stats.second + stats.third > 0.14f))

        var evs = listOf(0)
        var merge = 0
        if (!native && !flashUsed) {
            if (mode == 0) {
                evs = List(6) { oneEv }
                merge = 1
            } else if (dark) {
                evs = List(4) { 0 }
                merge = 1
            } else if (hdrWanted && twoEv >= 1) {
                evs = listOf(0, -twoEv, twoEv)
                merge = 2
            } else if (zoom > 1.3f) {
                evs = List(if (nrMode == 2) 6 else 4) { 0 }
                merge = 1
            } else if (nrMode == 2 || (nrMode == 1 && !(stats != null && stats.first >= 110))) {
                evs = List(if (nrMode == 2) 5 else 3) { 0 }
                merge = 1
            }
        }
        val soft = !native && zoom > 1.05f
        val params = Params(
            front = lens == CameraSelector.LENS_FACING_FRONT,
            zoom = zoom,
            softZoom = soft,
            merge = merge,
            hdrSingle = hdrWanted && merge != 2,
            night = mode == 0 && !native,
            portrait = mode == 2 && !native,
            blur = blurSeek.progress,
            beauty = if (mode == 0) 0 else beautySeek.progress,
            filter = filterIdx,
            ai = aiMode,
            bright = brightSeek.progress,
            detail = detailSeek.progress,
            nrBoost = when (nrMode) { 0 -> -0.15f; 2 -> 0.25f; else -> 0f },
            dark = dark,
            native = native
        )

        if (soft) {
            val fb = try { preview.bitmap } catch (e: Throwable) { null }
            if (fb != null) {
                ivFreeze.setImageBitmap(fb)
                ivFreeze.visibility = View.VISIBLE
            }
        }
        val shots = ArrayList<Shot>()
        val mainEx = ContextCompat.getMainExecutor(this)

        fun restore() {
            try { cam.cameraControl.setExposureCompensationIndex(0) } catch (e: Throwable) { }
            if (soft) {
                try { cam.cameraControl.setZoomRatio(zoom) } catch (e: Throwable) { }
            }
            ivFreeze.visibility = View.GONE
        }

        fun next(i: Int) {
            if (i >= evs.size) {
                restore()
                setBusy(null)
                busy = false
                submitJob(shots, params)
                return
            }
            val setEv = try { cam.cameraControl.setExposureCompensationIndex(evs[i]) } catch (e: Throwable) { null }
            val go = Runnable {
                ic.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val buf = image.planes[0].buffer
                            val bytes = ByteArray(buf.remaining())
                            buf.get(bytes)
                            shots.add(Shot(bytes, image.imageInfo.rotationDegrees))
                        } finally {
                            image.close()
                        }
                        runOnUiThread {
                            if (i + 1 < evs.size) setBusy("ছবি ${i + 2}/${evs.size} … স্থির রাখো")
                            next(i + 1)
                        }
                    }

                    override fun onError(e: ImageCaptureException) {
                        runOnUiThread { restore(); fail(e.message) }
                    }
                })
            }
            val delay = if (merge == 2) 300L else 30L
            if (setEv != null) setEv.addListener({ main.postDelayed(go, delay) }, mainEx)
            else main.postDelayed(go, delay)
        }

        if (soft) {
            val zf = try { cam.cameraControl.setZoomRatio(1f) } catch (e: Throwable) { null }
            if (zf != null) zf.addListener({ main.postDelayed({ next(0) }, 250) }, mainEx)
            else next(0)
        } else {
            next(0)
        }
    }

    private fun updateJobsUi() {
        val n = jobsPending.get()
        if (n <= 0) {
            tvJobs.visibility = View.GONE
        } else {
            tvJobs.text = "⏳ প্রসেস চলছে ($n)" + (if (jobStatus.isNotEmpty()) "\n$jobStatus" else "")
            tvJobs.visibility = View.VISIBLE
        }
    }

    private fun submitJob(shots: List<Shot>, p: Params) {
        jobsPending.incrementAndGet()
        updateJobsUi()
        procExec.execute { runProcess(shots, p) }
    }

    private fun runProcess(shots: List<Shot>, p: Params) {
        try {
            val (bmp, noPerson) = Pipeline.run(shots, p, sr) { msg ->
                jobStatus = msg
                runOnUiThread { updateJobsUi() }
            }
            val uri = save(bmp)
            val th = Bitmap.createScaledBitmap(bmp, 160, max(1, 160 * bmp.height / bmp.width), true)
            bmp.recycle()
            val note = Pipeline.note
            runOnUiThread {
                lastUri = uri
                gallery.setImageBitmap(th)
                Toast.makeText(
                    this,
                    (if (noPerson) "মানুষ পাওয়া যায়নি, সাধারণ ছবি সেভ হয়েছে" else "ছবি সেভ হয়েছে") +
                        (if (note.isNotEmpty()) "\n" + note else ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Throwable) {
            val msg = if (e is OutOfMemoryError) "মেমোরি কম। RES-কে 12MP করো" else (e.message ?: "অজানা এরর")
            runOnUiThread { Toast.makeText(this, "এরর: $msg", Toast.LENGTH_LONG).show() }
        } finally {
            jobsPending.decrementAndGet()
            jobStatus = ""
            runOnUiThread { updateJobsUi() }
        }
    }

    // ---------- গ্যালারির ছবি ভালো করা (AI আপস্কেল + নয়েজ + উজ্জ্বল) ----------

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) enhanceUri(uri)
    }

    private fun decodeUri(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
            ?: throw IllegalStateException("ছবি পড়া যায়নি")
        val orient = try {
            contentResolver.openInputStream(uri)!!.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: Throwable) { ExifInterface.ORIENTATION_NORMAL }
        val deg = when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            val m = Matrix().apply { postRotate(deg) }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        return bmp
    }

    private fun enhanceUri(uri: Uri) {
        if (jobsPending.get() >= 3) {
            Toast.makeText(this, "আগের ছবিগুলো প্রসেস হচ্ছে, একটু অপেক্ষা করো", Toast.LENGTH_SHORT).show()
            return
        }
        jobsPending.incrementAndGet()
        jobStatus = "ছবি পড়া হচ্ছে…"
        updateJobsUi()
        val ai = aiMode
        val bright = brightSeek.progress
        val detail = detailSeek.progress
        val boost = when (nrMode) { 0 -> -0.15f; 2 -> 0.25f; else -> 0f }
        procExec.execute {
            try {
                val src = decodeUri(uri)
                val inW = src.width
                val inH = src.height
                val out = Pipeline.enhance(src, ai, bright, detail, boost, sr) { msg ->
                    jobStatus = msg
                    runOnUiThread { updateJobsUi() }
                }
                val saved = save(out, "ENH_")
                val th = Bitmap.createScaledBitmap(out, 160, max(1, 160 * out.height / out.width), true)
                val note = Pipeline.note
                val info = "${inW}x${inH} → ${out.width}x${out.height}"
                out.recycle()
                runOnUiThread {
                    lastUri = saved
                    gallery.setImageBitmap(th)
                    Toast.makeText(this, "ভালো করা ছবি সেভ হয়েছে ($info)" + (if (note.isNotEmpty()) "\n$note" else ""), Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                val msg = if (e is OutOfMemoryError) "মেমোরি কম" else (e.message ?: "অজানা এরর")
                runOnUiThread { Toast.makeText(this, "এরর: $msg", Toast.LENGTH_LONG).show() }
            } finally {
                jobsPending.decrementAndGet()
                jobStatus = ""
                runOnUiThread { updateJobsUi() }
            }
        }
    }

    private fun save(bmp: Bitmap, prefix: String = "IMG_"): Uri {
        val name = prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PortraitCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            ?: throw IllegalStateException("সেভ করা যায়নি")
        contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 98, it) }
        v.clear()
        v.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, v, null, null)
        return uri
    }
}
