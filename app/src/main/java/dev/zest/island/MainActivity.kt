package dev.zest.island

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        status = TextView(this).apply { textSize = 15f }
        col.addView(status)
        fun btn(t: String, a: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { a() }
        }
        col.addView(btn("Aktifkan otomatis (butuh priv-app)") { AutoEnable.run(this); update() })
        col.addView(btn("Buka pengaturan Aksesibilitas") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(btn("Buka akses notifikasi") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        col.addView(btn("Tes island") { IslandHub.systemAlert("Halo", "Island aktif") })
        setContentView(col)
    }

    override fun onResume() {
        super.onResume()
        update()
    }

    private fun update() {
        status.text = "Izin sistem: " + (if (AutoEnable.canWrite(this)) "ada" else "tidak ada") +
            "\nAksesibilitas: " + (if (AutoEnable.a11yOn(this)) "aktif" else "mati") +
            "\nAkses notifikasi: " + (if (AutoEnable.listenerOn(this)) "aktif" else "mati")
    }
}
