package com.zenlauncher.app

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.TextView
import com.zenlauncher.app.manager.DefaultLauncherManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

/** Tests the Settings button wiring and the real Activity Result callback, not just the manager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsDefaultLauncherTest {
    private var controller: ActivityController<SettingsActivity>? = null

    private val activity: SettingsActivity
        get() = requireNotNull(controller).get()

    @Before
    fun createSettingsWithAnotherHomeSelected() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("zen_launcher_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        selectResolvedHome("com.example.systemhome", "com.example.systemhome.Home")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            shadowOf(app.getSystemService(RoleManager::class.java))
                .addAvailableRole(RoleManager.ROLE_HOME)
        }
        controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        assertFalse(DefaultLauncherManager.getStatus(activity).isDefault)
    }

    @After
    fun destroySettings() {
        ShadowDialog.getShownDialogs().toList().forEach { it.dismiss() }
        controller?.pause()?.stop()?.destroy()
        controller = null
    }

    @Test
    fun primaryButtonRequestsHomeRoleWithoutOpeningHelpAtTheSameTime() {
        val request = clickDefaultButtonAndGetRoleRequest()

        assertEquals("android.app.role.action.REQUEST_ROLE", request.action)
        assertEquals(RoleManager.ROLE_HOME, request.getStringExtra("android.intent.extra.ROLE_NAME"))
        assertNoOpenDialogs()
        assertNull("A single tap must launch only one setup screen", shadowOf(activity).nextStartedActivityForResult)
    }

    @Test
    fun rejectingRoleRequestKeepsStatusNonDefaultAndShowsHelp() {
        val request = clickDefaultButtonAndGetRoleRequest()

        shadowOf(activity).receiveResult(request, Activity.RESULT_CANCELED, Intent())

        assertNotDefaultInUi()
        assertTrue("A rejected request should offer the compatibility help", latestDialogIsShowing())
    }

    @Test
    fun resultOkCannotClaimSuccessWhenSystemStillResolvesAnotherHome() {
        val request = clickDefaultButtonAndGetRoleRequest()

        shadowOf(activity).receiveResult(request, Activity.RESULT_OK, Intent())

        assertNotDefaultInUi()
        assertTrue(latestDialogIsShowing())
    }

    @Test
    fun actualHomeOwnershipIsRecognizedEvenIfVendorReturnsCanceled() {
        val request = clickDefaultButtonAndGetRoleRequest()
        selectResolvedHome(activity.packageName, MainActivity::class.java.name)
        shadowOf(activity.getSystemService(RoleManager::class.java)).addHeldRole(RoleManager.ROLE_HOME)

        shadowOf(activity).receiveResult(request, Activity.RESULT_CANCELED, Intent())

        assertTrue(DefaultLauncherManager.getStatus(activity).isDefault)
        assertEquals("✓ 已设为系统默认桌面", statusText())
        assertNoOpenDialogs()
    }

    @Test
    @Config(sdk = [28])
    fun legacyAndroidPrimaryButtonOpensStandardHomeSettings() {
        assertTrue(activity.findViewById<View>(R.id.btnSetDefaultLauncher).performClick())

        val intent = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(Settings.ACTION_HOME_SETTINGS, intent.action)
        assertNoOpenDialogs()
        assertFalse(DefaultLauncherManager.getStatus(activity).isDefault)
    }

    @Test
    fun helpDialogIsDismissedWhenSettingsActivityIsDestroyed() {
        assertTrue(activity.findViewById<View>(R.id.btnLauncherHelp).performClick())
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue(dialog.isShowing)

        requireNotNull(controller).pause().stop().destroy()
        controller = null

        assertFalse(dialog.isShowing)
    }

    private fun clickDefaultButtonAndGetRoleRequest(): Intent {
        assertTrue(activity.findViewById<View>(R.id.btnSetDefaultLauncher).performClick())
        return requireNotNull(shadowOf(activity).nextStartedActivityForResult).intent
    }

    private fun assertNotDefaultInUi() {
        assertFalse(DefaultLauncherManager.getStatus(activity).isDefault)
        assertFalse(statusText().contains("✓"))
        assertEquals("设为默认桌面", activity.findViewById<TextView>(R.id.btnSetDefaultLauncher).text.toString())
    }

    private fun statusText() = activity.findViewById<TextView>(R.id.tvDefaultLauncherStatus).text.toString()

    private fun latestDialogIsShowing() = ShadowDialog.getLatestDialog()?.isShowing == true

    private fun assertNoOpenDialogs() {
        assertFalse(ShadowDialog.getShownDialogs().any { it.isShowing })
    }

    @Suppress("DEPRECATION")
    private fun selectResolvedHome(packageName: String, activityName: String) {
        val app = RuntimeEnvironment.getApplication()
        val application = ApplicationInfo().apply {
            this.packageName = packageName
            flags = ApplicationInfo.FLAG_INSTALLED or ApplicationInfo.FLAG_SYSTEM
            enabled = true
        }
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = activityName
                applicationInfo = application
                enabled = true
                exported = true
            }
            isDefault = true
            priority = 1000
        }
        shadowOf(app.packageManager).setResolveInfosForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), listOf(resolveInfo)
        )
    }
}
