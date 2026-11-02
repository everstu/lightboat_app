package com.lightboat.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.lightboat.app.backup.BackupOptions
import com.lightboat.app.backup.BackupRepository
import com.lightboat.app.userscript.ScriptValueStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class RecoveryAndValueValidationTest {
    private val real = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(real.cacheDir, "recovery-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(real) {
        override fun getFilesDir() = root
        override fun getApplicationContext(): Context = this
    }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun corruptConfigIsQuarantinedOnceAndAValidBackupCanReplaceIt() {
        val store = ConfigStore(context, AppConfig.DEFAULT_USER_AGENT)
        val expected = AppConfig.EMPTY.upsert(Profile("kept", "Recovered", "https://example.com"))
        store.save(expected)
        val repository = BackupRepository(context, AppConfig.DEFAULT_USER_AGENT)
        val archive = File(root, "test.zip")
        val options = BackupOptions(scriptData = false, preferences = false)
        repository.create(archive, File(root, "workspace"), options)
        val candidate = repository.open(archive, File(root, "candidate"))
        File(root, "config.json").writeText("{damaged config")
        repeat(3) {
            try { store.load(); fail("A corrupt configuration was silently accepted") }
            catch (_: ConfigStore.RecoveryRequired) { }
        }
        assertEquals("{damaged config", File(root, "config.invalid.json").readText())
        assertFalse(File(root, "config.json").exists())
        assertEquals(1, root.listFiles()!!.count { it.name.startsWith("config.invalid") })
        assertEquals(expected, repository.restore(candidate, options))
        assertEquals(expected, store.load())
        assertFalse(File(root, "config.recovery-required").exists())
        assertTrue(File(root, "config.invalid.json").exists())
    }

    @Test fun backupValidationAndLiveStorageRejectTheSameMalformedValuesWithoutReplacingExistingData() {
        val store = ScriptValueStore(context, "validation-test")
        val valid = "{\"type\":\"json\",\"value\":null}"
        store.updateEncoded("key", valid)
        val testFile = File(root, "candidate-store.json")
        val invalid = listOf(
            "{\"type\":\"json\"}",
            "{\"type\":\"undefined\",\"value\":1}",
            "{\"type\":\"json\",\"type\":\"undefined\"}",
            "{\"type\":\"json\",\"value\":0,\"other\":true}",
            "{\"type\":\"json\",\"value\":" + "[".repeat(130) + "0" + "]".repeat(130) + "}"
        )
        for (value in invalid) {
            try { store.updateEncoded("key", value); fail("Invalid live value accepted: $value") }
            catch (_: Exception) { }
            assertEquals(valid, store.encoded("key"))
            testFile.writeText("{\"key\":$value}")
            try { BackupRepository.validateStore(testFile); fail("Invalid backup value accepted: $value") }
            catch (_: IllegalArgumentException) { }
        }
        for (value in listOf(valid, "{\"type\":\"undefined\"}", "{\"value\":{\"a\":[true,1,\"中\"]},\"type\":\"json\"}")) {
            store.updateEncoded("key", value)
            testFile.writeText("{\"key\":$value}"); BackupRepository.validateStore(testFile)
        }
    }

    @Test fun largeDraftUsesAFileAndKeepsUnsavedLibraryOrderAndDisabledReferences() {
        val library = (0 until 100).map { index ->
            SharedScript("id-$index", "Script $index", "https://example.com/$index/" + "x".repeat(8000) + ".js")
        }
        val refs = listOf(ProfileScript("id-99", false), ProfileScript("id-0"))
        val drafts = EditorDraftStore(context)
        val id = drafts.save(null, library, refs)
        assertTrue(File(root, "editor-drafts/$id.json").length() > 800_000)
        assertEquals(library to refs, drafts.load(id))
        drafts.delete(id)
        assertFalse(File(root, "editor-drafts/$id.json").exists())
    }
}
