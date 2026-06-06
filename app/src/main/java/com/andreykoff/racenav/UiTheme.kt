package com.andreykoff.racenav

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat

object UiTheme {
    data class Palette(
        val background: Int,
        val surface: Int,
        val surfaceTop: Int,
        val surfaceVariant: Int,
        val surfaceOverlay: Int,
        val divider: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textMuted: Int,
        val textHint: Int,
        val accent: Int,
        val warning: Int,
        val success: Int,
        val error: Int
    ) {
        fun rowBackground(position: Int): Int =
            if (position % 2 == 0) surfaceVariant else Color.TRANSPARENT
    }

    fun palette(context: Context): Palette = Palette(
        background = ContextCompat.getColor(context, R.color.background),
        surface = ContextCompat.getColor(context, R.color.surface),
        surfaceTop = ContextCompat.getColor(context, R.color.surface_top),
        surfaceVariant = ContextCompat.getColor(context, R.color.surface_variant),
        surfaceOverlay = ContextCompat.getColor(context, R.color.surface_overlay),
        divider = ContextCompat.getColor(context, R.color.card_stroke),
        textPrimary = ContextCompat.getColor(context, R.color.text_primary),
        textSecondary = ContextCompat.getColor(context, R.color.text_secondary),
        textMuted = ContextCompat.getColor(context, R.color.text_muted),
        textHint = ContextCompat.getColor(context, R.color.text_hint),
        accent = ContextCompat.getColor(context, R.color.primary),
        warning = ContextCompat.getColor(context, R.color.warning),
        success = ContextCompat.getColor(context, R.color.success),
        error = ContextCompat.getColor(context, R.color.error)
    )
}
