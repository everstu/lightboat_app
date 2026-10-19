package com.lightboat.app

import com.lightboat.app.backup.BackupInfo
import com.lightboat.app.backup.BackupPreferences
import com.lightboat.app.backup.BackupOptions
import com.lightboat.app.backup.BackupTransaction
import com.lightboat.app.backup.OpenedBackup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupTransactionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val old = AppConfig.EMPTY.upsert(Profile("old-id", "Old", "https://old.example/"))
    private val next = AppConfig.EMPTY.upsert(Profile("new-id", "New", "https://new.example/"))
    private val oldPrefs = BackupPreferences(null, false)
    private val nextPrefs = BackupPreferences(null, true)
    private fun live(): File = temporary.newFolder().apply {
        resolve("config.json").writeText(ConfigCodec.encode(old))
        resolve("userscript-values").mkdirs(); resolve("userscript-values/old.json").writeText("old values")
    }
    private fun candidate(): OpenedBackup {
        val directory = temporary.newFolder()
        directory.resolve("userscript-values").mkdirs(); directory.resolve("userscript-values/new.json").writeText("new values")
        return OpenedBackup(directory, next, nextPrefs, BackupInfo("id", 1, "test", "test", 1, 0, 1, 10))
    }

    @Test fun successfulRestoreReplacesTheWholeStoreAndRemovesAtomicFileSidecars() {
        val root = live()
        root.resolve("config.json.bak").writeText("outdated")
        root.resolve("config.json.new").writeText("incomplete")
        var preferences = oldPrefs
        val transaction = BackupTransaction(root) { preferences = it }
        val retired = transaction.install(candidate(), ConfigCodec.encode(old), oldPrefs)
        assertEquals(next, ConfigCodec.decode(root.resolve("config.json").readText()))
        assertEquals(nextPrefs, preferences)
        assertEquals(listOf("new.json"), root.resolve("userscript-values").listFiles()!!.map { it.name })
        assertFalse(root.resolve("config.json.bak").exists()); assertFalse(root.resolve("config.json.new").exists())
        assertNotNull(retired)
        assertNull(transaction.recover())
    }

    @Test fun preferenceFailureRollsBackConfigAndGmFilesAsASet() {
        val root = live()
        var fail = true
        var preferences = oldPrefs
        val transaction = BackupTransaction(root) { value ->
            if (fail) { fail = false; throw IllegalStateException("simulated preferences I/O error") }
            preferences = value
        }
        assertThrows(IllegalStateException::class.java) { transaction.install(candidate(), ConfigCodec.encode(old), oldPrefs) }
        assertEquals(old, ConfigCodec.decode(root.resolve("config.json").readText()))
        assertEquals(oldPrefs, preferences)
        assertEquals("old values", root.resolve("userscript-values/old.json").readText())
        assertFalse(root.resolve("userscript-values/new.json").exists())
    }

    @Test fun interruptedPendingRestoreIsRecoveredBeforeOpeningAnyProfile() {
        val root = live()
        val journal = root.resolve("webdav-restore-transaction").apply { mkdirs() }
        journal.resolve("old-config.json").writeText(ConfigCodec.encode(old))
        journal.resolve("old-preferences.json").writeText(oldPrefs.json().toString())
        journal.resolve("state.json").writeText("""{"state":"pending"}""")
        root.resolve("userscript-values").renameTo(journal.resolve("old-values"))
        root.resolve("userscript-values").mkdirs(); root.resolve("userscript-values/new.json").writeText("new values")
        root.resolve("config.json").writeText(ConfigCodec.encode(next))
        var preferences = nextPrefs
        val transaction = BackupTransaction(root) { preferences = it }
        assertNotNull(transaction.recover())
        assertEquals(old, ConfigCodec.decode(root.resolve("config.json").readText()))
        assertEquals(oldPrefs, preferences)
        assertEquals("old values", root.resolve("userscript-values/old.json").readText())
        assertFalse(root.resolve("userscript-values/new.json").exists())
        assertNull(transaction.recover())
    }

    @Test fun committedJournalKeepsTheNewDataAfterProcessRestart() {
        val root = live()
        val journal = root.resolve("webdav-restore-transaction").apply { mkdirs() }
        journal.resolve("state.json").writeText("""{"state":"committed"}""")
        root.resolve("config.json").writeText(ConfigCodec.encode(next))
        val transaction = BackupTransaction(root) { fail("Committed recovery must not restore old preferences") }
        assertNotNull(transaction.recover())
        assertEquals(next, ConfigCodec.decode(root.resolve("config.json").readText()))
    }

    @Test fun preparationFailuresDoNotBlockLiveDataButPendingOrDamagedJournalsDo() {
        val root = live()
        assertFalse(BackupTransaction.requiresRecovery(root))
        val journal = root.resolve("webdav-restore-transaction").apply { mkdirs() }
        journal.resolve("old-config.json").writeText(ConfigCodec.encode(old))
        assertFalse(BackupTransaction.requiresRecovery(root))
        val retired = BackupTransaction(root) { fail("Preparation never changed preferences") }.recover()
        assertNotNull(retired)
        assertEquals(old, ConfigCodec.decode(root.resolve("config.json").readText()))
        assertEquals("old values", root.resolve("userscript-values/old.json").readText())

        journal.mkdirs()
        val marker = journal.resolve("state.json")
        marker.writeText("""{"state":"pending"}""")
        assertTrue(BackupTransaction.requiresRecovery(root))
        marker.writeText("truncated")
        assertTrue(BackupTransaction.requiresRecovery(root))
        marker.writeText("""{"state":"committed"}""")
        assertFalse(BackupTransaction.requiresRecovery(root))
    }

    @Test fun omittedComponentsAndDeselectedComponentsPreserveTheCurrentDeviceData() {
        for (selection in listOf(BackupOptions.CONFIG_ONLY, BackupOptions(true, false), BackupOptions(false, true))) {
            val root = live()
            var preferences = oldPrefs
            BackupTransaction(root) { preferences = it }.install(candidate(), ConfigCodec.encode(old), oldPrefs, selection)
            assertEquals(next, ConfigCodec.decode(root.resolve("config.json").readText()))
            assertEquals(if (selection.preferences) nextPrefs else oldPrefs, preferences)
            assertEquals(!selection.scriptData, root.resolve("userscript-values/old.json").exists())
            assertEquals(selection.scriptData, root.resolve("userscript-values/new.json").exists())
        }
    }
}
