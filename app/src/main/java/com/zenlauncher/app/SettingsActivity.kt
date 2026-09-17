package com.zenlauncher.app

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.Manifest
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.zenlauncher.app.databinding.ActivitySettingsBinding
import com.zenlauncher.app.manager.AppManager
import com.zenlauncher.app.manager.CalendarManager
import com.zenlauncher.app.manager.PrefManager
import com.zenlauncher.app.model.AppInfo
import com.zenlauncher.app.util.PinyinSearchEngine

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var pref: PrefManager
    private var allApps: List<AppInfo> = emptyList()

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pref.setCalendarEnabled(true)
            updateCalendarViews()
        } else {
            pref.setCalendarEnabled(false)
            updateCalendarViews()
            Toast.makeText(this, "需要日历读取权限以展示日程", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pref = PrefManager(this)
        allApps = AppManager.loadAllApps(this, pref)

        setupViews()
    }

    override fun onResume() {
        super.onResume()
        updateDefaultLauncherStatus()
        updateCalendarViews()
    }

    private fun setupViews() {
        updateDefaultLauncherStatus()

        binding.btnSetDefaultLauncher.setOnClickListener {
            AppManager.openDefaultLauncherSettings(this)
            showDefaultLauncherGuideDialog()
        }

        // Dopamine Apps
        updateDopamineSummary()
        binding.btnManageDopamine.setOnClickListener {
            showDopamineBottomSheet()
        }

        // Cooldown Duration
        updateCooldownSummary()
        binding.btnCooldownDuration.setOnClickListener {
            showCooldownDurationDialog()
        }

        // Motto
        updateMottoSummary()
        binding.btnCustomMotto.setOnClickListener {
            showMottoEditDialog()
        }

        // Search Engine
        updateSearchEngineSummary()
        binding.btnSearchEngine.setOnClickListener {
            showSearchEngineDialog()
        }

        // Calendar & Agenda
        updateCalendarViews()
        binding.btnSelectCalendars.setOnClickListener {
            showCalendarSelectionDialog()
        }
        binding.btnCalendarCount.setOnClickListener {
            showCalendarCountDialog()
        }
        binding.btnICloudGuide.setOnClickListener {
            showICloudGuideDialog()
        }
    }

    private fun updateDefaultLauncherStatus() {
        val isDefault = AppManager.isDefaultLauncher(this)
        if (isDefault) {
            binding.tvDefaultLauncherStatus.text = "✓ 已设为系统默认桌面"
            binding.tvDefaultLauncherStatus.setTextColor(getColor(R.color.white))
        } else {
            binding.tvDefaultLauncherStatus.text = "当前未设为系统默认桌面"
            binding.tvDefaultLauncherStatus.setTextColor(getColor(R.color.amber_accent))
        }
    }

    private fun showDefaultLauncherGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("如何设为默认桌面")
            .setMessage(
                "💡 国产手机系统（小米/HyperOS、华为/鸿蒙、vivo、OPPO 等）对桌面有安全拦截限制。若未自动弹出切换框，可通过以下方式设置：\n\n" +
                "【最快方式】：\n" +
                "按手机底部的 Home 键（或从屏幕底部边缘轻轻上滑返回桌面），系统通常会直接弹出「选择主屏幕应用」选择框，选择 ZenLauncher 并点击「始终」。\n\n" +
                "【手动设置路径】：\n" +
                "• 小米 / Redmi：设置 → 应用设置 → 应用管理 → 右上角三个点「默认应用设置」 → 桌面 → 选择 ZenLauncher\n" +
                "• 华为 / 荣耀：设置 → 应用和服务 → 默认应用 → 桌面 → 选择 ZenLauncher\n" +
                "• vivo / iQOO：设置 → 应用与权限 → 默认应用设置 → 桌面 → 选择 ZenLauncher\n" +
                "• OPPO / 一加：设置 → 应用 → 默认应用 → 桌面 → 选择 ZenLauncher"
            )
            .setPositiveButton("我知道了", null)
            .show()
    }

    private fun updateDopamineSummary() {
        val count = pref.getDopamineApps().size
        binding.tvDopamineSummary.text = "已配置 $count 个需倒计时应用 (如抖音/小红书)"
    }

    private fun showDopamineBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_dopamine_apps, null)
        dialog.setContentView(sheetView)

        val tvSelectedCount = sheetView.findViewById<TextView>(R.id.tvSelectedCount)
        val etSearch = sheetView.findViewById<EditText>(R.id.etSearchDopamine)
        val rvApps = sheetView.findViewById<RecyclerView>(R.id.rvDopamineApps)
        val btnClose = sheetView.findViewById<ImageButton>(R.id.btnCloseDopamineSheet)

        rvApps.layoutManager = LinearLayoutManager(this)

        val dopamineSet = pref.getDopamineApps().toMutableSet()
        tvSelectedCount.text = "已选中 ${dopamineSet.size} 个应用"

        val sortedList = allApps.sortedWith { a, b ->
            val p1 = if (a.pinyin.isNotEmpty()) a.pinyin else a.appName.lowercase()
            val p2 = if (b.pinyin.isNotEmpty()) b.pinyin else b.appName.lowercase()
            p1.compareTo(p2)
        }

        var currentFiltered = sortedList

        class DopamineAdapter : RecyclerView.Adapter<DopamineAdapter.VH>() {
            inner class VH(v: View) : RecyclerView.ViewHolder(v) {
                val tvName: TextView = v.findViewById(R.id.tvAppName)
                val cb: CheckBox = CheckBox(v.context).apply {
                    (v as ViewGroup).addView(this, 0)
                }
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_text, parent, false)
                return VH(view)
            }

            override fun onBindViewHolder(holder: VH, position: Int) {
                val app = currentFiltered[position]
                holder.tvName.text = if (app.isClone) "${app.appName} (分身)" else app.appName
                val isChecked = dopamineSet.contains(app.id)
                holder.cb.isChecked = isChecked

                holder.itemView.setOnClickListener {
                    if (dopamineSet.contains(app.id)) {
                        dopamineSet.remove(app.id)
                        holder.cb.isChecked = false
                    } else {
                        dopamineSet.add(app.id)
                        holder.cb.isChecked = true
                    }
                    pref.saveDopamineApps(dopamineSet)
                    tvSelectedCount.text = "已选中 ${dopamineSet.size} 个应用"
                    updateDopamineSummary()
                }
            }

            override fun getItemCount(): Int = currentFiltered.size
        }

        val adapter = DopamineAdapter()
        rvApps.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim() ?: ""
                currentFiltered = if (q.isEmpty()) {
                    sortedList
                } else {
                    PinyinSearchEngine.search(sortedList, q)
                }
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateCooldownSummary() {
        val seconds = pref.getFrictionSeconds()
        binding.tvCooldownSummary.text = "$seconds 秒"
    }

    private fun showCooldownDurationDialog() {
        val options = arrayOf("3 秒", "5 秒 (推荐)", "10 秒", "15 秒 (极度克制)")
        val values = intArrayOf(3, 5, 10, 15)
        val current = pref.getFrictionSeconds()
        val checkedItem = values.indexOfFirst { it == current }.let { if (it >= 0) it else 1 }

        AlertDialog.Builder(this)
            .setTitle("选择冷静缓冲时长")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                pref.setFrictionSeconds(values[which])
                updateCooldownSummary()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateMottoSummary() {
        binding.tvMottoSummary.text = pref.getMotto()
    }

    private fun showMottoEditDialog() {
        val et = EditText(this).apply {
            setText(pref.getMotto())
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("修改专注标语")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                val newMotto = et.text.toString().trim()
                if (newMotto.isNotEmpty()) {
                    pref.setMotto(newMotto)
                    updateMottoSummary()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateSearchEngineSummary() {
        binding.tvSearchEngineSummary.text = when (pref.getSearchEngine()) {
            "Google" -> "Google"
            "Bing" -> "Bing (必应)"
            "DuckDuckGo" -> "DuckDuckGo"
            else -> "百度"
        }
    }

    private fun showSearchEngineDialog() {
        val engines = arrayOf("百度", "Google", "Bing (必应)", "DuckDuckGo")
        val values = arrayOf("Baidu", "Google", "Bing", "DuckDuckGo")
        val current = pref.getSearchEngine()
        val checkedItem = values.indexOfFirst { it.equals(current, true) }.let { if (it >= 0) it else 0 }

        AlertDialog.Builder(this)
            .setTitle("默认搜索引擎")
            .setSingleChoiceItems(engines, checkedItem) { dialog, which ->
                pref.setSearchEngine(values[which])
                updateSearchEngineSummary()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateCalendarViews() {
        val hasPermission = CalendarManager.hasCalendarPermission(this)
        val isEnabled = pref.isCalendarEnabled() && hasPermission

        binding.switchEnableCalendar.setOnCheckedChangeListener(null)
        binding.switchEnableCalendar.isChecked = isEnabled
        binding.layoutCalendarSubOptions.visibility = if (isEnabled) View.VISIBLE else View.GONE

        binding.switchEnableCalendar.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (CalendarManager.hasCalendarPermission(this)) {
                    pref.setCalendarEnabled(true)
                    updateCalendarViews()
                } else {
                    calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                }
            } else {
                pref.setCalendarEnabled(false)
                updateCalendarViews()
            }
        }

        updateCalendarCountSummary()
        updateCalendarSelectionSummary()
    }

    private fun updateCalendarCountSummary() {
        val count = pref.getCalendarMaxCount()
        binding.tvCalendarCountSummary.text = "$count 条"
    }

    private fun updateCalendarSelectionSummary() {
        val selected = pref.getSelectedCalendars()
        if (selected.isEmpty()) {
            binding.tvSelectCalendarsSummary.text = "全部日历"
        } else {
            binding.tvSelectCalendarsSummary.text = "已选 ${selected.size} 个日历"
        }
    }

    private fun showCalendarCountDialog() {
        val options = arrayOf("1 条", "2 条", "3 条 (推荐)", "5 条")
        val values = intArrayOf(1, 2, 3, 5)
        val current = pref.getCalendarMaxCount()
        val checkedItem = values.indexOfFirst { it == current }.let { if (it >= 0) it else 2 }

        AlertDialog.Builder(this)
            .setTitle("最多展示数量")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                pref.setCalendarMaxCount(values[which])
                updateCalendarCountSummary()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCalendarSelectionDialog() {
        if (!CalendarManager.hasCalendarPermission(this)) {
            calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            return
        }

        val calendars = CalendarManager.getAvailableCalendars(this)
        if (calendars.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("未发现日历")
                .setMessage("当前系统尚未检测到任何日历账户。\n\n如需连接 iCloud 日历，请参考下方指引完成配置。")
                .setPositiveButton("我知道了", null)
                .show()
            return
        }

        val items = calendars.map {
            val acc = if (it.accountName.isNotEmpty()) " (${it.accountName})" else ""
            "${it.displayName}$acc"
        }.toTypedArray()

        val selectedSet = pref.getSelectedCalendars().toMutableSet()
        // If selectedSet is empty, initially treat all as checked
        val checkedItems = BooleanArray(calendars.size) { i ->
            if (selectedSet.isEmpty()) true else selectedSet.contains(calendars[i].id.toString())
        }

        AlertDialog.Builder(this)
            .setTitle("选择要展示的日历来源")
            .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                val idStr = calendars[which].id.toString()
                if (isChecked) {
                    selectedSet.add(idStr)
                } else {
                    selectedSet.remove(idStr)
                }
            }
            .setPositiveButton("确定") { _, _ ->
                if (selectedSet.size == calendars.size || selectedSet.isEmpty()) {
                    pref.saveSelectedCalendars(emptySet())
                } else {
                    pref.saveSelectedCalendars(selectedSet)
                }
                updateCalendarSelectionSummary()
            }
            .setNeutralButton("全选") { _, _ ->
                pref.saveSelectedCalendars(emptySet())
                updateCalendarSelectionSummary()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showICloudGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("如何在安卓上同步 iCloud 日历")
            .setMessage(
                "💡 Apple iCloud 日历使用标准 CalDAV 协议，Android 原生可实现秒级静默同步：\n\n" +
                "【推荐方案：DAVx⁵ 同步（开源免费无广告）】\n" +
                "1. 在应用商店或应用宝/酷安下载「DAVx⁵」。\n" +
                "2. 浏览器打开 appleid.apple.com 登录，在「App 专用密码」处生成一个专用密码（安全防泄露）。\n" +
                "3. 打开 DAVx⁵，点击添加账户：\n" +
                "   • 选择「使用 URL 和用户名登录 CalDAV」\n" +
                "   • 服务器 URL：caldav.icloud.com\n" +
                "   • 用户名：你的 Apple ID 邮箱\n" +
                "   • 密码：刚才生成的专用密码\n" +
                "4. 勾选需要同步的日历（如工作、个人、提醒事项），点击同步即可。\n\n" +
                "【系统自带 CalDAV 支持】\n" +
                "• 小米 / HyperOS：设置 → 账号与同步 → 添加账号 → CalDAV\n" +
                "• 三星 Galaxy：设置 → 账户与备份 → 管理账户 → 添加账户 → CalDAV\n\n" +
                "⚡ 同步后日程存储在安卓本地，ZenLauncher 无需联网即可极速展示，零后台耗电，绝不泄露隐私。"
            )
            .setPositiveButton("我知道了", null)
            .show()
    }
}
