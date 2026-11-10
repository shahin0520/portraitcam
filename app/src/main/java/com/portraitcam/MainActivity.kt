package com.portraitcam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
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
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private val YELLOW = 0xFFFFD60A.toInt()

    private lateinit var preview: PreviewView
    private lateinit var ivFlash: ImageView
    private lateinit var tvHdr: TextView
    private lateinit var tvBusy: TextView
    private lateinit var tvPhoto: TextView
    private lateinit var tvPortrait: TextView
    private lateinit var gallery: ImageView
    private lateinit var blurSeek: SeekBar
    private lateinit var blurRow: View
    private lateinit var shutter: View
    private lateinit var chips: List<Pair<TextView, Float>>

    private var provider: ProcessCameraProvider? = null
    private var extMgr: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private var flash = ImageCapture.FLASH_MODE_OFF
    private var portrait = false
    private var hdrOn = true
    private var nativeMode = false
    private var busy = false
    private var lastUri: Uri? = null
    @Volatile private var noPerson = false
    private val io = Executors.newSingleThreadExecutor()

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
        )
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else Toast.makeText(this, "ক্যামেরা পারমিশন লাগবে", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        preview.scaleType = PreviewView.ScaleType.FIT_START
        ivFlash = findViewById(R.id.ivFlash)
        tvHdr = findViewById(R.id.tvHdr)
        tvBusy = findViewById(R.id.tvBusy)
        tvPhoto = findViewById(R.id.tvPhoto)
        tvPortrait = findViewById(R.id.tvPortrait)
        gallery = findViewById(R.id.gallery)
        blurSeek = findViewById(R.id.blurSeek)
        blurRow = findViewById(R.id.blurRow)
        shutter = findViewById(R.id.shutter)
        gallery.clipToOutline = true
        gallery.outlineProvider = ViewOutlineProvider.BACKGROUND

        chips = listOf(
            findViewById<TextView>(R.id.z05) to 0.5f,
            findViewById<TextView>(R.id.z1) to 1f,
            findViewById<TextView>(R.id.z2) to 2f,
            findViewById<TextView>(R.id.z3) to 3f
        )
        chips.forEach { (v, r) -> v.setOnClickListener { setZoom(r) } }

        tvPhoto.setOnClickListener { setMode(false) }
        tvPortrait.setOnClickListener { setMode(true) }
        updateModeUi()
        updateHdrUi()
        updateFlashUi()

        tvHdr.setOnClickListener {
            hdrOn = !hdrOn
            updateHdrUi()
            bind()
        }
        ivFlash.setOnClickListener {
            flash = when (flash) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = flash
            updateFlashUi()
        }
        findViewById<View>(R.id.flip).setOnClickListener {
            lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
            bind()
        }
        shutter.setOnClickListener { capture() }
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
                            setMode(dx < 0)
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera() else permLauncher.launch(Manifest.permission.CAMERA)
    }

    // ---------- UI ----------

    private fun setMode(p: Boolean) {
        if (p == portrait) return
        portrait = p
        updateModeUi()
        bind()
    }

    private fun updateModeUi() {
        tvPhoto.setTextColor(if (!portrait) YELLOW else 0xB3FFFFFF.toInt())
        tvPortrait.setTextColor(if (portrait) YELLOW else 0xB3FFFFFF.toInt())
        blurRow.visibility = if (portrait && !nativeMode) View.VISIBLE else View.GONE
    }

    private fun updateHdrUi() {
        tvHdr.setTextColor(if (hdrOn) YELLOW else Color.WHITE)
        tvHdr.alpha = if (hdrOn) 1f else 0.6f
    }

    private fun updateFlashUi() {
        when (flash) {
            ImageCapture.FLASH_MODE_ON -> { ivFlash.setColorFilter(YELLOW); ivFlash.alpha = 1f }
            ImageCapture.FLASH_MODE_AUTO -> { ivFlash.setColorFilter(Color.WHITE); ivFlash.alpha = 1f }
            else -> { ivFlash.setColorFilter(Color.WHITE); ivFlash.alpha = 0.4f }
        }
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
        val mode = if (portrait) ExtensionMode.BOKEH else ExtensionMode.HDR
        val wantNative = (portrait || hdrOn)
        var ok = false
        nativeMode = false
        val em = extMgr
        if (wantNative && em != null) {
            try {
                if (em.isExtensionAvailable(base, mode)) {
                    ok = bindUse(p, em.getExtensionEnabledCameraSelector(base, mode), true)
                    nativeMode = ok
                }
            } catch (e: Throwable) {
                ok = false
                nativeMode = false
            }
        }
        if (!ok) {
            ok = bindUse(p, base, false)
        }
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
                b.setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                b.setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
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

    private fun capture() {
        val ic = imageCapture ?: return
        if (busy) return
        busy = true
        tvBusy.visibility = View.VISIBLE
        shutter.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).withEndAction {
            shutter.animate().scaleX(1f).scaleY(1f).setDuration(80)
        }
        val front = lens == CameraSelector.LENS_FACING_FRONT
        val doPortrait = portrait
        val native = nativeMode
        val hdr = hdrOn
        val zoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        val level = blurSeek.progress
        ic.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rot = image.imageInfo.rotationDegrees
                    val raw = image.toBitmap()
                    val m = Matrix().apply {
                        postRotate(rot.toFloat())
                        if (front) postScale(-1f, 1f)
                    }
                    var bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    if (bmp !== raw) raw.recycle()
                    val longSide = max(bmp.width, bmp.height)
                    if (longSide > 4096) {
                        val s = 4096f / longSide
                        val sc = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
                        bmp.recycle()
                        bmp = sc
                    }
                    val out = process(bmp, doPortrait && !native, zoom, level, hdr && !native, native)
                    val uri = save(out)
                    val thumb = Bitmap.createScaledBitmap(out, 160, max(1, 160 * out.height / out.width), true)
                    out.recycle()
                    runOnUiThread {
                        lastUri = uri
                        gallery.setImageBitmap(thumb)
                        if (noPerson) Toast.makeText(this@MainActivity, "মানুষ পাওয়া যায়নি, সাধারণ ছবি সেভ হয়েছে", Toast.LENGTH_LONG).show()
                        else Toast.makeText(this@MainActivity, "ছবি সেভ হয়েছে", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Throwable) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "এরর: ${e.message}", Toast.LENGTH_LONG).show() }
                } finally {
                    image.close()
                    busy = false
                    runOnUiThread { tvBusy.visibility = View.GONE }
                }
            }

            override fun onError(e: ImageCaptureException) {
                busy = false
                runOnUiThread {
                    tvBusy.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "ছবি তোলা যায়নি: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    // ---------- Processing ----------

    private fun process(src: Bitmap, portrait: Boolean, zoom: Float, blurLevel: Int, hdr: Boolean, native: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        noPerson = false
        val mask = if (portrait) personMask(src) else null
        if (portrait && mask == null) noPerson = true

        var px: IntArray? = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        src.recycle()
        if (!native) tonemap(px!!, w, h, hdr)
        sharpen(px!!, w, h, if (native) 0.5f else (0.9f + 0.15f * (zoom - 1f)).coerceIn(0.9f, 1.6f))
        val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        base.setPixels(px, 0, w, 0, 0, w, h)
        px = null
        if (mask == null) return base

        val bg = blurredBackground(base, mask, blurLevel)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val full = Rect(0, 0, w, h)
        c.drawBitmap(bg, null, full, Paint(Paint.FILTER_BITMAP_FLAG))
        val bc = Canvas(base)
        val mp = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        bc.drawBitmap(mask, null, full, mp)
        c.drawBitmap(base, 0f, 0f, null)
        base.recycle()
        return out
    }

    private fun lum(p: Int): Int = ((((p shr 16) and 255) * 77) + (((p shr 8) and 255) * 150) + ((p and 255) * 29)) shr 8

    // HDR-এর মতো: অন্ধকার অংশ উজ্জ্বল, বেশি আলো অংশ নরম + রং ও কনট্রাস্ট
    private fun tonemap(px: IntArray, w: Int, h: Int, hdr: Boolean) {
        val gw = 48
        val gh = (gw * h / w).coerceAtLeast(2)
        val gain = FloatArray(gw * gh) { 1f }
        if (hdr) {
            var lg = FloatArray(gw * gh)
            for (gy in 0 until gh) for (gx in 0 until gw) {
                val x0 = gx * w / gw
                val x1 = min(w, (gx + 1) * w / gw)
                val y0 = gy * h / gh
                val y1 = min(h, (gy + 1) * h / gh)
                val sx = max(1, (x1 - x0) / 4)
                val sy = max(1, (y1 - y0) / 4)
                var sum = 0f
                var n = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) { sum += lum(px[y * w + x]); n++; x += sx }
                    y += sy
                }
                lg[gy * gw + gx] = if (n > 0) sum / n else 128f
            }
            repeat(2) {
                val t = FloatArray(gw * gh)
                for (gy in 0 until gh) for (gx in 0 until gw) {
                    var s = 0f
                    var n = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val yy = gy + dy
                        val xx = gx + dx
                        if (yy in 0 until gh && xx in 0 until gw) { s += lg[yy * gw + xx]; n++ }
                    }
                    t[gy * gw + gx] = s / n
                }
                lg = t
            }
            for (i in gain.indices) {
                gain[i] = (125f / max(lg[i], 30f)).pow(0.4f).coerceIn(0.88f, 1.9f)
            }
        }
        val lut = IntArray(256) { i ->
            val t = i / 255f
            val s = t * t * (3f - 2f * t)
            ((t * 0.78f + s * 0.22f) * 1.04f * 255f).toInt().coerceIn(0, 255)
        }
        val x0s = IntArray(w)
        val fxs = FloatArray(w)
        for (x in 0 until w) {
            val gxf = x * (gw - 1f) / (w - 1)
            val i = min(gxf.toInt(), gw - 2)
            x0s[x] = i
            fxs[x] = gxf - i
        }
        for (y in 0 until h) {
            val gyf = y * (gh - 1f) / (h - 1)
            val j = min(gyf.toInt(), gh - 2)
            val fy = gyf - j
            val ra = j * gw
            val rb = (j + 1) * gw
            val row = y * w
            for (x in 0 until w) {
                val i = x0s[x]
                val fx = fxs[x]
                val a = gain[ra + i] * (1f - fx) + gain[ra + i + 1] * fx
                val b = gain[rb + i] * (1f - fx) + gain[rb + i + 1] * fx
                val g = a * (1f - fy) + b * fy
                val p = px[row + x]
                var r = lut[(((p shr 16) and 255) * g).toInt().coerceIn(0, 255)]
                var gr = lut[(((p shr 8) and 255) * g).toInt().coerceIn(0, 255)]
                var bl = lut[((p and 255) * g).toInt().coerceIn(0, 255)]
                val l = (r * 77 + gr * 150 + bl * 29) shr 8
                r = (l + (((r - l) * 288) shr 8)).coerceIn(0, 255)
                gr = (l + (((gr - l) * 288) shr 8)).coerceIn(0, 255)
                bl = (l + (((bl - l) * 288) shr 8)).coerceIn(0, 255)
                px[row + x] = (0xFF shl 24) or (r shl 16) or (gr shl 8) or bl
            }
        }
    }

    // ডিটেইলস বাড়ানো (হালকা, নয়েজ না বাড়িয়ে)
    private fun sharpen(px: IntArray, w: Int, h: Int, amt: Float) {
        val k = (amt * 256f).toInt()
        var prev = IntArray(w)
        var cur = IntArray(w)
        var next = IntArray(w)
        System.arraycopy(px, 0, prev, 0, w)
        System.arraycopy(px, 0, cur, 0, w)
        for (y in 0 until h) {
            val ny = if (y + 1 < h) y + 1 else y
            System.arraycopy(px, ny * w, next, 0, w)
            val base = y * w
            for (x in 1 until w - 1) {
                val c = cur[x]
                val l = cur[x - 1]
                val r = cur[x + 1]
                val u = prev[x]
                val d = next[x]
                val nr = sh((c shr 16) and 255, (((l shr 16) and 255) + ((r shr 16) and 255) + ((u shr 16) and 255) + ((d shr 16) and 255)) shr 2, k)
                val ng = sh((c shr 8) and 255, (((l shr 8) and 255) + ((r shr 8) and 255) + ((u shr 8) and 255) + ((d shr 8) and 255)) shr 2, k)
                val nb = sh(c and 255, ((l and 255) + (r and 255) + (u and 255) + (d and 255)) shr 2, k)
                px[base + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
            val t = prev
            prev = cur
            cur = next
            next = t
        }
    }

    private fun sh(v: Int, avg: Int, k: Int): Int {
        val d = v - avg
        if (d > -3 && d < 3) return v
        val delta = ((d * k) shr 9).coerceIn(-40, 40)
        return (v + delta).coerceIn(0, 255)
    }

    private fun personMask(src: Bitmap): Bitmap? {
        return try {
            val s = 512f / max(src.width, src.height)
            val small = Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
            val m = Tasks.await(segmenter.process(InputImage.fromBitmap(small, 0)))
            val buf = m.buffer
            val mw = m.width
            val mh = m.height
            val px = IntArray(mw * mh)
            var sum = 0L
            for (i in px.indices) {
                val conf = buf.float
                val t = ((conf - 0.3f) / 0.5f).coerceIn(0f, 1f)
                val a = (t * t * (3f - 2f * t) * 255f).toInt()
                sum += a
                px[i] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
            }
            if (sum.toFloat() / (px.size * 255f) < 0.02f) return null
            val g = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
            g.setPixels(px, 0, mw, 0, 0, mw, mh)
            val soft = boxBlur(g, 2, 2)
            val sp = IntArray(mw * mh)
            soft.getPixels(sp, 0, mw, 0, 0, mw, mh)
            for (i in sp.indices) sp[i] = (sp[i] and 255) shl 24
            val mb = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
            mb.setPixels(sp, 0, mw, 0, 0, mw, mh)
            mb
        } catch (e: Throwable) {
            null
        }
    }

    // লেন্স-ব্লারের মতো: মানুষের কাছে হালকা, দূরে বেশি ঝাপসা
    private fun blurredBackground(base: Bitmap, mask: Bitmap, level: Int): Bitmap {
        val s = 900f / max(base.width, base.height)
        val sw = max(4, (base.width * s).toInt())
        val sh = max(4, (base.height * s).toInt())
        val small = Bitmap.createScaledBitmap(base, sw, sh, true)
        val sp = IntArray(sw * sh)
        small.getPixels(sp, 0, sw, 0, 0, sw, sh)
        for (i in sp.indices) {
            val p = sp[i]
            var r = (p shr 16) and 255
            var g = (p shr 8) and 255
            var b = p and 255
            if (r > 215) r = min(255, r + (r - 215) * 2)
            if (g > 215) g = min(255, g + (g - 215) * 2)
            if (b > 215) b = min(255, b + (b - 215) * 2)
            sp[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        small.setPixels(sp, 0, sw, 0, 0, sw, sh)
        val rs = 3 + (level * 0.12f).toInt()
        val strong = boxBlur(small, rs, 3)
        val mild = boxBlur(small, max(2, rs / 3), 2)

        val ms = Bitmap.createScaledBitmap(mask, sw, sh, true)
        val mp = IntArray(sw * sh)
        ms.getPixels(mp, 0, sw, 0, 0, sw, sh)
        for (i in mp.indices) {
            val a = mp[i] ushr 24
            mp[i] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
        }
        val gray = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        gray.setPixels(mp, 0, sw, 0, 0, sw, sh)
        val dist = boxBlur(gray, sw / 10, 2)
        val dp = IntArray(sw * sh)
        dist.getPixels(dp, 0, sw, 0, 0, sw, sh)
        val a1 = IntArray(sw * sh)
        val b1 = IntArray(sw * sh)
        strong.getPixels(a1, 0, sw, 0, 0, sw, sh)
        mild.getPixels(b1, 0, sw, 0, 0, sw, sh)
        val outPx = IntArray(sw * sh)
        for (i in outPx.indices) {
            val m = (dp[i] and 255) / 255f
            val wt = (1f - m * 1.7f).coerceIn(0f, 1f)
            val s1 = a1[i]
            val m1 = b1[i]
            val r = (((s1 shr 16) and 255) * wt + ((m1 shr 16) and 255) * (1f - wt)).toInt()
            val g = (((s1 shr 8) and 255) * wt + ((m1 shr 8) and 255) * (1f - wt)).toInt()
            val b = ((s1 and 255) * wt + (m1 and 255) * (1f - wt)).toInt()
            outPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val res = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        res.setPixels(outPx, 0, sw, 0, 0, sw, sh)
        return res
    }

    private fun boxBlur(bmp: Bitmap, r: Int, passes: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(passes) {
            blurT(px, tmp, w, h, r)
            blurT(tmp, px, h, w, r)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun blurT(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
            }
            for (x in 0 until w) {
                dst[x * h + y] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val a = src[row + (x + r + 1).coerceAtMost(w - 1)]
                val d = src[row + (x - r).coerceAtLeast(0)]
                sr += ((a shr 16) and 255) - ((d shr 16) and 255)
                sg += ((a shr 8) and 255) - ((d shr 8) and 255)
                sb += (a and 255) - (d and 255)
            }
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
