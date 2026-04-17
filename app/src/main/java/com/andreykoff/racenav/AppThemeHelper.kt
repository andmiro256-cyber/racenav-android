package com.andreykoff.racenav

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

object AppThemeHelper {
    const val PREF_APP_THEME_MODE = "app_theme_mode"

    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    fun applyTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (getThemeMode(context)) {
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun getThemeMode(context: Context): String {
        val prefs = context.getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_APP_THEME_MODE, MODE_SYSTEM) ?: MODE_SYSTEM
    }

    fun setThemeMode(context: Context, mode: String) {
        val normalized = when (mode) {
            MODE_LIGHT, MODE_DARK -> mode
            else -> MODE_SYSTEM
        }
        context.getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_APP_THEME_MODE, normalized)
            .apply()
        applyTheme(context)
    }

    fun isDarkTheme(context: Context): Boolean {
        val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }
}
