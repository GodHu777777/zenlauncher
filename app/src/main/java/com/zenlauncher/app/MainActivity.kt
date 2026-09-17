package com.zenlauncher.app

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserHandle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.zenlauncher.app.databinding.ActivityMainBinding
import com.zenlauncher.app.manager.AppManager
import com.zenlauncher.app.manager.PrefManager
import com.zenlauncher.app.model.AppInfo
import com.zenlauncher.app.ui.AppAdapter
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
    private var launcherAppsCallback: LauncherApps.Callback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pref = PrefManager(this)

        setupAdapters()
        setupListeners()
        registerLauncherAppsCallback()
    }

    override fun onResume() {
        super.onResume()
        binding.tvMotto.text = pref.getMotto()
        loadApps()
    }

    private fun setupAdapters() {
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
}
