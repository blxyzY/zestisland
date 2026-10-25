package dev.zest.island

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.database.ContentObserver
import android.net.Uri
import android.os.BatteryManager
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import kotlin.math.max

/** Memasang overlay island dan mengatur buka/tutup. */
class IslandAccessibilityService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private lateinit var view: IslandView
    private lateinit var holder: Holder
    private val handler = Handler(Looper.getMainLooper())
    private var attached = false
    private var expanded = false
    private val seenAlerts = HashSet<String>()
    private var expandedByAlert = false
    private var params: WindowManager.LayoutParams? = null
    private var shadeOpen = false
    private val km by lazy { getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }

    private var offX = 0
    private var offY = 0
    private val prefKeys = listOf("zest_island_width", "zest_island_height", "zest_island_x", "zest_island_y")
    private val prefObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) { loadPrefs() }
    }

    private fun geti(k: String) = Settings.System.getInt(contentResolver, k, 0)

    /** Baca ukuran/posisi pill dari UN1CA Settings (dp), lalu terapkan. */
    private fun loadPrefs() {
        val d = resources.displayMetrics.density
        view.setPillSize(geti("zest_island_width"), geti("zest_island_height"))
        if (attached) applyWindow()
        offX = (geti("zest_island_x").coerceIn(-400, 400) * d).toInt()
        offY = (geti("zest_island_y").coerceIn(-100, 600) * d).toInt()
        val p = params
        if (attached && p != null && (p.x != offX || p.y != offY)) {
            p.x = offX
            p.y = offY
            try { wm.updateViewLayout(holder, p) } catch (_: Throwable) {}
        }
    }

    private val hubListener: () -> Unit = {
        // Gabungkan banyak pembaruan beruntun (media/progress) jadi satu refresh.
        handler.removeCallbacks(refreshRun)
        handler.postDelayed(refreshRun, 16)
    }
    private val collapse = Runnable { setExpanded(false) }
    /**
     * Panel QS / shade terbuka = jendela sistem fokus berjudul "notification shade" dst.
     * Hanya membaca daftar jendela (murah); root jendela dibaca hanya untuk satu kandidat.
     */
    private val checkShade = Runnable {
        val open = try {
            val cand = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.isFocused }
            if (cand == null) false else {
                val t = cand.title?.toString()?.lowercase().orEmpty()
                t.contains("shade") || t.contains("notifikasi") || t.contains("notification") ||
                    t.contains("quick") || t.contains("pengaturan cepat") ||
                    (t.isEmpty() && cand.root?.packageName?.toString() == "com.android.systemui")
            }
        } catch (_: Throwable) { false }
        if (open != shadeOpen) {
            shadeOpen = open
            refresh()
        }
    }
    private var windowBig = false
    private val detachRun = Runnable { if (IslandHub.snapshot().isEmpty()) detach() }
    private val refreshRun = Runnable { refresh() }
    private val doExpand = Runnable {
        if (expanded && attached) view.bind(IslandHub.snapshot(), true)
    }
    private fun shrinkNow() {
        if (expanded) return
        if (!view.isSettled()) {
            handler.postDelayed(shrink, 200)
            return
        }
        val (w, h) = view.collapsedWindowSize()
        setWindowSize(w, h)
        windowBig = false
    }
    private val shrink: Runnable = Runnable { shrinkNow() }
    private val ticker = object : Runnable {
        override fun run() {
            view.tick()
            handler.postDelayed(this, 50)
        }
    }
    private var ticking = false

    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.let { b ->
                    val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val s = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (l >= 0) l * 100 / s else -1
                } ?: -1
            val text = if (level >= 0) "$level%" else ""
            when (i.action) {
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> refresh()
            }
        }
    }

    override fun onServiceConnected() {
        if (SysUiMode.active(this)) {   // island sudah berjalan di dalam SystemUI
            disableSelf()
            return
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        view = IslandView(this)
        view.onToggle = { setExpanded(!expanded) }
        holder = Holder(this)
        holder.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        holder.onOutside = { if (expanded) setExpanded(false) }
        IslandHub.addListener(hubListener)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(power, f)
        SystemNotices.register(applicationContext)
        for (k in prefKeys) contentResolver.registerContentObserver(Settings.System.getUriFor(k), false, prefObserver)
        loadPrefs()
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() == packageName) return   // abaikan jendela island sendiri
        handler.removeCallbacks(checkShade)
        handler.postDelayed(checkShade, 150)
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        IslandHub.removeListener(hubListener)
        try { unregisterReceiver(power) } catch (_: Throwable) {}
        SystemNotices.unregister(applicationContext)
        try { contentResolver.unregisterContentObserver(prefObserver) } catch (_: Throwable) {}
        handler.removeCallbacksAndMessages(null)
        detach()
        super.onDestroy()
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        expandedByAlert = false
        handler.removeCallbacks(collapse)
        if (value) handler.postDelayed(collapse, 7000)
        refresh()
    }

    private fun refresh() {
        // Sembunyikan di layar kunci / layar mati / saat panel QS terbuka.
        if (km.isKeyguardLocked || shadeOpen) {
            expanded = false
            seenAlerts.clear()
            expandedByAlert = false
            stopTicker()
            detach()
            return
        }
        val items = IslandHub.snapshot()
        // Notifikasi baru langsung membuka island (gaya HyperOS), tutup sendiri setelah 5 detik.
        val alertKeys = items.filter { it.kind == Kind.ALERT }.map { it.key }.toSet()
        val fresh = alertKeys.any { it !in seenAlerts }
        seenAlerts.retainAll(alertKeys)
        seenAlerts.addAll(alertKeys)
        if (fresh) {
            expanded = true
            expandedByAlert = true
            handler.removeCallbacks(collapse)
            handler.postDelayed(collapse, 5000)
        } else if (alertKeys.isEmpty() && expandedByAlert) {
            expanded = false
            expandedByAlert = false
        }
        if (items.isEmpty()) {
            expanded = false
            stopTicker()
            if (attached) {
                view.bind(items, false)            // animasi menghilang dulu
                handler.removeCallbacks(detachRun)
                handler.postDelayed(detachRun, 450)
            }
            return
        }
        handler.removeCallbacks(detachRun)
        attach()
        if (expanded && !windowBig) {
            // Besarkan jendela dulu, baru mulai animasi (menghindari kedip saat surface berubah ukuran).
            val (w, h) = view.expandedSize()
            setWindowSize(w, h)
            windowBig = true
            view.bind(items, false)
            handler.removeCallbacks(doExpand)
            handler.postDelayed(doExpand, 70)
        } else {
            view.bind(items, expanded)
        }
        applyWindow()
        val needTick = items.any { it.hasTimer } ||
            (expanded && items.any { it.kind == Kind.MEDIA && it.playing }) ||
            items.firstOrNull()?.let { it.kind == Kind.MEDIA && it.playing } == true
        if (needTick) startTicker() else stopTicker()
    }

    private fun attach() {
        if (attached) return
        val (cw, ch) = view.collapsedWindowSize()
        val lp = WindowManager.LayoutParams(
            cw,
            ch,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = offX
            y = offY
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            wm.addView(holder, lp)
            params = lp
            attached = true
        } catch (_: Throwable) {}
    }

    /**
     * Saat mengembang, jendela langsung dibuat seukuran kartu akhir sehingga animasi cukup
     * mengubah ukuran view di dalamnya (tanpa mengubah ukuran jendela tiap frame).
     */
    private fun applyWindow() {
        if (!attached) return
        handler.removeCallbacks(shrink)
        if (!expanded) handler.postDelayed(shrink, 700)
    }

    private fun setWindowSize(w: Int, h: Int) {
        val p = params ?: return
        if (!attached) return
        if (p.width == w && p.height == h) return
        p.width = w
        p.height = h
        try { wm.updateViewLayout(holder, p) } catch (_: Throwable) {}
    }

    private fun detach() {
        windowBig = false
        handler.removeCallbacks(doExpand)
        if (!attached) return
        handler.removeCallbacks(shrink)
        try { wm.removeView(holder) } catch (_: Throwable) {}
        attached = false
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        handler.post(ticker)
    }

    private fun stopTicker() {
        ticking = false
        handler.removeCallbacks(ticker)
    }
}

/** Akar jendela: menangkap ketukan di luar kartu. */
private class Holder(context: Context) : FrameLayout(context) {
    var onOutside: (() -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE || event.action == MotionEvent.ACTION_DOWN) {
            onOutside?.invoke()
        }
        return false
    }
}
