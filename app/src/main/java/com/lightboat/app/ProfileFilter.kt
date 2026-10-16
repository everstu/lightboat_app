package com.lightboat.app

/** null selects every category; the empty ID selects profiles without a category. */
data class ProfileFilter(val categoryId: String? = null, val favoritesOnly: Boolean = false) {
    fun apply(config: AppConfig, query: String = ""): List<Profile> {
        val text = query.trim()
        return config.profiles.filter { profile ->
            (categoryId == null || profile.categoryId.orEmpty() == categoryId) &&
                (!favoritesOnly || profile.favorite) &&
                (text.isEmpty() || profile.name.contains(text, ignoreCase = true) ||
                    profile.url.contains(text, ignoreCase = true))
        }
    }

    fun resolve(config: AppConfig): ProfileFilter =
        if (categoryId.isNullOrEmpty() || config.categories.containsKey(categoryId)) this else copy(categoryId = null)
}
