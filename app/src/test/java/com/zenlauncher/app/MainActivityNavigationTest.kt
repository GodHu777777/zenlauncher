package com.zenlauncher.app

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.EditText
import com.zenlauncher.app.model.AppInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

/**
 * Exercises the real Activity, dialogs, and AndroidX back dispatcher on old and modern Android.
 * System HOME resolution and predictive-back gestures still require emulator/device testing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28, 34])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityNavigationTest {
    private var controller: ActivityController<MainActivity>? = null

    private val activity: MainActivity
        get() = requireNotNull(controller).get()

    @Before
    fun createHome() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("zen_launcher_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        controller = Robolectric.buildActivity(MainActivity::class.java, homeIntent()).setup()
    }

    @After
    fun destroyHome() {
        // Dismiss any dialog retained by a failed assertion so it cannot contaminate another test.
        ShadowDialog.getShownDialogs().toList().forEach { it.dismiss() }
        controller?.pause()?.stop()?.destroy()
        controller = null
    }

    @Test
    fun repeatedBackAtRootKeepsHomeActivityAlive() {
        repeat(5) { activity.onBackPressedDispatcher.onBackPressed() }

        assertHomeVisible()
        assertFalse(activity.isFinishing)
        assertFalse(activity.isDestroyed)
    }

    @Test
    fun backClearsSearchThenStaysOnHome() {
        val search = activity.findViewById<EditText>(R.id.etSearch)
        search.setText("camera")
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.rvSearchResults).visibility)

        activity.onBackPressedDispatcher.onBackPressed()

        assertEquals("", search.text.toString())
        assertHomeVisible()
        repeat(3) { activity.onBackPressedDispatcher.onBackPressed() }
        assertFalse(activity.isFinishing)
    }

    @Test
    fun repeatedHomeIntentsClearSearchAndFocusWithoutFinishing() {
        val search = activity.findViewById<EditText>(R.id.etSearch)
        search.requestFocus()
        search.setText("mail")

        repeat(3) { requireNotNull(controller).newIntent(homeIntent()) }

        assertEquals("", search.text.toString())
        assertFalse("HOME must not leave the search field focused", search.hasFocus())
        assertHomeVisible()
        assertFalse(activity.isFinishing)
    }

    @Test
    fun homeClosesAllAppsSheetAndDiscardsItsFilter() {
        val sheet = openAllApps()
        sheet.findViewById<EditText>(R.id.etFilterApps).setText("camera")

        requireNotNull(controller).newIntent(homeIntent())

        assertFalse("HOME must close an open app drawer", sheet.isShowing)
        assertHomeVisible()
        val reopened = openAllApps()
        assertNotSame(sheet, reopened)
        assertEquals("", reopened.findViewById<EditText>(R.id.etFilterApps).text.toString())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun homeCancelsFrictionDialogInsteadOfLeavingItAboveDesktop() {
        val dialog = openFrictionDialog()

        requireNotNull(controller).newIntent(homeIntent())

        assertFalse("HOME must dismiss the pending launch confirmation", dialog.isShowing)
        assertHomeVisible()
        assertFalse(activity.isFinishing)
    }

    @Test
    @Suppress("DEPRECATION")
    fun backCancelsFrictionWithoutClosingDesktop() {
        val dialog = openFrictionDialog()

        dialog.onBackPressed()

        assertFalse("Back should abandon the pending app launch", dialog.isShowing)
        activity.onBackPressedDispatcher.onBackPressed()
        assertHomeVisible()
        assertFalse(activity.isFinishing)
    }

    @Test
    fun nonHomeIntentDoesNotResetCurrentUi() {
        val search = activity.findViewById<EditText>(R.id.etSearch)
        search.setText("notes")
        val sheet = openAllApps()
        sheet.findViewById<EditText>(R.id.etFilterApps).setText("notes")

        requireNotNull(controller).newIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        )

        assertEquals("notes", search.text.toString())
        assertTrue("Only an actual HOME intent should reset transient UI", sheet.isShowing)
        assertEquals("notes", sheet.findViewById<EditText>(R.id.etFilterApps).text.toString())
    }

    @Test
    fun backRemainsConsumedAfterBackgroundAndResume() {
        requireNotNull(controller).pause().stop().restart().start().resume()

        repeat(3) { activity.onBackPressedDispatcher.onBackPressed() }

        assertHomeVisible()
        assertFalse(activity.isFinishing)
        assertFalse(activity.isDestroyed)
    }

    @Test
    fun recreationClosesOldWindowAndKeepsBackHandlingOnNewActivity() {
        val oldActivity = activity
        val oldSheet = openAllApps()

        requireNotNull(controller).recreate()

        assertNotSame(oldActivity, activity)
        assertTrue(oldActivity.isDestroyed)
        assertFalse("Destroyed activities must not retain their dialog windows", oldSheet.isShowing)
        activity.onBackPressedDispatcher.onBackPressed()
        assertHomeVisible()
        assertFalse(activity.isFinishing)
    }

    private fun openAllApps(): Dialog {
        assertTrue(activity.findViewById<View>(R.id.btnAllApps).performClick())
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue(dialog.isShowing)
        return dialog
    }

    private fun openFrictionDialog(): Dialog {
        val app = AppInfo(
            appName = "Test distraction",
            originalName = "Test distraction",
            packageName = "com.example.distraction",
            activityName = "com.example.distraction.MainActivity",
            isDopamineApp = true
        )
        // Launch interception has no standalone UI entry without an installed third-party app.
        MainActivity::class.java.getDeclaredMethod("tryLaunchApp", AppInfo::class.java).apply {
            isAccessible = true
        }.invoke(activity, app)
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue(dialog.isShowing)
        return dialog
    }

    private fun assertHomeVisible() {
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.layoutNormalHome).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.rvSearchResults).visibility)
    }

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
}
