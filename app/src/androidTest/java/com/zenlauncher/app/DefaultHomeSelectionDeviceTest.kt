package com.zenlauncher.app

import android.app.Instrumentation
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the user-facing default HOME request rather than provisioning Zen with a shell command.
 * The fixture uses shell commands only to establish/restore the pre-install stock HOME. Assignment
 * to Zen must happen through the app button and Android's Settings/PermissionController UI.
 * Selectors follow AOSP Android 7 HomeSettings/preference_home_app and Android 14
 * PermissionController RequestRoleFragment/request_role_item. Missing UI is a failure, not a skip.
 */
@RunWith(AndroidJUnit4::class)
class DefaultHomeSelectionDeviceTest {
    @get:Rule
    val home = IsolatedEmulatorHomeRule(selectZen = false)

    private val device: UiDevice get() = home.device
    private val instrumentation: Instrumentation get() = home.instrumentation

    @Test
    fun approvingSystemSelectionMakesZenTheActualHome() {
        val stockHome = home.resolveHome()
        assertDefault(stockHome, expectedRoleHeld = false)
        openFromLauncherEntry()
        val systemUi = requestDefaultThroughApp()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The custom title and app rows both use id/title, so constrain to the choices list.
            waitFor(By.res(systemUi, "title").text(ownCandidateLabel())
                .hasAncestor(By.res(systemUi, "list"))).click()
            waitFor(By.res("android", "button1").pkg(systemUi).enabled(true)).click()
        } else {
            // Android 7's title container consumes clicks. The radio itself is non-clickable,
            // so clicking it dispatches to home_app_pref's actual HomeSettings listener.
            val candidate = legacyHomeCandidate()
            val radio = requireNotNull(candidate.findObject(By.res(SYSTEM_SETTINGS_PACKAGE, "home_radio"))) {
                "Stock HOME settings candidate has no selection control"
            }
            radio.click()
            waitFor(By.res(SYSTEM_SETTINGS_PACKAGE, "home_radio").checked(true)
                .hasAncestor(legacyHomeCandidateSelector()))
            injectNavigationKey(device, KeyEvent.KEYCODE_BACK)
        }

        assertDefault(ZEN_HOME_COMPONENT, expectedRoleHeld = true)
        assertZenRoot(expectDefault = true)
        // Verify both an actual HOME route out of a different app and root Back afterwards.
        instrumentation.targetContext.startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        waitFor(By.pkg(SYSTEM_SETTINGS_PACKAGE).depth(0))
        assertEquals(SYSTEM_SETTINGS_PACKAGE, device.currentPackageName)
        injectNavigationKey(device, KeyEvent.KEYCODE_HOME)
        val original = assertZenRoot(expectDefault = true)
        repeat(3) {
            injectNavigationKey(device, KeyEvent.KEYCODE_BACK)
            assertSame("Back must preserve the HOME instance selected through system UI", original, assertZenRoot(expectDefault = true))
        }
        assertDefault(ZEN_HOME_COMPONENT, expectedRoleHeld = true)
    }

    @Test
    fun cancelingSystemSelectionKeepsStockHome() {
        val stockHome = home.resolveHome()
        assertDefault(stockHome, expectedRoleHeld = false)
        openFromLauncherEntry()
        val systemUi = requestDefaultThroughApp()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Cancel explicitly; never select the "Don't ask again" option.
            waitFor(By.res("android", "button2").pkg(systemUi)).click()
        } else {
            legacyHomeCandidate() // Prove that a real selection screen opened before canceling.
            injectNavigationKey(device, KeyEvent.KEYCODE_BACK)
        }

        waitFor(By.pkg(ZEN_PACKAGE).depth(0))
        // Role rejection returns to the app's compatibility-help dialog. Back dismisses it;
        // on old Android this same key is consumed by the already-visible desktop root.
        injectNavigationKey(device, KeyEvent.KEYCODE_BACK)
        assertZenRoot(expectDefault = false)
        assertDefault(stockHome, expectedRoleHeld = false)
        injectNavigationKey(device, KeyEvent.KEYCODE_HOME)
        waitFor(By.pkg(stockHome.packageName).depth(0))
        device.waitForIdle(SELECTION_TIMEOUT_MS)
        assertEquals("Canceling must retain the actual stock HOME route", stockHome.packageName, device.currentPackageName)
        assertDefault(stockHome, expectedRoleHeld = false)
    }

    private fun openFromLauncherEntry() {
        val launchIntent = requireNotNull(instrumentation.targetContext.packageManager.getLaunchIntentForPackage(ZEN_PACKAGE))
        assertEquals(Intent.ACTION_MAIN, launchIntent.action)
        assertTrue("Use the ordinary app icon entry, not a synthetic HOME launch", launchIntent.hasCategory(Intent.CATEGORY_LAUNCHER))
        instrumentation.targetContext.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertZenRoot(expectDefault = false)
    }

    private fun requestDefaultThroughApp(): String {
        val expectedSystemPackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = requireNotNull(instrumentation.targetContext.getSystemService(RoleManager::class.java))
            assertTrue("The emulator must offer the HOME role", roles.isRoleAvailable(RoleManager.ROLE_HOME))
            assertFalse("The user request must start before Zen owns HOME", roles.isRoleHeld(RoleManager.ROLE_HOME))
            // Read the platform controller package, which differs for AOSP and Google images.
            // Only clicking the app's setup button below launches the role request.
            requireNotNull(roles.createRequestRoleIntent(RoleManager.ROLE_HOME).`package`) {
                "System HOME role request did not specify its PermissionController"
            }
        } else {
            SYSTEM_SETTINGS_PACKAGE
        }
        waitFor(res("btnSetDefaultQuick")).click()
        waitFor(By.pkg(expectedSystemPackage).depth(0))
        assertEquals("The app must open the expected system default-HOME selection UI", expectedSystemPackage, device.currentPackageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            waitFor(By.res(expectedSystemPackage, "title").text(ownCandidateLabel())
                .hasAncestor(By.res(expectedSystemPackage, "list")))
        } else {
            legacyHomeCandidate()
        }
        return expectedSystemPackage
    }

    private fun legacyHomeCandidateSelector(): BySelector = By.res(SYSTEM_SETTINGS_PACKAGE, "home_app_pref")
        .hasDescendant(By.res("android", "title").text(ownCandidateLabel()))

    private fun legacyHomeCandidate(): UiObject2 = waitFor(legacyHomeCandidateSelector())

    @Suppress("DEPRECATION")
    private fun ownCandidateLabel(): String {
        val packageManager = instrumentation.targetContext.packageManager
        val label = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            packageManager.getApplicationLabel(instrumentation.targetContext.applicationInfo)
        } else {
            packageManager.getActivityInfo(ZEN_HOME_COMPONENT, 0).loadLabel(packageManager)
        }.toString().trim()
        check(label.isNotEmpty()) { "Zen's installed HOME candidate has no label" }
        return label
    }

    private fun assertDefault(expectedHome: ComponentName, expectedRoleHeld: Boolean) {
        var resolved: ComponentName? = null
        var roleHeld: Boolean? = null
        val confirmed = awaitCondition {
            resolved = runCatching { home.resolveHome() }.getOrNull()
            roleHeld = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roles = requireNotNull(instrumentation.targetContext.getSystemService(RoleManager::class.java))
                check(roles.isRoleAvailable(RoleManager.ROLE_HOME)) { "HOME role unavailable on the test image" }
                roles.isRoleHeld(RoleManager.ROLE_HOME)
            } else null
            resolved == expectedHome && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || roleHeld == expectedRoleHeld)
        }
        assertTrue("Expected HOME=$expectedHome and Zen role=$expectedRoleHeld; actual HOME=$resolved role=$roleHeld", confirmed)
    }

    private fun assertZenRoot(expectDefault: Boolean): MainActivity {
        device.waitForIdle(SELECTION_TIMEOUT_MS)
        waitFor(res("btnSettings"))
        waitFor(res("btnAllApps"))
        waitFor(res("clockTime"))
        assertEquals(ZEN_PACKAGE, device.currentPackageName)
        if (expectDefault) {
            assertTrue("The setup banner must disappear after system-confirmed selection", device.wait(
                Until.gone(res("btnSetDefaultQuick")), SELECTION_TIMEOUT_MS
            ))
        } else {
            waitFor(res("btnSetDefaultQuick"))
        }
        var activity: MainActivity? = null
        assertTrue("Zen HOME did not settle in a resumed, focused state", awaitCondition {
            var ready = false
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull()
                ready = activity?.let { !it.isFinishing && !it.isDestroyed && it.hasWindowFocus() } == true
            }
            ready
        })
        return requireNotNull(activity)
    }

    private fun waitFor(selector: BySelector): UiObject2 = requireNotNull(device.wait(Until.findObject(selector), SELECTION_TIMEOUT_MS)) {
        "Missing required system/application UI: $selector; foreground=${device.currentPackageName}"
    }

    private fun res(id: String) = By.res(ZEN_PACKAGE, id)
}

private const val ZEN_PACKAGE = "com.zenlauncher.app"
private const val SYSTEM_SETTINGS_PACKAGE = "com.android.settings"
private const val SELECTION_TIMEOUT_MS = 15_000L
private val ZEN_HOME_COMPONENT = ComponentName(ZEN_PACKAGE, "$ZEN_PACKAGE.MainActivity")
