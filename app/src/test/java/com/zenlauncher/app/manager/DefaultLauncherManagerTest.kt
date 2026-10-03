package com.zenlauncher.app.manager

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises real manager queries against Android services, including API 33 typed query flags. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34])
class DefaultLauncherManagerTest {
    private lateinit var context: Context
    private lateinit var pm: PackageManager
    private lateinit var ownHome: ComponentName
    private lateinit var samsungHome: ComponentName

    @Before
    fun installAlternativeHome() {
        context = RuntimeEnvironment.getApplication()
        pm = context.packageManager
        ownHome = ComponentName(context.packageName, "com.zenlauncher.app.MainActivity")
        samsungHome = installHome("com.sec.android.app.launcher", "Samsung Home")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            shadowOf(context.getSystemService(RoleManager::class.java))
                .addAvailableRole(RoleManager.ROLE_HOME)
        }
    }

    @Test
    fun matchingHomeResolutionAndRoleAreConfirmedAsDefault() {
        preferHome(ownHome)
        grantHomeRole()

        val status = DefaultLauncherManager.getStatus(context)

        assertTrue(status.isDefault)
        assertFalse(status.hasConflictingSignals)
        assertEquals(true, status.roleAvailable)
        assertEquals(true, status.roleHeld)
        assertEquals(ownHome, status.resolvedHome)
        assertEquals(ownHome, status.currentHome?.component)
        assertTrue("Public HOME query should expose the installed alternative",
            status.homeActivities.any { it.component == samsungHome })
        assertTrue(status.inspectionNotes.isEmpty())
    }

    @Test
    fun heldRoleCannotHideRoutingToAnotherLauncher() {
        preferHome(samsungHome)
        grantHomeRole()

        val status = DefaultLauncherManager.getStatus(context)

        assertFalse(status.isDefault)
        assertTrue(status.hasConflictingSignals)
        assertEquals(true, status.roleHeld)
        assertEquals(samsungHome, status.resolvedHome)
        assertEquals(samsungHome, status.currentHome?.component)
        assertTrue(status.summary.contains("不一致"))
    }

    @Test
    fun routingToOwnHomeWithAnExplicitlyMissingRoleNeedsAttention() {
        preferHome(ownHome)

        val status = DefaultLauncherManager.getStatus(context)

        assertEquals(ownHome, status.resolvedHome)
        assertEquals(false, status.roleHeld)
        assertTrue(status.hasConflictingSignals)
        assertFalse(status.isDefault)
    }

    @Test
    fun anotherHomeAndMissingRoleAreConsistentlyReported() {
        preferHome(samsungHome)

        val status = DefaultLauncherManager.getStatus(context)

        assertFalse(status.isDefault)
        assertFalse(status.hasConflictingSignals)
        assertEquals(samsungHome, status.currentHome?.component)
        assertTrue(status.summary.contains("Samsung Home"))
    }

    @Test
    fun systemChooserIsNotMisidentifiedAsASelectedHome() {
        shadowOf(pm).setShouldShowActivityChooser(true)

        val status = DefaultLauncherManager.getStatus(context)

        assertEquals("android", status.resolvedHome?.packageName)
        assertNull(status.currentHome)
        assertFalse(status.isDefault)
        assertFalse(status.homeActivities.any { it.component == status.resolvedHome })
    }

    @Test
    @Config(sdk = [24])
    fun oldAndroidUsesActualHomeResolutionWithoutRoleManager() {
        preferHome(ownHome)

        val status = DefaultLauncherManager.getStatus(context)

        assertEquals(ownHome, status.resolvedHome)
        assertNull(status.roleAvailable)
        assertNull(status.roleHeld)
        assertTrue(status.isDefault)
        assertNull(DefaultLauncherManager.createRoleRequestIntent(context))
    }

    @Test
    fun requestsHomeRoleOnlyWhenAvailableAndNotHeld() {
        val request = DefaultLauncherManager.createRoleRequestIntent(context)
        assertNotNull(request)
        assertNotNull("Role request should target a system action", request?.action)

        grantHomeRole()

        assertNull(DefaultLauncherManager.createRoleRequestIntent(context))
    }

    @Test
    fun unavailableHomeRoleFallsBackWithoutInventingARequest() {
        shadowOf(context.getSystemService(RoleManager::class.java))
            .removeAvailableRole(RoleManager.ROLE_HOME)

        assertNull(DefaultLauncherManager.createRoleRequestIntent(context))
        val status = DefaultLauncherManager.getStatus(context)
        assertEquals(false, status.roleAvailable)
        assertNull(status.roleHeld)
    }

    @Test
    fun launcherDetailsTargetsActualInstalledSamsungHomeInsteadOfXiaomi() {
        preferHome(samsungHome)
        val recording = RecordingContext(context)

        assertTrue(DefaultLauncherManager.openSystemLauncherDetails(recording))

        val opened = recording.attempts.single()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.action)
        assertEquals("package:com.sec.android.app.launcher", opened.dataString)
        assertTrue(opened.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun launcherDetailsPrefersCurrentOtherHomeOverFirstSystemCandidate() {
        val otherHome = installHome("com.example.otherlauncher", "Other Home", isSystem = false)
        preferHome(otherHome)
        val recording = RecordingContext(context)

        assertTrue(DefaultLauncherManager.openSystemLauncherDetails(recording))

        assertEquals("package:com.example.otherlauncher", recording.attempts.single().dataString)
    }

    @Test
    fun unsupportedHomeSettingsFallBackToDefaultAppSettings() {
        val recording = RecordingContext(context, setOf(Settings.ACTION_HOME_SETTINGS))

        assertTrue(DefaultLauncherManager.openHomeSettings(recording))

        assertEquals(
            listOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            recording.attempts.map { it.action }
        )
    }

    @Test
    fun allSettingsFailuresAreReportedAsFailure() {
        val recording = RecordingContext(context, rejectEverything = true)

        assertFalse(DefaultLauncherManager.openHomeSettings(recording))

        assertEquals(Settings.ACTION_HOME_SETTINGS, recording.attempts.first().action)
        assertEquals(Settings.ACTION_SETTINGS, recording.attempts.last().action)
    }

    @Test
    fun diagnosticsIncludeActualHomeAndDeviceApiForBugReports() {
        preferHome(samsungHome)

        val diagnostic = DefaultLauncherManager.getDiagnosticText(context)

        assertTrue(diagnostic.contains("API ${Build.VERSION.SDK_INT}"))
        assertTrue(diagnostic.contains(samsungHome.flattenToShortString()))
        assertTrue(diagnostic.contains("ROLE_HOME 持有：false"))
        assertTrue(diagnostic.contains("需实际测试"))
    }

    private fun grantHomeRole() {
        shadowOf(context.getSystemService(RoleManager::class.java)).addHeldRole(RoleManager.ROLE_HOME)
    }

    @Suppress("DEPRECATION")
    private fun preferHome(component: ComponentName) {
        val candidates = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        ).map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }.toTypedArray()
        assertTrue("The preferred activity must advertise HOME", component in candidates)
        pm.addPreferredActivity(homeFilter(), IntentFilter.MATCH_CATEGORY_EMPTY, candidates, component)
    }

    private fun installHome(packageName: String, label: String, isSystem: Boolean = true): ComponentName {
        val component = ComponentName(packageName, "$packageName.HomeActivity")
        val info = ApplicationInfo().apply {
            this.packageName = packageName
            nonLocalizedLabel = label
            enabled = true
            flags = ApplicationInfo.FLAG_INSTALLED or if (isSystem) ApplicationInfo.FLAG_SYSTEM else 0
        }
        val activity = ActivityInfo().apply {
            this.packageName = packageName
            name = component.className
            applicationInfo = info
            nonLocalizedLabel = label
            enabled = true
            exported = true
        }
        // addOrUpdateActivity installs the package and registers the activity's filter
        // table. installPackage alone does not initialize that table in Robolectric.
        shadowOf(pm).addOrUpdateActivity(activity)
        shadowOf(pm).addIntentFilterForActivity(component, homeFilter())
        return component
    }

    private fun homeFilter() = IntentFilter(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    private class RecordingContext(
        base: Context,
        private val rejectedActions: Set<String> = emptySet(),
        private val rejectEverything: Boolean = false
    ) : ContextWrapper(base) {
        val attempts = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            attempts += Intent(intent)
            if (rejectEverything || intent.action in rejectedActions) {
                throw ActivityNotFoundException("Unsupported settings action in test")
            }
        }
    }
}
