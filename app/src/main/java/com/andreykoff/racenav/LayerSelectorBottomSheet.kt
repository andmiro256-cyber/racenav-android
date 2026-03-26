package com.andreykoff.racenav

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mapbox.mapboxsdk.geometry.LatLng

/**
 * BottomSheet для выбора слоёв и zoom-диапазона перед скачиванием карт.
 *
 * Показывает:
 * - Базовые карты (radio, один выбор)
 * - Оверлеи (checkbox, несколько)
 * - Zoom range (два SeekBar)
 * - Оценка размера (обновляется в реальном времени)
 * - Кнопка "Скачать"
 */
class LayerSelectorBottomSheet(
    private val context: Context,
    private val area: PolygonArea,
    private val tileSources: Map<String, Pair<String, Boolean>>,  // key → (label, isOverlay)
    private val onCancel: (() -> Unit)? = null,
    private val onDownload: (config: LayerSelectionConfig) -> Unit
) {
    companion object {
        const val MIN_ZOOM = 8
        const val MAX_ZOOM = 17
        const val DEFAULT_MIN_ZOOM = 10
        const val DEFAULT_MAX_ZOOM = 15
    }

    private var selectedBase: String = ""
    private val selectedOverlays = mutableSetOf<String>()
    private var minZoom = DEFAULT_MIN_ZOOM
    private var maxZoom = DEFAULT_MAX_ZOOM
    private var txtEstimate: TextView? = null
    private var btnDownload: Button? = null

    private var nameInput: EditText? = null
    private var downloadClicked = false

    fun show() {
        val dp = context.resources.displayMetrics.density
        val dialog = BottomSheetDialog(context, com.google.android.material.R.style.Theme_Design_BottomSheetDialog)

        // NestedScrollView for proper scroll inside BottomSheet
        val scroll = androidx.core.widget.NestedScrollView(context).apply {
            setBackgroundColor(0xFF1A1A1A.toInt())
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
        }

        // Title
        root.addView(TextView(context).apply {
            text = "Скачивание карт"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (4 * dp).toInt())
        })

        // Area info
        root.addView(TextView(context).apply {
            text = "Область: ${String.format("%.0f", area.areaKm2)} км² • ${area.polygon.size} точек"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 13f
            setPadding(0, 0, 0, (8 * dp).toInt())
        })

        // Map name input
        val ts = java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.getDefault()).format(java.util.Date())
        val autoName = "Карта $ts"
        nameInput = EditText(context).apply {
            setText(autoName)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF666666.toInt())
            hint = "Название карты"
            textSize = 15f
            setBackgroundColor(0xFF2A2A2A.toInt())
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            selectAll()
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        root.addView(nameInput)
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (8 * dp).toInt())
        })

        // Base maps section
        val baseMaps = tileSources.filter { !it.value.second }
        if (baseMaps.isNotEmpty()) {
            root.addView(sectionLabel("БАЗОВАЯ КАРТА", dp))
            val radioGroup = RadioGroup(context)
            baseMaps.entries.forEachIndexed { i, (key, pair) ->
                val rb = RadioButton(context).apply {
                    text = pair.first
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    buttonTintList = android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt())
                    id = View.generateViewId()
                    tag = key
                }
                radioGroup.addView(rb)
                if (i == 0) {
                    rb.isChecked = true
                    selectedBase = key
                }
            }
            radioGroup.setOnCheckedChangeListener { group, checkedId ->
                val rb = group.findViewById<RadioButton>(checkedId)
                selectedBase = rb?.tag as? String ?: ""
                updateEstimate()
            }
            root.addView(radioGroup)
        }

        // Overlays section
        val overlays = tileSources.filter { it.value.second }
        if (overlays.isNotEmpty()) {
            root.addView(sectionLabel("СЛОИ (можно несколько)", dp))
            for ((key, pair) in overlays) {
                val cb = CheckBox(context).apply {
                    text = pair.first
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    buttonTintList = android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt())
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedOverlays.add(key) else selectedOverlays.remove(key)
                        updateEstimate()
                    }
                }
                root.addView(cb)
            }
        }

        // Zoom range
        root.addView(sectionLabel("ZOOM ДИАПАЗОН", dp))
        val txtZoomRange = TextView(context).apply {
            text = "z$minZoom — z$maxZoom"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        }
        root.addView(txtZoomRange)

        // Min/Max zoom seekbars
        var seekMaxRef: SeekBar? = null

        root.addView(TextView(context).apply {
            text = "Минимум"
            setTextColor(0xFF888888.toInt()); textSize = 12f
        })
        val seekMin = SeekBar(context).apply {
            max = MAX_ZOOM - MIN_ZOOM
            progress = minZoom - MIN_ZOOM
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    minZoom = MIN_ZOOM + p
                    if (minZoom > maxZoom) { maxZoom = minZoom; seekMaxRef?.progress = maxZoom - MIN_ZOOM }
                    txtZoomRange.text = "z$minZoom — z$maxZoom"
                    updateEstimate()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        root.addView(seekMin)

        root.addView(TextView(context).apply {
            text = "Максимум"
            setTextColor(0xFF888888.toInt()); textSize = 12f
        })
        seekMaxRef = SeekBar(context).apply {
            max = MAX_ZOOM - MIN_ZOOM
            progress = maxZoom - MIN_ZOOM
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    maxZoom = MIN_ZOOM + p
                    if (maxZoom < minZoom) { minZoom = maxZoom; seekMin.progress = minZoom - MIN_ZOOM }
                    txtZoomRange.text = "z$minZoom — z$maxZoom"
                    updateEstimate()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        root.addView(seekMaxRef)

        // Estimate
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt())
                .apply { topMargin = (16 * dp).toInt(); bottomMargin = (8 * dp).toInt() }
            setBackgroundColor(0xFF333333.toInt())
        })
        txtEstimate = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        }
        root.addView(txtEstimate)

        // Download button
        btnDownload = Button(context).apply {
            text = "Скачать"
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFFFF6F00.toInt())
            setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
            setOnClickListener {
                val mapName = nameInput?.text?.toString()?.trim()?.ifBlank { null } ?: autoName
                val config = LayerSelectionConfig(
                    name = mapName,
                    selectedBase = selectedBase,
                    selectedOverlays = selectedOverlays.toList(),
                    minZoom = minZoom,
                    maxZoom = maxZoom
                )
                downloadClicked = true
                dialog.dismiss()
                onDownload(config)
            }
        }
        root.addView(btnDownload)

        // Cancel
        root.addView(Button(context).apply {
            text = "Отмена"
            textSize = 14f
            isAllCaps = false
            setTextColor(0xFF888888.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dialog.dismiss() }
        })

        scroll.addView(root)
        dialog.setContentView(scroll)
        dialog.setOnDismissListener { if (!downloadClicked) onCancel?.invoke() }
        dialog.behavior.peekHeight = context.resources.displayMetrics.heightPixels
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(0xFF1A1A1A.toInt())

        updateEstimate()
    }

    private fun sectionLabel(text: String, dp: Float) = TextView(context).apply {
        this.text = text
        setTextColor(0xFF888888.toInt())
        textSize = 11f
        letterSpacing = 0.1f
        setPadding(0, (16 * dp).toInt(), 0, (6 * dp).toInt())
    }

    private fun updateEstimate() {
        val baseLayers = if (selectedBase.isNotEmpty()) listOf(selectedBase) else emptyList()
        val polygonPairs = area.polygon.map { Pair(it.latitude, it.longitude) }
        val estimate = SizeEstimator.estimate(area.boundingBox, baseLayers, selectedOverlays.toList(), minZoom, maxZoom, polygonPairs)
        txtEstimate?.text = "${estimate.totalTiles} тайлов • ${estimate.formatSize()}"
        txtEstimate?.setTextColor(if (estimate.isLarge) 0xFFFF5252.toInt() else Color.WHITE)
        btnDownload?.isEnabled = estimate.totalTiles > 0 && selectedBase.isNotEmpty()
    }
}

data class LayerSelectionConfig(
    val name: String,
    val selectedBase: String,
    val selectedOverlays: List<String>,
    val minZoom: Int,
    val maxZoom: Int
) {
    val allLayers: List<String>
        get() = listOf(selectedBase) + selectedOverlays
}
