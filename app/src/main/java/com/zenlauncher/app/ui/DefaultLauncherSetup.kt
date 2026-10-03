package com.zenlauncher.app.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.zenlauncher.app.manager.AppManager
import com.zenlauncher.app.manager.DefaultLauncherManager

/** One user-initiated setup flow shared by the home screen and settings. */
class DefaultLauncherSetup(
    private val activity: AppCompatActivity,
    private val onStatusChanged: () -> Unit
) : DefaultLifecycleObserver {
    private var dialog: Dialog? = null
    private val roleRequest = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // OEMs do not always return an accurate RESULT_OK. Inspect the actual HOME route.
        onStatusChanged()
        if (DefaultLauncherManager.getStatus(activity).isDefault) {
            toast("已确认 ZenLauncher 为默认桌面")
        } else {
            showHelp()
        }
    }

    init {
        activity.lifecycle.addObserver(this)
    }

    fun requestDefault() {
        dismiss()
        if (DefaultLauncherManager.getStatus(activity).isDefault) {
            onStatusChanged()
            toast("已是默认桌面；若仍回到系统桌面，请查看兼容性帮助")
            return
        }
        val intent = DefaultLauncherManager.createRoleRequestIntent(activity)
        if (intent != null) {
            try {
                roleRequest.launch(intent)
                return
            } catch (_: RuntimeException) {
                // Missing or protected role UI on a vendor ROM: try public Settings actions.
            }
        }
        openSettings()
    }

    private fun openSettings() {
        if (!DefaultLauncherManager.openHomeSettings(activity)) {
            toast("未能打开设置，请手动进入系统设置 → 默认应用 → 桌面")
        }
    }

    fun showHelp() {
        show(AlertDialog.Builder(activity)
            .setTitle("默认桌面与返回问题")
            .setItems(arrayOf(
                "申请将 ZenLauncher 设为默认桌面",
                "打开系统默认桌面设置",
                "查看当前桌面的默认操作",
                "查看导航方式设置",
                "查看兼容性说明与诊断",
                "高级：ADB 设置命令"
            )) { _, which ->
                when (which) {
                    0 -> requestDefault()
                    1 -> openSettings()
                    2 -> {
                        toast("如有「清除默认操作」，可清除后按 Home 并选择「始终」使用 ZenLauncher")
                        if (!DefaultLauncherManager.openSystemLauncherDetails(activity)) openSettings()
                    }
                    3 -> {
                        toast("可尝试切换三键导航后再次设置默认桌面；具体支持取决于系统")
                        if (!DefaultLauncherManager.openSystemNavigationSettings(activity)) openSettings()
                    }
                    4 -> showDiagnostics()
                    5 -> showAdbHelp()
                }
            }
            .setNegativeButton("关闭", null)
            .create())
    }

    fun showDiagnostics() {
        val report = DefaultLauncherManager.getDiagnosticText(activity)
        show(AlertDialog.Builder(activity)
            .setTitle("桌面兼容性诊断")
            .setMessage(
                "Home 键和上滑回桌面由系统决定去向，返回键只关闭当前页面。" +
                    "请先设为默认桌面，再分别测试 Home、返回、锁屏解锁及重启。\n\n" +
                    "部分厂商系统限制第三方桌面的全面屏手势或会重置默认值。" +
                    "可尝试三键导航并重新选择默认桌面；应用无法保证绕过这些限制。\n\n" +
                    report
            )
            .setPositiveButton("复制诊断") { _, _ ->
                copy("ZenLauncher 桌面诊断", report)
            }
            .setNegativeButton("关闭", null)
            .create())
    }

    private fun showAdbHelp() {
        val command = AppManager.getAdbCommand()
        show(AlertDialog.Builder(activity)
            .setTitle("ADB 设置默认桌面")
            .setMessage("需要已授权的 ADB 连接。命令可能被系统策略拒绝，也不能保证厂商手势支持。执行后请返回应用核验状态，并实际按 Home 测试。\n\n$command")
            .setPositiveButton("复制命令") { _, _ -> copy("ZenLauncher ADB", command) }
            .setNegativeButton("关闭", null)
            .create())
    }

    private fun show(next: Dialog) {
        dismiss()
        if (activity.isFinishing || activity.isDestroyed) return
        dialog = next
        next.setOnDismissListener { if (dialog === next) dialog = null }
        next.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun copy(label: String, text: String) {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("已复制")
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    override fun onDestroy(owner: LifecycleOwner) = dismiss()
}
