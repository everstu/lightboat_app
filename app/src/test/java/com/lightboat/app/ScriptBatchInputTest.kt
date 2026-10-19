package com.lightboat.app

import org.junit.Assert.*
import org.junit.Test

class ScriptBatchInputTest {
    @Test fun acceptsMixedNamedAndUnnamedRowsInOrderAndNeverFetchesUrls() {
        val result = ScriptBatchInput.addTo(emptyList(), "\uFEFF 页面增强 | https://offline.invalid/enhance.user.js\r\n\r\n" +
            "https://offline.invalid/%E4%B8%AD%E6%96%87.js?v=1\n|https://offline.invalid/third.js")
        assertEquals(listOf("页面增强", "中文.js", "third.js"), result.library.map { it.name })
        assertEquals(3, result.added)
        assertEquals(0, result.duplicates)
        assertEquals(3, result.library.map { it.id }.toSet().size)
    }

    @Test fun skipsEquivalentAddressesWithoutOverwritingExistingNamesIdsOrOrder() {
        val existing = SharedScript("kept", "原名称", "https://example.com/a.js")
        val result = ScriptBatchInput.addTo(listOf(existing), "替换名|HTTPS://EXAMPLE.COM:443/a.js\n" +
            "第一条|https://example.com/b.js\n第二条|https://example.com/b.js\nhttps://example.com/b.js?v=2")
        assertEquals(existing, result.library.first())
        assertEquals(listOf("原名称", "第一条", "b.js"), result.library.map { it.name })
        assertEquals(2, result.added)
        assertEquals(2, result.duplicates)
    }

    @Test fun anInvalidLineReportsItsOriginalLineNumberAndLeavesTheInputLibraryUntouched() {
        val library = listOf(SharedScript("kept", "原脚本", "https://example.com/original.js"))
        val error = assertThrows(IllegalArgumentException::class.java) {
            ScriptBatchInput.addTo(library, "https://example.com/valid.js\n\n错误|not-a-url")
        }
        assertTrue(error.message!!.startsWith("第 3 行："))
        assertEquals(1, library.size)
        assertEquals("kept", library.single().id)
    }

    @Test fun rejectsEmptyInputInvalidNamesAndOverfullLibrariesAtomically() {
        assertThrows(IllegalArgumentException::class.java) { ScriptBatchInput.addTo(emptyList(), "\n ") }
        assertThrows(IllegalArgumentException::class.java) { ScriptBatchInput.addTo(emptyList(), "x".repeat(61) + "|https://example.com/a.js") }
        val full = (1..100).map { SharedScript("s$it", "脚本$it", "https://example.com/$it.js") }
        val error = assertThrows(IllegalArgumentException::class.java) { ScriptBatchInput.addTo(full, "https://example.com/new.js") }
        assertTrue(error.message!!.contains("100"))
        assertEquals(100, full.size)
        assertEquals(1, ScriptBatchInput.addTo(full, "https://example.com/1.js").duplicates)
    }
}
