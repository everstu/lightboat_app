package com.lightboat.app

import android.content.Context
import android.util.AtomicFile
import java.io.File

class ConfigStore(context: Context) {
    private val assets = context.assets
    private val file = File(context.filesDir, "config.json")
    private val atomicFile = AtomicFile(file)

    fun load(): AppConfig {
        if (!file.exists() && !File(file.path + ".bak").exists()) {
            val initial = assets.open("config.json").bufferedReader(Charsets.UTF_8).use {
                ConfigCodec.decode(it.readText())
            }
            save(initial)
            return initial
        }
        val text = atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        return try { ConfigCodec.decode(text) } catch (error: Exception) {
            File(file.parentFile, "config.invalid-${System.currentTimeMillis()}.json").writeText(text)
            throw error
        }
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
    }
}
