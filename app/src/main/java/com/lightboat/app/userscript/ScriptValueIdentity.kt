package com.lightboat.app.userscript

import com.lightboat.app.AppConfig
import com.lightboat.app.Profile
import com.lightboat.app.SharedScript

/**
 * GM storage identity is based on the shared script and its userscript metadata only.
 * Profile selection, enabled state and order deliberately do not take part.
 */
internal object ScriptValueIdentity {
    /** [metadataIdentity] is "namespace:name" from the downloaded userscript header. */
    fun canonical(scriptId: String, metadataIdentity: String) = "$scriptId:$metadataIdentity"

    /**
     * Old stores that may belong to [script] for any profile. The currently opened
     * profile is first; remaining profiles keep their order from the configuration.
     */
    fun migrationCandidates(config: AppConfig, activeProfile: Profile, script: SharedScript,
        metadataIdentity: String): List<String> {
        val profiles = buildList {
            config.profiles.firstOrNull { it.id == activeProfile.id }?.let { add(it) }
                ?: add(activeProfile)
            addAll(config.profiles.filter { it.id != activeProfile.id })
        }
        val candidates = LinkedHashSet<String>()
        profiles.forEach { profile ->
            val references = profile.scripts.filter { it.scriptId == script.id }
            if (references.isEmpty() && profile.id == activeProfile.id) {
                // The active script was opened even if its reference is not in the
                // saved profile yet; its previous identity must still be migrated.
                candidates += "${profile.id}:${script.id}:$metadataIdentity"
            }
            references.forEach { reference ->
                candidates += "${profile.id}:${script.id}:$metadataIdentity"
                if (reference.legacyStorage) candidates += "${profile.id}:$metadataIdentity"
            }
        }
        return candidates.toList()
    }
}
