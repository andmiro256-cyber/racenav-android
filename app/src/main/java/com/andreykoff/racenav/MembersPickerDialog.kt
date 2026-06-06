package com.andreykoff.racenav

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * Picker for adding members to a group. Shows all live devices, excluding self + already added.
 */
class MembersPickerDialog : DialogFragment() {

    private var alreadyIds: Set<String> = emptySet()
    private val selected: MutableSet<String> = mutableSetOf()
    private var searchText: String = ""
    private var devicesOnline: List<LiveUsersPoller.LiveDevice> = emptyList()
    private var devicesOffline: List<LiveUsersPoller.LiveDevice> = emptyList()

    private var listContainer: LinearLayout? = null
    private var titleLabel: TextView? = null
    private var groupName: String = ""

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        alreadyIds = arguments?.getStringArrayList("already")?.map { it.uppercase() }?.toSet() ?: emptySet()
        groupName = arguments?.getString("groupName", "") ?: ""

        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val palette = UiTheme.palette(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.surface)
            setPadding(0, 0, 0, (8 * density).toInt())
        }

        titleLabel = TextView(ctx).apply {
            text = "Добавить в «$groupName»"
            setTextColor(palette.textPrimary)
            textSize = 16f
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }
        root.addView(titleLabel)

        val searchEdit = EditText(ctx).apply {
            hint = "Поиск по имени или ID"
            setHintTextColor(palette.textHint)
            setTextColor(palette.textPrimary)
            setBackgroundColor(palette.surfaceVariant)
            textSize = 14f
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
        }
        val lpSearch = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lpSearch.setMargins((12 * density).toInt(), 0, (12 * density).toInt(), (8 * density).toInt())
        searchEdit.layoutParams = lpSearch
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchText = s?.toString().orEmpty().trim()
                renderList()
            }
        })
        root.addView(searchEdit)

        val scroll = android.widget.ScrollView(ctx)
        val scrollLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        scroll.layoutParams = scrollLp

        listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listContainer)
        root.addView(scroll)

        loadDevices()
        renderList()

        return AlertDialog.Builder(ctx)
            .setView(root)
            .setPositiveButton("Добавить") { _, _ -> sendResult() }
            .setNegativeButton("Отмена", null)
            .create()
    }

    private fun loadDevices() {
        val mapFrag = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
        val all = mapFrag?.lastLiveDevices ?: emptyList()
        val myId = prefsCtx()?.getString(MapFragment.PREF_TRACCAR_DEVICE_ID, "")?.uppercase() ?: ""
        val candidates = all
            .filter { it.uniqueId.isNotBlank() }
            .filter { it.uniqueId.uppercase() != myId }
            .filter { it.uniqueId.uppercase() !in alreadyIds }
            .distinctBy { it.uniqueId.uppercase() }
        devicesOnline = candidates.filter { it.status == "online" }.sortedBy { it.name.lowercase() }
        devicesOffline = candidates.filter { it.status != "online" }.sortedBy { it.name.lowercase() }
    }

    private fun prefsCtx(): android.content.SharedPreferences? {
        val c: Context = context ?: return null
        return c.getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun matches(d: LiveUsersPoller.LiveDevice): Boolean {
        if (searchText.isBlank()) return true
        val q = searchText.lowercase()
        return d.name.lowercase().contains(q) || d.uniqueId.lowercase().contains(q)
    }

    private fun renderList() {
        val ctx = context ?: return
        val container = listContainer ?: return
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val onlineFiltered = devicesOnline.filter { matches(it) }
        val offlineFiltered = devicesOffline.filter { matches(it) }

        fun sectionHeader(text: String, count: Int) {
            val palette = UiTheme.palette(ctx)
            val tv = TextView(ctx).apply {
                this.text = "$text ($count)"
                setTextColor(palette.textMuted)
                textSize = 10f
                setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (6 * density).toInt())
            }
            container.addView(tv)
        }

        if (onlineFiltered.isEmpty() && offlineFiltered.isEmpty()) {
            val palette = UiTheme.palette(ctx)
            val tv = TextView(ctx).apply {
                text = "Никого не найдено"
                setTextColor(palette.textHint)
                textSize = 13f
                setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            }
            container.addView(tv)
            return
        }

        if (onlineFiltered.isNotEmpty()) {
            sectionHeader("СЕЙЧАС ОНЛАЙН", onlineFiltered.size)
            onlineFiltered.forEach { container.addView(buildRow(it, true)) }
        }
        if (offlineFiltered.isNotEmpty()) {
            sectionHeader("ОФЛАЙН", offlineFiltered.size)
            offlineFiltered.forEach { container.addView(buildRow(it, false)) }
        }
    }

    private fun buildRow(d: LiveUsersPoller.LiveDevice, online: Boolean): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val palette = UiTheme.palette(ctx)
        val selectableAttrs = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val selectableAttr = selectableAttrs.getResourceId(0, 0)
        selectableAttrs.recycle()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            isClickable = true
            setBackgroundColor(palette.surfaceVariant)
            if (selectableAttr != 0) {
                foreground = androidx.core.content.ContextCompat.getDrawable(ctx, selectableAttr)
            }
        }
        val uid = d.uniqueId.uppercase()
        val checkBox = CheckBox(ctx).apply {
            isChecked = uid in selected
            buttonTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            setOnCheckedChangeListener { _, c ->
                if (c) selected.add(uid) else selected.remove(uid)
            }
        }
        row.addView(checkBox)
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(ctx).apply {
            text = d.name.ifBlank { uid }
            setTextColor(if (online) palette.textPrimary else palette.textSecondary)
            textSize = 14f
        })
        texts.addView(TextView(ctx).apply {
            text = uid
            setTextColor(palette.textHint)
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
        })
        row.addView(texts)
        row.setOnClickListener { checkBox.toggle() }
        return row
    }

    private fun sendResult() {
        val b = Bundle()
        b.putStringArrayList("selected", ArrayList(selected))
        parentFragmentManager.setFragmentResult("members_picker_result", b)
    }
}
