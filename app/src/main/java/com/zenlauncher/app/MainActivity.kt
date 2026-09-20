package com.zenlauncher.app

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.CalendarContract
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.zenlauncher.app.databinding.ActivityMainBinding
import com.zenlauncher.app.manager.AppManager
import com.zenlauncher.app.manager.CalendarManager
import com.zenlauncher.app.manager.PrefManager
import com.zenlauncher.app.model.AppInfo
import com.zenlauncher.app.ui.AppAdapter
import com.zenlauncher.app.ui.CalendarAdapter
import com.zenlauncher.app.ui.FrictionDialog
import com.zenlauncher.app.util.PinyinSearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pref: PrefManager

    private var allApps: List<AppInfo> = emptyList()
    private var favoriteApps: List<AppInfo> = emptyList()
    private var searchResults: List<AppInfo> = emptyList()

    private lateinit var favoritesAdapter: AppAdapter
    private lateinit var searchAdapter: AppAdapter
    private lateinit var calendarAdapter: CalendarAdapter
    private var launcherAppsCallback: LauncherApps.Callback? = null
    private var calendarObserver: ContentObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pref = PrefManager(this)

        setupAdapters()
        setupListeners()
        setupBackPressHandler()
        registerLauncherAppsCallback()
    }

    override fun onResume() {
        super.onResume()
        updateDefaultLauncherBanner()
        binding.tvMotto.text = pref.getMotto()
        loadApps()
        loadAgenda()
        registerCalendarObserver()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        clearSearch()
        hideKeyboard()
    }

    private fun setupAdapters() {
        calendarAdapter = CalendarAdapter(emptyList()) { event ->
            CalendarManager.openEventDetails(this, event.eventId)
        }
        binding.rvAgenda.layoutManager = LinearLayoutManager(this)
        binding.rvAgenda.adapter = calendarAdapter

        favoritesAdapter = AppAdapter(
            items = emptyList(),
            onItemClick = { app -> tryLaunchApp(app) },
            onItemLongClick = { app -> showAppActions(app) }
        )
        binding.rvFavorites.layoutManager = LinearLayoutManager(this)
        binding.rvFavorites.adapter = favoritesAdapter

        searchAdapter = AppAdapter(
            items = emptyList(),
            onItemClick = { app ->
                if (app.isWebSearchItem) {
                    val q = binding.etSearch.text.toString().trim()
                    PinyinSearchEngine.openWebSearch(this, q, pref.getSearchEngine())
                    clearSearch()
                } else {
                    tryLaunchApp(app)
                }
            },
            onItemLongClick = { app ->
                if (!app.isWebSearchItem) showAppActions(app)
            }
        )
        binding.rvSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvSearchResults.adapter = searchAdapter
    }

    private fun setupListeners() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnSetDefaultQuick.setOnClickListener {
            AppManager.openDefaultLauncherSettings(this)
            showDefaultLauncherGuideDialog()
        }

        binding.btnAllApps.setOnClickListener {
            showAllAppsBottomSheet()
        }

        binding.btnClearSearch.setOnClickListener {
            clearSearch()
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim() ?: ""
                if (q.isEmpty()) {
                    binding.btnClearSearch.visibility = View.GONE
                    binding.layoutNormalHome.visibility = View.VISIBLE
                    binding.rvSearchResults.visibility = View.GONE
                } else {
                    binding.btnClearSearch.visibility = View.VISIBLE
                    binding.layoutNormalHome.visibility = View.GONE
                    binding.rvSearchResults.visibility = View.VISIBLE
                    performSearch(q)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = binding.etSearch.text.toString().trim()
                if (q.isNotEmpty()) {
                    if (searchResults.size > 1 && !searchResults[1].isWebSearchItem) {
                        tryLaunchApp(searchResults[1])
                    } else {
                        PinyinSearchEngine.openWebSearch(this, q, pref.getSearchEngine())
                        clearSearch()
                    }
                }
                true
            } else {
                false
            }
        }
    }

    private fun registerLauncherAppsCallback() {
        val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps ?: return
        launcherAppsCallback = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) {
                loadApps()
            }
            override fun onPackageRemoved(packageName: String, user: UserHandle) {
                loadApps()
            }
            override fun onPackageChanged(packageName: String, user: UserHandle) {
                loadApps()
            }
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
                loadApps()
            }
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
                loadApps()
            }
        }
        launcherApps.registerCallback(launcherAppsCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        launcherAppsCallback?.let {
            val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            launcherApps?.unregisterCallback(it)
        }
        unregisterCalendarObserver()
    }

    private fun loadAgenda() {
        if (!pref.isCalendarEnabled() || !CalendarManager.hasCalendarPermission(this)) {
            binding.layoutAgenda.visibility = View.GONE
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val events = CalendarManager.getUpcomingEvents(this@MainActivity, pref)
            withContext(Dispatchers.Main) {
                if (events.isEmpty() || !pref.isCalendarEnabled()) {
                    binding.layoutAgenda.visibility = View.GONE
                } else {
                    binding.layoutAgenda.visibility = View.VISIBLE
                    calendarAdapter.updateEvents(events)
                }
            }
        }
    }

    private fun registerCalendarObserver() {
        if (!pref.isCalendarEnabled() || !CalendarManager.hasCalendarPermission(this)) {
            unregisterCalendarObserver()
            return
        }
        if (calendarObserver == null) {
            calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    loadAgenda()
                }
            }
            try {
                contentResolver.registerContentObserver(
                    CalendarContract.Events.CONTENT_URI,
                    true,
                    calendarObserver!!
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun unregisterCalendarObserver() {
        calendarObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            calendarObserver = null
        }
    }

    private fun loadApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val apps = AppManager.loadAllApps(this@MainActivity, pref)
            withContext(Dispatchers.Main) {
                allApps = apps
                val favIds = pref.getFavorites()
                favoriteApps = allApps.filter { favIds.contains(it.id) }.take(6)
                favoritesAdapter.updateList(favoriteApps)
            }
        }
    }

    private fun performSearch(query: String) {
        val matches = PinyinSearchEngine.search(allApps, query)
        val engine = pref.getSearchEngine()
        val webSearchItem = AppInfo(
            appName = "🌐 在 $engine 中搜索「$query」",
            originalName = "",
            packageName = "",
            activityName = "",
            isWebSearchItem = true
        )

        val combined = ArrayList<AppInfo>()
        combined.add(webSearchItem)
        combined.addAll(matches)
        searchResults = combined
        searchAdapter.updateList(combined)
    }

    private fun clearSearch() {
        binding.etSearch.text.clear()
        binding.btnClearSearch.visibility = View.GONE
        binding.layoutNormalHome.visibility = View.VISIBLE
        binding.rvSearchResults.visibility = View.GONE
        hideKeyboard()
    }

    private fun tryLaunchApp(app: AppInfo) {
        val displayName = if (app.isClone) "${app.appName} (分身)" else app.appName
        if (app.isDopamineApp) {
            val dialog = FrictionDialog(
                context = this,
                appName = displayName,
                totalSeconds = pref.getFrictionSeconds(),
                promptText = "停顿 ${pref.getFrictionSeconds()} 秒。\n确认这是你真正想做的事，还是下意识的习惯？",
                onConfirmed = {
                    clearSearch()
                    AppManager.launchApp(this, app)
                }
            )
            dialog.show()
        } else {
            clearSearch()
            AppManager.launchApp(this, app)
        }
    }

    private fun showAppActions(app: AppInfo) {
        val title = if (app.isClone) "${app.appName} (分身)" else app.appName
        val items = arrayOf(
            if (app.isFavorite) "从主页取消置顶" else "置顶到主页",
            if (app.isDopamineApp) "取消冷静防沉迷限制" else "设为冷静应用 (5秒倒计时阻断)",
            if (app.isHidden) "取消隐藏" else "从全应用列表隐藏",
            "重命名 (防诱惑别名)",
            "系统应用详情 / 卸载"
        )

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        pref.toggleFavorite(app.id)
                        loadApps()
                    }
                    1 -> {
                        pref.toggleDopamine(app.id)
                        loadApps()
                    }
                    2 -> {
                        pref.toggleHidden(app.id)
                        loadApps()
                    }
                    3 -> showRenameDialog(app)
                    4 -> AppManager.openAppInfo(this, app)
                }
            }
            .show()
    }

    private fun showRenameDialog(app: AppInfo) {
        val label = if (app.isClone) "${app.originalName} (分身)" else app.originalName
        val et = EditText(this).apply {
            hint = "如：工作微信"
            setText(app.appName)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("重命名「$label」")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                val alias = et.text.toString().trim()
                pref.setAlias(app.id, if (alias.isEmpty()) null else alias)
                loadApps()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAllAppsBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_all_apps, null)
        dialog.setContentView(sheetView)

        val etFilter = sheetView.findViewById<EditText>(R.id.etFilterApps)
        val rvAll = sheetView.findViewById<RecyclerView>(R.id.rvAllApps)
        rvAll.layoutManager = LinearLayoutManager(this)

        val visibleApps = allApps.filter { !it.isHidden }
        var currentApps = visibleApps

        val adapter = AppAdapter(
            items = currentApps,
            onItemClick = { app ->
                dialog.dismiss()
                tryLaunchApp(app)
            },
            onItemLongClick = { app ->
                dialog.dismiss()
                showAppActions(app)
            }
        )
        rvAll.adapter = adapter

        etFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                currentApps = if (q.isEmpty()) {
                    visibleApps
                } else {
                    PinyinSearchEngine.search(allApps, q)
                }
                adapter.updateList(currentApps)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dialog.show()
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackAction()
            }
        })
    }

    private fun handleBackAction() {
        if (binding.etSearch.text.isNotEmpty() || binding.rvSearchResults.visibility == View.VISIBLE) {
            clearSearch()
        } else {
            // Root desktop: DO NOTHING!
            // A home launcher must NEVER finish or return to system launcher on back press/gesture.
            // Consuming the back event guarantees ZenLauncher stays active.
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackAction()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        binding.etSearch.clearFocus()
    }

    private fun updateDefaultLauncherBanner() {
        val isDefault = AppManager.isDefaultLauncher(this)
        binding.layoutSetDefaultBanner.visibility = if (isDefault) View.GONE else View.VISIBLE
    }

    private fun showDefaultLauncherGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("如何设为系统默认桌面")
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
}
