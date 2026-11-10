package com.portraitcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class Shot(val jpeg: ByteArray, val rot: Int)

class Params(
    val front: Boolean,
    val zoom: Float,
    val softZoom: Boolean,
    val merge: Int,          // 0 = এক ফ্রেম, 1 = গড় (নয়েজ কমানো), 2 = HDR
    val hdrSingle: Boolean,
    val night: Boolean,
    val portrait: Boolean,
    val blur: Int,
    val beauty: Int,
    val filter: Int,
    val dark: Boolean,
    val native: Boolean
)

class Merged(val px: IntArray, val w: Int, val h: Int)

object Pipeline {

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
        )
    }
    private val faceDet by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
        )
    }

    fun run(shots: List<Shot>, p: Params, progress: (String) -> Unit): Pair<Bitmap, Boolean> {
        progress("ফ্রেম মেলানো হচ্ছে…")
        val first = shots[0]
        val dec0 = BitmapRegionDecoder.newInstance(first.jpeg, 0, first.jpeg.size, false)
        val sw0 = dec0.width
        val sh0 = dec0.height
        dec0.recycle()
        val z = if (p.softZoom) p.zoom else 1f
        val cw = max(64, (sw0 / z).toInt() and 1.inv())
        val ch = max(64, (sh0 / z).toInt() and 1.inv())
        val x0 = ((sw0 - cw) / 2) and 1.inv()
        val y0 = ((sh0 - ch) / 2) and 1.inv()
        val rect = Rect(x0, y0, x0 + cw, y0 + ch)
        var sample = 1
        while ((cw / sample).toLong() * (ch / sample) > 13_000_000L) sample *= 2

        val ghost = if (p.dark) 160 else 100
        val m = merge(shots, rect, sample, p.merge, ghost)
        if (sample == 1) deblock(m.px, m.w, m.h, rect.left, rect.top)
        var msum = 0L
        var mcnt = 0
        var si = 0
        while (si < m.px.size) { msum += lum(m.px[si]); mcnt++; si += 37 }
        val meanL = if (mcnt > 0) (msum / mcnt).toInt() else 120
        var strength = 0.3f + max(0f, (110f - meanL) / 110f) * 0.6f + (z - 1f) * 0.1f - (shots.size - 1) * 0.04f
        strength = strength.coerceIn(0.2f, 1f)
        if (p.native) strength = min(strength, 0.3f)
        progress("নয়েজ কমানো হচ্ছে…")
        denoise(m.px, m.w, m.h, strength)
        var px0 = m.px
        var mw = m.w
        var mh = m.h
        val up = if (p.softZoom) min(z, 3f) else 1f
        if (up > 1.01f) {
            progress("ছবি বড় করা হচ্ছে…")
            val nw = max(2, (mw * up).toInt() and 1.inv())
            val nh = max(2, (mh * up).toInt() and 1.inv())
            px0 = lanczos(px0, mw, mh, nw, nh)
            mw = nw
            mh = nh
        }
        var bmp = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px0, 0, mw, 0, 0, mw, mh)
        px0 = IntArray(0)
        val mx = Matrix()
        mx.postRotate(first.rot.toFloat())
        if (p.front) mx.postScale(-1f, 1f)
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mx, true)
        if (rotated !== bmp) bmp.recycle()
        bmp = rotated

        progress("প্রসেস হচ্ছে…")
        val w = bmp.width
        val h = bmp.height
        val mask = if (p.portrait) personMask(bmp) else null
        val noPerson = p.portrait && mask == null
        var px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        if (!p.native) tonemap(px, w, h, p.hdrSingle, p.night)
        val amt = if (p.native) 0.5f else (0.9f + 0.2f * (z - 1f)).coerceIn(0.9f, 1.7f)
        sharpen(px, w, h, amt)
        var out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        px = IntArray(0)
        if (mask != null) {
            progress("ব্যাকগ্রাউন্ড ব্লার…")
            out = composite(out, mask, p.blur)
        }
        if (p.beauty > 0) {
            progress("ফেস স্মুদ…")
            beautify(out, p.beauty)
        }
        if (p.filter > 0) applyFilter(out, p.filter)
        return Pair(out, noPerson)
    }

    // ---------- ফ্রেম মেলানো ----------

    private fun lum(p: Int): Int =
        ((((p shr 16) and 255) * 77) + (((p shr 8) and 255) * 150) + ((p and 255) * 29)) shr 8

    private fun decode(s: Shot, rect: Rect, sample: Int): Merged {
        val dec = BitmapRegionDecoder.newInstance(s.jpeg, 0, s.jpeg.size, false)
        val o = BitmapFactory.Options()
        o.inSampleSize = sample
        o.inPreferredConfig = Bitmap.Config.ARGB_8888
        val b = dec.decodeRegion(rect, o)
        dec.recycle()
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        b.recycle()
        return Merged(px, w, h)
    }

    private class Grad(val g: IntArray, val w: Int, val h: Int)

    private fun gradSmall(px: IntArray, w: Int, h: Int, f: Int): Grad {
        val sw = w / f
        val sh = h / f
        val l = IntArray(sw * sh)
        val hf = f / 2
        for (y in 0 until sh) for (x in 0 until sw) {
            val bx = x * f
            val by = y * f
            val a = lum(px[by * w + bx]) + lum(px[by * w + bx + hf]) +
                lum(px[(by + hf) * w + bx]) + lum(px[(by + hf) * w + bx + hf])
            l[y * sw + x] = a shr 2
        }
        val g = IntArray(sw * sh)
        var sum = 0L
        for (y in 0 until sh - 1) for (x in 0 until sw - 1) {
            val v = abs(l[y * sw + x + 1] - l[y * sw + x]) + abs(l[(y + 1) * sw + x] - l[y * sw + x])
            g[y * sw + x] = v
            sum += v
        }
        val mean = max(1L, sum / (sw * sh))
        for (i in g.indices) g[i] = (g[i] * 64L / mean).toInt()
        return Grad(g, sw, sh)
    }

    private fun findShift(a: Grad, b: Grad, r: Int, cx: Int, cy: Int): IntArray {
        val sw = a.w
        val sh = a.h
        var best = Long.MAX_VALUE
        var bx = cx
        var by = cy
        for (dy in cy - r..cy + r) for (dx in cx - r..cx + r) {
            val ys = max(0, -dy)
            val ye = min(sh - 1, sh - 1 - dy)
            val xs = max(0, -dx)
            val xe = min(sw - 1, sw - 1 - dx)
            if (ye <= ys || xe <= xs) continue
            var s = 0L
            var n = 0
            var y = ys
            while (y <= ye) {
                var x = xs
                val ra = y * sw
                val rb = (y + dy) * sw + dx
                while (x <= xe) {
                    s += abs(a.g[ra + x] - b.g[rb + x])
                    n++
                    x += 2
                }
                y += 2
            }
            if (n == 0) continue
            val c = s * 1000L / n
            if (c < best) { best = c; bx = dx; by = dy }
        }
        return intArrayOf(bx, by)
    }

    private class Bil(val w: Int, val h: Int, val gw: Int, val gh: Int) {
        val x0 = IntArray(w)
        val fx = FloatArray(w)
        var ra = 0
        var rb = 0
        var fy = 0f

        init {
            for (x in 0 until w) {
                val g = x * (gw - 1f) / (w - 1)
                val i = min(g.toInt(), gw - 2)
                x0[x] = i
                fx[x] = g - i
            }
        }

        fun row(y: Int) {
            val g = y * (gh - 1f) / (h - 1)
            val j = min(g.toInt(), gh - 2)
            fy = g - j
            ra = j * gw
            rb = (j + 1) * gw
        }

        fun at(grid: FloatArray, x: Int): Float {
            val i = x0[x]
            val f = fx[x]
            val a = grid[ra + i] * (1f - f) + grid[ra + i + 1] * f
            val b = grid[rb + i] * (1f - f) + grid[rb + i + 1] * f
            return a * (1f - fy) + b * fy
        }
    }

    private fun lumaGrid(px: IntArray, w: Int, h: Int, gw: Int, gh: Int, dx: Int, dy: Int): FloatArray {
        var lg = FloatArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val x0 = gx * w / gw
            val x1 = max(x0 + 1, (gx + 1) * w / gw)
            val y0 = gy * h / gh
            val y1 = max(y0 + 1, (gy + 1) * h / gh)
            val sx = max(1, (x1 - x0) / 4)
            val sy = max(1, (y1 - y0) / 4)
            var sum = 0f
            var n = 0
            var y = y0
            while (y < y1) {
                var x = x0
                val yy = (y + dy).coerceIn(0, h - 1)
                while (x < x1) {
                    sum += lum(px[yy * w + (x + dx).coerceIn(0, w - 1)])
                    n++
                    x += sx
                }
                y += sy
            }
            lg[gy * gw + gx] = if (n > 0) sum / n else 128f
        }
        val t = FloatArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var s = 0f
            var n = 0
            for (dy2 in -1..1) for (dx2 in -1..1) {
                val yy = gy + dy2
                val xx = gx + dx2
                if (yy in 0 until gh && xx in 0 until gw) { s += lg[yy * gw + xx]; n++ }
            }
            t[gy * gw + gx] = s / n
        }
        lg = t
        return lg
    }

    private fun expWeights(px: IntArray, w: Int, h: Int, gw: Int, gh: Int, dx: Int, dy: Int): FloatArray {
        val lg = lumaGrid(px, w, h, gw, gh, dx, dy)
        for (i in lg.indices) {
            val d = (lg[i] - 125f) / 60f
            lg[i] = exp(-(d * d)) + 0.03f
        }
        return lg
    }

    private fun merge(shots: List<Shot>, rect: Rect, sample: Int, mode: Int, ghost: Int): Merged {
        val ref = decode(shots[0], rect, sample)
        if (shots.size == 1 || mode == 0) return ref
        val w = ref.w
        val h = ref.h
        val out = ref.px
        val cf = if (min(w, h) >= 600) 8 else 4
        val ff = max(2, cf / 2)
        val rg = gradSmall(out, w, h, cf)
        val rg2 = gradSmall(out, w, h, ff)
        val gw = 64
        val gh = max(2, gw * h / w)
        val sumGrid: FloatArray? = if (mode == 2) expWeights(out, w, h, gw, gh, 0, 0) else null
        val bil = Bil(w, h, gw, gh)
        var count = 1
        for (k in 1 until shots.size) {
            val cur = try { decode(shots[k], rect, sample) } catch (e: Throwable) { continue }
            if (cur.w != w || cur.h != h) continue
            val cg = gradSmall(cur.px, w, h, cf)
            val s1 = findShift(rg, cg, 16, 0, 0)
            val ratio = cf / ff
            val cg2 = gradSmall(cur.px, w, h, ff)
            val s2 = findShift(rg2, cg2, 3, s1[0] * ratio, s1[1] * ratio)
            val dx = s2[0] * ff
            val dy = s2[1] * ff
            val cp = cur.px
            if (mode == 1) {
                for (y in 0 until h) {
                    val sy = (y + dy).coerceIn(0, h - 1)
                    val ro = y * w
                    val rc = sy * w
                    for (x in 0 until w) {
                        val sx = (x + dx).coerceIn(0, w - 1)
                        val p = cp[rc + sx]
                        val o = out[ro + x]
                        val r0 = (o shr 16) and 255
                        val g0 = (o shr 8) and 255
                        val b0 = o and 255
                        val r1 = (p shr 16) and 255
                        val g1 = (p shr 8) and 255
                        val b1 = p and 255
                        if (abs(r1 - r0) + abs(g1 - g0) + abs(b1 - b0) > ghost) continue
                        val n = count
                        val nr = (r0 * n + r1 + (n + 1) / 2) / (n + 1)
                        val ng = (g0 * n + g1 + (n + 1) / 2) / (n + 1)
                        val nb = (b0 * n + b1 + (n + 1) / 2) / (n + 1)
                        out[ro + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                    }
                }
            } else {
                val wg = expWeights(cp, w, h, gw, gh, dx, dy)
                val sg = sumGrid!!
                for (y in 0 until h) {
                    bil.row(y)
                    val sy = (y + dy).coerceIn(0, h - 1)
                    val ro = y * w
                    val rc = sy * w
                    for (x in 0 until w) {
                        val sx = (x + dx).coerceIn(0, w - 1)
                        val p = cp[rc + sx]
                        val o = out[ro + x]
                        val wk = bil.at(wg, x)
                        val ws = bil.at(sg, x)
                        val t = wk / (ws + wk)
                        val r0 = (o shr 16) and 255
                        val g0 = (o shr 8) and 255
                        val b0 = o and 255
                        val nr = (r0 + (((p shr 16) and 255) - r0) * t).toInt().coerceIn(0, 255)
                        val ng = (g0 + (((p shr 8) and 255) - g0) * t).toInt().coerceIn(0, 255)
                        val nb = (b0 + ((p and 255) - b0) * t).toInt().coerceIn(0, 255)
                        out[ro + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                    }
                }
                for (i in sg.indices) sg[i] += wg[i]
            }
            count++
        }
        return ref
    }

    // ---------- টোন, রং, ডিটেইলস ----------

    private fun tonemap(px: IntArray, w: Int, h: Int, hdr: Boolean, night: Boolean) {
        val gw = 48
        val gh = max(2, gw * h / w)
        var gain = FloatArray(gw * gh) { 1f }
        if (hdr || night) {
            val lg = lumaGrid(px, w, h, gw, gh, 0, 0)
            gain = FloatArray(gw * gh)
            for (i in gain.indices) {
                gain[i] = if (night) (135f / max(lg[i], 25f)).pow(0.5f).coerceIn(1.0f, 2.0f)
                else (125f / max(lg[i], 30f)).pow(0.35f).coerceIn(0.9f, 1.5f)
            }
        }
        val lut = IntArray(256) { i ->
            val t = i / 255f
            val s = t * t * (3f - 2f * t)
            ((t * 0.78f + s * 0.22f) * 1.04f * 255f).toInt().coerceIn(0, 255)
        }
        val bil = Bil(w, h, gw, gh)
        for (y in 0 until h) {
            bil.row(y)
            val row = y * w
            for (x in 0 until w) {
                val g = bil.at(gain, x)
                val p = px[row + x]
                var r = lut[(((p shr 16) and 255) * g).toInt().coerceIn(0, 255)]
                var gr = lut[(((p shr 8) and 255) * g).toInt().coerceIn(0, 255)]
                var bl = lut[((p and 255) * g).toInt().coerceIn(0, 255)]
                val l = (r * 77 + gr * 150 + bl * 29) shr 8
                r = (l + (((r - l) * 276) shr 8)).coerceIn(0, 255)
                gr = (l + (((gr - l) * 276) shr 8)).coerceIn(0, 255)
                bl = (l + (((bl - l) * 276) shr 8)).coerceIn(0, 255)
                px[row + x] = (0xFF shl 24) or (r shl 16) or (gr shl 8) or bl
            }
        }
    }

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

    // নয়েজ না বাড়িয়ে শার্প (soft coring)
    private fun sh(v: Int, avg: Int, k: Int): Int {
        val d = v - avg
        val ad = abs(d)
        if (ad <= 4) return v
        val dd = min(((ad - 4) * k) shr 9, 36)
        return (if (d > 0) v + dd else v - dd).coerceIn(0, 255)
    }

    // ---------- পোর্ট্রেট ----------

    private fun personMask(src: Bitmap): Bitmap? {
        return try {
            val s = 512f / max(src.width, src.height)
            val small = Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
            val m = Tasks.await(segmenter.process(InputImage.fromBitmap(small, 0)))
            small.recycle()
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

    private fun composite(base: Bitmap, mask: Bitmap, level: Int): Bitmap {
        val w = base.width
        val h = base.height
        val bg = blurredBackground(base, mask, level)
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

    private fun discBlur(px: IntArray, w: Int, h: Int, rad: Int): IntArray {
        val out = IntArray(w * h)
        val xs = IntArray(2 * rad + 1)
        for (i in -rad..rad) xs[i + rad] = sqrt((rad * rad - i * i).toDouble()).toInt()
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0
            var sg = 0
            var sb = 0
            var n = 0
            for (dy in -rad..rad) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                val dm = xs[dy + rad]
                val xa = max(0, x - dm)
                val xb = min(w - 1, x + dm)
                val row = yy * w
                for (xx in xa..xb) {
                    val p = px[row + xx]
                    sr += (p shr 16) and 255
                    sg += (p shr 8) and 255
                    sb += p and 255
                    n++
                }
            }
            out[y * w + x] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
        }
        return out
    }

    // লেন্সের মতো গোল (bokeh) ব্লার: মানুষের কাছে হালকা, দূরে বেশি
    private fun blurredBackground(base: Bitmap, mask: Bitmap, level: Int): Bitmap {
        val s = 900f / max(base.width, base.height)
        val sw = max(8, (base.width * s).toInt())
        val sh = max(8, (base.height * s).toInt())
        val small = Bitmap.createScaledBitmap(base, sw, sh, true)
        val sp = IntArray(sw * sh)
        small.getPixels(sp, 0, sw, 0, 0, sw, sh)

        // উজ্জ্বল আলো বাড়ানো, যাতে ব্লারে গোল আলো তৈরি হয়
        val boosted = IntArray(sw * sh)
        for (i in sp.indices) {
            val p = sp[i]
            var r = (p shr 16) and 255
            var g = (p shr 8) and 255
            var b = p and 255
            if (r > 210) r = min(255, r + (r - 210) * 3)
            if (g > 210) g = min(255, g + (g - 210) * 3)
            if (b > 210) b = min(255, b + (b - 210) * 3)
            boosted[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val hw = sw / 2
        val hh = sh / 2
        val bb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        bb.setPixels(boosted, 0, sw, 0, 0, sw, sh)
        val half = Bitmap.createScaledBitmap(bb, hw, hh, true)
        val hp = IntArray(hw * hh)
        half.getPixels(hp, 0, hw, 0, 0, hw, hh)
        val rad = 2 + (level * 0.1f).toInt()
        val blurredHalf = discBlur(hp, hw, hh, rad)
        val bh = Bitmap.createBitmap(hw, hh, Bitmap.Config.ARGB_8888)
        bh.setPixels(blurredHalf, 0, hw, 0, 0, hw, hh)
        val strongBmp = Bitmap.createScaledBitmap(bh, sw, sh, true)
        val a1 = IntArray(sw * sh)
        strongBmp.getPixels(a1, 0, sw, 0, 0, sw, sh)

        val small2 = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        small2.setPixels(sp, 0, sw, 0, 0, sw, sh)
        val mild = boxBlur(small2, 3, 2)
        val b1 = IntArray(sw * sh)
        mild.getPixels(b1, 0, sw, 0, 0, sw, sh)

        val ms = Bitmap.createScaledBitmap(mask, sw, sh, true)
        val mp = IntArray(sw * sh)
        ms.getPixels(mp, 0, sw, 0, 0, sw, sh)
        for (i in mp.indices) {
            val a = mp[i] ushr 24
            mp[i] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
        }
        val gray = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        gray.setPixels(mp, 0, sw, 0, 0, sw, sh)
        val dist = boxBlur(gray, max(2, sw / 8), 2)
        val dp = IntArray(sw * sh)
        dist.getPixels(dp, 0, sw, 0, 0, sw, sh)

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

    // ---------- ফেস স্মুদ ----------

    private fun smooth01(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun beautify(bmp: Bitmap, strength: Int) {
        val w = bmp.width
        val h = bmp.height
        val s = 640f / max(w, h)
        val small = Bitmap.createScaledBitmap(bmp, (w * s).toInt(), (h * s).toInt(), true)
        val faces = try {
            Tasks.await(faceDet.process(InputImage.fromBitmap(small, 0)))
        } catch (e: Throwable) {
            return
        }
        small.recycle()
        val k = strength / 100f
        for (f in faces) {
            val box = f.boundingBox
            val fw = box.width() / s
            val fh = box.height() / s
            val cx = box.centerX() / s
            val cy = box.centerY() / s - fh * 0.05f
            val rw = (fw * 1.25f).toInt()
            val rh = (fh * 1.45f).toInt()
            val rx = (cx - rw / 2f).toInt().coerceIn(0, w - 2)
            val ry = (cy - rh / 2f).toInt().coerceIn(0, h - 2)
            val cw = min(rw, w - rx)
            val chh = min(rh, h - ry)
            if (cw < 20 || chh < 20) continue
            val crop = Bitmap.createBitmap(bmp, rx, ry, cw, chh)
            val rad = max(2, (fw / 55f).toInt())
            val blurred = boxBlur(crop, rad, 2)
            val po = IntArray(cw * chh)
            val pb = IntArray(cw * chh)
            crop.getPixels(po, 0, cw, 0, 0, cw, chh)
            blurred.getPixels(pb, 0, cw, 0, 0, cw, chh)
            for (y in 0 until chh) for (x in 0 until cw) {
                val nx = (x - cw / 2f) / (cw / 2f)
                val ny = (y - chh / 2f) / (chh / 2f)
                val d = nx * nx + ny * ny
                if (d >= 1f) continue
                val fall = min(1f, (1f - d) * 3f)
                val i = y * cw + x
                val o = po[i]
                val r = (o shr 16) and 255
                val g = (o shr 8) and 255
                val b = o and 255
                val cb = (128 * 256 - 43 * r - 85 * g + 128 * b) shr 8
                val cr = (128 * 256 + 128 * r - 107 * g - 21 * b) shr 8
                if (cb < 77 || cb > 127 || cr < 133 || cr > 173) continue
                val bl = pb[i]
                val diff = abs(lum(o) - lum(bl)).toFloat()
                val edge = 1f - smooth01((diff - 3f) / 14f)
                val wgt = k * 0.9f * fall * edge
                val nr = (r + (((bl shr 16) and 255) - r) * wgt).toInt().coerceIn(0, 255)
                val ng = (g + (((bl shr 8) and 255) - g) * wgt).toInt().coerceIn(0, 255)
                val nb = (b + ((bl and 255) - b) * wgt).toInt().coerceIn(0, 255)
                po[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
            bmp.setPixels(po, 0, cw, rx, ry, cw, chh)
            crop.recycle()
            blurred.recycle()
        }
    }

    // ---------- ফিল্টার ----------

    private fun applyFilter(bmp: Bitmap, idx: Int) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            var r = ((p shr 16) and 255).toFloat()
            var g = ((p shr 8) and 255).toFloat()
            var b = (p and 255).toFloat()
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            when (idx) {
                1 -> { // Vivid
                    r = (l + (r - l) * 1.35f - 128f) * 1.08f + 128f
                    g = (l + (g - l) * 1.35f - 128f) * 1.08f + 128f
                    b = (l + (b - l) * 1.35f - 128f) * 1.08f + 128f
                }
                2 -> { r *= 1.07f; g *= 1.02f; b *= 0.92f } // Warm
                3 -> { r *= 0.92f; b *= 1.08f }             // Cool
                4 -> { val m = (l - 128f) * 1.1f + 128f; r = m; g = m; b = m } // Mono
                5 -> { // Film
                    r = (l * 0.5f + r * 0.5f) * 1.06f + 10f
                    g = (l * 0.5f + g * 0.5f) * 0.98f + 6f
                    b = (l * 0.5f + b * 0.5f) * 0.85f + 8f
                }
            }
            px[i] = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    // ---------- JPEG ব্লক কমানো, নয়েজ কমানো, ভালো আপস্কেল ----------

    private fun smoothPix(l: Int, c: Int, r: Int): Int {
        val nr = ((((l shr 16) and 255) + 2 * ((c shr 16) and 255) + ((r shr 16) and 255)) shr 2)
        val ng = ((((l shr 8) and 255) + 2 * ((c shr 8) and 255) + ((r shr 8) and 255)) shr 2)
        val nb = (((l and 255) + 2 * (c and 255) + (r and 255)) shr 2)
        return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    // ৮x৮ JPEG ব্লকের কিনারা মসৃণ করা (জুমে "ফেটে যাওয়া" ভাব কমায়)
    private fun deblock(px: IntArray, w: Int, h: Int, ox: Int, oy: Int) {
        val t = 14
        var x = (8 - (ox % 8)) % 8
        if (x < 2) x += 8
        while (x < w - 2) {
            for (y in 0 until h) {
                val i = y * w + x
                val a = px[i - 1]
                val b = px[i]
                if (abs(lum(a) - lum(b)) < t) {
                    val a2 = px[i - 2]
                    val b2 = px[i + 1]
                    px[i - 1] = smoothPix(a2, a, b)
                    px[i] = smoothPix(a, b, b2)
                }
            }
            x += 8
        }
        var y = (8 - (oy % 8)) % 8
        if (y < 2) y += 8
        while (y < h - 2) {
            for (xx in 0 until w) {
                val i = y * w + xx
                val a = px[i - w]
                val b = px[i]
                if (abs(lum(a) - lum(b)) < t) {
                    val a2 = px[i - 2 * w]
                    val b2 = px[i + w]
                    px[i - w] = smoothPix(a2, a, b)
                    px[i] = smoothPix(a, b, b2)
                }
            }
            y += 8
        }
    }

    private fun blurT1(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var s = 0
            for (i in -r..r) s += src[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[x * h + y] = s / div
                s += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
    }

    private fun blurGray(a: IntArray, w: Int, h: Int, r: Int, passes: Int) {
        val tmp = IntArray(w * h)
        repeat(passes) {
            blurT1(a, tmp, w, h, r)
            blurT1(tmp, a, h, w, r)
        }
    }

    // রং-নয়েজ (দানা) কমানো + ডিটেইলস না নষ্ট করে ব্রাইটনেস-নয়েজ কমানো
    private fun denoise(px: IntArray, w: Int, h: Int, strength: Float) {
        if (strength <= 0.05f || w < 16 || h < 16) return
        val cw = w / 2
        val ch = h / 2
        val cb = IntArray(cw * ch)
        val cr = IntArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            var sb = 0
            var sr = 0
            for (dy in 0..1) for (dx in 0..1) {
                val p = px[(2 * y + dy) * w + 2 * x + dx]
                val yy = lum(p)
                sb += (p and 255) - yy
                sr += ((p shr 16) and 255) - yy
            }
            cb[y * cw + x] = sb shr 2
            cr[y * cw + x] = sr shr 2
        }
        val rc = 2 + (strength * 3f).toInt()
        blurGray(cb, cw, ch, rc, 2)
        blurGray(cr, cw, ch, rc, 2)

        val thr = (6 + 16 * strength).toInt()
        val wFlat = ((0.6f + 0.35f * strength) * 256f).toInt()
        val wEdge = (0.3f * 256f).toInt()
        var prevY = IntArray(w)
        var curY = IntArray(w)
        var nextY = IntArray(w)
        for (x in 0 until w) {
            curY[x] = lum(px[x])
            prevY[x] = curY[x]
        }
        for (y in 0 until h) {
            val ny = if (y + 1 < h) y + 1 else y
            for (x in 0 until w) nextY[x] = lum(px[ny * w + x])
            val row = y * w
            val cy = min(y / 2, ch - 1)
            for (x in 1 until w - 1) {
                val yc = curY[x]
                var sum = yc * 2
                var n = 2
                var v = prevY[x - 1]; if (abs(v - yc) <= thr) { sum += v; n++ }
                v = prevY[x]; if (abs(v - yc) <= thr) { sum += v; n++ }
                v = prevY[x + 1]; if (abs(v - yc) <= thr) { sum += v; n++ }
                v = curY[x - 1]; if (abs(v - yc) <= thr) { sum += v; n++ }
                v = curY[x + 1]; if (abs(v - yc) <= thr) { sum += v; n++ }
                v = nextY[x - 1]; if (abs(v - yc) <= thr) { sum += v; n++ }
                v = nextY[x]; if (abs(v - yc) <= thr) { sum += v; n++ }
                v = nextY[x + 1]; if (abs(v - yc) <= thr) { sum += v; n++ }
                val yn = sum / n
                val p = px[row + x]
                val cbo = (p and 255) - yc
                val cro = ((p shr 16) and 255) - yc
                val g = abs(curY[x + 1] - curY[x - 1]) + abs(prevY[x] - nextY[x])
                val wc = if (g > 30) wEdge else wFlat
                val ci = cy * cw + min(x / 2, cw - 1)
                val cbn = cbo + (((cb[ci] - cbo) * wc) shr 8)
                val crn = cro + (((cr[ci] - cro) * wc) shr 8)
                val r = (yn + crn).coerceIn(0, 255)
                val b = (yn + cbn).coerceIn(0, 255)
                val gg = (((yn shl 8) - 77 * r - 29 * b) / 150).coerceIn(0, 255)
                px[row + x] = (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
            }
            val t = prevY
            prevY = curY
            curY = nextY
            nextY = t
        }
    }

    private fun lanczosW(x: Float): Float {
        if (x == 0f) return 1f
        if (abs(x) >= 3f) return 0f
        val a = (Math.PI * x).toFloat()
        return (3f * sin(a) * sin(a / 3f)) / (a * a)
    }

    private class Taps(val idx: IntArray, val wt: FloatArray)

    private fun makeTaps(nIn: Int, nOut: Int): Taps {
        val scale = nOut.toFloat() / nIn
        val idx = IntArray(nOut * 6)
        val wt = FloatArray(nOut * 6)
        for (i in 0 until nOut) {
            val c = (i + 0.5f) / scale - 0.5f
            val left = floor(c).toInt() - 2
            var sum = 0f
            for (t in 0 until 6) {
                val sIdx = left + t
                val wv = lanczosW(c - sIdx)
                idx[i * 6 + t] = sIdx.coerceIn(0, nIn - 1)
                wt[i * 6 + t] = wv
                sum += wv
            }
            for (t in 0 until 6) wt[i * 6 + t] /= sum
        }
        return Taps(idx, wt)
    }

    // Lanczos আপস্কেল: সাধারণ বড় করার চেয়ে অনেক ধারালো, ব্লক ছাড়া
    private fun lanczos(src: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        val tx = makeTaps(w, nw)
        val ty = makeTaps(h, nh)
        val tmp = IntArray(nw * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until nw) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (t in 0 until 6) {
                    val p = src[row + tx.idx[x * 6 + t]]
                    val wv = tx.wt[x * 6 + t]
                    r += ((p shr 16) and 255) * wv
                    g += ((p shr 8) and 255) * wv
                    b += (p and 255) * wv
                }
                tmp[y * nw + x] = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
            }
        }
        val out = IntArray(nw * nh)
        val ro = IntArray(6)
        for (y in 0 until nh) {
            for (t in 0 until 6) ro[t] = ty.idx[y * 6 + t] * nw
            for (x in 0 until nw) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (t in 0 until 6) {
                    val p = tmp[ro[t] + x]
                    val wv = ty.wt[y * 6 + t]
                    r += ((p shr 16) and 255) * wv
                    g += ((p shr 8) and 255) * wv
                    b += (p and 255) * wv
                }
                out[y * nw + x] = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
            }
        }
        return out
    }

    // ---------- ব্লার সহায়ক ----------

    fun boxBlur(bmp: Bitmap, r: Int, passes: Int): Bitmap {
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
}
