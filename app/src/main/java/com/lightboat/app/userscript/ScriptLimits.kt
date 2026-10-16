package com.lightboat.app.userscript

import java.io.FilterOutputStream
import java.io.OutputStream

internal object ScriptLimits {
    const val MAX_BYTES = 10 * 1024 * 1024
    const val CHUNK_CHARS = 32 * 1024
}

internal class LimitedScriptOutput(output: OutputStream, private val limit: Int = ScriptLimits.MAX_BYTES) : FilterOutputStream(output) {
    var count = 0; private set
    override fun write(value: Int) {
        require(count < limit) { "每个脚本的 GM 存储不能超过 10 MiB" }
        out.write(value); count++
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(length <= limit - count) { "每个脚本的 GM 存储不能超过 10 MiB" }
        out.write(bytes, offset, length); count += length
    }
}
