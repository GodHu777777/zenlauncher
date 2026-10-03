package com.zenlauncher.app

import android.app.Instrumentation
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Xml
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
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.StringReader

/**
 * Exercises the user-facing default HOME request rather than provisioning Zen with a shell command.
 * The fixture uses shell commands only to establish/restore the pre-install stock HOME. Assignment
 * to Zen must happen through the app button and Android's Settings/PermissionController UI.
 * The candidate must have one exact installed-app label in the expected system package. Nougat
 * opens Configure apps, then a Home app list dialog; modern role requests use a radio choice and
 * confirmation. Missing or ambiguous UI is a failure, never a skip.
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
            candidateRadio(systemUi).click()
            waitFor(By.res("android", "button1").pkg(systemUi).enabled(true)).click()
        } else {
            legacyHomeCandidate().click()
            // Nougat's AppListPreference saves and closes the list immediately. Its parent
            // preference summary now also contains Zen's label, so label disappearance is not
            // evidence that the dialog closed; check the real AlertDialog title instead.
            waitForLegacyDialogClosed()
            assertDefault(ZEN_HOME_COMPONENT, expectedRoleHeld = true)
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
            legacyHomeCandidate() // Prove the Home app list opened before canceling.
            injectNavigationKey(device, KeyEvent.KEYCODE_BACK) // Dismiss the list dialog.
            waitForLegacyDialogClosed()
            assertDefault(stockHome, expectedRoleHeld = false)
            // ACTION_HOME_SETTINGS opened Configure apps, which is still underneath the dialog.
            injectNavigationKey(device, KeyEvent.KEYCODE_BACK)
        }

        waitFor(By.pkg(ZEN_PACKAGE).depth(0))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Returning to the app can precede the ActivityResult callback. Wait for its actual
            // help dialog before Back, otherwise root may consume Back just before it is shown.
            waitFor(By.pkg(ZEN_PACKAGE).text("默认桌面与返回问题"))
            injectNavigationKey(device, KeyEvent.KEYCODE_BACK)
        }
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
            candidateRadio(expectedSystemPackage)
        } else {
            // On the API24 system image ACTION_HOME_SETTINGS routes to AdvancedAppsActivity.
            // Read Settings' own localized preference title instead of assuming English text.
            waitFor(By.res("android", "title").pkg(SYSTEM_SETTINGS_PACKAGE)
                .text(legacyHomeSettingsTitle())).click()
            legacyHomeCandidate()
        }
        return expectedSystemPackage
    }

    private fun legacyHomeSettingsTitle(): String {
        val resources = instrumentation.targetContext.packageManager.getResourcesForApplication(SYSTEM_SETTINGS_PACKAGE)
        val titleId = resources.getIdentifier("home_app", "string", SYSTEM_SETTINGS_PACKAGE)
        if (titleId == 0) failWithUi("The Nougat Settings Home app preference title resource is missing")
        return resources.getString(titleId)
    }

    private fun legacyDialogTitleSelector(): BySelector = By.res("android", "alertTitle")
        .pkg(SYSTEM_SETTINGS_PACKAGE).text(legacyHomeSettingsTitle())

    private fun legacyHomeCandidate(): UiObject2 {
        waitFor(legacyDialogTitleSelector())
        // AppListPreference inflates app_preference_item with android:id/title in a framework
        // AlertDialog ListView. It has no RadioButton; clicking a list item persists the choice.
        val selector = By.res("android", "title").pkg(SYSTEM_SETTINGS_PACKAGE).text(ownCandidateLabel())
            .hasAncestor(By.clazz("android.widget.ListView").pkg(SYSTEM_SETTINGS_PACKAGE))
        waitFor(selector)
        val candidates = device.findObjects(selector).filter { !it.visibleBounds.isEmpty }
        if (candidates.size != 1) failWithUi("Expected one Zen candidate in the Nougat Home app dialog; found ${candidates.size}")
        return candidates.single()
    }

    private fun waitForLegacyDialogClosed() {
        if (!device.wait(Until.gone(legacyDialogTitleSelector()), SELECTION_TIMEOUT_MS)) {
            failWithUi("The Nougat Home app dialog did not close")
        }
    }

    private fun candidateRadio(systemPackage: String): UiObject2 {
        val label = ownCandidateLabel()
        val selector = By.pkg(systemPackage).text(label)
        waitFor(selector)
        val labels = device.findObjects(selector).filter { !it.visibleBounds.isEmpty }
        if (labels.size != 1) {
            failWithUi("Expected exactly one visible '$label' candidate in $systemPackage; found ${labels.size}")
        }
        if (labels.single().className == "android.widget.RadioButton") return labels.single()
        var ancestor = labels.single().parent
        while (ancestor != null) {
            val radios = ancestor.findObjects(By.pkg(systemPackage).clazz("android.widget.RadioButton"))
                .filter { !it.visibleBounds.isEmpty }
            if (radios.size == 1) return radios.single()
            if (radios.size > 1) {
                failWithUi("The nearest radio-containing ancestor of '$label' contains ${radios.size} radios; refusing an ambiguous selection")
            }
            ancestor = ancestor.parent
        }
        failWithUi("No radio control belongs to the unique '$label' candidate in $systemPackage")
    }

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
        "Missing required system/application UI: $selector; foreground=${device.currentPackageName}; ${compactUiDiagnostic()}"
    }

    private fun failWithUi(message: String): Nothing = throw AssertionError(
        "$message; foreground=${device.currentPackageName}; ${compactUiDiagnostic()}"
    )

    /** Keep useful failure evidence in the public test annotation if artifact downloads fail. */
    private fun compactUiDiagnostic(): String = runCatching {
        val output = ByteArrayOutputStream()
        device.dumpWindowHierarchy(output)
        val parser = Xml.newPullParser().apply { setInput(StringReader(output.toString("UTF-8"))) }
        val foreground = device.currentPackageName
        val nodes = mutableListOf<Pair<Boolean, String>>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "node") {
                fun attribute(name: String) = parser.getAttributeValue(null, name).orEmpty()
                val text = attribute("text").replace(Regex("\\s+"), " ")
                val description = attribute("content-desc").replace(Regex("\\s+"), " ")
                val id = attribute("resource-id")
                val bounds = attribute("bounds")
                val checkable = attribute("checkable") == "true"
                val visible = attribute("visible-to-user") != "false" && bounds != "[0,0][0,0]"
                if (visible && attribute("package") == foreground &&
                    (text.isNotEmpty() || description.isNotEmpty() || id.isNotEmpty() || checkable)) {
                    val meaningful = text.isNotEmpty() || description.isNotEmpty() || checkable
                    nodes += meaningful to ("t='${text.take(60)}' desc='${description.take(60)}' id='${id.take(75)}' " +
                        "c=${attribute("class").substringAfterLast('.')} checked=${attribute("checked")} " +
                        "clickable=${attribute("clickable")} b=$bounds")
                }
            }
            parser.next()
        }
        // System bars appear first in the dump. Prioritize the actual foreground choices so
        // their labels and descriptions survive the public annotation's size limit.
        val selected = nodes.sortedByDescending { it.first }.take(20).map { it.second }
        "ui(package=$foreground, nodes=${nodes.size})=[${selected.joinToString("; ")}]".take(2200)
    }.getOrElse { "UI dump failed: ${it.javaClass.simpleName}: ${it.message}".take(2200) }

    private fun res(id: String) = By.res(ZEN_PACKAGE, id)
}

private const val ZEN_PACKAGE = "com.zenlauncher.app"
private const val SYSTEM_SETTINGS_PACKAGE = "com.android.settings"
private const val SELECTION_TIMEOUT_MS = 15_000L
private val ZEN_HOME_COMPONENT = ComponentName(ZEN_PACKAGE, "$ZEN_PACKAGE.MainActivity")
