package com.zenlauncher.app.manager

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import com.zenlauncher.app.model.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowToast

/** Exercise profile failures with a real, launchable personal copy of the same package present. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], shadows = [AppManagerProfileTest.RecordingLauncherApps::class])
class AppManagerProfileTest {
    private lateinit var context: RecordingContext
    private lateinit var launcher: RecordingLauncherApps
    private val component = ComponentName("com.example.profileapp", "com.example.profileapp.MainActivity")
    private val otherUser: UserHandle get() = UserHandle.getUserHandleForUid(10 * 100_000)

    @Before
    fun installPersonalCopyAndRejectProfileRequests() {
        val application = RuntimeEnvironment.getApplication()
        context = RecordingContext(application)
        launcher = Shadow.extract(application.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps)
        val appInfo = ApplicationInfo().apply {
            packageName = component.packageName
            enabled = true
            flags = ApplicationInfo.FLAG_INSTALLED
        }
        shadowOf(application.packageManager).apply {
            addOrUpdateActivity(ActivityInfo().apply {
                packageName = component.packageName
                name = component.className
                applicationInfo = appInfo
                enabled = true
                exported = true
            })
            addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            })
        }
        assertNotNull("The incorrect fallback must actually be launchable in this regression test",
            application.packageManager.getLaunchIntentForPackage(component.packageName))
    }

    @Test
    fun failedOtherUserHandleDoesNotLaunchPersonalCopy() {
        // The handle alone must preserve the target even when other profile markers are absent.
        val selected = app().copy(userHandle = otherUser)

        assertFalse(AppManager.launchApp(context, selected))

        assertEquals(listOf(component to otherUser), launcher.launches)
        assertTrue("Failure must not start the installed personal copy", context.started.isEmpty())
    }

    @Test
    fun failedProfileDetailsDoesNotOpenPersonalSettingsAndReportsFailure() {
        assertFalse(AppManager.openAppInfo(context, profileApp()))

        assertEquals(listOf(component to otherUser), launcher.details)
        assertTrue("Failure must not show uninstall/settings for the personal copy", context.started.isEmpty())
        assertTrue(ShadowToast.getTextOfLatestToast().contains("所属用户空间不可用"))
    }

    @Test
    fun missingLauncherServiceCannotRedirectProfileActions() {
        context.launcherServiceAvailable = false

        assertFalse(AppManager.launchApp(context, profileApp()))
        assertFalse(AppManager.openAppInfo(context, profileApp()))

        assertTrue(launcher.launches.isEmpty())
        assertTrue(launcher.details.isEmpty())
        assertTrue(context.started.isEmpty())
    }

    @Test
    fun missingHandleRetainsCloneOrSerialTarget() {
        val selections = listOf(
            app().copy(userHandle = null, isClone = true),
            app().copy(userHandle = null, userId = 42L)
        )
        selections.forEach { selected ->
            assertFalse(AppManager.launchApp(context, selected))
            assertFalse(AppManager.openAppInfo(context, selected))
        }

        assertTrue(launcher.launches.isEmpty())
        assertTrue(launcher.details.isEmpty())
        assertTrue(context.started.isEmpty())
    }

    @Test
    fun inconsistentCloneOrSerialMarkersStillPreventFallback() {
        val selections = listOf(app().copy(isClone = true), app().copy(userId = 42L))
        selections.forEach { selected ->
            assertFalse(AppManager.launchApp(context, selected))
            assertFalse(AppManager.openAppInfo(context, selected))
        }

        assertEquals(2, launcher.launches.size)
        assertEquals(2, launcher.details.size)
        assertTrue(context.started.isEmpty())
    }

    @Test
    fun successfulProfileRequestsKeepTheRequestedComponentAndUser() {
        launcher.rejectRequests = false

        assertTrue(AppManager.launchApp(context, profileApp()))
        assertTrue(AppManager.openAppInfo(context, profileApp()))

        assertEquals(listOf(component to otherUser), launcher.launches)
        assertEquals(listOf(component to otherUser), launcher.details)
        assertTrue(context.started.isEmpty())
    }

    @Test
    fun currentUserRequestsKeepNormalFallbackAfterLauncherServiceFailure() {
        assertTrue(AppManager.launchApp(context, app()))
        assertTrue(AppManager.openAppInfo(context, app()))

        assertEquals(listOf(component to Process.myUserHandle()), launcher.launches)
        assertEquals(listOf(component to Process.myUserHandle()), launcher.details)
        assertEquals(component, context.started[0].component)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, context.started[1].action)
        assertEquals("package:${component.packageName}", context.started[1].dataString)
        assertTrue(context.started.all { it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 })
    }

    @Test
    fun ordinaryCurrentUserEntryWithoutHandleKeepsFallbackWithoutLauncherService() {
        context.launcherServiceAvailable = false
        val selected = app().copy(userHandle = null)

        assertTrue(AppManager.launchApp(context, selected))
        assertTrue(AppManager.openAppInfo(context, selected))

        assertEquals(component, context.started[0].component)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, context.started[1].action)
    }

    private fun app() = AppInfo(
        appName = "Profile app",
        originalName = "Profile app",
        packageName = component.packageName,
        activityName = component.className,
        userHandle = Process.myUserHandle()
    )

    private fun profileApp() = app().copy(userHandle = otherUser, isClone = true, userId = 42L)

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        var launcherServiceAvailable = true

        override fun getSystemService(name: String): Any? =
            if (name == Context.LAUNCHER_APPS_SERVICE && !launcherServiceAvailable) null
            else super.getSystemService(name)

        override fun startActivity(intent: Intent) {
            started += Intent(intent)
        }
    }

    @Implements(LauncherApps::class)
    class RecordingLauncherApps {
        val launches = mutableListOf<Pair<ComponentName, UserHandle>>()
        val details = mutableListOf<Pair<ComponentName, UserHandle>>()
        var rejectRequests = true

        @Implementation
        @Suppress("UNUSED_PARAMETER")
        fun startMainActivity(component: ComponentName, user: UserHandle, bounds: Rect?, options: Bundle?) {
            launches += component to user
            if (rejectRequests) throw ActivityNotFoundException("Selected profile activity is unavailable")
        }

        @Implementation
        @Suppress("UNUSED_PARAMETER")
        fun startAppDetailsActivity(component: ComponentName, user: UserHandle, bounds: Rect?, options: Bundle?) {
            details += component to user
            if (rejectRequests) throw SecurityException("Selected profile is inaccessible")
        }
    }
}
