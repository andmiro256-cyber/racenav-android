package com.andreykoff.racenav

import android.app.Application
import com.mapbox.mapboxsdk.Mapbox

class RaceNavApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Mapbox.getInstance(this)
    }
}
