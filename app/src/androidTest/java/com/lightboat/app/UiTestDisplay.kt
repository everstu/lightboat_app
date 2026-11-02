package com.lightboat.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Pin matrix runs explicitly: fullSensor activities can ignore the system rotation-lock setting. */
internal fun launchConfiguredActivity(): ActivityScenario<MainActivity> {
    val arguments = InstrumentationRegistry.getArguments()
    val scenario = ActivityScenario.launch(MainActivity::class.java)
    arguments.getString("rotation")?.let { rotation ->
        val portrait = when (rotation) {
            "0" -> true
            "90" -> false
            else -> error("rotation must be 0 or 90")
        }
        scenario.onActivity {
            it.requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        val expected = if (portrait) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
        var ready = false
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { ready = it.resources.configuration.orientation == expected }
            if (!ready) SystemClock.sleep(50)
        }
        assertTrue("The requested orientation was not applied", ready)
    }
    scenario.onActivity { activity ->
        val configuration = activity.resources.configuration
        arguments.getString("expectFontScale")?.let { assertEquals("fontScale", it.toFloat(), configuration.fontScale, 0.01f) }
        arguments.getString("expectScreenWidthDp")?.let { assertEquals("screenWidthDp", it.toInt(), configuration.screenWidthDp) }
        arguments.getString("expectScreenHeightDp")?.let { assertEquals("screenHeightDp", it.toInt(), configuration.screenHeightDp) }
    }
    return scenario
}
