package com.lightboat.app

import com.lightboat.app.backup.BackupArchive
import com.lightboat.app.backup.BackupPreferences
import com.lightboat.app.backup.BackupOptions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val hashName = "a".repeat(64) + ".json"
    private val note = "备份备注 😀\n第二行\t缩进   \r\n<b>文本</b>"
    private val config = AppConfig.EMPTY.upsertScript(SharedScript("script-id", "共享脚本", "https://scripts.example/no-ad.js"))
        .copy(categories = mapOf("category-id" to "阅读"))
        .upsert(Profile("profile-id", "日常", "https://page.example/", listOf(ProfileScript("script-id", false)),
            categoryId = "category-id", favorite = true, note = note))
    private val prefs = BackupPreferences("category-id", true)

    private fun payload(): File = temporary.newFolder().apply {
        resolve("config.json").writeText(ConfigCodec.encode(config))
        resolve("preferences.json").writeText(prefs.json().toString())
        resolve("userscript-values").mkdirs()
        resolve("userscript-values/$hashName").writeText("""{"cache":{"type":"json","value":{"规则":"正文"}},"settings":{"type":"json","value":true}}""")
    }
    private fun archive(): File = temporary.newFile().also { BackupArchive.write(payload(), it, "Test device", "1.0.3 (35)") { file -> JSONObject(file.readText()) } }

    @Test fun roundTripRetainsConfigurationIdsDisabledReferencesAndIndependentGmValues() {
        val zip = archive()
        val result = BackupArchive.open(zip, File(temporary.root, "restored")) { file -> JSONObject(file.readText()) }
        assertEquals(config, result.config)
        assertEquals(note, result.config.profiles.single().note)
        assertEquals(prefs, result.preferences)
        assertEquals(1, result.info.profiles); assertEquals(1, result.info.scripts); assertEquals(1, result.info.stores)
        assertTrue(result.directory.resolve("userscript-values/$hashName").readText().contains("规则"))
        ZipFile(zip).use { input ->
            val names = input.entries().asSequence().map { it.name }.toList()
            assertEquals(setOf("config.json", "preferences.json", "userscript-values/$hashName", "manifest.json"), names.toSet())
            assertFalse(names.any { it.contains("password") || it.contains("webdav") })
        }
    }

    @Test fun rejectsTraversalUnknownEntriesAndBackslashPathsAndCleansStaging() {
        listOf("../escape", "/config.json", "C:/config.json", "userscript-values\\$hashName", "cookies.json").forEachIndexed { index, path ->
            val bad = temporary.newFile()
            ZipOutputStream(bad.outputStream()).use { output -> output.putNextEntry(ZipEntry(path)); output.write("{}".toByteArray()); output.closeEntry() }
            val destination = File(temporary.root, "bad-$index")
            assertThrows(Exception::class.java) { BackupArchive.open(bad, destination) {} }
            assertFalse(destination.exists())
        }
        assertFalse(File(temporary.root.parentFile, "escape").exists())
    }

    @Test fun refusesModifiedPayloadMissingFilesAndUnknownBackupVersionsBeforeReturningData() {
        val good = archive()
        val files = ZipFile(good).use { input -> input.entries().asSequence().associate { it.name to input.getInputStream(it).readBytes() } }
        val variants = listOf(
            files + ("config.json" to ConfigCodec.encode(AppConfig.EMPTY).toByteArray()),
            files - "preferences.json",
            files + ("manifest.json" to JSONObject(String(files.getValue("manifest.json"))).put("version", 9).toString().toByteArray())
        )
        variants.forEachIndexed { index, entries ->
            val bad = temporary.newFile()
            ZipOutputStream(bad.outputStream()).use { output -> entries.forEach { (name, bytes) -> output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry() } }
            val destination = File(temporary.root, "mismatch-$index")
            assertThrows(Exception::class.java) { BackupArchive.open(bad, destination) {} }
            assertFalse(destination.exists())
        }
    }

    @Test fun refusesTruncatedZipAndOversizedDecompressedStore() {
        val zip = archive()
        java.io.RandomAccessFile(zip, "rw").use { it.setLength(zip.length() - 30) }
        assertThrows(Exception::class.java) { BackupArchive.open(zip, File(temporary.root, "truncated")) {} }
        val bomb = temporary.newFile()
        ZipOutputStream(bomb.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("userscript-values/$hashName"))
            val buffer = ByteArray(16 * 1024)
            repeat((BackupArchive.MAX_STORE_BYTES / buffer.size).toInt() + 1) { output.write(buffer) }
            output.closeEntry()
        }
        assertThrows(Exception::class.java) { BackupArchive.open(bomb, File(temporary.root, "large")) {} }
    }

    @Test fun invalidGmDataNeverBecomesAnOpenedBackupAndInvalidUtf8IsRejected() {
        val zip = archive()
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.open(zip, File(temporary.root, "invalid-store")) { throw IllegalArgumentException("bad store") }
        }
        assertFalse(File(temporary.root, "invalid-store").exists())
        val text = temporary.newFile().apply { writeBytes(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(Exception::class.java) { BackupArchive.readText(text, 10) }
    }

    @Test fun optionalComponentsAreExplicitEvenWhenAnIncludedStoreIsEmpty() {
        for (options in listOf(BackupOptions.CONFIG_ONLY, BackupOptions(true, false), BackupOptions(false, true), BackupOptions.ALL)) {
            val source = payload()
            source.resolve("userscript-values/$hashName").delete()
            val zip = temporary.newFile()
            BackupArchive.write(source, zip, "device", "test", options) {}
            val opened = BackupArchive.open(zip, File(temporary.newFolder(), "opened")) {}
            assertEquals(options, opened.info.options)
            assertEquals(config, opened.config)
            ZipFile(zip).use { assertEquals(options.preferences, it.getEntry("preferences.json") != null) }
        }
        val source = payload()
        source.resolve("userscript-values/$hashName").writeText("bad store which must not be read")
        val zip = temporary.newFile()
        BackupArchive.write(source, zip, "device", "test", BackupOptions.CONFIG_ONLY) { fail("Excluded GM stores must not be inspected") }
        assertEquals(BackupOptions.CONFIG_ONLY, BackupArchive.open(zip, File(temporary.newFolder(), "opened")) {}.info.options)
    }

    @Test fun fullVersionOneBackupsRemainReadableAndInvalidComponentDeclarationsAreRejected() {
        val zip = archive()
        val files = ZipFile(zip).use { input -> input.entries().asSequence().associate { it.name to input.getInputStream(it).readBytes() } }
        val manifest = JSONObject(String(files.getValue("manifest.json")))
        fun variant(json: JSONObject): File = temporary.newFile().also { file ->
            ZipOutputStream(file.outputStream()).use { output ->
                (files + ("manifest.json" to json.toString().toByteArray())).forEach { (name, bytes) ->
                    output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
                }
            }
        }
        val legacy = variant(JSONObject(manifest.toString()).put("version", 1).apply { remove("components") })
        assertEquals(BackupOptions.ALL, BackupArchive.open(legacy, File(temporary.newFolder(), "opened")) {}.info.options)
        for (components in listOf("[]", "[\"configuration\",\"configuration\"]", "[\"configuration\",\"unknown\"]", "[\"configuration\"]")) {
            val bad = variant(JSONObject(manifest.toString()).put("components", org.json.JSONArray(components)))
            assertThrows(Exception::class.java) { BackupArchive.open(bad, File(temporary.newFolder(), "opened")) {} }
        }
    }
}
