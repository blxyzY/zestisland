package dev.zest.island

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.RemoteViews

/** Membaca notifikasi (telepon, timer/stopwatch, notifikasi biasa) dan sesi media. */
class IslandNotificationListener : NotificationListenerService() {
    companion object {
        /** true bila berjalan di dalam proses SystemUI (mode tertanam). */
        @Volatile var inSystemUi = false
        private const val OWN_PACKAGE = "dev.zest.island"
        private const val PAUSED_HIDE_MS = 60_000L
    }

    /** Dipakai host SystemUI: pasang konteks dasar lalu daftar sebagai layanan sistem. */
    fun attachAndRegister(app: android.content.Context): Boolean {
        return try {
            attachBaseContext(app)
            val m = NotificationListenerService::class.java.getMethod(
                "registerAsSystemService",
                android.content.Context::class.java,
                ComponentName::class.java,
                Int::class.javaPrimitiveType
            )
            m.invoke(this, app, ComponentName(app.packageName, IslandNotificationListener::class.java.name), -1)
            true
        } catch (_: Throwable) { false }
    }

    private val controllers = HashMap<String, Pair<MediaController, MediaController.Callback>>()
    private var msm: MediaSessionManager? = null
    private val clockStart = HashMap<String, Long>()
    private val mainH = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleRemovers = HashMap<String, Runnable>()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { syncSessions(it) }

    override fun onListenerConnected() {
        val m = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm = m
        // Di SystemUI cukup komponen null (SystemUI punya MEDIA_CONTENT_CONTROL).
        val cn = if (inSystemUi) null else ComponentName(this, IslandNotificationListener::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, cn)
            syncSessions(m.getActiveSessions(cn))
        } catch (_: Throwable) {}
    }

    override fun onListenerDisconnected() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Throwable) {}
        for ((k, v) in controllers) {
            try { v.first.unregisterCallback(v.second) } catch (_: Throwable) {}
            IslandHub.remove(k)
        }
        controllers.clear()
    }

    // ---- Notifikasi ----

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == OWN_PACKAGE) return
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT) return   // ditangani sesi media

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val app = appLabel(sbn.packageName)
        val icon = try { n.smallIcon?.loadDrawable(this) } catch (_: Throwable) { null }
        var chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        var chronoWhen = n.`when`
        var chronoDown = ex.getBoolean("android.chronometerCountDown", false)
        val isCall = n.category == Notification.CATEGORY_CALL
        val ongoing = (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
        // Stopwatch/timer Samsung Jam sering memakai tampilan kustom tanpa chronometer standar.
        val isClock = ongoing && (
            sbn.packageName.contains("clock", true) ||
                n.category == "stopwatch"
            )
        android.util.Log.d(
            "ZestIsland",
            "notif pkg=${sbn.packageName} cat=${n.category} flags=${n.flags} chrono=$chrono when=${n.`when`} clock=$isClock title=$title"
        )
        if (!chrono && !isCall && (n.flags and Notification.FLAG_ONGOING_EVENT) != 0) {
            // Stopwatch/timer aplikasi jam sering memakai tampilan kustom berisi Chronometer.
            scanChronometer(sbn)?.let { (w, down) ->
                chrono = true
                chronoWhen = w
                chronoDown = down
            }
        }

        when {
            chrono || isCall || isClock -> {
                val item = IslandItem(
                    sbn.key,
                    if (isCall) Kind.CALL else Kind.TIMER,
                    title.ifEmpty { app },
                    text.ifEmpty { app }
                )
                item.icon = icon
                if (chrono) {
                    item.whenMs = chronoWhen
                    item.countDown = chronoDown
                }
                IslandHub.put(item)
            }
            (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 -> Unit   // abaikan layanan latar
            title.isNotEmpty() || text.isNotEmpty() ->
                IslandHub.alert(sbn.key, title.ifEmpty { app }, text.ifEmpty { app }, icon, n.contentIntent)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        clockStart.remove(sbn.key)
        IslandHub.remove(sbn.key)
        IslandHub.remove("alert:" + sbn.key)
    }

    /** Cari Chronometer berjalan di RemoteViews notifikasi; hasil: (waktu mulai ms jam dinding, hitung mundur). */
    private fun scanChronometer(sbn: StatusBarNotification): Pair<Long, Boolean>? {
        val n = sbn.notification
        val views = listOfNotNull(n.contentView, n.bigContentView, n.headsUpContentView)
        if (views.isEmpty()) return null
        val ctx = try { createPackageContext(sbn.packageName, 0) } catch (_: Throwable) { return null }
        for (rv in views) {
            try {
                val root = rv.apply(ctx, FrameLayout(ctx))
                val ch = findChronometer(root) ?: continue
                val started = try {
                    val f = Chronometer::class.java.getDeclaredField("mStarted")
                    f.isAccessible = true
                    f.getBoolean(ch)
                } catch (_: Throwable) { true }
                if (!started) continue
                val down = ch.isCountDown
                val wall = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime() + ch.base
                return wall to down
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun findChronometer(v: View): Chronometer? {
        if (v is Chronometer) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findChronometer(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) { pkg }

    // ---- Media ----

    private fun syncSessions(list: List<MediaController>?) {
        val live = HashSet<String>()
        list?.forEach { c ->
            val key = "media:" + c.packageName
            live.add(key)
            if (!controllers.containsKey(key)) {
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = updateMedia(c)
                    override fun onPlaybackStateChanged(state: PlaybackState?) = updateMedia(c)
                    override fun onSessionDestroyed() {
                        controllers.remove(key)
                        IslandHub.remove(key)
                    }
                }
                c.registerCallback(cb)
                controllers[key] = c to cb
            }
            updateMedia(c)
        }
        val stale = controllers.keys.filter { it !in live }
        for (k in stale) {
            controllers.remove(k)?.let { try { it.first.unregisterCallback(it.second) } catch (_: Throwable) {} }
            IslandHub.remove(k)
        }
    }

    private fun updateMedia(c: MediaController) {
        val key = "media:" + c.packageName
        val state = c.playbackState?.state
        if (state == null || state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_STOPPED ||
            state == PlaybackState.STATE_ERROR
        ) {
            IslandHub.remove(key)
            return
        }
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifEmpty { appLabel(c.packageName) }
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val item = IslandItem(key, Kind.MEDIA, title, artist)
        item.art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        item.playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        item.controller = c
        item.durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        item.positionMs = c.playbackState?.position ?: 0L
        item.posTime = c.playbackState?.lastPositionUpdateTime ?: android.os.SystemClock.elapsedRealtime()
        item.speed = c.playbackState?.playbackSpeed ?: 1f
        IslandHub.put(item)
        // Musik yang di-pause lama tidak terus menempel di island (tetap kembali saat diputar lagi).
        idleRemovers.remove(key)?.let { mainH.removeCallbacks(it) }
        if (state == PlaybackState.STATE_PAUSED) {
            val r = Runnable {
                idleRemovers.remove(key)
                IslandHub.remove(key)
            }
            idleRemovers[key] = r
            mainH.postDelayed(r, PAUSED_HIDE_MS)
        }
    }
}
