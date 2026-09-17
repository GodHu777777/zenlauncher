package com.zenlauncher.app

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.zenlauncher.app/launcher"

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getInstalledApps" -> {
                    val includeSystem = call.argument<Boolean>("includeSystem") ?: true
                    val apps = getInstalledApps(includeSystem)
                    result.success(apps)
                }
                "launchApp" -> {
                    val packageName = call.argument<String>("packageName")
                    if (packageName != null) {
                        val launched = launchApp(packageName)
                        result.success(launched)
                    } else {
                        result.error("INVALID_ARGUMENT", "Package name is null", null)
                    }
                }
                "openAppInfo" -> {
                    val packageName = call.argument<String>("packageName")
                    if (packageName != null) {
                        openAppInfo(packageName)
                        result.success(true)
                    } else {
                        result.error("INVALID_ARGUMENT", "Package name is null", null)
                    }
                }
                "openDefaultLauncherSettings" -> {
                    openDefaultLauncherSettings()
                    result.success(true)
                }
                "isDefaultLauncher" -> {
                    result.success(isDefaultLauncher())
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun getInstalledApps(includeSystem: Boolean): List<Map<String, Any>> {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        val appList = ArrayList<Map<String, Any>>()
        val currentPackage = packageName

        for (resolveInfo in resolveInfos) {
            val pkg = resolveInfo.activityInfo.packageName
            // Skip the launcher itself
            if (pkg == currentPackage) continue

            val isSystem = (resolveInfo.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && isSystem) continue

            val label = resolveInfo.loadLabel(pm).toString()
            val activityName = resolveInfo.activityInfo.name

            val appMap = HashMap<String, Any>()
            appMap["appName"] = label
            appMap["packageName"] = pkg
            appMap["activityName"] = activityName
            appMap["isSystemApp"] = isSystem

            appList.add(appMap)
        }

        return appList
    }

    private fun launchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun openAppInfo(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val REQUEST_ROLE_HOME = 1001

    private fun openDefaultLauncherSettings() {
        // 1. Try Android Q+ RoleManager with startActivityForResult
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = getSystemService(Context.ROLE_SERVICE) as? RoleManager
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                    startActivityForResult(intent, REQUEST_ROLE_HOME)
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Try MIUI / HyperOS Preferred App Intent
        try {
            val miuiIntent = Intent("miui.intent.action.PREFERRED_APP").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (packageManager.resolveActivity(miuiIntent, 0) != null) {
                startActivity(miuiIntent)
                return
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Try ACTION_MANAGE_DEFAULT_APPS_SETTINGS (Standard Android 7+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 4. Try ACTION_HOME_SETTINGS
        try {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 5. Try Application Details Settings
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 6. Fallback to General Settings
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isDefaultLauncher(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return resolveInfo?.activityInfo?.packageName == packageName
    }
}
