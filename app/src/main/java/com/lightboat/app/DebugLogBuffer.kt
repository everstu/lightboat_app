package com.lightboat.app

/** Callers synchronize access. Returned strings are shared, never copied for UI reads. */
internal class DebugLogBuffer(private val limit: Int = 2000) {
    data class Read(val sequence: Long, val reset: Boolean, val lines: List<String>)
    private val rows = ArrayDeque<Pair<Long, String>>()
    private var sequence = 0L

    fun append(line: String) {
        rows.addLast(++sequence to line)
        while (rows.size > limit) rows.removeFirst()
    }

    fun clear() { rows.clear(); sequence++ }
    fun read(after: Long?): Read {
        val reset = after == null || after > sequence || rows.firstOrNull()?.let { after < it.first - 1 } == true
        return Read(sequence, reset, rows.filter { reset || it.first > after!! }.map { it.second })
    }
}
