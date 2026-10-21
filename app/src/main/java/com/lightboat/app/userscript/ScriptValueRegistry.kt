package com.lightboat.app.userscript

import android.content.Context

/** A host's claim on one process-wide value store. Only the storage worker touches [store]. */
internal class ScriptValueLease internal constructor(val identity: String) {
    @Volatile internal var store: ScriptValueStore? = null
    @Volatile internal var failure: String? = null
    @Volatile internal var released = false

    fun requireStore(): ScriptValueStore =
        store ?: throw IllegalStateException(failure ?: "GM 存储尚未就绪")
}

/**
 * Active hosts for one identity share a single [ScriptValueStore], so two profiles
 * cannot hold independent stale snapshots and overwrite each other's writes.
 * Every method runs on the single storage queue.
 */
internal object ScriptValueRegistry {
    private class Entry(val store: ScriptValueStore, var leases: Int = 0)
    private val entries = HashMap<String, Entry>()

    fun acquire(context: Context, lease: ScriptValueLease, candidates: List<String>) {
        check(!lease.released) { "GM 存储已释放" }
        val existing = entries[lease.identity]
        if (existing != null) {
            existing.leases++
            lease.store = existing.store
            return
        }
        val store = ScriptValueStore(context.applicationContext, lease.identity,
            migrationCandidates = candidates)
        entries[lease.identity] = Entry(store, 1)
        lease.store = store
    }

    fun release(lease: ScriptValueLease) {
        val entry = entries[lease.identity] ?: return
        if (entry.store !== lease.store) return
        entry.leases--
        if (entry.leases <= 0) {
            entries.remove(lease.identity)
            // No host can write this store any more. Drop the large encoded map;
            // a future host reloads it from disk (and therefore from a restore).
            entry.store.discardCache()
        }
        lease.store = null
    }

    /**
     * Called on the storage queue after a backup restore swapped the GM directory.
     * Idle entries are dropped; active entries keep the same store object but must
     * reload its snapshot from the restored files.
     */
    fun invalidateAll() {
        entries.values.forEach { it.store.discardCache() }
        entries.entries.removeAll { it.value.leases <= 0 }
    }
}
