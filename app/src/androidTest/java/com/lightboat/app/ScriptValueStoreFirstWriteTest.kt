package com.lightboat.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightboat.app.userscript.ScriptValueStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ScriptValueStoreFirstWriteTest {
    @Test fun legacyValuesMigrateOnceAndRemainAvailableWhenTheReferenceIsSelectedAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val legacy = "legacy-$suffix"
        val modern = "shared-$suffix"
        try {
            ScriptValueStore(context, legacy).update("key", JSONObject().put("type", "json").put("value", "old-setting"))
            val migrated = ScriptValueStore(context, modern, legacy).encoded("key")
            assertEquals("old-setting", JSONObject(requireNotNull(migrated)).getString("value"))
            assertEquals(migrated, ScriptValueStore(context, modern).encoded("key"))
            ScriptValueStore(context, modern).update("key", JSONObject().put("type", "json").put("value", "new-setting"))
            assertEquals("new-setting", JSONObject(requireNotNull(ScriptValueStore(context, modern, legacy).encoded("key"))).getString("value"))
        } finally {
            for (identity in listOf(legacy, modern)) {
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                File(context.filesDir, "userscript-values/$hash.json").delete()
            }
        }
    }

    @Test fun firstWriteCreatesItsDirectoryAndIsReadableByANewStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "first-gm-write-${UUID.randomUUID()}")
        val freshContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        try {
            assertFalse(directory.exists())
            ScriptValueStore(freshContext, "fresh").update("key", JSONObject().put("type", "json").put("value", "saved"))
            val restored = ScriptValueStore(freshContext, "fresh").encoded("key")
            assertEquals("saved", JSONObject(requireNotNull(restored)).getString("value"))
        } finally { directory.deleteRecursively() }
    }
}
