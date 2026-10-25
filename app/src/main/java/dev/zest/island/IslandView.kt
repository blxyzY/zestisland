package dev.zest.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pegas bentuk tertutup (analitik, stabil di frame berapa pun).
 * response = periode osilasi dalam detik, damping = rasio redaman.
 * Gaya yang sama dengan island referensi (response ~0.3-0.45, damping ~0.6-0.9).
 */
private class Spr(v: Float, var response: Float, var damping: Float, private val eps: Float = 0.4f) {
    var value = v
    var target = v
    var vel = 0f
    var rest = true

    fun to(t: Float, r: Float = response, d: Float = damping) {
        response = r
        damping = d
        if (t != target || !rest) {
            target = t
            rest = false
        }
    }

    fun snap(v: Float) {
        value = v; target = v; vel = 0f; rest = true
    }

    /** true bila masih bergerak. */
    fun step(dtIn: Float): Boolean {
        if (rest) return false
        val dt = dtIn.coerceIn(0f, 0.064f).toDouble()
        val w = 2.0 * PI / max(0.016f, response).toDouble()
        val z = max(0.05f, damping).toDouble()
        val x0 = (value - target).toDouble()
        val v0 = vel.toDouble()
        val x: Double
        val v: Double
        if (z < 1.0) {
            val wd = w * sqrt(1.0 - z * z)
            val e = exp(-z * w * dt)
            val c = cos(wd * dt)
            val s = sin(wd * dt)
            val b = (z * w * x0 + v0) / wd
            x = e * (x0 * c + b * s)
            v = -z * w * x + e * (-wd * x0 * s + wd * b * c)
        } else {
            val b = w * x0 + v0
            val e = exp(-w * dt)
            x = (x0 + b * dt) * e
            v = (b - w * (x0 + b * dt)) * e
        }
        value = (target + x).toFloat()
        vel = v.toFloat()
        if (abs(x) < eps && abs(v) < eps * 60f) {
            value = target; vel = 0f; rest = true
            return false
        }
        return true
    }
}

private class Block(val key: String, var item: IslandItem) {
    val a = Spr(0f, 0.32f, 0.9f, 0.002f)
    val y = Spr(0f, 0.45f, 0.75f, 0.4f)
    var live = true
    var fresh = true
    var h = 0f
    var title = ""
    var sub = ""
    var artBmp: Bitmap? = null
    var shader: BitmapShader? = null
}

/**
 * Seluruh island digambar di satu View (Canvas) pada jendela berukuran tetap.
 * Tidak ada child view / layout per frame -> animasi pegas mulus.
 */
class IslandView(context: Context) : View(context) {
    var onToggle: (() -> Unit)? = null
    var onOutside: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Int): Float = v * density
    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // Cutout A23: sekitar 61x23dp di tengah atas.
    private var collapsedW = dp(118)
    private var collapsedH = dp(28)
    private var satD = collapsedH
    private val satGap = dp(4)
    private val margin = dp(16)
    private val expandedW = min(resources.displayMetrics.widthPixels - dp(24), dp(380))
    private val pad = dp(12)
    private val blockGap = dp(8)
    private val blockW get() = expandedW - 2 * pad
    private val cornerExpanded = dp(30)

    // Pegas (gaya referensi)
    private val sw = Spr(collapsedW * 0.6f, 0.36f, 0.74f, 0.4f)
    private val sh = Spr(collapsedH * 0.6f, 0.38f, 0.70f, 0.4f)
    private val sr = Spr(collapsedH / 2f, 0.35f, 0.9f, 0.4f)
    private val sa = Spr(0f, 0.2f, 1f, 0.002f)        // isi kartu
    private val vis = Spr(0f, 0.22f, 1f, 0.002f)      // muncul
    private val split = Spr(0f, 0.46f, 0.62f, 0.002f) // kapsul kedua
    private val press = Spr(1f, 0.2f, 0.9f, 0.0005f)
    private val springs = listOf(sw, sh, sr, sa, vis, split, press)

    private var items: List<IslandItem> = emptyList()
    private var expanded = false
    private var primary: IslandItem? = null
    private var satItem: IslandItem? = null
    private val blocks = ArrayList<Block>()
    private var cardH = collapsedH

    // art kecil di pil
    private var pArtBmp: Bitmap? = null
    private var pArtShader: BitmapShader? = null

    // cat
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val blockBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E1E22.toInt() }
    private val iconBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A40.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = sp(15f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val tSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0B0B8.toInt(); textSize = sp(13f)
    }
    private val tBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = sp(19f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT; fontFeatureSettings = "tnum"
    }
    private val tPill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = sp(13f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT; fontFeatureSettings = "tnum"
    }
    private val tNotice = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = sp(13f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val tSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA0A0A8.toInt(); textSize = sp(11f); fontFeatureSettings = "tnum"
    }

    private val path = Path()
    private val rect = RectF()
    private val clipR = RectF()
    private val tmp = Matrix()

    // loop animasi
    private var running = false
    private var lastNs = 0L
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(nowNs: Long) {
            val dt = if (lastNs == 0L) 1f / 60f else (nowNs - lastNs) / 1e9f
            lastNs = nowNs
            var moving = false
            for (s in springs) if (s.step(dt)) moving = true
            for (b in blocks) {
                if (b.a.step(dt)) moving = true
                if (b.y.step(dt)) moving = true
            }
            blocks.removeAll { !it.live && it.a.rest && it.a.value < 0.01f }
            invalidateIsland()
            if (moving || mediaPlaying()) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                running = false
                lastNs = 0L
                dirtyW = 0f; dirtyH = 0f
                invalidate()   // bersihkan sisa area gambar
            }
        }
    }

    private val dirty = Rect()
    private var dirtyW = 0f
    private var dirtyH = 0f

    /** Hanya gambar ulang area island (bukan seluruh jendela besar) -> jauh lebih ringan di GPU lemah. */
    private fun invalidateIsland() {
        dirtyW = max(dirtyW * 0.97f, max(sw.value, collapsedW) + 2 * (satD + satGap))
        dirtyH = max(dirtyH * 0.97f, max(sh.value, collapsedH))
        val cx = width / 2f + offX
        val m = dp(24)
        dirty.set(
            (cx - dirtyW / 2f - m).toInt().coerceAtLeast(0),
            max(0f, offY - m).toInt(),
            (cx + dirtyW / 2f + m).toInt().coerceAtMost(width),
            (offY + dirtyH * 1.12f + m).toInt().coerceAtMost(height)
        )
        invalidate(dirty)
    }

    private fun kick() {
        if (running) return
        running = true
        lastNs = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun mediaPlaying() = items.any { (it.kind == Kind.MEDIA && it.playing) || it.kind == Kind.NOTICE }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Muncul dengan pegas setiap kali jendela dipasang.
        sw.snap(collapsedW * 0.6f)
        sh.snap(collapsedH * 0.6f)
        sr.snap(collapsedH / 2f)
        sa.snap(0f)
        vis.snap(0f)
        split.snap(0f)
        press.snap(1f)
        retarget()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        running = false
        lastNs = 0L
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ API

    private var offX = 0f
    private var offY = 0f

    /** Geser posisi pill (px) dari UN1CA Settings. */
    fun setOffset(x: Float, y: Float) {
        if (x == offX && y == offY) return
        offX = x
        offY = y
        invalidate()
    }

    /** Tinggi jendela tetap yang cukup untuk kartu terbesar (3 notifikasi + musik). */
    fun fixedWindowHeight(): Int = (dp(430) + max(0f, offY)).toInt()

    /** Area sentuh saat ini (koordinat jendela) untuk touchable region. */
    fun touchBounds(out: Rect) {
        if (items.isEmpty() && vis.value < 0.02f) { out.setEmpty(); return }
        val cx = width / 2f + offX
        val slop = dp(8)
        val hw: Float
        val bottom: Float
        var extraRight = 0f
        if (expanded || sa.value > 0.02f) {
            hw = max(sw.value, expandedW) / 2f
            bottom = max(sh.value, cardH)
        } else {
            hw = max(sw.value, collapsedW) / 2f
            bottom = max(sh.value, collapsedH)
            if (satItem != null || split.value > 0.02f) extraRight = satGap + satD
        }
        out.set(
            (cx - hw - slop).toInt(),
            (offY).toInt().coerceAtLeast(0),
            (cx + hw + extraRight + slop).toInt(),
            (offY + bottom * 1.06f + slop).toInt()
        )
    }

    /** Ukuran pill (dp) dari UN1CA Settings; 0 = bawaan. */
    fun setPillSize(wDp: Int, hDp: Int) {
        val w = if (wDp <= 0) 118 else wDp.coerceIn(40, 300)
        val h = if (hDp <= 0) 28 else hDp.coerceIn(16, 80)
        val nw = dp(w)
        val nh = dp(h)
        if (nw == collapsedW && nh == collapsedH) return
        collapsedW = nw
        collapsedH = nh
        satD = nh
        pArtBmp = null
        pickPrimary()
        syncBlocks()
        retarget()
    }

    fun bind(newItems: List<IslandItem>, isExpanded: Boolean) {
        items = newItems
        expanded = isExpanded
        pickPrimary()
        syncBlocks()
        retarget()
    }

    /**
     * Ukuran jendela saat kartu terbuka: selalu cukup untuk media + 3 notifikasi, jadi jendela
     * tidak perlu diubah ukurannya (dan tidak berkedip) ketika blok bertambah.
     */
    fun expandedSize(): Pair<Int, Int> {
        // Kartu sekarang + ruang untuk satu notifikasi lagi (tanpa ubah ukuran jendela).
        val need = max(cardH, collapsedH) + dp(64) + blockGap
        return Pair((expandedW + 2 * margin).toInt(), (need * 1.06f + margin).toInt())
    }

    /** True bila semua pegas diam (dipakai service untuk menyusutkan jendela). */
    fun isSettled(): Boolean =
        springs.all { it.rest } && blocks.all { it.a.rest && it.y.rest }

    /** Ukuran jendela saat pill menyusut (cukup untuk pill + kapsul kedua + pegas). */
    fun collapsedWindowSize(): Pair<Int, Int> =
        Pair((max(collapsedW, dp(210)) + 2 * (satD + satGap) + 2 * margin).toInt(), (collapsedH * 1.1f + margin).toInt())

    /** Dipanggil ~20x/detik dari service: timer & progress. */
    fun tick() {
        invalidateIsland()
    }

    // ------------------------------------------------------------ pemilihan

    private fun pickPrimary() {
        val notice = items.firstOrNull { it.kind == Kind.NOTICE }
        val list = items.filter { it.kind != Kind.NOTICE }
        val first = notice ?: list.firstOrNull()
        if (first == null) {
            primary = null; satItem = null
            return
        }
        val media = list.firstOrNull { it.kind == Kind.MEDIA }
        val timerLike = list.firstOrNull { it.kind == Kind.TIMER || it.kind == Kind.CALL }
        if (notice == null && first.kind != Kind.ALERT && media != null && timerLike != null) {
            primary = media
            satItem = timerLike
        } else {
            primary = first
            satItem = null
        }
        val p = primary
        val art = p?.art
        if (art != null) {
            if (art !== pArtBmp) {
                pArtBmp = art
                pArtShader = makeShader(art, artSize())
            }
        } else {
            pArtBmp = null; pArtShader = null
        }
    }

    private fun artSize() = min(collapsedH * 0.7f, dp(22))

    private fun makeShader(bmp: Bitmap, size: Float): BitmapShader {
        val sh = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val sc = size / min(bmp.width, bmp.height).toFloat()
        val m = Matrix()
        m.setScale(sc, sc)
        m.postTranslate((size - bmp.width * sc) / 2f, (size - bmp.height * sc) / 2f)
        sh.setLocalMatrix(m)
        return sh
    }

    private fun blockHeight(i: IslandItem): Float = if (i.kind == Kind.MEDIA) dp(132) else dp(64)

    private fun syncBlocks() {
        val list = items.filter { it.kind != Kind.NOTICE }
        val keys = list.map { it.key }.toHashSet()
        val byKey = HashMap<String, Block>()
        for (b in blocks) byKey[b.key] = b
        val live = ArrayList<Block>()
        for (it in list) {
            var b = byKey[it.key]
            if (b == null) {
                b = Block(it.key, it)
                blocks.add(b)
            }
            b.item = it
            b.live = true
            prepare(b)
            live.add(b)
        }
        for (b in blocks) if (b.key !in keys) {
            b.live = false
            b.a.to(0f, 0.2f, 1f)
        }
        var cursor = pad
        for (b in live) {
            b.h = blockHeight(b.item)
            if (b.fresh) {
                b.fresh = false
                b.y.snap(cursor - dp(14))
                b.a.snap(0f)
            }
            b.y.to(cursor, 0.45f, 0.75f)
            b.a.to(1f, 0.34f, 1f)
            cursor += b.h + blockGap
        }
        cardH = if (live.isEmpty()) collapsedH else cursor - blockGap + pad
    }

    private fun prepare(b: Block) {
        val i = b.item
        val media = i.kind == Kind.MEDIA
        val left = if (media) dp(74) else dp(60)
        val right = when {
            media -> dp(44)
            i.hasTimer -> dp(96)
            else -> dp(14)
        }
        val maxW = max(dp(40), blockW - left - right)
        b.title = TextUtils.ellipsize(i.title, tTitle, maxW, TextUtils.TruncateAt.END).toString()
        b.sub = TextUtils.ellipsize(i.subtitle, tSub, maxW, TextUtils.TruncateAt.END).toString()
        val art = i.art
        if (art != null) {
            if (art !== b.artBmp) {
                b.artBmp = art
                b.shader = makeShader(art, dp(58))
            }
        } else {
            b.artBmp = null; b.shader = null
        }
        i.icon?.let { d ->
            try { d.mutate().setTint(0xFFFFFFFF.toInt()) } catch (_: Throwable) {}
        }
    }

    private fun collapsedTargetW(): Float {
        val p = primary
        if (p == null || p.kind != Kind.NOTICE || p.title.isEmpty()) return collapsedW
        val gs = collapsedH * 0.56f
        val w = dp(16) + gs + dp(8) + tNotice.measureText(p.title) + dp(16)
        return min(max(collapsedW, w), dp(200))
    }

    private fun retarget() {
        if (expanded) {
            sw.to(expandedW, 0.36f, 0.74f)
            sh.to(max(cardH, collapsedH), 0.38f, 0.70f)
            sr.to(cornerExpanded, 0.35f, 0.9f)
            sa.to(1f, 0.30f, 1f)
        } else {
            sw.to(collapsedTargetW(), 0.38f, 0.70f)
            sh.to(collapsedH, 0.34f, 0.75f)
            sr.to(collapsedH / 2f, 0.34f, 0.9f)
            sa.to(0f, 0.16f, 1f)
        }
        split.to(if (satItem != null && !expanded) 1f else 0f, 0.46f, 0.62f)
        vis.to(if (items.isEmpty()) 0f else 1f, 0.22f, 1f)
        kick()
    }

    // --------------------------------------------------------------- sentuh

    private enum class Hit { NONE, PILL, PREV, PLAY, NEXT, OPEN, CARD }

    private var downHit = Hit.NONE
    private var downBlock: Block? = null

    private fun cx() = width / 2f

    private fun blockRect(b: Block, out: RectF): RectF {
        val l = cx() - blockW / 2f
        out.set(l, b.y.value, l + blockW, b.y.value + b.h)
        return out
    }

    private val ctlRects = arrayOf(RectF(), RectF(), RectF())
    private val ctlTmp = RectF()

    /** Mengisi (dan mengembalikan) array tetap -> tanpa alokasi per frame. */
    private fun controlRects(b: Block): Array<RectF> {
        val r = blockRect(b, ctlTmp)
        val cy = r.top + dp(114)
        val c = r.centerX()
        val half = dp(26)
        ctlRects[0].set(c - dp(88) - half, cy - half, c - dp(88) + half, cy + half)
        ctlRects[1].set(c - half, cy - half, c + half, cy + half)
        ctlRects[2].set(c + dp(88) - half, cy - half, c + dp(88) + half, cy + half)
        return ctlRects
    }

    private val hitTmp = RectF()

    private fun hit(rx: Float, ry: Float): Pair<Hit, Block?> {
        val x = rx - offX
        val y = ry - offY
        val cx = cx()
        if (!expanded) {
            val slop = dp(6)
            val hw = max(collapsedW, sw.value) / 2f
            if (x >= cx - hw - slop && x <= cx + hw + slop && y <= collapsedH + slop) {
                return Hit.PILL to null
            }
            if (satItem != null && x >= cx + collapsedW / 2f && x <= cx + collapsedW / 2f + satGap + satD + slop &&
                y <= collapsedH + slop
            ) return Hit.PILL to null
            return Hit.NONE to null
        }
        val tmpR = hitTmp
        for (b in blocks) {
            if (!b.live) continue
            if (b.item.kind == Kind.MEDIA) {
                val cr = controlRects(b)
                if (cr[0].contains(x, y)) return Hit.PREV to b
                if (cr[1].contains(x, y)) return Hit.PLAY to b
                if (cr[2].contains(x, y)) return Hit.NEXT to b
            }
            if (blockRect(b, tmpR).contains(x, y)) return Hit.OPEN to b
        }
        if (x >= cx - sw.value / 2f && x <= cx + sw.value / 2f && y <= sh.value) return Hit.CARD to null
        return Hit.NONE to null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                if (expanded) onOutside?.invoke()
                return false
            }
            MotionEvent.ACTION_DOWN -> {
                val (h, b) = hit(e.x, e.y)
                downHit = h
                downBlock = b
                if (h == Hit.NONE) return false
                if (h == Hit.PILL) {
                    press.to(0.94f, 0.2f, 0.9f)
                    kick()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val was = downHit
                val wasBlock = downBlock
                downHit = Hit.NONE
                downBlock = null
                if (was == Hit.PILL) {
                    press.to(1f, 0.35f, 0.6f)
                    kick()
                }
                if (was == Hit.NONE) return false
                val (h, b) = hit(e.x, e.y)
                if (h == was && b === wasBlock) perform(h, b)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (downHit == Hit.PILL) {
                    press.to(1f, 0.35f, 0.6f)
                    kick()
                }
                downHit = Hit.NONE
                downBlock = null
                return true
            }
        }
        return downHit != Hit.NONE
    }

    private fun perform(h: Hit, b: Block?) {
        val item = b?.item
        try {
            when (h) {
                Hit.PILL, Hit.CARD -> onToggle?.invoke()
                Hit.PREV -> item?.controller?.transportControls?.skipToPrevious()
                Hit.NEXT -> item?.controller?.transportControls?.skipToNext()
                Hit.PLAY -> item?.let {
                    if (it.playing) it.controller?.transportControls?.pause()
                    else it.controller?.transportControls?.play()
                }
                Hit.OPEN -> {
                    if (item != null) {
                        if (item.kind == Kind.MEDIA) item.controller?.sessionActivity?.send()
                        else item.tap?.send()
                    }
                    onToggle?.invoke()
                }
                Hit.NONE -> Unit
            }
        } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------- gambar

    override fun onDraw(c: Canvas) {
        c.save()
        c.translate(offX, offY)
        try {
            drawIsland(c)
        } catch (_: Throwable) {
        }
        c.restore()
    }

    private fun drawIsland(c: Canvas) {
        val v = vis.value.coerceIn(0f, 1f)
        if (v < 0.01f && items.isEmpty()) return
        val cx = cx()
        val w = max(dp(8), sw.value)
        val h = max(dp(8), sh.value)
        val appear = 0.55f + 0.45f * v
        val pulse = (sw.vel / 9000f).coerceIn(-0.05f, 0.05f)   // regang saat melebar
        val sx = (1f + pulse) * appear * press.value
        val sy = (1f - pulse * 0.6f) * appear * press.value
        val left = cx - w / 2f
        val top = 0f
        val pivotY = min(h / 2f, collapsedH / 2f)

        c.save()
        c.scale(sx, sy, cx, pivotY)
        val alpha = (255 * v).toInt()
        bg.alpha = alpha

        // pil utama
        rect.set(left, top, left + w, top + h)
        clipR.set(rect)
        val r = min(sr.value, min(w, h) / 2f)
        c.drawRoundRect(rect, r, r, bg)

        // kapsul kedua (stopwatch/telepon)
        val spl = split.value.coerceIn(0f, 1f)
        if (spl > 0.01f) {
            val d = satD * spl
            val scx = cx + collapsedW / 2f + satGap + satD / 2f
            c.drawCircle(scx, h / 2f, d / 2f, bg)
            val si = satItem
            if (si != null && spl > 0.5f) drawSatellite(c, si, scx, h / 2f, (spl - 0.5f) * 2f * v)
        }

        // isi
        c.save()
        c.clipRect(clipR)
        val expA = sa.value.coerceIn(0f, 1f)
        val ca = (1f - expA * 3f).coerceIn(0f, 1f) * v
        if (ca > 0.02f) drawCollapsed(c, left, w, ca)
        val ea = ((expA - 0.2f) / 0.8f).coerceIn(0f, 1f) * v
        if (ea > 0.02f) {
            for (b in blocks) {
                val a = ea * b.a.value.coerceIn(0f, 1f)
                if (a > 0.02f) drawBlock(c, b, cx, a)
            }
        }
        c.restore()
        c.restore()
    }

    private fun drawCollapsed(c: Canvas, left: Float, w: Float, a: Float) {
        val p = primary ?: return
        val h = collapsedH
        val midY = h / 2f
        val ia = (255 * a).toInt()
        if (p.kind == Kind.NOTICE) {
            drawNotice(c, p, left, w, midY, ia)
            return
        }
        val s = artSize()
        val ax = left + dp(9)
        val ay = midY - s / 2f
        val sh = pArtShader
        if (p.art != null && sh != null) {
            shaderPaint.shader = sh
            shaderPaint.alpha = ia
            c.save()
            c.translate(ax, ay)
            c.drawCircle(s / 2f, s / 2f, s / 2f, shaderPaint)
            c.restore()
            shaderPaint.shader = null
        } else {
            drawIcon(c, p.icon, ax, ay, s, ia)
        }
        val rx = left + w - dp(10)
        when {
            p.hasTimer -> {
                tPill.alpha = ia
                val fm = tPill.fontMetrics
                c.drawText(formatTimer(p, true), rx, midY - (fm.ascent + fm.descent) / 2f, tPill)
            }
            p.kind == Kind.MEDIA -> drawEq(c, rx - dp(14), midY, min(h * 0.45f, dp(12)), ia, p.playing)
        }
    }


    // ------------------------------------------------------- pemberitahuan kecil

    private fun drawNotice(c: Canvas, p: IslandItem, left: Float, w: Float, midY: Float, ia: Int) {
        val t = (SystemClock.uptimeMillis() - p.shownAt) / 1000f
        val gs = collapsedH * 0.56f
        val hasText = p.title.isNotEmpty()
        val textW = if (hasText) tNotice.measureText(p.title) else 0f
        val total = gs + if (hasText) dp(8) + textW else 0f
        val x0 = left + (w - total) / 2f
        drawGlyph(c, p, x0 + gs / 2f, midY, gs, ia, t)
        if (hasText) {
            tNotice.alpha = ia
            val fm = tNotice.fontMetrics
            c.drawText(p.title, x0 + gs + dp(8), midY - (fm.ascent + fm.descent) / 2f, tNotice)
        }
    }

    private fun drawGlyph(c: Canvas, p: IslandItem, cx: Float, cy: Float, s: Float, ia: Int, t: Float) {
        val col = if (p.accent == -1) 0xFFFFFFFF.toInt() else p.accent
        stroke.color = col
        stroke.alpha = ia
        stroke.strokeWidth = max(dp(1.3f), s * 0.1f)
        stroke.strokeJoin = Paint.Join.ROUND
        fill.color = col
        fill.alpha = ia
        when (p.glyph) {
            "ring", "silent" -> {
                c.save()
                if (p.glyph == "ring") c.rotate(sin(t * 22f) * 14f * exp(-t * 3.2f), cx, cy - s * 0.45f)
                bell(c, cx, cy, s)
                c.restore()
                if (p.glyph == "silent") {
                    c.drawLine(cx - s * 0.5f, cy - s * 0.5f, cx + s * 0.5f, cy + s * 0.5f, stroke)
                }
            }
            "vibrate" -> {
                val dx = sin(t * 55f) * s * 0.07f * exp(-t * 2.5f)
                c.save()
                c.translate(dx, 0f)
                rect.set(cx - s * 0.2f, cy - s * 0.42f, cx + s * 0.2f, cy + s * 0.42f)
                c.drawRoundRect(rect, s * 0.08f, s * 0.08f, stroke)
                c.restore()
                for (k in 0 until 2) {
                    val o = s * (0.36f + k * 0.14f)
                    c.drawLine(cx - o, cy - s * 0.16f, cx - o, cy + s * 0.16f, stroke)
                    c.drawLine(cx + o, cy - s * 0.16f, cx + o, cy + s * 0.16f, stroke)
                }
            }
            "charge", "low" -> {
                val bw = s * 1.05f
                val bh = s * 0.55f
                rect.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f - s * 0.07f, cy + bh / 2f)
                stroke.strokeWidth = max(dp(1.1f), s * 0.07f)
                c.drawRoundRect(rect, bh * 0.28f, bh * 0.28f, stroke)
                rect.set(cx + bw / 2f - s * 0.05f, cy - bh * 0.17f, cx + bw / 2f + s * 0.02f, cy + bh * 0.17f)
                c.drawRoundRect(rect, s * 0.02f, s * 0.02f, fill)
                val frac = (p.level / 100f).coerceIn(0.08f, 1f)
                val inL = cx - bw / 2f + s * 0.09f
                val inR = cx + bw / 2f - s * 0.16f
                rect.set(inL, cy - bh / 2f + s * 0.09f, inL + (inR - inL) * frac, cy + bh / 2f - s * 0.09f)
                c.drawRoundRect(rect, bh * 0.16f, bh * 0.16f, fill)
                if (p.glyph == "charge") {
                    fill.color = 0xFFFFFFFF.toInt()
                    fill.alpha = ia
                    path.reset()
                    path.moveTo(cx + s * 0.06f, cy - bh * 0.42f)
                    path.lineTo(cx - s * 0.14f, cy + s * 0.02f)
                    path.lineTo(cx - s * 0.01f, cy + s * 0.02f)
                    path.lineTo(cx - s * 0.06f, cy + bh * 0.42f)
                    path.lineTo(cx + s * 0.16f, cy - s * 0.04f)
                    path.lineTo(cx + s * 0.03f, cy - s * 0.04f)
                    path.close()
                    c.drawPath(path, fill)
                }
            }
            "unlock" -> {
                val k = (t / 0.32f).coerceIn(0f, 1f)
                val e = 1f - (1f - k) * (1f - k)                 // easeOut
                val bounce = 1f + 0.12f * sin(k * PI.toFloat())
                c.save()
                c.scale(bounce, bounce, cx, cy)
                fill.color = 0xFFFFFFFF.toInt()
                fill.alpha = ia
                rect.set(cx - s * 0.4f, cy - s * 0.04f, cx + s * 0.4f, cy + s * 0.46f)
                c.drawRoundRect(rect, s * 0.1f, s * 0.1f, fill)
                stroke.color = 0xFFFFFFFF.toInt()
                stroke.alpha = ia
                stroke.strokeWidth = max(dp(1.4f), s * 0.12f)
                val lift = s * 0.16f * e
                val lx = cx - s * 0.24f
                val rx = cx + s * 0.24f
                path.reset()
                path.moveTo(lx, cy - s * 0.04f)
                path.lineTo(lx, cy - s * 0.3f)
                path.arcTo(lx, cy - s * 0.54f, rx, cy - s * 0.06f, 180f, 180f, false)
                path.lineTo(rx, cy - s * 0.04f - lift)
                c.drawPath(path, stroke)
                c.restore()
            }
        }
    }

    private fun bell(c: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx - s * 0.4f, cy + s * 0.3f)
        path.lineTo(cx - s * 0.32f, cy + s * 0.2f)
        path.lineTo(cx - s * 0.3f, cy - s * 0.1f)
        path.arcTo(cx - s * 0.3f, cy - s * 0.52f, cx + s * 0.3f, cy + s * 0.08f, 180f, 180f, false)
        path.lineTo(cx + s * 0.32f, cy + s * 0.2f)
        path.lineTo(cx + s * 0.4f, cy + s * 0.3f)
        path.close()
        c.drawPath(path, stroke)
        c.drawCircle(cx, cy + s * 0.46f, s * 0.08f, fill)
    }

    private fun drawEq(c: Canvas, x: Float, midY: Float, maxH: Float, ia: Int, playing: Boolean) {
        val t = SystemClock.uptimeMillis() / 1000f
        fill.color = 0xFFFFFFFF.toInt()
        fill.alpha = ia
        val bw = dp(2.5f)
        val gap = dp(2f)
        for (i in 0 until 4) {
            val k = if (playing) 0.3f + 0.7f * abs(sin(t * 5.2f + i * 1.7f) * cos(t * 2.3f + i)) else 0.22f
            val bh = max(dp(2f), maxH * k)
            val bx = x + i * (bw + gap)
            rect.set(bx, midY - bh / 2f, bx + bw, midY + bh / 2f)
            c.drawRoundRect(rect, bw / 2f, bw / 2f, fill)
        }
    }

    private fun drawIcon(c: Canvas, d: Drawable?, x: Float, y: Float, size: Float, ia: Int) {
        if (d == null) return
        d.setBounds(x.toInt(), y.toInt(), (x + size).toInt(), (y + size).toInt())
        d.alpha = ia
        d.draw(c)
    }

    private fun drawSatellite(c: Canvas, item: IslandItem, cx: Float, cy: Float, a: Float) {
        val ia = (255 * a.coerceIn(0f, 1f)).toInt()
        val art = item.art
        if (art != null) {
            val s = satD * 0.72f
            val sh = makeShader(art, s)
            shaderPaint.shader = sh
            shaderPaint.alpha = ia
            c.save()
            c.translate(cx - s / 2f, cy - s / 2f)
            c.drawCircle(s / 2f, s / 2f, s / 2f, shaderPaint)
            c.restore()
            shaderPaint.shader = null
            return
        }
        if (item.kind == Kind.CALL && item.icon != null) {
            val s = satD * 0.6f
            drawIcon(c, item.icon, cx - s / 2f, cy - s / 2f, s, ia)
            return
        }
        // ikon stopwatch
        val r = satD * 0.27f
        val orange = 0xFFFF9F0A.toInt()
        stroke.color = orange
        stroke.alpha = ia
        stroke.strokeWidth = max(dp(1.4f), satD * 0.06f)
        val oy = cy + satD * 0.04f
        c.drawCircle(cx, oy, r, stroke)
        c.drawLine(cx, oy, cx, oy - r * 0.62f, stroke)
        val ang = (SystemClock.uptimeMillis() % 60_000L) / 60_000f * 2f * PI.toFloat()
        stroke.color = 0xFFFFFFFF.toInt()
        stroke.alpha = ia
        c.drawLine(cx, oy, cx + sin(ang) * r * 0.62f, oy - cos(ang) * r * 0.62f, stroke)
        fill.color = orange
        fill.alpha = ia
        rect.set(cx - r * 0.22f, oy - r - satD * 0.1f, cx + r * 0.22f, oy - r - satD * 0.02f)
        c.drawRoundRect(rect, dp(1f), dp(1f), fill)
    }

    private fun drawBlock(c: Canvas, b: Block, cx: Float, a: Float) {
        val ia = (255 * a).toInt()
        val x0 = cx - blockW / 2f
        val y0 = b.y.value
        val i = b.item
        if (i.kind == Kind.MEDIA) {
            drawMedia(c, b, x0, y0, ia)
            return
        }
        blockBg.alpha = ia
        rect.set(x0, y0, x0 + blockW, y0 + b.h)
        c.drawRoundRect(rect, dp(20), dp(20), blockBg)
        // ikon
        val ix = x0 + dp(10)
        val iy = y0 + (b.h - dp(40)) / 2f
        iconBg.alpha = ia
        rect.set(ix, iy, ix + dp(40), iy + dp(40))
        c.drawRoundRect(rect, dp(12), dp(12), iconBg)
        drawIcon(c, i.icon, ix + dp(9), iy + dp(9), dp(22), ia)
        val tx = x0 + dp(60)
        tTitle.alpha = ia
        tSub.alpha = ia
        c.drawText(b.title, tx, y0 + b.h * 0.42f, tTitle)
        if (b.sub.isNotEmpty()) c.drawText(b.sub, tx, y0 + b.h * 0.70f, tSub)
        if (i.hasTimer) {
            tBig.alpha = ia
            val fm = tBig.fontMetrics
            c.drawText(formatTimer(i, false), x0 + blockW - dp(14), y0 + b.h / 2f - (fm.ascent + fm.descent) / 2f, tBig)
        }
    }

    private fun drawMedia(c: Canvas, b: Block, x0: Float, y0: Float, ia: Int) {
        val i = b.item
        val artS = dp(58)
        val ax = x0 + dp(2)
        val ay = y0 + dp(2)
        val sh = b.shader
        if (sh != null && i.art != null) {
            shaderPaint.shader = sh
            shaderPaint.alpha = ia
            c.save()
            c.translate(ax, ay)
            c.drawRoundRect(0f, 0f, artS, artS, dp(12), dp(12), shaderPaint)
            c.restore()
            shaderPaint.shader = null
        } else {
            iconBg.alpha = ia
            rect.set(ax, ay, ax + artS, ay + artS)
            c.drawRoundRect(rect, dp(12), dp(12), iconBg)
            drawIcon(c, i.icon, ax + dp(15), ay + dp(15), dp(28), ia)
        }
        val tx = x0 + dp(74)
        tTitle.alpha = ia
        tTitle.textSize = sp(17f)
        tSub.alpha = ia
        c.drawText(b.title, tx, y0 + dp(26), tTitle)
        tTitle.textSize = sp(15f)
        if (b.sub.isNotEmpty()) c.drawText(b.sub, tx, y0 + dp(48), tSub)
        drawEq(c, x0 + blockW - dp(28), y0 + dp(14), dp(16), ia, i.playing)

        // progress
        var pos = i.positionMs
        if (i.playing) pos += ((SystemClock.elapsedRealtime() - i.posTime) * i.speed).toLong()
        pos = if (i.durationMs > 0) pos.coerceIn(0L, i.durationMs) else max(pos, 0L)
        val frac = if (i.durationMs > 0) pos.toFloat() / i.durationMs else 0f
        val px0 = x0 + dp(2)
        val px1 = x0 + blockW - dp(2)
        val py = y0 + dp(76)
        fill.color = 0xFFFFFFFF.toInt()
        fill.alpha = (ia * 0.28f).toInt()
        rect.set(px0, py, px1, py + dp(4))
        c.drawRoundRect(rect, dp(2), dp(2), fill)
        fill.alpha = ia
        rect.set(px0, py, px0 + (px1 - px0) * frac.coerceIn(0f, 1f), py + dp(4))
        c.drawRoundRect(rect, dp(2), dp(2), fill)
        tSmall.alpha = ia
        c.drawText(formatMs(pos), px0, y0 + dp(98), tSmall)
        val dur = if (i.durationMs > 0) formatMs(i.durationMs) else "--:--"
        tSmall.textAlign = Paint.Align.RIGHT
        c.drawText(dur, px1, y0 + dp(98), tSmall)
        tSmall.textAlign = Paint.Align.LEFT

        // kontrol
        val cr = controlRects(b)
        fill.color = 0xFFFFFFFF.toInt()
        fill.alpha = ia
        drawSkip(c, cr[0].centerX(), cr[0].centerY(), false)
        if (i.playing) drawPause(c, cr[1].centerX(), cr[1].centerY()) else drawPlay(c, cr[1].centerX(), cr[1].centerY())
        drawSkip(c, cr[2].centerX(), cr[2].centerY(), true)
    }

    private fun drawPlay(c: Canvas, x: Float, y: Float) {
        val s = dp(13)
        path.reset()
        path.moveTo(x - s * 0.7f, y - s)
        path.lineTo(x + s * 0.95f, y)
        path.lineTo(x - s * 0.7f, y + s)
        path.close()
        c.drawPath(path, fill)
    }

    private fun drawPause(c: Canvas, x: Float, y: Float) {
        val s = dp(12)
        val w = dp(5)
        rect.set(x - s * 0.8f, y - s, x - s * 0.8f + w, y + s)
        c.drawRoundRect(rect, dp(1.5f), dp(1.5f), fill)
        rect.set(x + s * 0.8f - w, y - s, x + s * 0.8f, y + s)
        c.drawRoundRect(rect, dp(1.5f), dp(1.5f), fill)
    }

    private fun drawSkip(c: Canvas, x: Float, y: Float, next: Boolean) {
        val s = dp(10)
        val d = if (next) 1f else -1f
        path.reset()
        path.moveTo(x - d * s, y - s)
        path.lineTo(x + d * s * 0.6f, y)
        path.lineTo(x - d * s, y + s)
        path.close()
        c.drawPath(path, fill)
        val bx = x + d * s * 0.85f
        rect.set(bx - dp(1.6f), y - s, bx + dp(1.6f), y + s)
        c.drawRoundRect(rect, dp(1f), dp(1f), fill)
    }

    // ----------------------------------------------------------------- teks

    private val msCache = HashMap<Long, String>()

    private fun formatMs(ms: Long): String {
        val sec = ms / 1000
        return msCache.getOrPut(sec) {
            if (msCache.size > 600) msCache.clear()
            String.format("%02d:%02d", sec / 60, sec % 60)
        }
    }

    private val timerSb = StringBuilder(12)

    private fun two(sb: StringBuilder, v: Long) {
        if (v < 10) sb.append('0')
        sb.append(v)
    }

    private fun formatTimer(item: IslandItem, short: Boolean): String {
        val now = System.currentTimeMillis()
        var ms = if (item.countDown) item.whenMs - now else now - item.whenMs
        if (ms < 0) ms = 0
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60
        val cs = (ms / 10) % 100
        val sb = timerSb
        sb.setLength(0)
        if (h > 0) {
            sb.append(h).append(':'); two(sb, m); sb.append(':'); two(sb, s)
        } else {
            sb.append(m).append(':'); two(sb, s)
            if (!short) { sb.append('.'); two(sb, cs) }
        }
        return sb.toString()
    }
}
