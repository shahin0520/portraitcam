package com.portraitcam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
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

    private lateinit var preview: PreviewView
    private lateinit var ivFreeze: ImageView
    private lateinit var grid: View
    private lateinit var tvFlash: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvHdr: TextView
    private lateinit var tvGrid: TextView
    private lateinit var tvFilter: TextView
    private lateinit var tvRes: TextView
    private lateinit var tvPhone: TextView
    private lateinit var tvBusy: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvNight: TextView
    private lateinit var tvPhoto: TextView
    private lateinit var tvPortrait: TextView
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
    private var lens = CameraSelector.LENS_FACING_BACK
    private var flash = ImageCapture.FLASH_MODE_OFF
    private var mode = 1            // 0 = NIGHT, 1 = PHOTO, 2 = PORTRAIT
    private var hdrOn = true
    private var gridOn = false
    private var timerSec = 0
    private var filterIdx = 0
    private var maxRes = false
    private var useNative = false
    private var nativeMode = false
    private var busy = false
    private var lastUri: Uri? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else Toast.makeText(this, "ক্যামেরা পারমিশন লাগবে", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        preview.scaleType = PreviewView.ScaleType.FIT_START
        ivFreeze = findViewById(R.id.ivFreeze)
        grid = findViewById(R.id.grid)
        tvFlash = findViewById(R.id.tvFlash)
        tvTimer = findViewById(R.id.tvTimer)
        tvHdr = findViewById(R.id.tvHdr)
        tvGrid = findViewById(R.id.tvGrid)
        tvFilter = findViewById(R.id.tvFilter)
        tvRes = findViewById(R.id.tvRes)
        tvPhone = findViewById(R.id.tvPhone)
        tvBusy = findViewById(R.id.tvBusy)
        tvCount = findViewById(R.id.tvCount)
        tvNight = findViewById(R.id.tvNight)
        tvPhoto = findViewById(R.id.tvPhoto)
        tvPortrait = findViewById(R.id.tvPortrait)
        gallery = findViewById(R.id.gallery)
        blurSeek = findViewById(R.id.blurSeek)
        beautySeek = findViewById(R.id.beautySeek)
        blurRow = findViewById(R.id.blurRow)
        beautyRow = findViewById(R.id.beautyRow)
        shutter = findViewById(R.id.shutter)
        gallery.clipToOutline = true
        gallery.outlineProvider = ViewOutlineProvider.BACKGROUND

        preview.post {
            val lp = grid.layoutParams
            lp.height = preview.width * 4 / 3
            grid.layoutParams = lp
        }

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

        tvFlash.setOnClickListener {
            flash = when (flash) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = flash
            updateTop()
        }
        tvTimer.setOnClickListener {
            timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
            updateTop()
        }
        tvHdr.setOnClickListener { hdrOn = !hdrOn; updateTop(); bind() }
        tvGrid.setOnClickListener { gridOn = !gridOn; updateTop() }
        tvFilter.setOnClickListener { filterIdx = (filterIdx + 1) % filterNames.size; updateTop() }
        tvRes.setOnClickListener { maxRes = !maxRes; updateTop(); bind() }
        tvPhone.setOnClickListener { useNative = !useNative; updateTop(); bind() }
        findViewById<View>(R.id.flip).setOnClickListener {
            lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
            bind()
        }
        shutter.setOnClickListener { onShutter() }
        gallery.setOnClickListener {
            lastUri?.let {
                startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(it, "image/jpeg")
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera() else permLauncher.launch(Manifest.permission.CAMERA)
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
        if (m == mode) return
        mode = m
        updateModeUi()
        bind()
    }

    private fun updateModeUi() {
        tvNight.setTextColor(if (mode == 0) YELLOW else DIM)
        tvPhoto.setTextColor(if (mode == 1) YELLOW else DIM)
        tvPortrait.setTextColor(if (mode == 2) YELLOW else DIM)
        blurRow.visibility = if (mode == 2 && !nativeMode) View.VISIBLE else View.GONE
        beautyRow.visibility = if (mode != 0) View.VISIBLE else View.GONE
    }

    private fun tint(v: TextView, on: Boolean) = v.setTextColor(if (on) YELLOW else Color.WHITE)

    private fun updateTop() {
        tvFlash.text = "FLASH\n" + when (flash) {
            ImageCapture.FLASH_MODE_AUTO -> "AUTO"
            ImageCapture.FLASH_MODE_ON -> "ON"
            else -> "OFF"
        }
        tvTimer.text = "TIMER\n" + (if (timerSec == 0) "OFF" else "${timerSec}s")
        tvHdr.text = "HDR\n" + (if (hdrOn) "ON" else "OFF")
        tvGrid.text = "GRID\n" + (if (gridOn) "ON" else "OFF")
        tvFilter.text = "FILTER\n" + filterNames[filterIdx]
        tvRes.text = "RES\n" + (if (maxRes) "MAX" else "12MP")
        tvPhone.text = "PHONE\n" + (if (useNative) "ON" else "OFF")
        tint(tvFlash, flash != ImageCapture.FLASH_MODE_OFF)
        tint(tvTimer, timerSec != 0)
        tint(tvHdr, hdrOn)
        tint(tvGrid, gridOn)
        tint(tvFilter, filterIdx != 0)
        tint(tvRes, maxRes)
        tint(tvPhone, useNative)
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
        val portrait = mode == 2
        val extMode = if (portrait) ExtensionMode.BOKEH else ExtensionMode.HDR
        val wantNative = useNative && mode != 0 && (portrait || hdrOn)
        var ok = false
        nativeMode = false
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
        if (!ok) {
            Toast.makeText(this, "ক্যামেরা চালু হয়নি", Toast.LENGTH_LONG).show()
            return
        }
        updateModeUi()
        preview.post {
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

    private fun bindUse(p: ProcessCameraProvider, sel: CameraSelector, ext: Boolean): Boolean {
        return try {
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val b = ImageCapture.Builder().setFlashMode(flash)
            if (!ext) {
                val target = if (maxRes) Size(8200, 6200) else Size(4000, 3000)
                b.setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                b.setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                )
            }
            imageCapture = b.build()
            p.unbindAll()
            camera = p.bindToLifecycle(this, sel, pv, imageCapture)
            true
        } catch (e: Throwable) {
            false
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
            startCapture()
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

        var evs = listOf(0)
        var merge = 0
        val flashUsed = flash != ImageCapture.FLASH_MODE_OFF
        if (!native && !flashUsed) {
            if (mode == 0) {
                evs = List(6) { oneEv }
                merge = 1
            } else if (hdrOn && twoEv >= 1) {
                evs = listOf(0, -twoEv, twoEv)
                merge = 2
            } else if (zoom > 1.3f) {
                evs = List(4) { 0 }
                merge = 1
            }
        }
        val soft = !native && zoom > 1.05f
        val params = Params(
            front = lens == CameraSelector.LENS_FACING_FRONT,
            zoom = zoom,
            softZoom = soft,
            merge = merge,
            hdrSingle = hdrOn && merge != 2 && !native && mode != 0,
            night = mode == 0 && !native,
            portrait = mode == 2 && !native,
            blur = blurSeek.progress,
            beauty = if (mode == 0) 0 else beautySeek.progress,
            filter = filterIdx,
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
                setBusy("প্রসেস হচ্ছে…")
                io.execute { runProcess(shots, params) }
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

    private fun runProcess(shots: List<Shot>, p: Params) {
        try {
            val (bmp, noPerson) = Pipeline.run(shots, p) { msg -> runOnUiThread { setBusy(msg) } }
            val uri = save(bmp)
            val th = Bitmap.createScaledBitmap(bmp, 160, max(1, 160 * bmp.height / bmp.width), true)
            bmp.recycle()
            runOnUiThread {
                lastUri = uri
                gallery.setImageBitmap(th)
                Toast.makeText(
                    this,
                    if (noPerson) "মানুষ পাওয়া যায়নি, সাধারণ ছবি সেভ হয়েছে" else "ছবি সেভ হয়েছে",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Throwable) {
            val msg = if (e is OutOfMemoryError) "মেমোরি কম। RES-কে 12MP করো" else (e.message ?: "অজানা এরর")
            runOnUiThread { Toast.makeText(this, "এরর: $msg", Toast.LENGTH_LONG).show() }
        } finally {
            busy = false
            runOnUiThread { setBusy(null) }
        }
    }

    private fun save(bmp: Bitmap): Uri {
        val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
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
