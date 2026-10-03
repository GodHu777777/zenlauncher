package com.zenlauncher.app.manager

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.zenlauncher.app.BuildConfig

/** A real activity advertising CATEGORY_HOME, rather than a system chooser. */
data class HomeActivity(
    val component: ComponentName,
    val label: String,
    val isSystem: Boolean
)

data class DefaultLauncherStatus(
    val ownPackageName: String,
    val roleAvailable: Boolean?,
    val roleHeld: Boolean?,
    val resolvedHome: ComponentName?,
    val homeActivities: List<HomeActivity>,
    val inspectionNotes: List<String>
) {
    // Role ownership alone does not prove that HOME currently routes to this app.
    // Conversely, an explicit negative role result needs attention even if the
    // resolver currently falls back to our activity (for example after an update).
    val isDefault: Boolean
        get() = resolvedHome?.packageName == ownPackageName && roleHeld != false

    val currentHome: HomeActivity?
        get() = homeActivities.firstOrNull { it.component == resolvedHome }

    val hasConflictingSignals: Boolean
        get() = roleHeld != null && resolvedHome != null &&
            roleHeld != (resolvedHome.packageName == ownPackageName)

    val summary: String
        get() = when {
            isDefault -> "✓ 已设为系统默认桌面"
            hasConflictingSignals -> "系统桌面角色与 HOME 路由不一致，请重新检查"
            currentHome != null -> "当前默认桌面：${currentHome!!.label}"
            roleHeld == true -> "已获得桌面角色，尚未确认 HOME 路由"
            else -> "尚未设为默认桌面，或系统未返回有效桌面"
        }
}

/**
 * Uses supported Android HOME selection APIs. The app cannot change HOME silently
 * or override an OEM's system gesture implementation without system privileges.
 */
object DefaultLauncherManager {
    fun getStatus(context: Context): DefaultLauncherStatus {
        val pm = context.packageManager
        val notes = mutableListOf<String>()
        var roleAvailable: Boolean? = null
        var roleHeld: Boolean? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val manager = context.getSystemService(RoleManager::class.java)
                roleAvailable = manager?.isRoleAvailable(RoleManager.ROLE_HOME)
                if (roleAvailable == true) roleHeld = manager?.isRoleHeld(RoleManager.ROLE_HOME)
            } catch (e: RuntimeException) {
                notes += "ROLE_HOME 查询失败：${e.javaClass.simpleName}"
            }
        }

        // Public SDK APIs only: enumerate HOME activities and independently resolve
        // the route. A resolver/chooser component is not itself a HOME candidate.
        val candidates = try {
            queryHomeActivities(pm)
        } catch (e: RuntimeException) {
            notes += "HOME 候选查询失败：${e.javaClass.simpleName}"
            emptyList()
        }
        val homeActivities = candidates.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            val label = try {
                info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: activity.packageName
            } catch (_: RuntimeException) {
                activity.packageName
            }
            HomeActivity(
                ComponentName(activity.packageName, activity.name),
                label,
                (activity.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }.distinctBy { it.component }
        val resolvedHome = try {
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(homeIntent(), PackageManager.ResolveInfoFlags.of(
                    PackageManager.MATCH_DEFAULT_ONLY.toLong()
                ))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            }
            resolved?.activityInfo?.let { ComponentName(it.packageName, it.name) }
        } catch (e: RuntimeException) {
            notes += "HOME 路由查询失败：${e.javaClass.simpleName}"
            null
        }
        return DefaultLauncherStatus(
            context.packageName, roleAvailable, roleHeld, resolvedHome,
            homeActivities, notes
        )
    }

    /** Null means the standard role request is unavailable or already granted. */
    fun createRoleRequestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val manager = context.getSystemService(RoleManager::class.java) ?: return null
            if (!manager.isRoleAvailable(RoleManager.ROLE_HOME) ||
                manager.isRoleHeld(RoleManager.ROLE_HOME)) return null
            manager.createRequestRoleIntent(RoleManager.ROLE_HOME)
        } catch (_: RuntimeException) {
            null
        }
    }

    /** For legacy callers; new UI should use Activity Result APIs and recheck getStatus. */
    @Suppress("DEPRECATION")
    fun requestDefaultLauncher(activity: Activity, requestCode: Int): Boolean {
        val request = createRoleRequestIntent(activity)
        if (request != null) {
            try {
                activity.startActivityForResult(request, requestCode)
                return true
            } catch (_: RuntimeException) {
                // Some OEMs expose ROLE_HOME but do not expose its request activity.
            }
        }
        return openHomeSettings(activity)
    }

    /** Returns whether a settings screen opened, never whether HOME was changed. */
    fun openHomeSettings(context: Context): Boolean {
        val intents = mutableListOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        )
        if (isXiaomiDevice()) intents += Intent("miui.intent.action.PREFERRED_APP")
        intents += Intent(Settings.ACTION_SETTINGS)
        return startFirstAvailable(context, intents)
    }

    /** Opens an installed HOME app's details, not an arbitrary hardcoded OEM package. */
    fun openSystemLauncherDetails(context: Context): Boolean {
        val status = getStatus(context)
        val otherHomes = status.homeActivities.filter {
            it.component.packageName != context.packageName
        }
        val launcher = otherHomes.firstOrNull { it.component == status.resolvedHome }
            ?: otherHomes.firstOrNull { it.isSystem }
            ?: otherHomes.firstOrNull()
        if (launcher != null && isInstalled(context.packageManager, launcher.component.packageName)) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${launcher.component.packageName}")
            }
            if (startFirstAvailable(context, listOf(intent))) return true
        }
        return openHomeSettings(context)
    }

    fun openSystemNavigationSettings(context: Context): Boolean {
        val intents = mutableListOf<Intent>()
        if (isXiaomiDevice()) {
            intents += Intent("miui.intent.action.FULLSCREEN_NAVIGATION")
            intents += Intent().setComponent(ComponentName(
                "com.miui.home", "com.miui.home.settings.NavigationModeSettings"
            ))
        }
        // Public settings entry points; hidden SubSettings fragments are not portable.
        intents += Intent(Settings.ACTION_DISPLAY_SETTINGS)
        intents += Intent(Settings.ACTION_SETTINGS)
        return startFirstAvailable(context, intents)
    }

    fun getDiagnosticText(context: Context): String {
        val status = getStatus(context)
        return buildString {
            appendLine("ZenLauncher 默认桌面诊断")
            appendLine("设备：${Build.MANUFACTURER} / ${Build.BRAND} / ${Build.MODEL}")
            appendLine("Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("系统版本：${Build.DISPLAY}")
            appendLine("ZenLauncher：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("当前用户：${Process.myUserHandle()}")
            appendLine("应用：${context.packageName}")
            appendLine("状态：${status.summary}")
            appendLine("ROLE_HOME 可用：${status.roleAvailable?.toString() ?: "不支持或未知"}")
            appendLine("ROLE_HOME 持有：${status.roleHeld?.toString() ?: "不支持或未知"}")
            appendLine("HOME 解析：${status.resolvedHome?.flattenToShortString() ?: "未知"}")
            appendLine("HOME 候选：")
            status.homeActivities.forEach {
                appendLine("- ${it.label}: ${it.component.flattenToShortString()}")
            }
            if (status.homeActivities.isEmpty()) appendLine("- 未返回")
            status.inspectionNotes.forEach { appendLine(it) }
            appendLine()
            append("此检查针对 Android HOME 路由。部分厂商的全面屏手势、最近任务或工作空间可能使用独立的系统桌面逻辑；即使已设为默认，也需实际测试返回键、Home 键和上滑手势。")
        }
    }

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    private fun queryHomeActivities(pm: PackageManager): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(homeIntent(), PackageManager.ResolveInfoFlags.of(
                PackageManager.MATCH_DEFAULT_ONLY.toLong()
            ))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        }

    private fun isInstalled(pm: PackageManager, packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun isXiaomiDevice(): Boolean = listOf(Build.MANUFACTURER, Build.BRAND).any {
        it.equals("Xiaomi", ignoreCase = true) || it.equals("Redmi", ignoreCase = true) ||
            it.equals("Poco", ignoreCase = true)
    }

    private fun startFirstAvailable(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Starting directly avoids false negatives from package visibility filtering.
                context.startActivity(intent)
                return true
            } catch (_: RuntimeException) {
                // Unsupported or non-exported OEM settings activities fall through safely.
            }
        }
        return false
    }
}
