package com.andreykoff.racenav

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor for single group: name, color, members.
 * Create mode: groupId arg is null/absent.
 * Edit mode: groupId arg contains existing id.
 */
class GroupEditorFragment : Fragment() {

    private var groupId: String? = null
    private var currentGroup: FavoritesGroup = FavoritesGroup(id = "", name = "", color = GroupColors.ORANGE, members = emptyList())
    private var currentDoc: FavoritesDocument = FavoritesDocument.empty()

    private lateinit var nameEdit: EditText
    private lateinit var colorsRow: LinearLayout
    private lateinit var membersContainer: LinearLayout
    private lateinit var membersCountLabel: TextView
    private lateinit var addMembersBtn: View
    private lateinit var deleteBtn: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupId = arguments?.getString("groupId")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_group_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        currentDoc = FavoritesGroupsRepository.getCached(ctx)

        nameEdit = view.findViewById(R.id.groupEditName)
        colorsRow = view.findViewById(R.id.groupEditColorsRow)
        membersContainer = view.findViewById(R.id.groupEditMembers)
        membersCountLabel = view.findViewById(R.id.groupEditMembersCount)
        addMembersBtn = view.findViewById(R.id.groupEditAddMembers)
        deleteBtn = view.findViewById(R.id.groupEditDelete)
        val title = view.findViewById<TextView>(R.id.groupEditTitle)

        currentGroup = if (groupId != null) {
            currentDoc.groups.firstOrNull { it.id == groupId }
                ?: FavoritesGroup(id = groupId!!, name = "", color = GroupColors.ORANGE, members = emptyList())
        } else {
            FavoritesGroup(id = "", name = "", color = GroupColors.ORANGE, members = emptyList())
        }

        title.text = if (groupId == null) "Новая группа" else "Редактирование"
        deleteBtn.visibility = if (groupId == null) View.GONE else View.VISIBLE

        view.findViewById<View>(R.id.groupEditBack).setOnClickListener { parentFragmentManager.popBackStack() }
        view.findViewById<View>(R.id.groupEditSave).setOnClickListener { save() }
        deleteBtn.setOnClickListener { confirmDelete() }
        addMembersBtn.setOnClickListener { openMembersPicker() }

        nameEdit.setText(currentGroup.name)
        nameEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentGroup = currentGroup.copy(name = s?.toString().orEmpty())
            }
        })

        buildColorsRow()
        renderMembers()

        // Listen for picker result
        parentFragmentManager.setFragmentResultListener("members_picker_result", viewLifecycleOwner) { _, bundle ->
            val selected = bundle.getStringArrayList("selected") ?: arrayListOf()
            val merged = (currentGroup.members + selected).map { it.uppercase() }.distinct()
            val limits = FavoritesGroupsRepository.getLimits()
            val limited = if (merged.size > limits.maxMembersPerGroup) {
                Toast.makeText(requireContext(), "Лимит участников: ${limits.maxMembersPerGroup}", Toast.LENGTH_LONG).show()
                merged.take(limits.maxMembersPerGroup)
            } else merged
            currentGroup = currentGroup.copy(members = limited)
            renderMembers()
        }
    }

    private fun buildColorsRow() {
        val density = resources.displayMetrics.density
        colorsRow.removeAllViews()
        GroupColors.ALL.forEach { hex ->
            val size = (36 * density).toInt()
            val margin = (6 * density).toInt()
            val v = View(requireContext())
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = margin
            v.layoutParams = lp
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(hex))
                setStroke(
                    (if (hex == currentGroup.color) 3 * density else 1 * density).toInt(),
                    if (hex == currentGroup.color) Color.WHITE else 0x44FFFFFF
                )
            }
            v.background = drawable
            v.setOnClickListener {
                currentGroup = currentGroup.copy(color = hex)
                buildColorsRow()
            }
            colorsRow.addView(v)
        }
    }

    private fun renderMembers() {
        val ctx = context ?: return
        val liveDevices = liveDevicesMap()
        membersContainer.removeAllViews()
        val limits = FavoritesGroupsRepository.getLimits()
        membersCountLabel.text = "Участники (${currentGroup.members.size} из ${limits.maxMembersPerGroup})"
        if (currentGroup.members.isEmpty()) {
            val tv = TextView(ctx).apply {
                text = "Нет участников. Нажмите «+ Добавить участников»."
                setTextColor(0xFF888888.toInt())
                textSize = 13f
                setPadding(24, 24, 24, 24)
            }
            membersContainer.addView(tv)
            return
        }
        val inflater = LayoutInflater.from(ctx)
        currentGroup.members.forEach { uid ->
            val item = inflater.inflate(R.layout.item_member, membersContainer, false)
            val live = liveDevices[uid]
            val name = live?.name ?: "—"
            item.findViewById<TextView>(R.id.memberName).text = name.ifBlank { uid }
            item.findViewById<TextView>(R.id.memberUniqueId).text = uid
            val status = item.findViewById<TextView>(R.id.memberStatus)
            when {
                live == null -> {
                    status.text = "⚠ Недоступен"
                    status.setTextColor(0xFFFFA000.toInt())
                }
                live.status == "online" -> {
                    status.text = "● онлайн"
                    status.setTextColor(0xFF4CAF50.toInt())
                }
                else -> {
                    status.text = "● офлайн"
                    status.setTextColor(0xFF888888.toInt())
                }
            }
            item.findViewById<View>(R.id.memberRemove).setOnClickListener {
                currentGroup = currentGroup.copy(members = currentGroup.members.filter { it != uid })
                renderMembers()
            }
            membersContainer.addView(item)
        }
    }

    private fun liveDevicesMap(): Map<String, LiveUsersPoller.LiveDevice> {
        val mapFrag = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
        val devices = mapFrag?.lastLiveDevices ?: emptyList()
        return devices.associateBy { it.uniqueId.uppercase() }
    }

    private fun save() {
        val ctx = requireContext()
        val name = currentGroup.name.trim()
        if (name.isBlank()) {
            Toast.makeText(ctx, "Введите название группы", Toast.LENGTH_SHORT).show()
            return
        }
        if (name.length > 32) {
            Toast.makeText(ctx, "Название — не больше 32 символов", Toast.LENGTH_SHORT).show()
            return
        }
        val limits = FavoritesGroupsRepository.getLimits()
        val isNew = groupId == null
        if (isNew && currentDoc.groups.size >= limits.maxGroups) {
            Toast.makeText(ctx, "Достигнут лимит групп (${limits.maxGroups})", Toast.LENGTH_LONG).show()
            return
        }
        val finalId = if (isNew) {
            FavoritesGroupsFragment.generateGroupId(currentDoc, name)
        } else groupId!!
        val finalGroup = currentGroup.copy(id = finalId, name = name)
        val newGroups = if (isNew) {
            currentDoc.groups + finalGroup
        } else {
            currentDoc.groups.map { if (it.id == finalId) finalGroup else it }
        }
        val updated = currentDoc.copy(groups = newGroups)
        persistAndClose(updated)
    }

    private fun confirmDelete() {
        val ctx = requireContext()
        android.app.AlertDialog.Builder(ctx, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Удалить группу")
            .setMessage("Удалить группу «${currentGroup.name}»?")
            .setPositiveButton("Удалить") { _, _ ->
                val newGroups = currentDoc.groups.filter { it.id != groupId }
                val newActive = if (currentDoc.activeGroupId == groupId) {
                    if (newGroups.isEmpty()) FavoritesGroupsRepository.ACTIVE_ALL else newGroups.first().id
                } else currentDoc.activeGroupId
                val updated = currentDoc.copy(groups = newGroups, activeGroupId = newActive)
                if (currentDoc.activeGroupId == groupId) {
                    FavoritesGroupsRepository.setActiveGroupId(ctx, newActive)
                }
                persistAndClose(updated)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun persistAndClose(doc: FavoritesDocument) {
        val ctx = requireContext()
        CoroutineScope(Dispatchers.IO).launch {
            val result = FavoritesGroupsRepository.saveToServer(ctx, doc)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                when (result) {
                    is FavoritesResult.Success, FavoritesResult.NoSync -> {
                        parentFragmentManager.popBackStack()
                    }
                    is FavoritesResult.VersionConflict -> {
                        Toast.makeText(ctx, "Группы обновлены с другого устройства. Обновляю…", Toast.LENGTH_LONG).show()
                        CoroutineScope(Dispatchers.IO).launch {
                            FavoritesGroupsRepository.syncFromServer(ctx)
                            withContext(Dispatchers.Main) {
                                if (isAdded) parentFragmentManager.popBackStack()
                            }
                        }
                    }
                    is FavoritesResult.NetworkError -> {
                        Toast.makeText(ctx, "Нет связи — сохранено локально", Toast.LENGTH_SHORT).show()
                        parentFragmentManager.popBackStack()
                    }
                    is FavoritesResult.AuthError -> {
                        Toast.makeText(ctx, "Ошибка авторизации sync", Toast.LENGTH_SHORT).show()
                    }
                    is FavoritesResult.RateLimit -> {
                        Toast.makeText(ctx, "Слишком много изменений — подождите", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun openMembersPicker() {
        val d = MembersPickerDialog()
        d.arguments = Bundle().apply {
            putStringArrayList("already", ArrayList(currentGroup.members))
            putString("groupName", currentGroup.name.ifBlank { "новая группа" })
        }
        d.show(parentFragmentManager, "members_picker")
    }
}
