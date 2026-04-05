package com.andreykoff.racenav

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * List screen for favorite groups. Shows cards, allows create/duplicate/delete/set-active.
 */
class FavoritesGroupsFragment : Fragment() {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var noSyncHint: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_favorites_groups, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listContainer = view.findViewById(R.id.favGroupsList)
        emptyHint = view.findViewById(R.id.favGroupsEmptyHint)
        noSyncHint = view.findViewById(R.id.favGroupsNoSyncHint)

        view.findViewById<View>(R.id.favGroupsBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.favGroupsAddBtn).setOnClickListener {
            openEditor(null)
        }

        // Background refresh from server
        val ctx = requireContext()
        if (FavoritesGroupsRepository.hasSync(ctx)) {
            noSyncHint.visibility = View.GONE
            CoroutineScope(Dispatchers.IO).launch {
                val result = FavoritesGroupsRepository.syncFromServer(ctx)
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        when (result) {
                            is FavoritesResult.AuthError -> Toast.makeText(ctx, "Ошибка авторизации sync", Toast.LENGTH_SHORT).show()
                            is FavoritesResult.NetworkError -> Toast.makeText(ctx, "Нет связи — показаны кэшированные группы", Toast.LENGTH_SHORT).show()
                            else -> {}
                        }
                        render()
                    }
                }
            }
        } else {
            noSyncHint.visibility = View.VISIBLE
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val ctx = context ?: return
        val doc = FavoritesGroupsRepository.getCached(ctx)
        val activeId = FavoritesGroupsRepository.getActiveGroupId(ctx)
        listContainer.removeAllViews()
        if (doc.groups.isEmpty()) {
            emptyHint.visibility = View.VISIBLE
        } else {
            emptyHint.visibility = View.GONE
            doc.groups.forEach { g ->
                listContainer.addView(buildGroupCard(g, g.id == activeId))
            }
        }
    }

    private fun buildGroupCard(g: FavoritesGroup, isActive: Boolean): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val inflater = LayoutInflater.from(ctx)
        val card = inflater.inflate(R.layout.item_group_card, listContainer, false)
        card.findViewById<View>(R.id.groupColorDot).setBackgroundColor(parseColor(g.color))
        card.findViewById<TextView>(R.id.groupName).text = g.name
        card.findViewById<TextView>(R.id.groupMemberCount).text = "${g.members.size} участн."
        val activeLabel = card.findViewById<TextView>(R.id.groupActiveLabel)
        activeLabel.visibility = if (isActive) View.VISIBLE else View.GONE
        card.findViewById<View>(R.id.groupMenu).setOnClickListener { anchor ->
            showMenu(anchor, g)
        }
        card.setOnClickListener { openEditor(g.id) }
        return card
    }

    private fun showMenu(anchor: View, g: FavoritesGroup) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Сделать активной")
        popup.menu.add(0, 2, 1, "Дублировать")
        popup.menu.add(0, 3, 2, "Удалить")
        popup.setOnMenuItemClickListener { item ->
            val ctx = requireContext()
            when (item.itemId) {
                1 -> {
                    FavoritesGroupsRepository.setActiveGroupId(ctx, g.id)
                    render()
                }
                2 -> duplicateGroup(g)
                3 -> confirmDelete(g)
            }
            true
        }
        popup.show()
    }

    private fun duplicateGroup(g: FavoritesGroup) {
        val ctx = requireContext()
        val doc = FavoritesGroupsRepository.getCached(ctx)
        val limits = FavoritesGroupsRepository.getLimits()
        if (doc.groups.size >= limits.maxGroups) {
            Toast.makeText(ctx, "Достигнут лимит групп (${limits.maxGroups})", Toast.LENGTH_LONG).show()
            return
        }
        val newId = generateGroupId(doc, g.id + "_copy")
        val copy = g.copy(id = newId, name = g.name + " (копия)")
        val updated = doc.copy(groups = doc.groups + copy)
        persistAndRender(updated)
    }

    private fun confirmDelete(g: FavoritesGroup) {
        val ctx = requireContext()
        android.app.AlertDialog.Builder(ctx, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Удалить группу")
            .setMessage("Удалить группу «${g.name}»?")
            .setPositiveButton("Удалить") { _, _ ->
                val doc = FavoritesGroupsRepository.getCached(ctx)
                val newGroups = doc.groups.filter { it.id != g.id }
                val newActive = if (doc.activeGroupId == g.id) {
                    if (newGroups.isEmpty()) FavoritesGroupsRepository.ACTIVE_ALL else newGroups.first().id
                } else doc.activeGroupId
                persistAndRender(doc.copy(groups = newGroups, activeGroupId = newActive))
                if (doc.activeGroupId == g.id) {
                    FavoritesGroupsRepository.setActiveGroupId(ctx, newActive)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun persistAndRender(doc: FavoritesDocument) {
        val ctx = requireContext()
        CoroutineScope(Dispatchers.IO).launch {
            val result = FavoritesGroupsRepository.saveToServer(ctx, doc)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                when (result) {
                    is FavoritesResult.VersionConflict -> {
                        Toast.makeText(ctx, "Группы обновлены с другого устройства. Загружаю свежую версию.", Toast.LENGTH_LONG).show()
                        CoroutineScope(Dispatchers.IO).launch {
                            FavoritesGroupsRepository.syncFromServer(ctx)
                            withContext(Dispatchers.Main) { if (isAdded) render() }
                        }
                    }
                    is FavoritesResult.NetworkError -> {
                        Toast.makeText(ctx, "Нет связи — изменения в кэше", Toast.LENGTH_SHORT).show()
                        render()
                    }
                    else -> render()
                }
            }
        }
    }

    private fun openEditor(groupId: String?) {
        val f = GroupEditorFragment()
        f.arguments = Bundle().apply { putString("groupId", groupId) }
        parentFragmentManager.beginTransaction()
            .add(R.id.container, f)
            .addToBackStack(null)
            .commit()
    }

    companion object {
        fun generateGroupId(doc: FavoritesDocument, base: String): String {
            var candidate = base.lowercase()
                .replace(Regex("[^a-z0-9_]"), "_")
                .take(24)
                .ifEmpty { "group" }
            val taken = doc.groups.map { it.id }.toSet()
            if (candidate !in taken) return candidate
            var i = 2
            while ("${candidate}_$i" in taken) i++
            return "${candidate}_$i"
        }

        fun parseColor(hex: String): Int {
            return try {
                Color.parseColor(hex)
            } catch (_: Exception) {
                Color.parseColor(GroupColors.ORANGE)
            }
        }
    }
}
