package com.andreykoff.racenav

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.andreykoff.racenav.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, MapFragment())
                .commit()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val prefs = getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val volumeZoom = prefs.getBoolean(MapFragment.PREF_VOLUME_ZOOM, true)
        if (volumeZoom) {
            val mapFrag = supportFragmentManager.findFragmentById(R.id.container) as? MapFragment
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { mapFrag?.zoomIn(); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { mapFrag?.zoomOut(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
