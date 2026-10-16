package dev.markectomy.blueprints.domain

/** Metadata snapshot; parsing build contents is deliberately outside the browser. */
data class BlueprintEntry(
    val reference: BlueprintReference,
    val name: String,
    val tags: Set<String>,
    val thumbnailPng: ByteArray? = null,
)

object BlueprintFilters {
    fun matches(entry: BlueprintEntry, query: String, selectedTags: Set<String>): Boolean =
        entry.name.contains(query.trim(), ignoreCase = true) && entry.tags.containsAll(selectedTags)

    val ORDER: Comparator<BlueprintEntry> = compareBy(String.CASE_INSENSITIVE_ORDER) { entry: BlueprintEntry -> entry.name }
        .thenBy { it.reference.path }
}
