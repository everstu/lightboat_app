package com.lightboat.app.userscript

import java.io.FilterOutputStream
import java.io.OutputStream

internal object ScriptLimits {
    const val MAX_BYTES = 10 * 1024 * 1024
    const val CHUNK_CHARS = 32 * 1024
}

/** UTF-8 byte count without materializing the encoded array; used for size guards only. */
internal fun utf8ByteLength(text: String): Int {
    var count = 0
    var index = 0
    while (index < text.length) {
        val character = text[index]
        when {
            character.code < 0x80 -> count++
            character.code < 0x800 -> count += 2
            character.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> { count += 4; index++ }
            else -> count += 3
        }
        index++
    }
    return count
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
