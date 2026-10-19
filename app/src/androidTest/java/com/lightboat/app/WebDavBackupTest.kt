package com.lightboat.app

import android.app.Dialog
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.lightboat.app.backup.BackupArchive
import com.lightboat.app.backup.BackupRepository
import com.lightboat.app.backup.BackupPreferences
import com.lightboat.app.backup.BackupOptions
import com.lightboat.app.userscript.ScriptStorage
import com.lightboat.app.userscript.ScriptValueStore
import okhttp3.Credentials
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/** An in-process WebDAV server exercises the real native UI and application files. */
@RunWith(AndroidJUnit4::class)
class WebDavBackupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val root get() = context.filesDir
    private val values get() = File(root, "userscript-values")
    private val local get() = File(root, "webdav-local")
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var saved: File
    private var savedConfig: ByteArray? = null
    private lateinit var savedUi: Map<String, *>
    private lateinit var savedDav: Map<String, *>
    private lateinit var savedOptions: Map<String, *>
    @Volatile private var remote: ByteArray? = null
    @Volatile private var remoteVersion = 1
    @Volatile private var conflictOnPut = false
    @Volatile private var childExists = false
    private val directoriesCreated = AtomicInteger()
    private val puts = AtomicInteger()
    private val successfulPuts = AtomicInteger()
    private val scriptId = "webdav-shared-script"
    private val profileId = "webdav-profile"
    private val identity = "$profileId:$scriptId:webdav-test:Test script"
    private val firstName get() = "${hash(identity)}.json"
    private val secondName get() = "${hash("second-device")}.json"
    private val server = MockWebServer().apply { dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/page") return MockResponse().setBody("<!doctype html><html><body>WebDAV test page</body></html>")
            if (request.getHeader("Authorization") != Credentials.basic("test-user", "do-not-export-password", Charsets.UTF_8))
                return MockResponse().setResponseCode(401)
            if (remote != null) childExists = true
            return when (request.method) {
                "PROPFIND" -> if (request.path == "/dav/" || request.path == "/dav/lightboat/" && childExists)
                    MockResponse().setResponseCode(207).addHeader("Content-Type", "application/xml").setBody(
                        """<d:multistatus xmlns:d="DAV:"><d:response><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response></d:multistatus>""")
                    else MockResponse().setResponseCode(404)
                "MKCOL" -> if (request.path != "/dav/lightboat/") MockResponse().setResponseCode(409)
                    else if (childExists) MockResponse().setResponseCode(405)
                    else { childExists = true; directoriesCreated.incrementAndGet(); MockResponse().setResponseCode(201) }
                "HEAD", "GET" -> {
                    val bytes = remote
                    if (bytes == null || request.path != "/dav/lightboat/lightboat-backup.zip") MockResponse().setResponseCode(404)
                    else MockResponse().addHeader("Content-Type", "application/zip").addHeader("ETag", "\"version-$remoteVersion\"")
                        .addHeader("Last-Modified", "Mon, 19 Oct 2026 03:00:00 GMT").apply {
                            if (request.method == "GET") setBody(Buffer().write(bytes)) else addHeader("Content-Length", bytes.size)
                        }
                }
                "PUT" -> {
                    if (!childExists || request.path != "/dav/lightboat/lightboat-backup.zip") return MockResponse().setResponseCode(409)
                    puts.incrementAndGet()
                    val bytes = request.body.readByteArray()
                    if (conflictOnPut) { conflictOnPut = false; remoteVersion++ }
                    val matches = if (remote == null) request.getHeader("If-None-Match") == "*"
                        else request.getHeader("If-Match") == "\"version-$remoteVersion\""
                    if (!matches) MockResponse().setResponseCode(412)
                    else {
                        remote = bytes; remoteVersion++; successfulPuts.incrementAndGet()
                        MockResponse().setResponseCode(201).addHeader("ETag", "\"version-$remoteVersion\"")
                    }
                }
                else -> MockResponse().setResponseCode(405)
            }
        }
    } }
    private val base get() = server.url("/").toString().trimEnd('/')
    private fun configA() = AppConfig.EMPTY.copy(categories = mapOf("saved-category" to "规则与阅读"))
        .upsertScript(SharedScript(scriptId, "去广告规则", "$base/script.user.js"))
        .upsert(Profile(profileId, "设备一的配置", "$base/page", listOf(ProfileScript(scriptId, false)),
            categoryId = "saved-category", favorite = true))
    private fun configB() = AppConfig.EMPTY.upsert(Profile("second-profile", "设备二的配置", "$base/page"))

    @Before fun setUp() {
        savedConfig = File(root, "config.json").takeIf(File::exists)?.readBytes()
        savedUi = context.getSharedPreferences("profile_ui", 0).all.toMap()
        savedDav = context.getSharedPreferences("webdav", 0).all.toMap()
        savedOptions = context.getSharedPreferences("backup-options", 0).all.toMap()
        saved = File(context.cacheDir, "webdav-test-original-${UUID.randomUUID()}").apply { mkdirs() }
        ScriptStorage.exclusive {
            if (values.exists()) check(values.renameTo(File(saved, "values")))
            if (local.exists()) check(local.renameTo(File(saved, "local")))
            values.mkdirs()
        }
        context.getSharedPreferences("webdav", 0).edit().clear().commit()
        context.getSharedPreferences("backup-options", 0).edit().clear().commit()
        context.getSharedPreferences("profile_ui", 0).edit().clear().commit()
        server.start()
    }
    @After fun tearDown() {
        scenario?.close()
        ScriptStorage.exclusive {
            values.deleteRecursively(); local.deleteRecursively()
            File(saved, "values").takeIf(File::exists)?.renameTo(values)
            File(saved, "local").takeIf(File::exists)?.renameTo(local)
        }
        savedConfig?.let { File(root, "config.json").writeBytes(it) } ?: File(root, "config.json").delete()
        restorePreferences("profile_ui", savedUi); restorePreferences("webdav", savedDav)
        restorePreferences("backup-options", savedOptions)
        saved.deleteRecursively(); server.shutdown()
    }

    @Test fun fullBackupRestoresIdsGmRulesAndPreferencesWithoutCreatingALocalUndoBackup() {
        val original = configA()
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(original)
        ScriptStorage.exclusive { ScriptValueStore(context, identity).update("rules", encoded("规则正文".repeat(180_000))) }
        context.getSharedPreferences("profile_ui", 0).edit().putString("categoryId", "saved-category").putBoolean("favoritesOnly", true).commit()
        launchManager(); openDialog(); fillServer()
        click(R.id.testWebdav); waitText("连接成功")
        capture("webdav-settings")
        click(R.id.uploadWebdav); waitText("备份上传完成")
        assertEquals(1, successfulPuts.get())
        assertEquals(1, directoriesCreated.get())
        val archive = File(saved, "remote.zip").apply { writeBytes(requireNotNull(remote)) }
        ZipFile(archive).use { zip ->
            assertNotNull(zip.getEntry("userscript-values/$firstName"))
            assertFalse(zip.entries().asSequence().any { "webdav" in it.name || "password" in it.name })
        }
        closeDialog(); scenario!!.close(); scenario = null
        val second = configB()
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(second)
        ScriptStorage.exclusive { values.deleteRecursively(); values.mkdirs(); File(values, secondName).writeText("""{"owner":{"type":"json","value":"device-B"}}""") }
        context.getSharedPreferences("profile_ui", 0).edit().clear().putBoolean("favoritesOnly", false).commit()
        launchManager(); openDialog()
        click(R.id.downloadWebdav); waitPreview()
        assertEquals(second, ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load())
        capture("webdav-restore-preview")
        click(R.id.webdavConfirm); waitText("恢复完成")
        assertEquals(original, ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load())
        assertTrue(context.getSharedPreferences("profile_ui", 0).getBoolean("favoritesOnly", false))
        assertEquals("saved-category", context.getSharedPreferences("profile_ui", 0).getString("categoryId", null))
        assertFalse(File(values, secondName).exists())
        ScriptStorage.exclusive {
            val restored = ScriptValueStore(context, identity).encoded("rules")!!
            assertEquals("规则正文".repeat(180_000), JSONObject(restored).getString("value"))
        }
        assertFalse(local.exists())
    }

    @Test fun cancellingAPreviewAndRejectingAnInvalidBackupLeaveLocalDataUntouched() {
        val original = configA()
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(original)
        val workspace = File(saved, "workspace").apply { mkdirs() }
        val zip = File(saved, "good.zip")
        BackupRepository(context, AppConfig.DEFAULT_USER_AGENT).create(zip, workspace)
        remote = zip.readBytes()
        val localConfig = configB()
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(localConfig)
        launchManager(); openDialog(); fillServer()
        click(R.id.downloadWebdav); waitPreview(); click(R.id.webdavPreviewBack)
        assertEquals(localConfig, ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load())
        assertFalse(File(local, "previous.zip").exists())
        remote = "not a backup".toByteArray()
        click(R.id.downloadWebdav)
        waitFor { status().isNotBlank() && !status().contains("正在") }
        assertEquals(localConfig, ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load())
        assertFalse(File(local, "previous.zip").exists())
    }

    @Test fun remoteChangesAreConfirmedAndAConcurrentUpdateIsNotOverwritten() {
        val original = configA()
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(original)
        remote = "existing remote backup".toByteArray()
        launchManager(); openDialog(); fillServer()
        click(R.id.uploadWebdav); waitPreview()
        scenario!!.onActivity { activity -> assertTrue(dialog(activity).findViewById<TextView>(R.id.webdavPreviewTitle)!!.text.contains("其他版本")) }
        click(R.id.webdavPreviewBack)
        assertEquals(0, puts.get())
        click(R.id.uploadWebdav); waitPreview(); conflictOnPut = true
        click(R.id.webdavConfirm); waitText("远端备份已发生变化")
        assertEquals(0, successfulPuts.get())
        assertEquals("existing remote backup", String(requireNotNull(remote)))
        click(R.id.uploadWebdav); waitPreview(); click(R.id.webdavConfirm); waitText("备份上传完成")
        assertEquals(1, successfulPuts.get())
    }

    @Test fun backupWaitsForPreviouslyQueuedGmWritesAndIncludesAtomicRecoverySidecars() {
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(configA())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        assertTrue(ScriptStorage.submit { entered.countDown(); release.await(10, TimeUnit.SECONDS) })
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertTrue(ScriptStorage.submit { ScriptValueStore(context, identity).update("owner", encoded("queued")) })
        val zip = File(saved, "ordered.zip")
        val work = File(saved, "queued-work").apply { mkdirs() }
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        val snapshot = Thread { try { BackupRepository(context, AppConfig.DEFAULT_USER_AGENT).create(zip, work) } catch (error: Throwable) { failure = error } finally { done.countDown() } }
        snapshot.start(); SystemClock.sleep(100); assertFalse(zip.exists()); release.countDown()
        assertTrue(done.await(15, TimeUnit.SECONDS)); failure?.let { throw AssertionError(it) }
        val repository = BackupRepository(context, AppConfig.DEFAULT_USER_AGENT)
        val restored = repository.open(zip, File(work, "opened"))
        assertEquals("queued", JSONObject(restored.directory.resolve("userscript-values/$firstName").readText()).getJSONObject("owner").getString("value"))
        ScriptStorage.exclusive { File(values, firstName).renameTo(File(values, "$firstName.bak")) }
        repository.create(zip, work)
        assertNotNull(repository.open(zip, File(work, "sidecar")).directory.resolve("userscript-values/$firstName").takeIf(File::exists))
    }

    @Test fun appStartupRollsBackAnInterruptedRestoreBeforeCreatingTheBrowser() {
        val original = configA()
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(original)
        ScriptStorage.exclusive { ScriptValueStore(context, identity).update("owner", encoded("before-restore")) }
        val transaction = File(root, "webdav-restore-transaction").apply { mkdirs() }
        transaction.resolve("old-config.json").writeText(ConfigCodec.encode(original))
        transaction.resolve("old-preferences.json").writeText(BackupPreferences("saved-category", true).json().toString())
        transaction.resolve("state.json").writeText("""{"state":"pending"}""")
        ScriptStorage.exclusive {
            check(values.renameTo(transaction.resolve("old-values")))
            values.mkdirs(); File(values, secondName).writeText("""{"owner":{"type":"json","value":"incomplete"}}""")
        }
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(configB())
        launchManager()
        assertEquals(original, ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load())
        assertEquals("before-restore", JSONObject(File(values, firstName).readText()).getJSONObject("owner").getString("value"))
        assertFalse(File(values, secondName).exists())
        assertTrue(context.getSharedPreferences("profile_ui", 0).getBoolean("favoritesOnly", false))
        assertFalse(transaction.exists())
    }

    @Test fun configOnlyWebdavBackupsKeepTheReceivingDevicesGmAndPreferences() {
        val original = configA()
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(original)
        ScriptStorage.exclusive { ScriptValueStore(context, identity).update("owner", encoded("device-A")) }
        context.getSharedPreferences("profile_ui", 0).edit().putBoolean("favoritesOnly", true).commit()
        launchManager(); openDialog(); fillServer()
        scenario!!.onActivity { activity -> dialog(activity).findViewById<BackupOptionsView>(R.id.webdavBackupOptions)!!.options = BackupOptions.CONFIG_ONLY }
        click(R.id.uploadWebdav); waitText("备份上传完成")
        val archive = File(saved, "config-only.zip").apply { writeBytes(requireNotNull(remote)) }
        ZipFile(archive).use { assertEquals(setOf("config.json", "manifest.json"), it.entries().asSequence().map { entry -> entry.name }.toSet()) }
        assertEquals(1, directoriesCreated.get())
        closeDialog(); scenario!!.close(); scenario = null
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(configB())
        ScriptStorage.exclusive { values.deleteRecursively(); values.mkdirs(); File(values, secondName).writeText("""{"owner":{"type":"json","value":"device-B"}}""") }
        context.getSharedPreferences("profile_ui", 0).edit().clear().putBoolean("favoritesOnly", false).commit()
        launchManager(); openDialog(); click(R.id.downloadWebdav); waitPreview()
        scenario!!.onActivity { activity ->
            val options = dialog(activity).findViewById<BackupOptionsView>(R.id.webdavRestoreOptions)!!
            assertEquals(BackupOptions.CONFIG_ONLY, options.options)
            assertFalse(options.findViewById<View>(R.id.backupScriptData).isEnabled)
            assertFalse(options.findViewById<View>(R.id.backupPreferences).isEnabled)
        }
        click(R.id.webdavConfirm); waitText("恢复完成")
        assertEquals(original, ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load())
        assertEquals("device-B", JSONObject(File(values, secondName).readText()).getJSONObject("owner").getString("value"))
        assertFalse(File(values, firstName).exists())
        assertFalse(context.getSharedPreferences("profile_ui", 0).getBoolean("favoritesOnly", true))
        closeDialog()
        scenario!!.onActivity { activity ->
            assertTrue(activity.findViewById<View>(R.id.returnButton).isShown)
            activity.findViewById<View>(R.id.returnButton).performClick()
        }
        waitFor { var ready = false; scenario!!.onActivity { ready = it.findViewById<View>(R.id.menuLayer).isShown }; ready }
    }

    @Test fun localExportAndImportUseTheSameZipAndRestoreOnlyTheSelectedComponents() {
        val original = configA()
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(original)
        ScriptStorage.exclusive { ScriptValueStore(context, identity).update("owner", encoded("exported-A")) }
        context.getSharedPreferences("profile_ui", 0).edit().putBoolean("favoritesOnly", true).commit()
        launchManager()
        val exported = File(saved, "portable-backup.zip")
        scenario!!.onActivity { activity ->
            val exportDialog = BackupExportDialog(activity, AppConfig.DEFAULT_USER_AGENT, BackupOptions.ALL, Uri.fromFile(exported),
                prepareData = { MainActivity::class.java.getDeclaredMethod("prepareBackupData").apply { isAccessible = true }.invoke(activity) }, chooseFile = {})
            MainActivity::class.java.getDeclaredMethod("showNativeDialog", Dialog::class.java).apply { isAccessible = true }.invoke(activity, exportDialog)
        }
        waitFor { var ready = false; scenario!!.onActivity { activity -> ready = dialog(activity).findViewById<TextView>(R.id.backupExportFeedback)!!.text.contains("备份已导出") }; ready }
        capture("local-backup-export")
        assertTrue(exported.isFile)
        val repository = BackupRepository(context, AppConfig.DEFAULT_USER_AGENT)
        assertEquals(BackupOptions.ALL, repository.open(exported, File(saved, "local-opened")).info.options)
        click(R.id.backupExportCancel); scenario!!.close(); scenario = null
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(configB())
        ScriptStorage.exclusive { values.deleteRecursively(); values.mkdirs(); File(values, secondName).writeText("""{"owner":{"type":"json","value":"local-B"}}""") }
        context.getSharedPreferences("profile_ui", 0).edit().clear().putBoolean("favoritesOnly", false).commit()
        launchManager()
        scenario!!.onActivity { activity -> MainActivity::class.java.getDeclaredMethod("showConfigImport", Uri::class.java).apply { isAccessible = true }.invoke(activity, Uri.fromFile(exported)) }
        waitFor { var ready = false; scenario!!.onActivity { activity -> ready = dialog(activity).findViewById<View>(R.id.importBackupOptions)!!.isShown }; ready }
        scenario!!.onActivity { activity ->
            val options = dialog(activity).findViewById<BackupOptionsView>(R.id.importBackupOptions)!!
            assertEquals(BackupOptions.ALL, options.options)
            options.options = BackupOptions.CONFIG_ONLY
        }
        capture("local-backup-import")
        click(R.id.importAction)
        waitFor { var restored = false; scenario!!.onActivity { activity -> restored = MainActivity::class.java.getDeclaredField("nativeDialog").apply { isAccessible = true }.get(activity) == null }; restored }
        assertEquals(original, ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load())
        assertEquals("local-B", JSONObject(File(values, secondName).readText()).getJSONObject("owner").getString("value"))
        assertFalse(context.getSharedPreferences("profile_ui", 0).getBoolean("favoritesOnly", true))
        assertFalse(local.exists())
    }

    @Test fun activityRecreationWaitsForAnInProcessRestoreBeforeLoadingConfig() {
        val original = configA()
        val repository = BackupRepository(context, AppConfig.DEFAULT_USER_AGENT)
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(original)
        ScriptStorage.exclusive { ScriptValueStore(context, identity).update("owner", encoded("restored")) }
        val workspace = File(saved, "recreation-work").apply { mkdirs() }
        val archive = File(saved, "recreation.zip")
        repository.create(archive, workspace)
        val candidate = repository.open(archive, File(workspace, "candidate"))
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(configB())
        ScriptStorage.exclusive { values.deleteRecursively(); values.mkdirs() }
        launchManager()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        val restore = Thread {
            try {
                repository.restore(candidate) {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    true
                }
            } catch (error: Throwable) { failure = error }
            finally { done.countDown() }
        }
        restore.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            scenario!!.recreate()
            scenario!!.onActivity { activity ->
                assertTrue(activity.findViewById<TextView>(R.id.statusTitle).text.contains("恢复"))
                assertFalse(activity.findViewById<View>(R.id.settingsButton).isEnabled)
            }
        } finally { release.countDown() }
        assertTrue(done.await(20, TimeUnit.SECONDS)); failure?.let { throw AssertionError(it) }
        waitFor {
            var loaded = false
            scenario!!.onActivity { activity ->
                val config = MainActivity::class.java.getDeclaredField("config").apply { isAccessible = true }.get(activity)
                loaded = config == original && activity.findViewById<View>(R.id.settingsButton).isEnabled
            }
            loaded
        }
        assertEquals("restored", JSONObject(File(values, firstName).readText()).getJSONObject("owner").getString("value"))
    }

    @Test fun backgroundingTheActivityDuringRestorePreservesTheCompletionCallback() {
        val original = configA()
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(original)
        val workspace = File(saved, "background-work").apply { mkdirs() }
        val archive = File(saved, "background.zip")
        BackupRepository(context, AppConfig.DEFAULT_USER_AGENT).create(archive, workspace)
        remote = archive.readBytes()
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(configB())
        launchManager(); openDialog(); fillServer()
        click(R.id.downloadWebdav); waitPreview()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        assertTrue(ScriptStorage.submit { entered.countDown(); release.await(15, TimeUnit.SECONDS) })
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        try {
            click(R.id.webdavConfirm)
            waitFor { BackupRepository.isRestoring }
            scenario!!.moveToState(Lifecycle.State.CREATED)
            scenario!!.moveToState(Lifecycle.State.RESUMED)
        } finally { release.countDown() }
        waitText("恢复完成")
        assertEquals(original, ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).load())
    }

    @Test fun restoreDoesNotReadOrArchiveTheGmDataItReplaces() {
        val original = configA()
        val repository = BackupRepository(context, AppConfig.DEFAULT_USER_AGENT)
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(original)
        ScriptStorage.exclusive { ScriptValueStore(context, identity).update("owner", encoded("incoming")) }
        val workspace = File(saved, "direct-restore").apply { mkdirs() }
        val archive = File(saved, "incoming.zip")
        repository.create(archive, workspace)
        val candidate = repository.open(archive, File(workspace, "candidate"))
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(configB())
        ScriptStorage.exclusive {
            values.deleteRecursively(); values.mkdirs()
            File(values, secondName).writeText("This discarded store must never be parsed or archived")
        }
        assertEquals(original, repository.restore(candidate))
        assertEquals("incoming", JSONObject(File(values, firstName).readText()).getJSONObject("owner").getString("value"))
        assertFalse(File(values, secondName).exists())
        assertFalse(local.exists())
        assertFalse(File(root, "webdav-restore-transaction").exists())
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("webdav-retired-") })
    }

    @Test fun startupRemovesOldAutomaticUndoBackupsAndKeepsManuallyExportedBackups() {
        ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(configB())
        local.mkdirs(); File(local, "previous.zip").writeText("obsolete automatic backup")
        File(local, "previous.pending.zip").writeText("incomplete automatic backup")
        val exported = File(root, "manual-export-${UUID.randomUUID()}.zip").apply { writeText("user export") }
        try {
            launchManager()
            waitFor { !local.exists() }
            assertEquals("user export", exported.readText())
        } finally { exported.delete() }
    }

    private fun launchManager() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity -> MainActivity::class.java.getDeclaredMethod("showProfiles").apply { isAccessible = true }.invoke(activity) }
    }
    private fun openDialog() = scenario!!.onActivity { it.findViewById<View>(R.id.webDavButton).performClick() }
    private fun closeDialog() = click(R.id.closeWebdav)
    private fun fillServer() = scenario!!.onActivity { activity ->
        dialog(activity).findViewById<EditText>(R.id.webdavDirectory)!!.setText("$base/dav/")
        dialog(activity).findViewById<EditText>(R.id.webdavUsername)!!.setText("test-user")
        dialog(activity).findViewById<EditText>(R.id.webdavPassword)!!.setText("do-not-export-password")
    }
    private fun click(id: Int) = scenario!!.onActivity { activity ->
        val button = dialog(activity).findViewById<View>(id)!!
        assertTrue("Button $id is disabled", button.isEnabled); button.performClick()
    }
    private fun dialog(activity: MainActivity) = MainActivity::class.java.getDeclaredField("nativeDialog").apply { isAccessible = true }.get(activity) as Dialog
    private fun waitPreview() = waitFor {
        var visible = false
        scenario!!.onActivity { activity -> visible = dialog(activity).findViewById<View>(R.id.webdavPreview)!!.visibility == View.VISIBLE && dialog(activity).findViewById<View>(R.id.webdavConfirm)!!.isEnabled }
        visible
    }
    private fun status(): String {
        var text = ""
        scenario!!.onActivity { activity -> text = dialog(activity).findViewById<TextView>(R.id.webdavStatus)!!.text.toString() }
        return text
    }
    private fun waitText(text: String) = waitFor { status().contains(text) }
    private fun waitFor(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) { if (check()) return; SystemClock.sleep(50) }
        fail("WebDAV state was not ready: ${runCatching { status() }.getOrDefault("no active dialog")}")
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
        instrumentation.waitForIdleSync(); SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun encoded(value: String) = JSONObject().put("type", "json").put("value", value)
    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun restorePreferences(name: String, preferences: Map<String, *>) {
        val editor = context.getSharedPreferences(name, 0).edit().clear()
        preferences.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
        } }
        editor.commit()
    }
}
