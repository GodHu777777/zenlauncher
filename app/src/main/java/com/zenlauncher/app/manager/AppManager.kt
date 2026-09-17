package com.zenlauncher.app.manager

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.zenlauncher.app.model.AppInfo
import com.zenlauncher.app.util.PinyinSearchEngine

object AppManager {

    const val REQUEST_CODE_ROLE_HOME = 1001

    fun loadAllApps(context: Context, pref: PrefManager): List<AppInfo> {
        val favorites = pref.getFavorites()
        val hidden = pref.getHiddenApps()
        val dopamine = pref.getDopamineApps()
        val myPkg = context.packageName

        val list = ArrayList<AppInfo>()
        var loadedViaLauncherApps = false

        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager

        if (launcherApps != null && userManager != null) {
            try {
                val myUser = android.os.Process.myUserHandle()
                val profileList = ArrayList<android.os.UserHandle>()

                // 1. Get all profiles known to UserManager
                val registeredProfiles = userManager.userProfiles
                if (!registeredProfiles.isNullOrEmpty()) {
                    profileList.addAll(registeredProfiles)
                } else {
                    profileList.add(myUser)
                }

                // 2. Extra safety net for Xiaomi/HyperOS Dual Apps (User 999) if not reported by userProfiles
                try {
                    val constructor = android.os.UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                    constructor.isAccessible = true
                    val dualUser = constructor.newInstance(999) as android.os.UserHandle
                    if (!profileList.contains(dualUser)) {
                        val testActivities = launcherApps.getActivityList(null, dualUser)
                        if (!testActivities.isNullOrEmpty()) {
                            profileList.add(dualUser)
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore reflection failure on non-MIUI or restricted environments
                }

                // Primary user first
                profileList.sortWith { a, b ->
                    if (a == myUser) -1 else if (b == myUser) 1 else 0
                }

                for (user in profileList) {
                    val isClone = (user != myUser)
                    val userSerial = if (isClone) {
                        val s = userManager.getSerialNumberForUser(user)
                        if (s > 0L) s else (user.hashCode().toLong().coerceAtLeast(1L))
                    } else {
                        0L
                    }

                    val activities = try {
                        launcherApps.getActivityList(null, user)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        emptyList()
                    }

                    for (info in activities) {
                        val pkg = info.componentName.packageName
                        if (pkg == myPkg) continue

                        val rawLabel = info.label?.toString()?.trim() ?: pkg
                        val cleanLabel = if (isClone) {
                            rawLabel.replace(Regex("[\\(（]?(分身|双开|克隆|Work|工作)[\\)）]?$"), "").trim()
                                .ifEmpty { rawLabel }
                        } else {
                            rawLabel
                        }

                        val appId = if (userSerial == 0L) pkg else "$pkg#$userSerial"
                        val customAlias = pref.getAlias(appId)
                        val displayName = customAlias ?: cleanLabel

                        val isSystem = (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val isDopamine = dopamine.contains(appId) || (isClone && dopamine.contains(pkg))

                        val item = AppInfo(
                            appName = displayName,
                            originalName = cleanLabel,
                            packageName = pkg,
                            activityName = info.componentName.className,
                            isSystemApp = isSystem,
                            isFavorite = favorites.contains(appId),
                            isHidden = hidden.contains(appId),
                            isDopamineApp = isDopamine,
                            isClone = isClone,
                            userHandle = user,
                            userId = userSerial
                        )

                        PinyinSearchEngine.enrich(item)
                        list.add(item)
                    }
                }

                if (list.isNotEmpty()) {
                    loadedViaLauncherApps = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback for environments where LauncherApps returned nothing
        if (!loadedViaLauncherApps) {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfos: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }

            for (info in resolveInfos) {
                val pkg = info.activityInfo.packageName
                if (pkg == myPkg) continue

                val originalLabel = info.loadLabel(pm).toString()
                val customAlias = pref.getAlias(pkg)
                val displayName = customAlias ?: originalLabel

                val isSystem = (info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                val item = AppInfo(
                    appName = displayName,
                    originalName = originalLabel,
                    packageName = pkg,
                    activityName = info.activityInfo.name,
                    isSystemApp = isSystem,
                    isFavorite = favorites.contains(pkg),
                    isHidden = hidden.contains(pkg),
                    isDopamineApp = dopamine.contains(pkg)
                )

                PinyinSearchEngine.enrich(item)
                list.add(item)
            }
        }

        // Sort alphabetically by pinyin/name, primary first if identical names
        list.sortWith { a, b ->
            val p1 = if (a.pinyin.isNotEmpty()) a.pinyin else a.appName.lowercase()
            val p2 = if (b.pinyin.isNotEmpty()) b.pinyin else b.appName.lowercase()
            val comp = p1.compareTo(p2)
            if (comp != 0) {
                comp
            } else {
                if (a.isClone != b.isClone) {
                    if (!a.isClone) -1 else 1
                } else {
                    0
                }
            }
        }

        return list
    }

    fun launchApp(context: Context, app: AppInfo): Boolean {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        if (launcherApps != null && app.userHandle != null) {
            try {
                val componentName = ComponentName(app.packageName, app.activityName)
                launcherApps.startMainActivity(componentName, app.userHandle, null, null)
                return true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return launchApp(context, app.packageName)
    }

    fun launchApp(context: Context, packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun openAppInfo(context: Context, app: AppInfo) {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        if (launcherApps != null && app.userHandle != null) {
            try {
                val componentName = ComponentName(app.packageName, app.activityName)
                launcherApps.startAppDetailsActivity(componentName, app.userHandle, null, null)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        openAppInfo(context, app.packageName)
    }

    fun openAppInfo(context: Context, packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun openDefaultLauncherSettings(activity: Activity) {
        // 1. Android Q+ RoleManager with startActivityForResult
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = activity.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                    activity.startActivityForResult(intent, REQUEST_CODE_ROLE_HOME)
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. MIUI / HyperOS Preferred App Intent
        try {
            val miuiIntent = Intent("miui.intent.action.PREFERRED_APP").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (activity.packageManager.resolveActivity(miuiIntent, 0) != null) {
                activity.startActivity(miuiIntent)
                return
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. ACTION_MANAGE_DEFAULT_APPS_SETTINGS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 4. ACTION_HOME_SETTINGS
        try {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            return
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 5. Application Details Settings
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            return
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 6. General Settings fallback
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isDefaultLauncher(context: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            resolveInfo?.activityInfo?.packageName == context.packageName
        } catch (e: Exception) {
            false
        }
    }
}
