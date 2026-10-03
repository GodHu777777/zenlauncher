package com.zenlauncher.app

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.util.Locale

/**
 * Real Android system navigation, run only on an isolated emulator with `-e emulator true`.
 *
 * The fixture changes the current user's default HOME and restores it in a finally block.
 * The host records its stock launcher before installing this APK and supplies `-e originalHome
 * package/activity`. Android may clear the old preference when a new HOME handler is installed,
 * so the supplied component is validated and reselected before each test. Without this argument,
 * the current HOME must already resolve to a valid stock launcher; a chooser is rejected.
 *
 * These tests cover platform HOME/Back dispatch and activity destruction, not process death,
 * OEM gesture implementations, or reboot. Force-stopping the target also kills its instrumentation
 * process, so a separate host-side test is required for process-death and reboot coverage.
 */
@RunWith(AndroidJUnit4::class)
class HomeNavigationDeviceTest {
    @get:Rule
    val home = IsolatedEmulatorHomeRule()

    private val device: UiDevice get() = home.device
    private val instrumentation: Instrumentation get() = home.instrumentation

    @Test
    fun homeKeyRoutesFromSystemSettingsToZenLauncher() {
        openSystemSettings()

        pressHomeAndAssertRoot()

        assertEquals(HOME_COMPONENT, home.resolveHome())
        assertFalse("A confirmed default should not show setup guidance", device.hasObject(res("btnSetDefaultQuick")))
    }

    @Test
    fun repeatedBackAtRootDoesNotFinishOrLeaveZenLauncher() {
        val original = resumedHome()

        repeat(6) {
            injectNavigationKey(device, KeyEvent.KEYCODE_BACK)
            assertRoot()
            assertFalse("Root Back finished the HOME activity", original.isFinishing)
            assertFalse("Root Back destroyed the HOME activity", original.isDestroyed)
            assertSame("Each Back must keep the original HOME instance resumed", original, resumedHome())
        }

        assertEquals("Back must not merely relaunch a destroyed HOME", original, resumedHome())
    }

    @Test
    fun homeFromLauncherSettingsReturnsToRoot() {
        openLauncherSettings()

        pressHomeAndAssertRoot()

        assertGone("btnSetDefaultLauncher")
        injectNavigationKey(device, KeyEvent.KEYCODE_BACK)
        assertRoot()
    }

    @Test
    fun backFromLauncherSettingsReturnsToRoot() {
        openLauncherSettings()

        injectNavigationKey(device, KeyEvent.KEYCODE_BACK)

        assertRoot()
        assertGone("btnSetDefaultLauncher")
        injectNavigationKey(device, KeyEvent.KEYCODE_BACK)
        assertRoot()
    }

    @Test
    fun backFromSystemSettingsReturnsToZenLauncher() {
        openSystemSettings()

        injectNavigationKey(device, KeyEvent.KEYCODE_BACK)

        assertRoot()
        assertEquals(HOME_COMPONENT, home.resolveHome())
    }

    @Test
    fun homeClearsSearchAndFocus() {
        val query = "zen-navigation-search"
        waitFor("etSearch").apply {
            click()
            text = query
        }
        assertTrue(device.wait(Until.hasObject(res("etSearch").text(query)), TIMEOUT_MS))
        waitFor("btnClearSearch")
        waitFor("rvSearchResults")

        pressHomeAndAssertRoot()

        assertGone("btnClearSearch")
        assertGone("rvSearchResults")
        assertFalse("HOME must clear the search field's focus", waitFor("etSearch").isFocused)
        // An empty EditText may expose its hint as accessibility text on older Android releases.
        assertFalse(device.hasObject(res("etSearch").text(query)))
    }

    @Test
    fun homeDismissesAllAppsAndReopeningHasNoPreviousFilter() {
        val query = "zen-navigation-filter"
        waitFor("btnAllApps").click()
        waitFor("etFilterApps").apply {
            click()
            text = query
        }
        assertTrue(device.wait(Until.hasObject(res("etFilterApps").text(query)), TIMEOUT_MS))

        pressHomeAndAssertRoot()

        assertGone("etFilterApps")
        waitFor("btnAllApps").click()
        val reopenedFilter = waitFor("etFilterApps")
        assertFalse("Reopening the drawer retained its old query", reopenedFilter.text == query)
        pressHomeAndAssertRoot()
        assertGone("etFilterApps")
    }

    @Test
    @Suppress("DEPRECATION")
    fun drawerListsAndLaunchesSettingsWithScopedPackageVisibility() {
        val packageManager = instrumentation.targetContext.packageManager
        val settingsLabel = packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(SYSTEM_SETTINGS, 0)
        ).toString().trim()
        assertTrue("The installed Settings application must have a real label", settingsLabel.isNotBlank())
        assertRoot()
        waitFor("btnAllApps").click()
        waitFor("etFilterApps").text = SYSTEM_SETTINGS
        assertTrue(device.wait(Until.hasObject(res("etFilterApps").text(SYSTEM_SETTINGS)), TIMEOUT_MS))

        // Validate an actual adapter row, not just the RecyclerView container. Use the installed
        // application's label so both Chinese and English system images exercise the same path.
        val settingsRow = requireNotNull(device.wait(
            Until.findObject(res("tvAppName").text(settingsLabel).hasAncestor(res("rvAllApps"))),
            TIMEOUT_MS
        )) { "Settings ($settingsLabel) was not listed under MAIN/LAUNCHER package visibility" }
        assertFalse("Settings row must have visible nonempty text", settingsRow.text.isNullOrBlank())
        assertFalse("Settings row must be on screen", settingsRow.visibleBounds.isEmpty)

        settingsRow.click()

        assertTrue("Selecting the Settings row did not launch its package", device.wait(
            Until.hasObject(By.pkg(SYSTEM_SETTINGS).depth(0)), TIMEOUT_MS
        ))
        assertEquals(SYSTEM_SETTINGS, device.currentPackageName)
        pressHomeAndAssertRoot()
        assertGone("etFilterApps")
    }

    @Test
    fun homeRecreatesDestroyedActivityAfterLeavingForSystemSettings() {
        val original = resumedHome()
        openSystemSettings()
        // Destroy the background Activity without killing the instrumentation process. This is
        // deliberately narrower than process-death testing and still requires real HOME routing.
        instrumentation.runOnMainSync { original.finish() }
        assertTrue("Background HOME activity was not destroyed", waitUntil { original.isDestroyed })
        assertEquals("Destroying background HOME should leave Settings visible", SYSTEM_SETTINGS, device.currentPackageName)

        pressHomeAndAssertRoot()

        assertNotSame("HOME should create a new Activity instance", original, resumedHome())
        injectNavigationKey(device, KeyEvent.KEYCODE_BACK)
        assertRoot()
    }

    private fun openLauncherSettings() {
        waitFor("btnSettings").click()
        waitFor("btnSetDefaultLauncher")
        assertEquals(APP_PACKAGE, device.currentPackageName)
        assertGone("etSearch")
    }

    private fun openSystemSettings() {
        // A fresh external task makes the Back assertion independent of Settings' previous screen.
        instrumentation.targetContext.startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        assertTrue("System Settings did not become visible", device.wait(Until.hasObject(By.pkg(SYSTEM_SETTINGS).depth(0)), TIMEOUT_MS))
        assertEquals(SYSTEM_SETTINGS, device.currentPackageName)
    }

    private fun pressHomeAndAssertRoot() {
        injectNavigationKey(device, KeyEvent.KEYCODE_HOME)
        assertRoot()
    }

    private fun assertRoot() {
        device.waitForIdle(TIMEOUT_MS)
        waitFor("btnSettings")
        waitFor("btnAllApps")
        waitFor("clockTime")
        assertEquals("System navigation left ZenLauncher", APP_PACKAGE, device.currentPackageName)
        assertGone("btnSetDefaultLauncher")
        val current = resumedHome()
        instrumentation.runOnMainSync {
            assertFalse("The visible HOME activity is finishing", current.isFinishing)
            assertFalse("The visible HOME activity was destroyed", current.isDestroyed)
            assertTrue("HOME must own window focus after navigation settles", current.hasWindowFocus())
        }
    }

    private fun waitFor(id: String): UiObject2 = requireNotNull(device.wait(Until.findObject(res(id)), TIMEOUT_MS)) {
        "Expected $APP_PACKAGE:id/$id; foreground=${device.currentPackageName}"
    }

    private fun assertGone(id: String) {
        assertTrue("Unexpected visible $APP_PACKAGE:id/$id", device.wait(Until.gone(res(id)), TIMEOUT_MS))
    }

    private fun resumedHome(): MainActivity {
        var result: MainActivity? = null
        assertTrue("MainActivity never resumed", waitUntil {
            instrumentation.runOnMainSync {
                result = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
            }
            result != null
        })
        return requireNotNull(result)
    }

    private fun waitUntil(condition: () -> Boolean): Boolean = awaitCondition(condition)

    private fun res(id: String) = By.res(APP_PACKAGE, id)
}

/** No activity/default/permission state is mutated until both emulator checks pass. */
class IsolatedEmulatorHomeRule(private val selectZen: Boolean = true) : TestRule {
    val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private var userId: Int = -1

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            requireIsolatedEmulator()
            val userOutput = device.executeShellCommand("am get-current-user").trim()
            userId = requireNotNull(userOutput.toIntOrNull()) { "Cannot identify foreground Android user: $userOutput" }
            val originalHome = originalHomeFromArgumentsOrResolver()
            validateRestorableHome(originalHome)
            Log.i(TAG, "${description.methodName}: SDK=${Build.VERSION.SDK_INT}; fingerprint=${Build.FINGERPRINT}; user=$userId; originalHome=$originalHome")
            var testFailure: Throwable? = null
            try {
                device.wakeUp()
                // Installing this APK can clear the emulator's preferred HOME on Android 7.
                // Restore the verified pre-install baseline before changing it for the test.
                setHomeAndVerify(originalHome)
                if (selectZen) setHomeAndVerify(HOME_COMPONENT)
                injectNavigationKey(device, KeyEvent.KEYCODE_HOME)
                if (selectZen) {
                    assertTrue("ZenLauncher HOME did not appear", device.wait(Until.hasObject(By.res(APP_PACKAGE, "btnSettings")), TIMEOUT_MS))
                } else {
                    assertTrue("Stock HOME did not appear before selection testing", device.wait(
                        Until.hasObject(By.pkg(originalHome.packageName).depth(0)), TIMEOUT_MS
                    ))
                    assertEquals(originalHome.packageName, device.currentPackageName)
                }
                base.evaluate()
            } catch (failure: Throwable) {
                testFailure = failure
                saveFailureArtifacts(description)
                throw failure
            } finally {
                try {
                    setHomeAndVerify(originalHome)
                    injectNavigationKey(device, KeyEvent.KEYCODE_HOME)
                    assertTrue(
                        "The restored stock HOME did not become visible: $originalHome",
                        device.wait(Until.hasObject(By.pkg(originalHome.packageName).depth(0)), TIMEOUT_MS)
                    )
                    device.waitForIdle(TIMEOUT_MS)
                    assertEquals("HOME returned to the wrong package after restoration", originalHome.packageName, device.currentPackageName)
                    assertEquals("Default HOME changed again after restoration", originalHome, resolveHome())
                    Log.i(TAG, "${description.methodName}: restoredHome=${resolveHome()}")
                } catch (restoreFailure: Throwable) {
                    saveFailureArtifacts(description)
                    if (testFailure != null) testFailure.addSuppressed(restoreFailure) else throw restoreFailure
                }
            }
        }
    }

    private fun requireIsolatedEmulator() {
        assertEquals(
            "Refusing to change HOME without explicit isolated-emulator authorization (-e emulator true)",
            "true", InstrumentationRegistry.getArguments().getString("emulator")
        )
        val qemu = device.executeShellCommand("getprop ro.kernel.qemu").trim()
        val fingerprint = Build.FINGERPRINT.lowercase(Locale.ROOT)
        assertTrue(
            "Refusing to change HOME on an unverified emulator: ro.kernel.qemu=$qemu, fingerprint=$fingerprint",
            qemu == "1" && listOf("generic", "emulator", "sdk_gphone", "sdk_google").any { it in fingerprint }
        )
    }

    private fun originalHomeFromArgumentsOrResolver(): ComponentName {
        val supplied = InstrumentationRegistry.getArguments().getString("originalHome") ?: return resolveHome()
        check(COMPONENT_PATTERN.matches(supplied)) { "Invalid originalHome component argument: $supplied" }
        return requireNotNull(ComponentName.unflattenFromString(supplied)) {
            "Cannot parse originalHome component argument: $supplied"
        }
    }

    @Suppress("DEPRECATION")
    private fun validateRestorableHome(component: ComponentName) {
        assertTrue(
            "Refusing a non-stock or unresolved original HOME: $component",
            component.packageName != APP_PACKAGE && component.packageName != "android" &&
                !component.className.contains("Resolver", ignoreCase = true)
        )
        val activity = instrumentation.targetContext.packageManager.getActivityInfo(component, 0)
        assertTrue("Original HOME activity is disabled: $component", activity.enabled)
        assertTrue("Original HOME application is disabled: $component", activity.applicationInfo.enabled)
        assertTrue("Original HOME is not exported: $component", activity.exported)
        assertTrue(
            "Original HOME application is not installed: $component",
            activity.applicationInfo.flags and ApplicationInfo.FLAG_INSTALLED != 0
        )
        // Query without MATCH_DISABLED_COMPONENTS/UNINSTALLED_PACKAGES to validate the effective
        // enabled state and HOME intent support for the exact foreground Android user as well.
        val output = device.executeShellCommand(
            "cmd package query-activities --components --user $userId -a android.intent.action.MAIN -c android.intent.category.HOME"
        )
        val enabledHomes = output.lineSequence().map { it.trim() }
            .filter { COMPONENT_PATTERN.matches(it) }
            .mapNotNull { ComponentName.unflattenFromString(it) }.toList()
        assertTrue("Original HOME is not an enabled HOME candidate for user $userId: $component; query=$output", component in enabledHomes)
    }

    fun resolveHome(): ComponentName {
        check(userId >= 0) { "Android user must be recorded before HOME resolution" }
        val output = device.executeShellCommand(
            "cmd package resolve-activity --components --user $userId -a android.intent.action.MAIN -c android.intent.category.HOME"
        )
        val components = output.lineSequence().map { it.trim() }
            .filter { COMPONENT_PATTERN.matches(it) }
            .mapNotNull { ComponentName.unflattenFromString(it) }.toList()
        check(components.size == 1) { "Expected one real HOME component, got: $output" }
        return components.single()
    }

    private fun setHomeAndVerify(component: ComponentName) {
        val flattened = component.flattenToShortString()
        check(COMPONENT_PATTERN.matches(flattened)) { "Unsafe HOME component: $flattened" }
        val output = device.executeShellCommand("cmd package set-home-activity --user $userId $flattened")
        // Android 7.0 prints nothing on success. Read back the platform resolver instead of
        // assuming a particular shell success message, which changes between Android versions.
        var lastResolved: ComponentName? = null
        var lastResolutionFailure: Exception? = null
        val applied = awaitCondition {
            try {
                lastResolved = resolveHome()
                lastResolutionFailure = null
                lastResolved == component
            } catch (failure: Exception) {
                // Role assignment may temporarily leave no resolved HOME. Keep observing the
                // actual resolver until the deadline, then include its last output in the error.
                lastResolved = null
                lastResolutionFailure = failure
                false
            }
        }
        assertTrue(
            "HOME assignment failed for $flattened; command output=$output; resolved=$lastResolved; resolver error=${lastResolutionFailure?.message}",
            applied
        )
    }

    private fun saveFailureArtifacts(description: Description) {
        runCatching {
            val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("screenshots"))
            check(directory.exists() || directory.mkdirs()) { "Cannot create $directory" }
            val name = "${description.methodName}-api${Build.VERSION.SDK_INT}"
            device.takeScreenshot(File(directory, "$name.png"))
            device.dumpWindowHierarchy(File(directory, "$name.xml"))
            Log.e(TAG, "Failure artifacts: $directory/$name; currentPackage=${device.currentPackageName}; HOME=${resolveHome()}")
        }.onFailure { Log.e(TAG, "Could not collect failure artifacts", it) }
    }
}

private const val APP_PACKAGE = "com.zenlauncher.app"
private const val SYSTEM_SETTINGS = "com.android.settings"
private const val TIMEOUT_MS = 15_000L
private const val TAG = "ZenHomeNavigationTest"
private val HOME_COMPONENT = ComponentName(APP_PACKAGE, "$APP_PACKAGE.MainActivity")
private val COMPONENT_PATTERN = Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")

internal fun injectNavigationKey(device: UiDevice, keyCode: Int) {
    device.waitForIdle(TIMEOUT_MS)
    // In UiAutomator 2.3 pressHome()/pressBack() return whether TYPE_WINDOW_CONTENT_CHANGED was
    // observed, which can be false when root Back is correctly consumed. pressKeyCode() instead
    // reports successful DOWN and UP injection. Callers must separately verify navigation state.
    assertTrue("System key injection failed: ${KeyEvent.keyCodeToString(keyCode)}", device.pressKeyCode(keyCode))
}

/** Poll real lifecycle/resolver state with a deadline; UI state uses UiDevice.wait(Until...). */
internal fun awaitCondition(condition: () -> Boolean): Boolean {
    val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
    while (true) {
        if (condition()) return true
        val remaining = deadline - SystemClock.uptimeMillis()
        if (remaining <= 0) return false
        SystemClock.sleep(minOf(100L, remaining))
    }
}
