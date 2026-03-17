package com.andreykoff.racenav

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/** Adapter for waypoint list in Settings bottom sheet */
class WaypointListAdapter(
    private val waypoints: List<Waypoint>,
    private val dp: Float,
    private val onItemClick: (index: Int, anchor: View) -> Unit
) : RecyclerView.Adapter<WaypointListAdapter.VH>() {

    class VH(row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val dot: View = row.findViewWithTag("dot")
        val symText: TextView = row.findViewWithTag("sym")
        val nameText: TextView = row.findViewWithTag("name")
        val coordText: TextView = row.findViewWithTag("coord")
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding((4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt())
            isClickable = true; isFocusable = true
        }
        val dotSize = (24 * dp).toInt()
        row.addView(View(ctx).apply {
            tag = "dot"
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply { marginEnd = (8 * dp).toInt() }
        })
        row.addView(TextView(ctx).apply {
            tag = "sym"; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(0, 0, (8 * dp).toInt(), 0); visibility = View.GONE
        })
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(ctx).apply { tag = "name"; textSize = 14f; setTextColor(Color.WHITE) })
        info.addView(TextView(ctx).apply { tag = "coord"; textSize = 11f; setTextColor(Color.parseColor("#888888")) })
        row.addView(info)
        return VH(row)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val wp = waypoints[position]
        val dotColor = wp.color.ifBlank { "#FF6F00" }
        holder.dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            try { setColor(Color.parseColor(dotColor)) } catch (_: Exception) { setColor(Color.parseColor("#FF6F00")) }
            setStroke((2 * dp).toInt(), Color.WHITE)
        }
        if (wp.symbol.isNotBlank()) {
            holder.symText.visibility = View.VISIBLE
            holder.symText.text = symbolChar(wp.symbol)
        } else {
            holder.symText.visibility = View.GONE
        }
        holder.nameText.text = wp.name.ifBlank { "WP%02d".format(position + 1) }
        holder.coordText.text = "%.5f, %.5f".format(wp.lat, wp.lon)
        holder.itemView.setBackgroundColor(if (position % 2 == 0) Color.parseColor("#1E1E1E") else Color.TRANSPARENT)
        holder.itemView.setOnClickListener { v ->
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) onItemClick(pos, v)
        }
    }

    override fun getItemCount() = waypoints.size
}

/** Adapter for route editor with drag & drop support */
class RouteEditorAdapter(
    val items: MutableList<Waypoint>,
    private val dp: Float,
    private val distanceCalc: (Waypoint, Waypoint) -> Float,
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<RouteEditorAdapter.VH>() {

    var dragStartListener: ((RecyclerView.ViewHolder) -> Unit)? = null

    class VH(row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val dragHandle: TextView = row.findViewWithTag("drag")
        val numText: TextView = row.findViewWithTag("num")
        val nameText: TextView = row.findViewWithTag("name")
        val coordText: TextView = row.findViewWithTag("coord")
        val distText: TextView = row.findViewWithTag("dist")
    }

    fun onItemMove(from: Int, to: Int) {
        if (from < 0 || from >= items.size || to < 0 || to >= items.size) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        }
        row.addView(TextView(ctx).apply {
            tag = "drag"; text = "≡"; textSize = 20f; setTextColor(Color.parseColor("#666666"))
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        })
        row.addView(TextView(ctx).apply {
            tag = "num"; textSize = 14f; setTextColor(Color.parseColor("#FF6F00"))
            setPadding(0, 0, (8 * dp).toInt(), 0)
        })
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(ctx).apply { tag = "name"; textSize = 14f; setTextColor(Color.WHITE) })
        info.addView(TextView(ctx).apply { tag = "coord"; textSize = 11f; setTextColor(Color.parseColor("#888888")) })
        info.addView(TextView(ctx).apply { tag = "dist"; textSize = 11f; setTextColor(Color.parseColor("#FF6F00")); visibility = View.GONE })
        row.addView(info)
        row.addView(Button(ctx).apply {
            text = "✏"; textSize = 14f; isAllCaps = false; tag = "edit"
            setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#FF6F00"))
            minWidth = 0; minimumWidth = 0; setPadding((8*dp).toInt(), 0, (8*dp).toInt(), 0)
        })
        row.addView(Button(ctx).apply {
            text = "✕"; textSize = 14f; isAllCaps = false; tag = "del"
            setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#FF4444"))
            minWidth = 0; minimumWidth = 0; setPadding((8*dp).toInt(), 0, (8*dp).toInt(), 0)
        })
        return VH(row)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val wp = items[position]
        holder.numText.text = "${position + 1}."
        holder.nameText.text = wp.name.ifBlank { "WP%02d".format(position + 1) }
        holder.coordText.text = "%.5f, %.5f".format(wp.lat, wp.lon)

        if (position < items.size - 1) {
            val distM = distanceCalc(wp, items[position + 1])
            holder.distText.text = if (distM < 1000) "→ ${distM.toInt()}м" else "→ %.1fкм".format(distM / 1000)
            holder.distText.visibility = View.VISIBLE
        } else {
            holder.distText.visibility = View.GONE
        }

        holder.itemView.setBackgroundColor(if (position % 2 == 0) Color.parseColor("#1E1E1E") else Color.TRANSPARENT)

        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) dragStartListener?.invoke(holder)
            false
        }
        holder.itemView.findViewWithTag<Button>("edit").setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) onEdit(pos)
        }
        holder.itemView.findViewWithTag<Button>("del").setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) onDelete(pos)
        }
    }

    override fun getItemCount() = items.size
}

/** ItemTouchHelper callback for route editor drag & drop */
class RouteItemTouchCallback(private val adapter: RouteEditorAdapter) : ItemTouchHelper.Callback() {
    override fun isLongPressDragEnabled() = false
    override fun isItemViewSwipeEnabled() = false

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
    }

    override fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
        val from = source.adapterPosition
        val to = target.adapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
        adapter.onItemMove(from, to)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            viewHolder?.itemView?.alpha = 0.7f
            viewHolder?.itemView?.setBackgroundColor(Color.parseColor("#333333"))
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.itemView.alpha = 1f
        adapter.notifyDataSetChanged()
    }
}

/** Adapter for widget order settings with drag & drop + toggle + optional move button */
class WidgetOrderAdapter(
    val items: MutableList<Triple<String, String, Boolean>>, // key, label, enabled
    private val dp: Float,
    private val onToggle: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<WidgetOrderAdapter.VH>() {

    var dragStartListener: ((RecyclerView.ViewHolder) -> Unit)? = null
    class VH(row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val dragHandle: TextView = row.findViewWithTag("drag")
        val label: TextView = row.findViewWithTag("label")
        val toggle: androidx.appcompat.widget.SwitchCompat = row.findViewWithTag("toggle")
    }

    fun onItemMove(from: Int, to: Int) {
        if (from < 0 || from >= items.size || to < 0 || to >= items.size) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * dp + 0.5f).toInt()
            )
            setPadding((4 * dp).toInt(), 0, (16 * dp).toInt(), 0)
        }
        row.addView(TextView(ctx).apply {
            tag = "drag"; text = "≡"; textSize = 20f; setTextColor(Color.parseColor("#666666"))
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        })
        row.addView(TextView(ctx).apply {
            tag = "label"; textSize = 15f; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(androidx.appcompat.widget.SwitchCompat(ctx).apply {
            tag = "toggle"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        return VH(row)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val (_, label, enabled) = items[position]
        holder.label.text = label
        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = enabled
        holder.toggle.setOnCheckedChangeListener { _, checked ->
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                items[pos] = items[pos].copy(third = checked)
                onToggle(pos, checked)
            }
        }
        holder.itemView.setBackgroundColor(
            if (position % 2 == 0) Color.parseColor("#1E1E1E") else Color.TRANSPARENT
        )
        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) dragStartListener?.invoke(holder)
            false
        }
    }

    override fun getItemCount() = items.size
}

private fun symbolChar(symbol: String): String = when (symbol.lowercase()) {
    "triangle" -> "△"; "flag" -> "⚑"; "star" -> "★"; "cross" -> "✚"
    "square" -> "■"; "diamond" -> "◆"; "pin" -> "📍"; else -> "⊕"
}
