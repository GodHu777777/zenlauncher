package com.zenlauncher.app

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Point
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowInsets
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Android 14 AOSP/Google emulator gestures, explicitly authorized with `-e gestures true`.
 * Exclude this class from API24 runs instead of treating unsupported gesture tests as skipped.
 * No KEYCODE_HOME/BACK substitutes are used for the tested actions. The outer HOME fixture uses
 * keys only for setup/cleanup; each asserted navigation transition is caused by a touch swipe.
 */
@RunWith(AndroidJUnit4::class)
class GestureNavigationDeviceTest {
    private val home = IsolatedEmulatorHomeRule()
    private val navigation = GesturalNavigationRule(home)

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(home).around(navigation)

    private val device: UiDevice get() = home.device
    private val instrumentation: Instrumentation get() = home.instrumentation

    @Test
    fun bottomHomeSwipeReturnsFromSettingsAndClosesDrawer() {
        val original = assertHome()
        instrumentation.targetContext.startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        assertTrue(device.wait(Until.hasObject(By.pkg(GESTURE_SYSTEM_SETTINGS).depth(0)), GESTURE_TIMEOUT_MS))
        assertTrue("System Settings must be focused before the HOME swipe", awaitCondition {
            device.currentPackageName == GESTURE_SYSTEM_SETTINGS && focusedWindowPackage() == GESTURE_SYSTEM_SETTINGS
        })

        swipeHome()

        assertSame("The bottom HOME gesture must return to the existing desktop", original, assertHome())
        openLauncherSettings()
        swipeHome()
        assertSame("HOME must remove the launcher's Settings page", original, assertHome())
        assertGone("btnSetDefaultLauncher")

        // Third-party HOME and system Overview are separate activities on Android 14.
        // Deliberately enter Overview, verify its exact component, then navigate HOME once.
        val recents = systemRecentsComponent()
        waitFor("btnAllApps").click()
        waitFor("etFilterApps")
        assertEquals(GESTURE_HOME, home.resolveHome())
        swipeOverviewAndHold()
        assertRecents(recents)
        swipeHome()
        assertGone("etFilterApps")
        assertSame("HOME from Overview must close the drawer and restore the original desktop", original, assertHome())
    }

    @Test
    fun leftEdgeBackReturnsFromSettingsAndKeepsHomeAlive() = verifyEdgeBack(fromLeft = true)

    @Test
    fun rightEdgeBackReturnsFromSettingsAndKeepsHomeAlive() = verifyEdgeBack(fromLeft = false)

    private fun verifyEdgeBack(fromLeft: Boolean) {
        val original = assertHome()
        // Positive control: exactly the same edge trajectory must actually finish a page.
        openLauncherSettings()
        swipeBack(fromLeft)
        assertGone("btnSetDefaultLauncher")
        assertSame("The edge swipe did not return from Settings to the original HOME", original, assertHome())

        // A second control on MainActivity itself proves its Back callback receives this gesture.
        // Setting text through accessibility does not tap/focus the field or request the keyboard.
        waitFor("etSearch").text = "gesture-back-control"
        waitFor("btnClearSearch")
        waitFor("rvSearchResults")
        instrumentation.runOnMainSync {
            val insets = requireNotNull(original.window.decorView.rootWindowInsets)
            assertFalse("The Back control must reach MainActivity rather than an open IME", insets.isVisible(WindowInsets.Type.ime()))
        }
        swipeBack(fromLeft)
        assertGone("btnClearSearch")
        assertGone("rvSearchResults")
        assertSame("The edge swipe must clear search in the same HOME", original, assertHome())

        repeat(3) { observeRootBack(original, fromLeft) }
    }

    private fun observeRootBack(original: MainActivity, fromLeft: Boolean) {
        val unexpectedLifecycle = AtomicReference<String?>(null)
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity === original && stage in setOf(Stage.PAUSED, Stage.STOPPED, Stage.DESTROYED)) {
                unexpectedLifecycle.compareAndSet(null, stage.name)
            }
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        try {
            swipeBack(fromLeft)
            assertSame("Root edge Back replaced MainActivity", original, assertHome())
            // Observe actual state throughout an asynchronous dispatch interval. This is not a
            // blind delay: any pause/stop/destroy is latched even if the old HOME is later resumed.
            val deadline = SystemClock.uptimeMillis() + 1_000L
            do {
                assertEquals("Root Back temporarily left HOME", null, unexpectedLifecycle.get())
                assertEquals("Root Back changed the foreground package", GESTURE_APP, device.currentPackageName)
                instrumentation.runOnMainSync {
                    assertFalse("Root Back finished HOME", original.isFinishing)
                    assertFalse("Root Back destroyed HOME", original.isDestroyed)
                    assertTrue("Root Back lost HOME window focus", original.hasWindowFocus())
                    assertTrue("Root Back stopped the original HOME", original in monitor.getActivitiesInStage(Stage.RESUMED))
                }
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining > 0) SystemClock.sleep(minOf(50L, remaining))
            } while (SystemClock.uptimeMillis() < deadline)
            assertEquals("Root Back dispatched a late lifecycle transition", null, unexpectedLifecycle.get())
            assertEquals(GESTURE_HOME, home.resolveHome())
        } finally {
            instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) }
        }
    }

    private fun openLauncherSettings() {
        waitFor("btnSettings").click()
        waitFor("btnSetDefaultLauncher")
        assertGone("etSearch")
        assertTrue("Launcher Settings must own focus before the control gesture", awaitCondition {
            var ready = false
            instrumentation.runOnMainSync {
                ready = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<SettingsActivity>().singleOrNull()?.hasWindowFocus() == true
            }
            ready
        })
    }

    private fun swipeHome() {
        navigation.assertGesturalMode()
        val width = device.displayWidth
        val height = device.displayHeight
        // A continuous bottom-edge swipe with no hold. Each caller verifies the HOME outcome;
        // step count alone does not prove how Quickstep classified the release velocity.
        assertTrue("Bottom HOME touch injection failed", device.swipe(width / 2, height - 2, width / 2, height / 3, 24))
    }

    private fun swipeOverviewAndHold() {
        navigation.assertGesturalMode()
        val x = device.displayWidth / 2
        val endY = device.displayHeight / 3
        // UiAutomator 2.3 emits MOVE events even between identical points, at least 5 ms apart.
        // One moving segment followed by six stationary segments keeps the same pointer down
        // for at least 690 ms at the endpoint, making this an explicit swipe-and-hold gesture.
        val points = Array(8) { index -> Point(x, if (index == 0) device.displayHeight - 2 else endY) }
        assertTrue("Overview swipe-and-hold injection failed", device.swipe(points, 24))
    }

    private fun systemRecentsComponent(): ComponentName {
        val context = instrumentation.targetContext
        val resource = context.resources.getIdentifier("config_recentsComponentName", "string", "android")
        check(resource != 0) { "The API34 emulator does not expose its configured Recents component" }
        val configured = context.getString(resource)
        val recentsProvider = requireNotNull(ComponentName.unflattenFromString(configured)) {
            "Invalid system Recents component: $configured"
        }
        // OverviewComponentObserver constructs this fallback class in the Quickstep provider's
        // package. The framework resource identifies that provider, not necessarily this class.
        val component = ComponentName(recentsProvider.packageName, "com.android.quickstep.RecentsActivity")
        val activity = context.packageManager.getActivityInfo(component, 0)
        assertTrue("The configured Recents activity must be enabled", activity.enabled && activity.applicationInfo.enabled)
        assertTrue("Recents must belong to a system application", activity.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        assertFalse("System Recents must be distinct from Zen HOME", component == GESTURE_HOME)
        return component
    }

    private fun assertRecents(expected: ComponentName) {
        device.waitForIdle(GESTURE_TIMEOUT_MS)
        assertTrue("Swipe-and-hold did not focus the configured system Recents activity; ${navigation.diagnostic()}", awaitCondition {
            assertEquals("Entering Overview changed default HOME", GESTURE_HOME, home.resolveHome())
            val windows = device.executeShellCommand("dumpsys window displays").lineSequence().toList()
            fun componentOnLine(marker: String): ComponentName? = windows.firstOrNull { marker in it }
                ?.let { FOCUSED_COMPONENT.find(it)?.value }
                ?.let(ComponentName::unflattenFromString)
            device.currentPackageName == expected.packageName &&
                focusedWindowPackage() == expected.packageName &&
                componentOnLine("mCurrentFocus=") == expected &&
                componentOnLine("mFocusedApp=") == expected
        })
        assertEquals("System Overview changed HOME routing", GESTURE_HOME, home.resolveHome())
    }

    private fun swipeBack(fromLeft: Boolean) {
        navigation.assertGesturalMode()
        val width = device.displayWidth
        val y = device.displayHeight / 2
        val startX = if (fromLeft) 1 else width - 2
        val endX = if (fromLeft) width * 2 / 3 else width / 3
        // The same coordinates and timing are used for the Settings/search controls and root.
        assertTrue("Edge Back touch injection failed (left=$fromLeft)", device.swipe(startX, y, endX, y, 32))
    }

    private fun assertHome(): MainActivity {
        device.waitForIdle(GESTURE_TIMEOUT_MS)
        waitFor("btnSettings")
        waitFor("clockTime")
        waitFor("btnAllApps")
        assertGone("btnSetDefaultLauncher")
        assertEquals(GESTURE_APP, device.currentPackageName)
        var current: MainActivity? = null
        assertTrue("HOME did not become resumed and focused; ${navigation.diagnostic()}", awaitCondition {
            var ready = false
            instrumentation.runOnMainSync {
                current = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull()
                ready = current?.let { !it.isFinishing && !it.isDestroyed && it.hasWindowFocus() } == true
            }
            ready && focusedWindowPackage() == GESTURE_APP
        })
        assertEquals("System HOME routing changed during gesture navigation", GESTURE_HOME, home.resolveHome())
        return requireNotNull(current)
    }

    private fun focusedWindowPackage(): String? = instrumentation.uiAutomation.windows
        .firstOrNull { it.isFocused }?.root?.packageName?.toString()

    private fun waitFor(id: String): UiObject2 = requireNotNull(device.wait(Until.findObject(By.res(GESTURE_APP, id)), GESTURE_TIMEOUT_MS)) {
        "Missing $GESTURE_APP:id/$id after gesture; ${navigation.diagnostic()}"
    }

    private fun assertGone(id: String) {
        assertTrue("Unexpected $id after gesture; ${navigation.diagnostic()}", device.wait(Until.gone(By.res(GESTURE_APP, id)), GESTURE_TIMEOUT_MS))
    }
}

/** Only used inside IsolatedEmulatorHomeRule, after its explicit emulator and hardware guards. */
private class GesturalNavigationRule(private val home: IsolatedEmulatorHomeRule) : TestRule {
    private val device: UiDevice get() = home.device
    private var userId = -1

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            assertEquals("Exclude this class from other API levels; this gesture suite requires API34", 34, Build.VERSION.SDK_INT)
            assertEquals("Gesture overlay changes require explicit -e gestures true", "true", InstrumentationRegistry.getArguments().getString("gestures"))
            userId = requireNotNull(device.executeShellCommand("am get-current-user").trim().toIntOrNull())
            val savedOverlays = frameworkOverlays()
            check(GESTURAL_OVERLAY in savedOverlays) { "Gesture overlay is not installed: $savedOverlays" }
            val savedMode = resourceMode()
            val savedSecureMode = secureMode()
            check(savedSecureMode == "null" || savedSecureMode == savedMode.toString()) {
                "Unsettled navigation baseline: resource=$savedMode secure=$savedSecureMode"
            }
            Log.i(GESTURE_TAG, "${description.methodName}: original navigation resource=$savedMode secure=$savedSecureMode overlays=$savedOverlays")
            var failure: Throwable? = null
            try {
                // --category changes only the navigation category, leaving other overlay types alone.
                device.executeShellCommand("cmd overlay enable-exclusive --user $userId --category $GESTURAL_OVERLAY")
                val ready = awaitCondition {
                    frameworkOverlays()[GESTURAL_OVERLAY] == "[x]" && resourceMode() == 2 && secureMode() == "2"
                }
                assertTrue("Gestural navigation did not activate; ${diagnostic()}", ready)
                device.waitForIdle(GESTURE_TIMEOUT_MS)
                base.evaluate()
            } catch (error: Throwable) {
                failure = error
                saveFailureArtifacts(description)
                throw error
            } finally {
                try {
                    restoreNavigation(savedOverlays, savedMode, savedSecureMode)
                    Log.i(GESTURE_TAG, "${description.methodName}: navigation restored; ${diagnostic()}")
                } catch (restoreError: Throwable) {
                    saveFailureArtifacts(description)
                    if (failure != null) failure.addSuppressed(restoreError) else throw restoreError
                }
            }
        }
    }

    fun assertGesturalMode() {
        assertEquals("Navigation resource must still report gestural mode", 2, resourceMode())
        assertEquals("SystemUI must have published gestural mode", "2", secureMode())
    }

    private fun restoreNavigation(savedOverlays: Map<String, String>, savedMode: Int, savedSecureMode: String) {
        val failures = mutableListOf<Throwable>()
        fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        var current: Map<String, String>? = null
        attempt { current = frameworkOverlays() }
        // Snapshot every framework overlay so even an OEM-renamed member of the navigation
        // category is restored. Only overlay states actually changed by the test are written.
        // A failed read/restore of one overlay must not prevent restoring the remaining state.
        savedOverlays.forEach { (overlay, state) ->
            if (current?.get(overlay) != state) {
                attempt {
                    val command = when (state) {
                        "[x]" -> "enable"
                        "[ ]" -> "disable"
                        else -> error("Cannot restore unavailable overlay $overlay to $state")
                    }
                    device.executeShellCommand("cmd overlay $command --user $userId $overlay")
                }
            }
        }
        attempt {
            val restored = awaitCondition {
                runCatching { frameworkOverlays() == savedOverlays && resourceMode() == savedMode }.getOrDefault(false)
            }
            assertTrue("Navigation overlays/resources were not restored; ${diagnostic()}", restored)
        }
        // navigation_mode is a SystemUI-published setting, not the switch that activates gestures.
        // Let SystemUI acknowledge the restored resources before restoring even an absent setting.
        attempt {
            val acknowledged = awaitCondition { runCatching { secureMode() == savedMode.toString() }.getOrDefault(false) }
            assertTrue("SystemUI did not acknowledge the restored navigation mode; ${diagnostic()}", acknowledged)
        }
        attempt {
            if (secureMode() != savedSecureMode) {
                val command = if (savedSecureMode == "null") "delete secure navigation_mode"
                else "put secure navigation_mode $savedSecureMode"
                device.executeShellCommand("settings --user $userId $command")
            }
            val settingRestored = awaitCondition { runCatching { secureMode() == savedSecureMode }.getOrDefault(false) }
            assertTrue("navigation_mode was not restored to $savedSecureMode; ${diagnostic()}", settingRestored)
        }
        attempt {
            // Catch late SystemUI writes by observing the complete restored state, not sleeping
            // and assuming asynchronous publication has finished.
            val deadline = SystemClock.uptimeMillis() + 500L
            do {
                assertEquals("An overlay changed after restoration", savedOverlays, frameworkOverlays())
                assertEquals("Navigation resources changed after restoration", savedMode, resourceMode())
                assertEquals("A late SystemUI write changed the restored setting", savedSecureMode, secureMode())
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining > 0) SystemClock.sleep(minOf(50L, remaining))
            } while (SystemClock.uptimeMillis() < deadline)
        }
        if (failures.isNotEmpty()) {
            val failure = failures.first()
            failures.drop(1).forEach { failure.addSuppressed(it) }
            throw failure
        }
    }

    private fun frameworkOverlays(): Map<String, String> {
        val output = device.executeShellCommand("cmd overlay list --user $userId android")
        val overlays = output.lineSequence().mapNotNull { line ->
            OVERLAY_LINE.matchEntire(line.trim())?.let { match -> match.groupValues[2] to match.groupValues[1] }
        }.toMap()
        check(overlays.isNotEmpty()) { "Could not inspect framework overlay states: $output" }
        return overlays
    }

    private fun resourceMode(): Int {
        val output = device.executeShellCommand(
            "cmd overlay lookup --user $userId android android:integer/config_navBarInteractionMode"
        ).trim()
        // AOSP lookup prints either the integer or "resource reference -> resolved integer".
        val resolved = output.substringAfterLast(" -> ")
        val value = resolved.toIntOrNull() ?: if (resolved.startsWith("0x")) resolved.removePrefix("0x").toIntOrNull(16) else null
        check(value != null && value in 0..2) { "Unexpected config_navBarInteractionMode: $output" }
        return requireNotNull(value)
    }

    private fun secureMode(): String {
        val value = device.executeShellCommand("settings --user $userId get secure navigation_mode").trim()
        check(value in setOf("null", "0", "1", "2")) { "Unexpected navigation_mode setting: $value" }
        return value
    }

    fun diagnostic(): String = runCatching {
        val focus = device.executeShellCommand("dumpsys window displays").lineSequence()
            .filter { "mCurrentFocus=" in it || "mFocusedApp=" in it }.take(4).joinToString(" ") { it.trim() }
        "resource=${resourceMode()} secure=${secureMode()} current=${device.currentPackageName} HOME=${home.resolveHome()} focus=$focus"
    }.getOrElse { "Navigation diagnostic failed: ${it.message}" }

    private fun saveFailureArtifacts(description: Description) {
        runCatching {
            val directory = requireNotNull(home.instrumentation.targetContext.getExternalFilesDir("screenshots"))
            check(directory.exists() || directory.mkdirs())
            val prefix = "${description.methodName}-api34-gestures"
            device.takeScreenshot(File(directory, "$prefix.png"))
            device.dumpWindowHierarchy(File(directory, "$prefix.xml"))
            File(directory, "$prefix.txt").writeText(diagnostic() + "\n" + frameworkOverlays())
            Log.e(GESTURE_TAG, "Gesture failure: ${diagnostic()}; files=$directory/$prefix")
        }.onFailure { Log.e(GESTURE_TAG, "Gesture failure evidence capture failed", it) }
    }
}

private const val GESTURE_APP = "com.zenlauncher.app"
private const val GESTURE_SYSTEM_SETTINGS = "com.android.settings"
private const val GESTURAL_OVERLAY = "com.android.internal.systemui.navbar.gestural"
private const val GESTURE_TIMEOUT_MS = 15_000L
private const val GESTURE_TAG = "ZenGestureNavigationTest"
private val GESTURE_HOME = ComponentName(GESTURE_APP, "$GESTURE_APP.MainActivity")
private val FOCUSED_COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")
private val OVERLAY_LINE = Regex("^(\\[[ x]\\]|---)\\s+([A-Za-z0-9_.]+(?::[A-Za-z0-9_.]+)?)$")
