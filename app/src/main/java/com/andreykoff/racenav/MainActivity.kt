package com.andreykoff.racenav

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.andreykoff.racenav.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyKeepScreen()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, MapFragment())
                .commit()
        }
    }

    fun applyKeepScreen() {
        val keep = getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(MapFragment.PREF_KEEP_SCREEN, true)
        if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // If we're in a sub-fragment (Settings etc.) — go back normally
        if (supportFragmentManager.backStackEntryCount > 0) {
            super.onBackPressed()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Выйти из приложения?")
            .setPositiveButton("Выйти") { _, _ -> finish() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val mapFrag = supportFragmentManager.findFragmentById(R.id.container) as? MapFragment
        val prefs = getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val volumeZoom = prefs.getBoolean(MapFragment.PREF_VOLUME_ZOOM, true)
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                event.startTracking() // needed for long-press detection
                // Zoom only on first press, ignore repeats from holding
                if (event.repeatCount == 0 && volumeZoom) mapFrag?.zoomIn()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Zoom only on first press, ignore repeats from holding
                if (event.repeatCount == 0 && volumeZoom) mapFrag?.zoomOut()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        // Volume UP held 2s → unlock screen
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val mapFrag = supportFragmentManager.findFragmentById(R.id.container) as? MapFragment
            if (mapFrag?.isScreenLocked == true) {
                mapFrag.unlockScreen()
                return true
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }
}
