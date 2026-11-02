package com.lightboat.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightboat.app.update.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AppUpdateUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val configFile get() = File(context.filesDir, "config.json")
    private var originalConfig: ByteArray? = null
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val server = MockWebServer()
    private var dialog: AppUpdateDialog? = null

    @Before fun setUp() {
        server.start()
        originalConfig = configFile.takeIf(File::exists)?.readBytes()
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(AppConfig.EMPTY)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun tearDown() {
        scenario.onActivity { dialog?.dismiss() }
        scenario.close()
        originalConfig?.let(configFile::writeBytes) ?: configFile.delete()
        server.shutdown()
    }

    @Test fun latestVersionAndNetworkFailureAreVisibleAndRetryWorks() {
        server.enqueue(MockResponse().setResponseCode(429))
        show()
        waitForText(R.string.update_rate_limit)
        val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName!!
        server.enqueue(MockResponse().setBody(release("v$current").toString()))
        click(R.id.updateAction)
        waitForText(R.string.update_latest)
        scenario.onActivity {
            assertEquals(View.GONE, dialog!!.findViewById<View>(R.id.updateAction)!!.visibility)
            assertEquals("正式版更新说明", dialog!!.findViewById<TextView>(R.id.updateNotes)!!.text.toString())
        }
    }

    @Test fun invalidDownloadedApkCannotReachInstallerAndCanBeRetried() {
        server.enqueue(MockResponse().setBody(release().toString()))
        show()
        waitForAction(R.string.update_download)
        server.enqueue(MockResponse().setBody("not an apk"))
        click(R.id.updateAction)
        waitForText(R.string.update_bad_apk)
        waitForAction(R.string.update_download)
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertEquals("/download", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
    }

    @Test fun closingDuringDownloadCancelsTheCallAndRemovesPartialApk() {
        server.enqueue(MockResponse().setBody(release(size = 1024 * 1024).toString()))
        show()
        waitForAction(R.string.update_download)
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1024 * 1024)))
            .throttleBody(1024, 100, TimeUnit.MILLISECONDS))
        click(R.id.updateAction)
        val workspaceField = AppUpdateDialog::class.java.getDeclaredField("workspace").apply { isAccessible = true }
        val directory = workspaceField.get(dialog) as File
        waitFor { File(directory, "update.apk").length() > 0 }
        click(R.id.updateClose)
        waitFor { !directory.exists() }
        scenario.onActivity { assertFalse(dialog!!.isShowing) }
    }

    @Test fun archiveVerifierRejectsSameVersionAndDifferentPackage() {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val version = ReleaseVersion.parse(installed.versionName!!)!!
        val release = AppRelease("v${version.name}", version, "", null)
        val same = File(installed.applicationInfo!!.sourceDir)
        val testPackage = context.packageManager.getPackageInfo(instrumentation.context.packageName, 0)
        for (file in listOf(same, File(testPackage.applicationInfo!!.sourceDir))) {
            try { UpdateApkVerifier.verify(context, file, release); fail("Must reject $file") }
            catch (error: UpdateException) { assertEquals(UpdateFailure.INVALID_APK, error.reason) }
        }
    }

    @Test fun verifiedUpdateRequestsPermissionThenHandsReadableApkToSystemInstaller() {
        val bytes = instrumentation.context.assets.open("update/candidate.apk").use { it.readBytes() }
        val launched = AtomicReference<Intent>()
        // Visit the real permission screen, but consume installation so the fixture is NEVER installed.
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) launched.set(intent)
                if (intent.type == "application/vnd.android.package-archive") {
                    launched.set(intent)
                    return Instrumentation.ActivityResult(0, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        var directory: File? = null
        var allowed = false
        try {
            server.enqueue(MockResponse().setBody(release(size = bytes.size).toString()))
            show { allowed }
            waitForAction(R.string.update_download)
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            click(R.id.updateAction)
            waitForText(R.string.update_ready)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(context.cacheDir, "update-ready.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            directory = AppUpdateDialog::class.java.getDeclaredField("workspace").apply { isAccessible = true }.get(dialog) as File
            click(R.id.updateAction)
            assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, launched.get().action)
            waitFor { scenario.state == androidx.lifecycle.Lifecycle.State.CREATED }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitFor {
                var focused = false
                scenario.onActivity { focused = it.hasWindowFocus() || dialog!!.window!!.decorView.hasWindowFocus() }
                focused
            }
            assertTrue("Permission round trip must retain the verified APK", File(directory, "update.apk").isFile)
            scenario.onActivity { assertTrue(dialog!!.isShowing) }
            allowed = true
            click(R.id.updateAction)
            val install = launched.get()
            assertEquals("application/vnd.android.package-archive", install.type)
            assertEquals("content", install.data!!.scheme)
            assertTrue(install.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(install.data!!)!!.use { it.readBytes() })
            scenario.onActivity { assertFalse(dialog!!.isShowing) }
        } finally {
            instrumentation.removeMonitor(monitor)
            directory?.deleteRecursively()
        }
    }

    private fun show(canInstall: () -> Boolean = { context.packageManager.canRequestPackageInstalls() }) {
        val endpoint = server.url("/latest")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(request.newBuilder().url(if (request.url.host == "github.com") server.url("/download") else request.url).build())
        }.build()
        scenario.onActivity { activity ->
            dialog = AppUpdateDialog(activity, GitHubUpdateClient(endpoint, client), canInstall).also { it.show() }
        }
    }
    private fun click(id: Int) { scenario.onActivity { dialog!!.findViewById<View>(id)!!.performClick() } }
    private fun waitForText(id: Int) = waitFor {
        var matches = false
        scenario.onActivity { matches = dialog!!.findViewById<TextView>(R.id.updateStatus)!!.text.toString() == context.getString(id) }
        matches
    }
    private fun waitForAction(id: Int) = waitFor {
        var matches = false
        scenario.onActivity {
            val action = dialog!!.findViewById<TextView>(R.id.updateAction)!!
            matches = action.isEnabled && action.text.toString() == context.getString(id)
        }
        matches
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Update UI did not reach expected state", condition())
    }
    private fun release(tag: String = "v99.0.0", size: Int = 10): JSONObject {
        val asset = JSONObject().put("name", "lightboat-$tag-debug.apk").put("size", size)
            .put("browser_download_url", "https://github.com/everstu/lightboat_app/releases/download/$tag/lightboat-$tag-debug.apk")
        return JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false)
            .put("body", "正式版更新说明").put("assets", JSONArray().put(asset))
    }
}
