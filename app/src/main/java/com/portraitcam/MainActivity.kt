package com.portraitcam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var tvFlash: TextView
    private lateinit var tvBusy: TextView
    private lateinit var tvPhoto: TextView
    private lateinit var tvPortrait: TextView
    private lateinit var gallery: ImageView
    private lateinit var chips: List<Pair<TextView, Float>>

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private var flash = ImageCapture.FLASH_MODE_OFF
    private var portrait = false
    private var busy = false
    private var lastUri: Uri? = null
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
        preview.scaleType = PreviewView.ScaleType.FIT_CENTER
        tvFlash = findViewById(R.id.tvFlash)
        tvBusy = findViewById(R.id.tvBusy)
        tvPhoto = findViewById(R.id.tvPhoto)
        tvPortrait = findViewById(R.id.tvPortrait)
        gallery = findViewById(R.id.gallery)
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
        setMode(false)

        tvFlash.setOnClickListener {
            flash = when (flash) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = flash
            tvFlash.text = when (flash) {
                ImageCapture.FLASH_MODE_AUTO -> "⚡ AUTO"
                ImageCapture.FLASH_MODE_ON -> "⚡ ON"
                else -> "⚡ OFF"
            }
        }
        findViewById<View>(R.id.flip).setOnClickListener {
            lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
            bind()
        }
        findViewById<View>(R.id.shutter).setOnClickListener { capture() }
        gallery.setOnClickListener {
            lastUri?.let {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(it, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }

        val sgd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val z = camera?.cameraInfo?.zoomState?.value ?: return true
                setZoom(z.zoomRatio * d.scaleFactor)
                return true
            }
        })
        preview.setOnTouchListener { v, e ->
            sgd.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP && !sgd.isInProgress && e.eventTime - e.downTime < 250) {
                val pt = preview.meteringPointFactory.createPoint(e.x, e.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                v.performClick()
            }
            true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera() else permLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun setMode(p: Boolean) {
        portrait = p
        tvPhoto.setTextColor(if (!p) 0xFFFFD60A.toInt() else Color.WHITE)
        tvPortrait.setTextColor(if (p) 0xFFFFD60A.toInt() else Color.WHITE)
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ provider = f.get(); bind() }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flash).build()
        val sel = CameraSelector.Builder().requireLensFacing(lens).build()
        try {
            p.unbindAll()
            camera = p.bindToLifecycle(this, sel, pv, imageCapture)
        } catch (e: Exception) {
            Toast.makeText(this, "ক্যামেরা চালু হয়নি: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
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
        }
    }

    private fun setZoom(r: Float) {
        val z = camera?.cameraInfo?.zoomState?.value ?: return
        camera?.cameraControl?.setZoomRatio(r.coerceIn(z.minZoomRatio, z.maxZoomRatio))
    }

    private fun capture() {
        val ic = imageCapture ?: return
        if (busy) return
        busy = true
        tvBusy.visibility = View.VISIBLE
        val front = lens == CameraSelector.LENS_FACING_FRONT
        val doPortrait = portrait
        ic.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rot = image.imageInfo.rotationDegrees
                    var bmp = image.toBitmap()
                    val m = Matrix().apply {
                        postRotate(rot.toFloat())
                        if (front) postScale(-1f, 1f)
                    }
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    val longSide = max(bmp.width, bmp.height)
                    if (longSide > 4096) {
                        val s = 4096f / longSide
                        bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
                    }
                    val out = process(bmp, doPortrait)
                    val uri = save(out)
                    val thumb = Bitmap.createScaledBitmap(out, 160, 160 * out.height / out.width, true)
                    runOnUiThread {
                        lastUri = uri
                        gallery.setImageBitmap(thumb)
                        Toast.makeText(this@MainActivity, "ছবি সেভ হয়েছে (Pictures/PortraitCam)", Toast.LENGTH_SHORT).show()
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

    private fun enhanceFilter(): ColorMatrixColorFilter {
        val sat = ColorMatrix().apply { setSaturation(1.12f) }
        val c = 1.06f
        val b = 6f
        val con = ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, b + (1 - c) * 128f,
            0f, c, 0f, 0f, b + (1 - c) * 128f,
            0f, 0f, c, 0f, b + (1 - c) * 128f,
            0f, 0f, 0f, 1f, 0f))
        sat.postConcat(con)
        return ColorMatrixColorFilter(sat)
    }

    private fun process(src: Bitmap, portrait: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val enh = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = enhanceFilter() }
        val dst = Rect(0, 0, w, h)

        val mask = if (portrait) personMask(src) else null
        if (mask == null) {
            c.drawBitmap(src, 0f, 0f, enh)
            return out
        }
        // ঝাপসা ব্যাকগ্রাউন্ড
        val bg = blurredBackground(src)
        c.drawBitmap(bg, null, dst, enh)
        // শার্প মানুষ (মাস্ক দিয়ে কাটা)
        val layer = src.copy(Bitmap.Config.ARGB_8888, true)
        val lc = Canvas(layer)
        val mp = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        lc.drawBitmap(mask, null, dst, mp)
        c.drawBitmap(layer, 0f, 0f, enh)
        return out
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
            for (i in px.indices) {
                val conf = buf.float
                // কিনারা পরিষ্কার করার smoothstep
                val t = ((conf - 0.35f) / 0.4f).coerceIn(0f, 1f)
                val a = (t * t * (3f - 2f * t) * 255f).toInt()
                px[i] = a shl 24
            }
            val mb = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
            mb.setPixels(px, 0, mw, 0, 0, mw, mh)
            mb
        } catch (e: Throwable) {
            null
        }
    }

    private fun blurredBackground(src: Bitmap): Bitmap {
        val s = 420f / max(src.width, src.height)
        val small = Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(2), (src.height * s).toInt().coerceAtLeast(2), true)
        return boxBlur(small, 9, 3)
    }

    private fun boxBlur(bmp: Bitmap, r: Int, passes: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(passes) {
            blurT(px, tmp, w, h, r)   // tmp = transposed (h x w)
            blurT(tmp, px, h, w, r)   // px = original orientation
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
        contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 96, it) }
        v.clear()
        v.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, v, null, null)
        return uri
    }
}
