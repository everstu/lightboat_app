package com.lightboat.app.userscript

import java.io.File
import java.io.Writer

/** Access only from the serial storage worker. Partial values never enter the store. */
internal class ScriptValueUpload(directory: File, val key: String, private val characters: Int) : AutoCloseable {
    private val file = createFile(directory)
    private val writer: Writer = LimitedScriptOutput(file.outputStream()).bufferedWriter(Charsets.UTF_8)
    private var next = 0
    private var received = 0

    fun append(index: Int, text: String, last: Boolean): String? {
        require(index == next++ && text.length <= ScriptLimits.CHUNK_CHARS) { "GM 存储分片顺序无效" }
        received += text.length
        require(received <= characters) { "GM 存储分片长度无效" }
        writer.write(text)
        if (!last) return null
        require(received == characters) { "GM 存储分片不完整" }
        writer.close()
        return file.readText(Charsets.UTF_8)
    }

    override fun close() { runCatching { writer.close() }; file.delete() }

    companion object {
        private var cleaned = false
        @Synchronized private fun createFile(directory: File): File {
            if (!cleaned) {
                directory.listFiles { file -> file.name.startsWith("gm-value-") && file.name.endsWith(".part") }
                    ?.forEach { it.delete() }
                cleaned = true
            }
            return File.createTempFile("gm-value-", ".part", directory)
        }
    }
}
