package com.lightboat.app

import android.content.Context
import android.util.AtomicFile
import java.io.File

class ConfigStore(context: Context, private val defaultUserAgent: String) {
    private val assets = context.assets
    private val file = File(context.filesDir, "config.json")
    private val atomicFile = AtomicFile(file)
    private val recovery = File(context.filesDir, "config.recovery-required")
    var recoveryRequired: Boolean = false
        private set

    class RecoveryRequired(cause: Exception? = null) : IllegalStateException("本机配置无法读取，请导入备份或重新开始", cause)

    fun load(): AppConfig {
        if (recovery.exists() && !file.exists() && !File(file.path + ".bak").exists()) {
            recoveryRequired = true
            throw RecoveryRequired()
        }
        if (!file.exists() && !File(file.path + ".bak").exists()) {
            val initial = assets.open("config.json").bufferedReader(Charsets.UTF_8).use {
                ConfigCodec.decode(it.readText(), defaultUserAgent)
            }
            save(initial)
            return initial
        }
        val text = try { atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } }
        catch (error: java.io.IOException) { recoveryRequired = true; throw RecoveryRequired(error) }
        return try { ConfigCodec.decode(text, defaultUserAgent).also { completeRecovery() } } catch (error: Exception) {
            recoveryRequired = true
            // One recovery copy, retained until the user has recovered their data.
            // Write it before removing the live file; failed quarantine never loses it.
            val invalid = AtomicFile(File(file.parentFile, "config.invalid.json"))
            val output = invalid.startWrite()
            try { output.write(text.toByteArray(Charsets.UTF_8)); invalid.finishWrite(output) }
            catch (failure: Exception) { invalid.failWrite(output); throw RecoveryRequired(failure) }
            recovery.writeText("config.invalid.json", Charsets.UTF_8)
            atomicFile.delete()
            throw RecoveryRequired(error)
        }
    }

    /** A valid backup must also be restorable when the live configuration is damaged. */
    fun loadForRestore(): AppConfig = try { load() } catch (_: RecoveryRequired) { AppConfig.EMPTY }

    fun completeRecovery() {
        recoveryRequired = false
        // Configuration/restore has already committed. A leftover marker must
        // never turn that success into an apparent failed/rolled-back operation.
        if (recovery.exists() && !recovery.delete()) DebugLog.event("config", "configuration recovered; marker cleanup deferred")
    }

    fun save(config: AppConfig) {
        val stream = atomicFile.startWrite()
        try {
            stream.write(ConfigCodec.encode(config).toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (error: Exception) {
            atomicFile.failWrite(stream)
            throw error
        }
        completeRecovery()
    }
}
