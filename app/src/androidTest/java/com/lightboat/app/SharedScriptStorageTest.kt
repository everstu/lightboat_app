package com.lightboat.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightboat.app.backup.BackupOptions
import com.lightboat.app.backup.BackupRepository
import com.lightboat.app.userscript.ScriptStorage
import com.lightboat.app.userscript.ScriptValueIdentity
import com.lightboat.app.userscript.ScriptValueLease
import com.lightboat.app.userscript.ScriptValueRegistry
import com.lightboat.app.userscript.ScriptValueStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SharedScriptStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root get() = File(context.filesDir, "userscript-values")
    private val suffix = UUID.randomUUID().toString()
    private val script = SharedScript("script-$suffix", "Shared script", "https://example.com/shared.user.js")
    private val namespace = "namespace-$suffix"
    private val name = "Shared name $suffix"
    private val metadata = "$namespace:$name"
    private val canonical = "${script.id}:$metadata"
    private val firstProfile = Profile("first-$suffix", "First", "https://example.com/first",
        listOf(ProfileScript(script.id)))
    private val secondProfile = Profile("second-$suffix", "Second", "https://example.com/second",
        listOf(ProfileScript(script.id)))
    private val config = AppConfig(secondProfile.id, listOf(firstProfile, secondProfile),
        scriptLibrary = listOf(script))
    private val identities = mutableSetOf<String>()

    private fun encoded(text: String) = JSONObject().put("type", "json").put("value", text).toString()
    private fun read(store: ScriptValueStore, key: String): String? =
        store.encoded(key)?.let { JSONObject(it).getString("value") }
    private fun source(identity: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(root, "$hash.json")
    }
    private fun write(identity: String, key: String, value: String) {
        identities += identity
        ScriptValueStore(context, identity).updateEncoded(key, encoded(value))
    }
    private fun delete(identity: String) {
        val file = source(identity)
        file.delete()
        File(file.path + ".bak").delete()
    }

    @After fun cleanup() {
        identities.forEach(::delete)
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        File(root, "$hash.json").delete()
        File(root, "$hash.json.bak").delete()
    }

    @Test fun differentSharedScriptIdsStayIsolatedWithTheSameNamespaceAndName() {
        val otherScriptId = "other-script-$suffix"
        val other = "$otherScriptId:$metadata"
        write(canonical, "owner", "first-script")
        write(other, "owner", "second-script")
        assertEquals("first-script", read(ScriptValueStore(context, canonical), "owner"))
        assertEquals("second-script", read(ScriptValueStore(context, other), "owner"))
    }

    @Test fun differentProfileStoresMergeDifferentKeysIntoTheCanonicalStore() {
        val first = "${firstProfile.id}:${script.id}:$metadata"
        val second = "${secondProfile.id}:${script.id}:$metadata"
        write(first, "first", "value-first")
        write(second, "second", "value-second")
        val candidates = ScriptValueIdentity.migrationCandidates(config, secondProfile, script, metadata)
        val store = ScriptValueStore(context, canonical, migrationCandidates = candidates)
        assertEquals("value-first", read(store, "first"))
        assertEquals("value-second", read(store, "second"))
        assertFalse(source(first).exists())
        assertFalse(source(second).exists())
    }

    @Test fun conflictsPreferTheOpenedProfileAndKeepTheLoserSourceFile() {
        val first = "${firstProfile.id}:${script.id}:$metadata"
        val second = "${secondProfile.id}:${script.id}:$metadata"
        val conflictKey = "account-email-$suffix"
        write(first, conflictKey, "first-profile")
        write(second, conflictKey, "second-profile")
        DebugLog.initialize(context)
        DebugLog.clear()
        val candidates = ScriptValueIdentity.migrationCandidates(config, secondProfile, script, metadata)
        val store = ScriptValueStore(context, canonical, migrationCandidates = candidates)
        assertEquals("second-profile", read(store, conflictKey))
        assertFalse("The winning source is fully contained and can be removed", source(second).exists())
        assertTrue("The conflicting loser must be kept for recovery", source(first).exists())
        val log = DebugLog.readAfter(null).text
        assertFalse("Raw GM keys must never be written to diagnostics", log.contains(conflictKey))
        assertTrue("Conflict count was not logged", log.contains("conflicts=1"))
    }

    @Test fun onlyReferencesWithLegacyStorageReadTheOldProfileWideIdentity() {
        val legacyFirst = "${firstProfile.id}:$metadata"
        val unflaggedSecond = "${secondProfile.id}:$metadata"
        write(legacyFirst, "legacy-owner", "legacy-value")
        write(unflaggedSecond, "wrong-owner", "must-not-be-read")
        val profiles = listOf(
            firstProfile.copy(scripts = listOf(ProfileScript(script.id, legacyStorage = true))),
            secondProfile
        )
        val candidates = ScriptValueIdentity.migrationCandidates(
            config.copy(profiles = profiles), secondProfile, script, metadata)
        val store = ScriptValueStore(context, canonical, migrationCandidates = candidates)
        assertEquals("legacy-value", read(store, "legacy-owner"))
        assertNull(read(store, "wrong-owner"))
        assertFalse(source(legacyFirst).exists())
        assertTrue(source(unflaggedSecond).exists())
    }

    @Test fun migrationOverTheSizeLimitFailsWithoutChangingAnySource() {
        val first = "${firstProfile.id}:${script.id}:$metadata"
        val second = "${secondProfile.id}:${script.id}:$metadata"
        write(first, "first", "x".repeat(6 * 1024 * 1024))
        write(second, "second", "y".repeat(6 * 1024 * 1024))
        val candidates = ScriptValueIdentity.migrationCandidates(config, secondProfile, script, metadata)
        try {
            ScriptValueStore(context, canonical, migrationCandidates = candidates).encoded("first")
            fail("An oversized migration was accepted")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("10 MiB"))
        }
        assertTrue(source(first).exists())
        assertTrue(source(second).exists())
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertFalse(File(root, "$hash.json").exists())
        assertFalse(File(root, "$hash.json.bak").exists())
    }

    @Test fun backupRestoreInvalidatesTheActiveRegistrySnapshot() {
        val configFile = File(context.filesDir, "config.json")
        val previousConfig = configFile.takeIf(File::exists)?.readBytes()
        val archive = File(context.cacheDir, "shared-store-$suffix.zip")
        val workspace = File(context.cacheDir, "shared-store-work-$suffix")
        val candidateDir = File(context.cacheDir, "shared-store-open-$suffix")
        val lease = ScriptValueLease(canonical)
        val repository = BackupRepository(context, AppConfig.DEFAULT_USER_AGENT)
        val options = BackupOptions(scriptData = true, preferences = false)
        try {
            ConfigStore(context, AppConfig.DEFAULT_USER_AGENT).save(config)
            ScriptStorage.exclusive {
                ScriptValueRegistry.acquire(context, lease, emptyList())
                lease.requireStore().updateEncoded("owner", encoded("before"))
            }
            repository.create(archive, workspace, options)
            ScriptStorage.exclusive {
                lease.requireStore().updateEncoded("owner", encoded("after"))
            }
            val candidate = repository.open(archive, candidateDir)
            repository.restore(candidate, options)
            ScriptStorage.exclusive {
                assertEquals("before", read(lease.requireStore(), "owner"))
            }
        } finally {
            ScriptStorage.exclusive { ScriptValueRegistry.release(lease) }
            previousConfig?.let(configFile::writeBytes) ?: configFile.delete()
            archive.delete(); workspace.deleteRecursively(); candidateDir.deleteRecursively()
        }
    }
}
